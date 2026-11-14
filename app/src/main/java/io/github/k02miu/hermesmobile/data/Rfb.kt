package io.github.k02miu.hermesmobile.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.Inflater

/**
 * The viewer's side of RFB 3.8 (RFC 6143) for a Bot Desktop: Xvnc with no authentication, reached through the
 * gateway's `/api/display/ws` bridge. Asks for 32-bit little-endian true colour, so a decoded pixel is already an
 * opaque ARGB int, and for ZRLE so a mostly still desktop costs little over a phone connection.
 *
 * [run] blocks on [input] until the stream ends; [send] takes whole client messages. The gateway drops key and
 * pointer messages from a viewer that does not hold the screen's lease, so sending them while only watching is
 * harmless.
 */
class RfbSession(input: InputStream, private val send: (ByteArray) -> Unit, private val listener: Listener) {
    interface Listener {
        /** The framebuffer was (re)allocated; [RfbSession.pixels] is a new array of [width] × [height]. */
        fun onResize(width: Int, height: Int)
        /** One update was applied; the rectangle bounds everything that changed. */
        fun onUpdate(x: Int, y: Int, width: Int, height: Int)
    }

    private val input = DataInputStream(input)
    private val inflater = Inflater()

    var width = 0; private set
    var height = 0; private set
    /** Row-major opaque ARGB, written only by the thread inside [run]. */
    var pixels = IntArray(0); private set

    fun run() {
        try {
            handshake()
            while (true) serverMessage()
        } finally {
            inflater.end()
        }
    }

    private fun handshake() {
        val version = ByteArray(12).also(input::readFully)
        if (!String(version, Charsets.US_ASCII).startsWith("RFB ")) throw IOException("not an RFB server")
        send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        val count = input.readUnsignedByte()
        if (count == 0) throw IOException("the screen refused the connection: " + readReason())
        val types = ByteArray(count).also(input::readFully)
        if (SECURITY_NONE !in types) throw IOException("the screen asks for a password")
        send(byteArrayOf(SECURITY_NONE))
        if (input.readInt() != 0) throw IOException("the screen refused the connection: " + readReason())
        send(byteArrayOf(1)) // ClientInit: share the screen with the agent and other viewers
        resize(input.readUnsignedShort(), input.readUnsignedShort())
        input.skipFully(16) // the server's own pixel format, replaced below
        input.skipFully(input.readInt()) // desktop name
        send(SET_PIXEL_FORMAT)
        send(setEncodings(ENC_ZRLE, ENC_COPY_RECT, ENC_RAW, ENC_DESKTOP_SIZE))
        requestUpdate(incremental = false)
    }

    private fun readReason(): String = runCatching {
        String(ByteArray(input.readInt().coerceIn(0, 4096)).also(input::readFully), Charsets.UTF_8)
    }.getOrDefault("")

    private fun serverMessage() {
        when (val type = input.readUnsignedByte()) {
            0 -> framebufferUpdate()
            1 -> { // SetColourMapEntries: never used with true colour, but framed so it cannot desync the stream
                input.skipFully(3)
                input.skipFully(input.readUnsignedShort() * 6)
            }
            2 -> Unit // Bell
            3 -> { // ServerCutText
                input.skipFully(3)
                input.skipFully(input.readInt())
            }
            else -> throw IOException("unknown RFB server message $type")
        }
    }

    private fun framebufferUpdate() {
        input.skipFully(1)
        val rects = input.readUnsignedShort()
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = 0; var bottom = 0
        var resized = false
        repeat(rects) {
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            val encoding = input.readInt()
            if (encoding == ENC_DESKTOP_SIZE) {
                resize(w, h); resized = true
                return@repeat
            }
            if (x + w > width || y + h > height) throw IOException("rectangle outside the screen")
            when (encoding) {
                ENC_RAW -> raw(x, y, w, h)
                ENC_COPY_RECT -> copyRect(x, y, w, h)
                ENC_ZRLE -> zrle(x, y, w, h)
                else -> throw IOException("unsupported RFB encoding $encoding")
            }
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + w); bottom = maxOf(bottom, y + h)
        }
        if (right > left && bottom > top) listener.onUpdate(left, top, right - left, bottom - top)
        // After a resize nothing held is valid any more, so ask for the whole screen again.
        requestUpdate(incremental = !resized)
    }

    private fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw IOException("unusable screen size ${w}x$h")
        width = w; height = h
        pixels = IntArray(w * h) { OPAQUE }
        listener.onResize(w, h)
    }

    private fun requestUpdate(incremental: Boolean) = send(byteArrayOf(
        3, if (incremental) 1 else 0, 0, 0, 0, 0,
        (width shr 8).toByte(), width.toByte(), (height shr 8).toByte(), height.toByte(),
    ))

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        val row = ByteArray(w * 4)
        for (j in 0 until h) {
            input.readFully(row)
            var o = (y + j) * width + x
            var i = 0
            while (i < row.size) {
                pixels[o++] = OPAQUE or (row[i + 2].toInt() and 0xFF shl 16) or (row[i + 1].toInt() and 0xFF shl 8) or (row[i].toInt() and 0xFF)
                i += 4
            }
        }
    }

    private fun copyRect(x: Int, y: Int, w: Int, h: Int) {
        val sx = input.readUnsignedShort(); val sy = input.readUnsignedShort()
        if (sx + w > width || sy + h > height) throw IOException("copy source outside the screen")
        // Rows in an order that never overwrites a source row before it is read; arraycopy handles overlap in a row.
        val rows = if (sy < y) (h - 1 downTo 0) else (0 until h)
        for (j in rows) System.arraycopy(pixels, (sy + j) * width + sx, pixels, (y + j) * width + x, w)
    }

    /** RFC 6143 §7.7.6: one zlib stream for the whole connection, 64×64 tiles, 3-byte pixels. */
    private fun zrle(x: Int, y: Int, w: Int, h: Int) {
        val length = input.readInt()
        if (length < 0 || length > MAX_ZRLE_BYTES) throw IOException("ZRLE rectangle too large")
        val data = TileReader(inflate(ByteArray(length).also(input::readFully)))
        val palette = IntArray(127)
        var ty = y
        while (ty < y + h) {
            val th = minOf(64, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(64, x + w - tx)
                tile(data, palette, tx, ty, tw, th)
                tx += 64
            }
            ty += 64
        }
    }

    private fun tile(data: TileReader, palette: IntArray, tx: Int, ty: Int, tw: Int, th: Int) {
        val sub = data.u8()
        when {
            sub == 0 -> for (j in 0 until th) {
                var o = (ty + j) * width + tx
                repeat(tw) { pixels[o++] = data.pixel() }
            }
            sub == 1 -> {
                val colour = data.pixel()
                for (j in 0 until th) pixels.fill(colour, (ty + j) * width + tx, (ty + j) * width + tx + tw)
            }
            sub in 2..16 -> {
                repeat(sub) { palette[it] = data.pixel() }
                val bits = if (sub == 2) 1 else if (sub <= 4) 2 else 4
                for (j in 0 until th) {
                    var o = (ty + j) * width + tx
                    var byte = 0; var have = 0
                    repeat(tw) { // each row starts on a byte boundary, most significant bits first
                        if (have == 0) { byte = data.u8(); have = 8 }
                        have -= bits
                        pixels[o++] = palette[(byte shr have) and ((1 shl bits) - 1)]
                    }
                }
            }
            sub == 128 || sub >= 130 -> {
                val size = sub - 128 // 0: plain run-length; otherwise run-length over a palette
                repeat(size) { palette[it] = data.pixel() }
                val total = tw * th
                var done = 0
                while (done < total) {
                    val colour: Int
                    var run = 1
                    if (size == 0) {
                        colour = data.pixel(); run = data.runLength()
                    } else {
                        val index = data.u8()
                        if (index and 0x80 != 0) run = data.runLength()
                        colour = palette[index and 0x7F]
                    }
                    if (done + run > total) throw IOException("ZRLE run past the tile")
                    repeat(run) {
                        pixels[(ty + done / tw) * width + tx + done % tw] = colour
                        done++
                    }
                }
            }
            else -> throw IOException("bad ZRLE tile type $sub")
        }
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 4)
        val chunk = ByteArray(64 * 1024)
        while (!inflater.needsInput()) {
            val n = try { inflater.inflate(chunk) } catch (e: java.util.zip.DataFormatException) { throw IOException("bad ZRLE data", e) }
            if (n == 0 && (inflater.finished() || inflater.needsDictionary())) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_PIXELS * 4) throw IOException("ZRLE rectangle too large")
        }
        return out.toByteArray()
    }

    private class TileReader(private val bytes: ByteArray) {
        private var at = 0
        fun u8(): Int {
            if (at >= bytes.size) throw EOFException("ZRLE data ended early")
            return bytes[at++].toInt() and 0xFF
        }
        /** A 3-byte "compressed pixel": the low three bytes of the little-endian pixel, so blue comes first. */
        fun pixel(): Int {
            if (at + 3 > bytes.size) throw EOFException("ZRLE data ended early")
            val p = OPAQUE or (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 1].toInt() and 0xFF shl 8) or (bytes[at].toInt() and 0xFF)
            at += 3
            return p
        }
        fun runLength(): Int {
            var run = 1
            while (true) {
                val b = u8()
                run += b
                if (b != 255) return run
            }
        }
    }

    companion object {
        private const val SECURITY_NONE: Byte = 1
        private const val ENC_RAW = 0
        private const val ENC_COPY_RECT = 1
        private const val ENC_ZRLE = 16
        private const val ENC_DESKTOP_SIZE = -223
        private const val OPAQUE = 0xFF shl 24
        private const val MAX_PIXELS = 8192L * 8192L / 4
        private const val MAX_ZRLE_BYTES = 64 * 1024 * 1024

        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16

        const val KEY_BACKSPACE = 0xFF08
        const val KEY_TAB = 0xFF09
        const val KEY_ENTER = 0xFF0D
        const val KEY_ESCAPE = 0xFF1B
        const val KEY_LEFT = 0xFF51
        const val KEY_UP = 0xFF52
        const val KEY_RIGHT = 0xFF53
        const val KEY_DOWN = 0xFF54

        // 32 bits per pixel, depth 24, little-endian, true colour, 8 bits each at red 16 / green 8 / blue 0.
        private val SET_PIXEL_FORMAT = byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0)

        private fun setEncodings(vararg encodings: Int): ByteArray {
            val out = ByteArray(4 + 4 * encodings.size)
            out[0] = 2; out[3] = encodings.size.toByte()
            encodings.forEachIndexed { i, e -> for (b in 0..3) out[4 + i * 4 + b] = (e shr (24 - 8 * b)).toByte() }
            return out
        }

        fun pointerEvent(buttons: Int, x: Int, y: Int): ByteArray =
            byteArrayOf(5, buttons.toByte(), (x shr 8).toByte(), x.toByte(), (y shr 8).toByte(), y.toByte())

        fun keyEvent(keysym: Int, down: Boolean): ByteArray = byteArrayOf(
            4, if (down) 1 else 0, 0, 0,
            (keysym shr 24).toByte(), (keysym shr 16).toByte(), (keysym shr 8).toByte(), keysym.toByte(),
        )

        /** X11 keysym for a typed character: Latin-1 maps to itself, the rest of Unicode sits at 0x01000000 + code point. */
        fun keysym(codePoint: Int): Int = when (codePoint) {
            '\n'.code, '\r'.code -> KEY_ENTER
            '\t'.code -> KEY_TAB
            in 0x20..0x7E, in 0xA0..0xFF -> codePoint
            else -> 0x01000000 or codePoint
        }
    }
}

private fun DataInputStream.skipFully(count: Int) {
    if (count < 0) throw IOException("negative length")
    var left = count
    val scratch = ByteArray(minOf(count, 8192))
    while (left > 0) {
        readFully(scratch, 0, minOf(left, scratch.size))
        left -= minOf(left, scratch.size)
    }
}

/** Turns chunks pushed from a WebSocket listener into the blocking stream [RfbSession] reads. */
class ChunkStream : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var at = 0

    fun offer(chunk: ByteArray) { if (chunk.isNotEmpty()) queue.put(chunk) }

    /** Ends the stream: a reader waiting for more sees end of file. */
    fun finish() { queue.put(END) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (at >= current.size) {
            if (current === END) return -1
            current = queue.take()
            at = 0
            if (current === END) return -1
        }
        val n = minOf(len, current.size - at)
        System.arraycopy(current, at, b, off, n)
        at += n
        return n
    }

    private companion object {
        val END = ByteArray(0)
    }
}
