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

    private val _state = MutableStateFlow(SocketState.Disconnected)
    val state: StateFlow<SocketState> = _state

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<GatewayEvent> = _events

    private val _requests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 16)
    val requests: SharedFlow<ServerRequest> = _requests

    /** Fires after every successful (re)connect so screens can re-resume their sessions. */
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connected: SharedFlow<Unit> = _connected

    suspend fun ensureConnected() {
        if (_state.value == SocketState.Connected) return
        if (_state.value == SocketState.Connecting) {
            ready.await(); return
        }
        _state.value = SocketState.Connecting
        ready = CompletableDeferred()
        val ticket = try {
            api.wsTicket().ticket
        } catch (e: Exception) {
            _state.value = SocketState.Disconnected
            ready.completeExceptionally(e)
            throw e
        }
        socket = client.newWebSocket(Request.Builder().url(api.webSocketUrl(ticket)).build(), Listener())
        withTimeout(20_000) { ready.await() }
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
        socket?.close(1000, null)
        socket = null
        heartbeat?.cancel()
        _state.value = SocketState.Disconnected
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(15_000)
                runCatching { request("gateway.ping") }.onFailure { socket?.cancel() }
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

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = drop(IOException("closed $code"))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = drop(t)

        private fun drop(cause: Throwable) {
            heartbeat?.cancel()
            _state.value = SocketState.Disconnected
            if (!ready.isCompleted) ready.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(IOException("connection lost", cause)) }
            pending.clear()
        }
    }
}
