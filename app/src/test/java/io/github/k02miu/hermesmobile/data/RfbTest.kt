package io.github.k02miu.hermesmobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.zip.Deflater

class RfbTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** A scripted server: the handshake for a [w]×[h] screen, then whatever [body] writes. */
    private fun server(w: Int, h: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.008\n".toByteArray())
            write(byteArrayOf(1, 1)) // one security type: None
            writeInt(0)              // SecurityResult OK
            writeShort(w); writeShort(h)
            write(ByteArray(16))
            writeInt(4); write("test".toByteArray())
            body()
        }
        return bytes.toByteArray()
    }

    private class Run(val session: RfbSession, val sent: List<ByteArray>, val updates: List<List<Int>>, val sizes: List<Pair<Int, Int>>)

    private fun play(stream: ByteArray): Run {
        val sent = mutableListOf<ByteArray>()
        val updates = mutableListOf<List<Int>>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val session = RfbSession(stream.inputStream(), { sent += it }, object : RfbSession.Listener {
            override fun onResize(width: Int, height: Int) { sizes += width to height }
            override fun onUpdate(x: Int, y: Int, width: Int, height: Int) { updates += listOf(x, y, width, height) }
        })
        try { session.run() } catch (_: EOFException) {}
        return Run(session, sent, updates, sizes)
    }

    private fun DataOutputStream.update(rects: Int) { write(byteArrayOf(0, 0)); writeShort(rects) }

    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
    }

    private fun cpixel(argb: Int) = byteArrayOf(argb.toByte(), (argb shr 8).toByte(), (argb shr 16).toByte())

    private fun DataOutputStream.zrle(deflater: Deflater, tiles: ByteArray) {
        deflater.setInput(tiles)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = deflater.deflate(chunk, 0, chunk.size, Deflater.SYNC_FLUSH)
            if (n == 0) break
            out.write(chunk, 0, n)
        }
        writeInt(out.size()); write(out.toByteArray())
    }

    @Test
    fun handshakeAsksForOurPixelFormatAndTheWholeScreen() {
        val run = play(server(4, 2) {})
        assertEquals(listOf(4 to 2), run.sizes)
        assertEquals("RFB 003.008\n", String(run.sent[0]))
        assertArrayEquals(byteArrayOf(1), run.sent[1]) // security: None
        assertArrayEquals(byteArrayOf(1), run.sent[2]) // shared
        assertEquals(20, run.sent[3].size)             // SetPixelFormat, as the gateway's filter frames it
        assertEquals(2, run.sent[4][0].toInt())        // SetEncodings
        assertEquals(4 + 4 * run.sent[4][3], run.sent[4].size)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 4, 0, 2), run.sent[5])
    }

    @Test
    fun rawPixelsBecomeOpaqueArgb() {
        val run = play(server(2, 1) {
            update(1); rect(0, 0, 2, 1, 0)
            write(byteArrayOf(0x33, 0x22, 0x11, 0, 0, 0, 0xFF.toByte(), 0))
        })
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), red), run.session.pixels)
        assertEquals(listOf(listOf(0, 0, 2, 1)), run.updates)
        assertEquals(1, run.sent.last()[1].toInt()) // the next request is incremental
    }

    @Test
    fun copyRectMovesOverlappingRowsDownWithoutSmearing() {
        val run = play(server(1, 3) {
            update(1); rect(0, 0, 1, 3, 0)
            listOf(red, green, blue).forEach { write(cpixel(it)); write(0) }
            update(1); rect(0, 1, 1, 2, 1); writeShort(0); writeShort(0)
        })
        assertArrayEquals(intArrayOf(red, red, green), run.session.pixels)
    }

    @Test
    fun zrleTilesOfEveryKindShareOneZlibStream() {
        val deflater = Deflater()
        val run = play(server(4, 2) {
            // solid
            update(1); rect(0, 0, 4, 2, 16)
            zrle(deflater, byteArrayOf(1) + cpixel(blue))
            // raw, in a second rectangle of the same zlib stream
            update(1); rect(0, 0, 2, 1, 16)
            zrle(deflater, byteArrayOf(0) + cpixel(red) + cpixel(green))
            // packed palette of two: one bit per pixel, each row padded to a byte
            update(1); rect(0, 1, 3, 1, 16)
            zrle(deflater, byteArrayOf(2) + cpixel(red) + cpixel(green) + byteArrayOf(0b0100_0000))
        })
        assertArrayEquals(intArrayOf(red, green, blue, blue, red, green, red, blue), run.session.pixels)
    }

    @Test
    fun zrleRunLengthsPlainAndPalette() {
        val deflater = Deflater()
        val run = play(server(3, 2) {
            update(1); rect(0, 0, 3, 2, 16)
            zrle(deflater, byteArrayOf(128.toByte()) + cpixel(red) + byteArrayOf(3) + cpixel(green) + byteArrayOf(1))
            update(1); rect(0, 0, 3, 1, 16)
            // palette of two: index 1 run of 2 (high bit + length-1), then index 0 once
            zrle(deflater, byteArrayOf(130.toByte()) + cpixel(blue) + cpixel(green) + byteArrayOf(0x81.toByte(), 1, 0))
        })
        assertArrayEquals(intArrayOf(green, green, blue, red, green, green), run.session.pixels)
    }

    @Test
    fun zrleSplitsARectangleInto64PixelTiles() {
        val deflater = Deflater()
        val run = play(server(70, 1) {
            update(1); rect(0, 0, 70, 1, 16)
            zrle(deflater, byteArrayOf(1) + cpixel(red) + byteArrayOf(1) + cpixel(green))
        })
        assertEquals(red, run.session.pixels[63])
        assertEquals(green, run.session.pixels[64])
        assertEquals(green, run.session.pixels[69])
    }

    @Test
    fun aResizeReallocatesAndAsksForEverythingAgain() {
        val run = play(server(2, 2) { update(1); rect(0, 0, 3, 1, -223) })
        assertEquals(listOf(2 to 2, 3 to 1), run.sizes)
        assertEquals(3, run.session.pixels.size)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 3, 0, 1), run.sent.last())
    }

    @Test
    fun inputMessagesMatchTheLengthsTheGatewayFilterExpects() {
        assertArrayEquals(byteArrayOf(5, 1, 0x01, 0x02, 0x00, 0x03), RfbSession.pointerEvent(1, 0x102, 3))
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0xFF.toByte(), 0x0D), RfbSession.keyEvent(RfbSession.KEY_ENTER, true))
        assertEquals('a'.code, RfbSession.keysym('a'.code))
        assertEquals(0x01003042, RfbSession.keysym('あ'.code))
        assertEquals(RfbSession.KEY_ENTER, RfbSession.keysym('\n'.code))
    }

    @Test
    fun chunkStreamJoinsChunksAndEnds() {
        val stream = ChunkStream()
        stream.offer(byteArrayOf(1, 2)); stream.offer(byteArrayOf(3)); stream.finish()
        val out = ByteArray(3)
        var read = 0
        while (read < 3) read += stream.read(out, read, 3 - read)
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
        assertTrue(stream.read() < 0)
        assertTrue(stream.read() < 0)
    }

    /** Dev aid: HERMES_RFB_LIVE="ssh host nc -U /path/rfb.sock" decodes two updates of a real screen into /tmp. */
    @Test
    fun liveScreenWhenAsked() {
        val command = System.getenv("HERMES_RFB_LIVE") ?: return
        val process = ProcessBuilder(command.split(" ")).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var updates = 0
        lateinit var session: RfbSession
        session = RfbSession(process.inputStream, { process.outputStream.write(it); process.outputStream.flush() }, object : RfbSession.Listener {
            override fun onResize(width: Int, height: Int) = println("screen ${width}x$height")
            override fun onUpdate(x: Int, y: Int, width: Int, height: Int) {
                println("update $x,$y ${width}x$height")
                if (++updates == 2) process.destroy()
            }
        })
        runCatching { session.run() }.onFailure { println("ended: $it") }
        val image = java.awt.image.BufferedImage(session.width, session.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, session.width, session.height, session.pixels, 0, session.width)
        javax.imageio.ImageIO.write(image, "png", java.io.File(System.getenv("HERMES_RFB_OUT") ?: "/tmp/hermes-rfb.png"))
        assertTrue(updates >= 1)
    }
}
