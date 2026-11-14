package io.github.k02miu.hermesmobile.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k02miu.hermesmobile.HermesApplication
import io.github.k02miu.hermesmobile.data.ChunkStream
import io.github.k02miu.hermesmobile.data.GatewayEvent
import io.github.k02miu.hermesmobile.data.RfbSession
import io.github.k02miu.hermesmobile.data.RpcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

sealed interface ScreenPhase {
    data object Loading : ScreenPhase
    /** This bot has no screen to show, and nothing here can change that. */
    data class Unavailable(val message: String) : ScreenPhase
    /** The screen exists but is not running; [blocker] is why starting it would be refused right now. */
    data class Stopped(val blocker: String? = null, val starting: Boolean = false, val error: String? = null) : ScreenPhase
    data object Connecting : ScreenPhase
    data object Live : ScreenPhase
    data class Failed(val message: String) : ScreenPhase
}

/** Who may type and click: the bot itself, this phone, or a person somewhere else (Desktop, another phone). */
enum class ScreenHolder { Agent, Me, Other }

data class ScreenState(
    val phase: ScreenPhase = ScreenPhase.Loading,
    val holder: ScreenHolder = ScreenHolder.Agent,
    val width: Int = 0,
    val height: Int = 0,
    /** A lease request is on its way. */
    val busy: Boolean = false,
    val notice: String? = null,
)

/**
 * One bot's Bot Desktop: the headless Linux desktop its computer use drives. `display.*` RPCs ride the server's
 * JSON-RPC socket; the picture is raw RFB on a second WebSocket opened with a single-use ticket from
 * `display.observe`. Watching is free; typing and clicking need the lease ([takeControl]), which the gateway
 * enforces by dropping input from everyone else.
 */
class ScreenViewModel(private val app: HermesApplication, val bot: BotRef) : ViewModel() {
    private val conn get() = app.connection(bot.server)

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state

    /** Bumped after every change to [bitmap], so the canvas knows to draw again. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame

    @Volatile var bitmap: Bitmap? = null
        private set

    private var profileKey: String? = null
    private var viewerId: String? = null
    private var viewerHash: String? = null
    @Volatile private var socket: WebSocket? = null
    private var retries = 0
    private var resumeControl = false
    private var attached = false
    private val attaching = Mutex()
    private var reattach: Job? = null

    init {
        viewModelScope.launch { conn.socket.events.collect(::onEvent) }
    }

    // ---- attaching ----------------------------------------------------------------------------

    /** The pane is on screen: find out what state the bot's screen is in and start watching it. */
    fun attach() {
        if (attached) return
        attached = true
        retries = 0
        viewModelScope.launch { open() }
    }

    /**
     * The pane is gone. Leaving it ([release]) hands the screen back to the bot; the app merely going to the
     * background does not, since that is usually a trip to a password manager in the middle of a login.
     */
    fun detach(release: Boolean) {
        if (!attached) return
        attached = false
        reattach?.cancel()
        resumeControl = !release && _state.value.holder == ScreenHolder.Me
        // The gateway releases the lease of a viewer that closes cleanly and keeps it for one that drops.
        socket?.close(if (release) 1000 else CLOSE_PAUSED, null)
        socket = null
    }

    private suspend fun open() = attaching.withLock {
        if (!attached) return@withLock
        if (bitmap == null) _state.update { it.copy(phase = ScreenPhase.Loading) }
        val status = try {
            request("display.status")
        } catch (e: Exception) {
            _state.update { it.copy(phase = unavailable(e)) }
            return@withLock
        }
        profileKey = status.str("profile_key")
        when {
            status.bool("supported") != true ->
                _state.update { it.copy(phase = ScreenPhase.Unavailable("Bot Desktop は Linux で動いている Hermes でだけ使えます")) }
            status.bool("installed") != true ->
                _state.update { it.copy(phase = ScreenPhase.Unavailable("サーバーに画面用のパッケージが入っていません。Hermes Desktop の Bot の画面からインストールしてください")) }
            status.bool("running") != true -> {
                bitmap = null
                _state.update { it.copy(phase = ScreenPhase.Stopped(blocker = status.str("blocker")), holder = ScreenHolder.Agent) }
            }
            else -> observe()
        }
    }

    /** Mints a ticket and opens the picture. Keeps this phone's viewer id when the gateway still knows it. */
    private suspend fun observe() {
        if (bitmap == null) _state.update { it.copy(phase = ScreenPhase.Connecting) }
        val res = try {
            request("display.observe") { viewerId?.let { put("viewer_id", it) } }
        } catch (e: Exception) {
            _state.update { it.copy(phase = ScreenPhase.Failed("画面を開けませんでした（${e.message}）")) }
            return
        }
        val ticket = res.str("ticket") ?: return
        viewerId = res.str("viewer_id")
        viewerHash = viewerId?.let(::viewerHash)
        _state.update { it.copy(holder = holderOf(res["lease"] as? JsonObject)) }
        socket?.close(CLOSE_PAUSED, null)
        val url = conn.api.displaySocketUrl(res.str("path") ?: "/api/display/ws", ticket)
        socket = app.http.newWebSocket(Request.Builder().url(url).build(), Picture())
        if (resumeControl) {
            // Back from the background: carry on with the control this phone had (no retry here, the id is fresh).
            resumeControl = false
            if (_state.value.holder != ScreenHolder.Me) {
                runCatching { requestLease("display.lease.acquire") }.onSuccess { lease -> _state.update { it.copy(holder = holderOf(lease)) } }
            }
        }
    }

    fun start() {
        _state.update { it.copy(phase = ScreenPhase.Stopped(starting = true)) }
        viewModelScope.launch {
            try {
                request("display.start")
                open()
            } catch (e: Exception) {
                _state.update { it.copy(phase = ScreenPhase.Stopped(error = "起動できませんでした（${e.message}）")) }
            }
        }
    }

    fun retry() {
        retries = 0
        viewModelScope.launch { open() }
    }

    private fun unavailable(e: Exception): ScreenPhase =
        if (e is RpcException && (e.code == -32601 || e.message.orEmpty().contains("method not found", ignoreCase = true)))
            ScreenPhase.Unavailable("このサーバーの Hermes は Bot Desktop に対応していません。Hermes を更新してください")
        else ScreenPhase.Failed("画面の状態を取れませんでした（${e.message}）")

    // ---- the lease ----------------------------------------------------------------------------

    fun takeControl() {
        viewModelScope.launch { acquire() }
    }

    private suspend fun acquire() {
        _state.update { it.copy(busy = true, notice = null) }
        val lease = runCatching { requestLease("display.lease.acquire") }.recoverCatching {
            // The JSON-RPC socket reconnected since the ticket was minted, and viewer ids die with their connection.
            attaching.withLock { observe() }
            requestLease("display.lease.acquire")
        }
        _state.update { s ->
            lease.fold(
                onSuccess = { s.copy(busy = false, holder = holderOf(it)) },
                onFailure = { e -> s.copy(busy = false, notice = "操作を始められませんでした（${e.message}）") },
            )
        }
    }

    fun handBack() {
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val lease = runCatching { requestLease("display.lease.release") }
            _state.update { s ->
                lease.fold(
                    onSuccess = { s.copy(busy = false, holder = holderOf(it)) },
                    onFailure = { e -> s.copy(busy = false, notice = "操作を返せませんでした（${e.message}）") },
                )
            }
        }
    }

    private suspend fun requestLease(method: String): JsonObject? {
        val id = viewerId ?: throw IllegalStateException("画面につながっていません")
        return request(method) { put("viewer_id", id) }["lease"] as? JsonObject
    }

    private fun holderOf(lease: JsonObject?): ScreenHolder = when {
        lease?.str("holder") != "human" -> ScreenHolder.Agent
        lease.str("viewer_hash") == viewerHash -> ScreenHolder.Me
        else -> ScreenHolder.Other
    }

    private fun onEvent(e: GatewayEvent) {
        if (e.type != "display.lease" && e.type != "display.status") return
        if (profileKey == null || e.payload.str("profile_key") != profileKey) return
        when (e.type) {
            "display.lease" -> _state.update { it.copy(holder = holderOf(e.payload["lease"] as? JsonObject)) }
            // Started or stopped from somewhere else (the bot itself, Desktop, the idle timer).
            "display.status" -> {
                val running = e.payload.bool("running") == true
                val showing = _state.value.phase.let { it is ScreenPhase.Live || it is ScreenPhase.Connecting }
                if (attached && running != showing) viewModelScope.launch { open() }
            }
        }
    }

    // ---- input --------------------------------------------------------------------------------

    private var pointerX = 0
    private var pointerY = 0

    /** Moves the bot's pointer with [buttons] held; dropped by the gateway unless this phone holds the lease. */
    fun pointer(buttons: Int, x: Int, y: Int) {
        val s = _state.value
        if (s.holder != ScreenHolder.Me || s.width == 0) return
        pointerX = x.coerceIn(0, s.width - 1); pointerY = y.coerceIn(0, s.height - 1)
        socket?.send(RfbSession.pointerEvent(buttons, pointerX, pointerY).toByteString())
    }

    fun click(x: Int, y: Int, button: Int = RfbSession.BUTTON_LEFT) {
        pointer(0, x, y); pointer(button, x, y); pointer(0, x, y)
    }

    /** One notch of the wheel where the pointer last was. */
    fun wheel(up: Boolean) {
        val button = if (up) RfbSession.BUTTON_WHEEL_UP else RfbSession.BUTTON_WHEEL_DOWN
        repeat(3) { pointer(button, pointerX, pointerY); pointer(0, pointerX, pointerY) }
    }

    fun key(keysym: Int) {
        if (_state.value.holder != ScreenHolder.Me) return
        socket?.send(RfbSession.keyEvent(keysym, true).toByteString())
        socket?.send(RfbSession.keyEvent(keysym, false).toByteString())
    }

    /** Types [text] as key presses, so it lands wherever the bot's keyboard focus is. */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            key(RfbSession.keysym(cp))
            i += Character.charCount(cp)
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---- the picture --------------------------------------------------------------------------

    private inner class Picture : WebSocketListener(), RfbSession.Listener {
        private val stream = ChunkStream()
        private lateinit var session: RfbSession
        private var ws: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            session = RfbSession(stream, { webSocket.send(it.toByteString()) }, this)
            viewModelScope.launch(Dispatchers.IO) {
                // Ends with the stream; the close callbacks below decide what happens next.
                runCatching { session.run() }.onFailure { webSocket.close(1000, null) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = stream.offer(bytes.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(webSocket, null)

        private fun ended(webSocket: WebSocket, code: Int?) {
            stream.finish()
            if (webSocket !== socket) return // replaced, or closed by detach()
            socket = null
            when (code) {
                // Someone else took the screen: back to watching.
                CLOSE_CONTROL_TAKEN, CLOSE_BAD_TICKET -> reopen(0)
                CLOSE_DESKTOP_GONE -> reopen(0)
                else -> if (retries++ < MAX_RETRIES) reopen(1_000L * retries) else {
                    _state.update { it.copy(phase = ScreenPhase.Failed("画面との接続が切れました")) }
                }
            }
        }

        private fun reopen(afterMs: Long) {
            reattach = viewModelScope.launch {
                delay(afterMs)
                open()
            }
        }

        override fun onResize(width: Int, height: Int) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            _state.update { it.copy(width = width, height = height) }
        }

        override fun onUpdate(x: Int, y: Int, width: Int, height: Int) {
            if (ws !== socket) return
            val target = bitmap ?: return
            if (target.width != session.width || target.height != session.height) return
            target.setPixels(session.pixels, y * session.width + x, session.width, x, y, width, height)
            if (_state.value.phase != ScreenPhase.Live) _state.update { it.copy(phase = ScreenPhase.Live) }
            _frame.update { it + 1 }
        }
    }

    private suspend fun request(method: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        conn.socket.request(method, buildJsonObject { put("profile", bot.profile); params() }).jsonObject

    override fun onCleared() {
        socket?.close(1000, null)
        socket = null
    }

    private companion object {
        const val CLOSE_CONTROL_TAKEN = 4000
        const val CLOSE_DESKTOP_GONE = 4001
        const val CLOSE_BAD_TICKET = 4401
        /** Any code other than 1000/1001 reads as a dropped link, which keeps the lease. */
        const val CLOSE_PAUSED = 4900
        const val MAX_RETRIES = 3

        /** `viewer_hash` as the lease names its holder: the first 12 hex digits of sha256(viewer id). */
        fun viewerHash(viewerId: String): String =
            MessageDigest.getInstance("SHA-256").digest(viewerId.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
