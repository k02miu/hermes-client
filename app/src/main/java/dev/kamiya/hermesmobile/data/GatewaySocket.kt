package dev.kamiya.hermesmobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RpcException(val code: Int, message: String, val data: JsonElement?) : IOException(message)

/** One session event from the gateway's fan-out (`{"method":"event","params":{type, session_id, payload, seq}}`). */
data class GatewayEvent(val type: String, val sessionId: String, val payload: JsonObject, val seq: Long?)

/** A server→client request the session owner sends to attached viewers (`{"id":"srq-…","method","params"}`). */
data class ServerRequest(val id: String, val method: String, val params: JsonObject)

enum class SocketState { Disconnected, Connecting, Connected }

/**
 * The JSON-RPC WebSocket at `/api/ws` (newline-delimited frames, server speaks first with
 * `gateway.ready`). One socket serves every session the app has resumed.
 */
class GatewaySocket(
    private val client: OkHttpClient,
    private val api: HermesApi,
    private val scope: CoroutineScope,
) {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private var socket: WebSocket? = null
    private var ready = CompletableDeferred<Unit>()
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    @Volatile private var closed = false

    private val _state = MutableStateFlow(SocketState.Disconnected)
    val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 16)
    val requests: SharedFlow<ServerRequest> = _requests

    /** Fires after every successful (re)connect so screens can re-resume their sessions. */
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connected: SharedFlow<Unit> = _connected

    // One connect at a time: the reconnect loop and a returning screen can both ask at once.
    private val connecting = Mutex()

    suspend fun ensureConnected() = connecting.withLock {
        closed = false
        if (_state.value == SocketState.Connected) return@withLock
        _state.value = SocketState.Connecting
        ready = CompletableDeferred()
        val ticket = try {
            api.wsTicket().ticket
        } catch (e: Exception) {
            _state.value = SocketState.Disconnected
            ready.completeExceptionally(e)
            throw e
        }
        val ws = client.newWebSocket(Request.Builder().url(api.webSocketUrl(ticket)).build(), Listener())
        socket = ws
        try {
            withTimeout(20_000) { ready.await() }
        } catch (e: Exception) {
            // Never left "connecting": the next attempt must start over rather than wait on this one.
            if (socket === ws) {
                socket = null
                _state.value = SocketState.Disconnected
            }
            ws.cancel()
            throw e
        }
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        ensureConnected()
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        if (socket?.send(frame.toString()) != true) {
            pending.remove(id)
            throw IOException("socket not open")
        }
        return try {
            withTimeout(60_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        closed = true
        reconnect?.cancel()
        socket?.close(1000, null)
        socket = null
        heartbeat?.cancel()
        _state.value = SocketState.Disconnected
    }

    /**
     * Whether the connection still answers. A socket can look open after the phone slept or changed networks while
     * nothing reaches it; one that does not answer within a few seconds is torn down, which starts a reconnect.
     */
    suspend fun ping(): Boolean {
        if (_state.value != SocketState.Connected) return false
        val ok = runCatching { withTimeout(5_000) { request("gateway.ping") } }.isSuccess
        if (!ok) socket?.cancel()
        return ok
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(15_000)
                ping()
            }
        }
    }

    /**
     * Comes back on its own after a drop (Wi-Fi switch, the gateway restarting, the phone waking up): events that
     * happen while nothing is connected are never replayed, so waiting for the next request would leave open
     * sessions stale. Backs off to a minute; [connected] then tells sessions to resync.
     */
    private fun scheduleReconnect() {
        if (closed || reconnect?.isActive == true) return
        reconnect = scope.launch {
            var wait = 1_000L
            while (isActive && !closed && _state.value != SocketState.Connected) {
                delay(wait)
                runCatching { ensureConnected() }
                wait = (wait * 2).coerceAtMost(60_000)
            }
        }
    }

    private fun handleLine(line: String) {
        val frame = runCatching { api.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val id = frame["id"]?.jsonPrimitive?.longOrNull
        val method = frame["method"]?.jsonPrimitive?.contentOrNull
        when {
            method == null && id != null -> {
                val deferred = pending[id] ?: return
                val error = frame["error"]
                if (error != null && error !is JsonNull) {
                    val err = error.jsonObject
                    deferred.completeExceptionally(RpcException(
                        err["code"]?.jsonPrimitive?.intOrNull ?: -1,
                        err["message"]?.jsonPrimitive?.contentOrNull ?: "rpc error",
                        err["data"]))
                } else {
                    deferred.complete(frame["result"] ?: JsonNull)
                }
            }
            method == "event" -> {
                val params = frame["params"]?.jsonObject ?: return
                val type = params["type"]?.jsonPrimitive?.contentOrNull ?: return
                if (type == "gateway.ready") {
                    _state.value = SocketState.Connected
                    ready.complete(Unit)
                    startHeartbeat()
                    _connected.tryEmit(Unit)
                    return
                }
                _events.tryEmit(GatewayEvent(
                    type = type,
                    sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    payload = (params["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                    seq = params["seq"]?.jsonPrimitive?.longOrNull))
            }
            // A server→client request, e.g. the clarify batch of a turn Desktop submitted.
            method != null -> {
                val requestId = (frame["id"] as? JsonPrimitive)?.contentOrNull ?: return
                _requests.tryEmit(ServerRequest(requestId, method, (frame["params"] as? JsonObject) ?: JsonObject(emptyMap())))
            }
            else -> Unit
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            text.lineSequence().filter { it.isNotBlank() }.forEach(::handleLine)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = drop(webSocket, IOException("closed $code"))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = drop(webSocket, t)

        private fun drop(webSocket: WebSocket, cause: Throwable) {
            if (webSocket !== socket) return // a connection already replaced
            heartbeat?.cancel()
            _state.value = SocketState.Disconnected
            if (!ready.isCompleted) ready.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(IOException("connection lost", cause)) }
            pending.clear()
            scheduleReconnect()
        }
    }
}
