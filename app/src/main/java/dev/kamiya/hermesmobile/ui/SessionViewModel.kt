package dev.kamiya.hermesmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kamiya.hermesmobile.HermesApplication
import dev.kamiya.hermesmobile.data.GatewayEvent
import dev.kamiya.hermesmobile.data.RpcException
import dev.kamiya.hermesmobile.data.ServerRequest
import dev.kamiya.hermesmobile.data.StoredMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * What a session pane opens: a stored session by id, the profile's hidden "Bot Chat" by title, or
 * a conversation to create ([newId] set; it doubles as the create's idempotent request id).
 */
data class SessionTarget(val profile: String, val sessionId: String?, val title: String, val newId: String? = null) {
    val key get() = "$profile/${sessionId ?: newId ?: BOT_CHAT}"

    companion object {
        const val BOT_CHAT = "Bot Chat"
        const val NEW_TITLE = "新しい会話"
        fun botChat(profile: String) = SessionTarget(profile, null, BOT_CHAT)
        fun newChat(profile: String) = SessionTarget(profile, null, NEW_TITLE, newId = "android-" + UUID.randomUUID())
    }
}

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val pending: Boolean = false) : ChatItem
    data class Assistant(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class Tool(override val key: String, val name: String, val detail: String, val state: ToolState) : ChatItem
    data class Notice(override val key: String, val text: String) : ChatItem
}

enum class ToolState { Running, Done, Failed }

/** One question of the clarify tool; a multi-select answer goes back as a JSON array of labels. */
data class ClarifyQuestion(val qid: String, val question: String, val choices: List<String>, val multiSelect: Boolean)

sealed interface PendingPrompt {
    val promptId: String

    data class Approval(
        override val promptId: String, val generation: Long,
        val command: String, val description: String, val choices: List<String>,
    ) : PendingPrompt

    /** A question published as a shared control (`clarify.request`), answered with `clarify.respond`. */
    data class Clarify(override val promptId: String, val generation: Long, val question: ClarifyQuestion) : PendingPrompt

    /**
     * The clarify tool's whole question set for a turn submitted with `clarify_batch` (Desktop does),
     * sent to attached viewers as a `clarify` server request. Each answer is locked with
     * `clarify.lock`; the last lock settles it. [answered] maps question id → the answer sent.
     */
    data class ClarifyBatch(
        override val promptId: String, val questions: List<ClarifyQuestion>, val answered: Map<String, String> = emptyMap(),
    ) : PendingPrompt
}

data class SessionState(
    val title: String,
    val items: List<ChatItem> = emptyList(),
    val prompts: List<PendingPrompt> = emptyList(),
    val running: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class SessionViewModel(
    private val app: HermesApplication,
    private val baseUrl: String,
    val target: SessionTarget,
) : ViewModel() {
    private val conn get() = app.connection(baseUrl)

    private val _state = MutableStateFlow(SessionState(title = target.title))
    val state: StateFlow<SessionState> = _state

    /** Runtime id the socket subscription uses; events and RPCs address this one. */
    private var liveId: String? = null
    private var storedId: String? = target.sessionId
    private var subscriptionId: String? = null

    init {
        viewModelScope.launch { conn.socket.events.collect(::onEvent) }
        viewModelScope.launch { conn.socket.requests.collect(::onServerRequest) }
        viewModelScope.launch { conn.socket.connected.collect { if (liveId != null) open() } }
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try {
            val known = storedId
            val creating = known == null && target.newId != null
            val snap = if (creating) {
                conn.socket.request("session.create", buildJsonObject {
                    put("profile", target.profile); put("request_id", target.newId); put("source", "gui")
                }).jsonObject
            } else {
                conn.socket.request("session.resume", buildJsonObject {
                    put("profile", target.profile)
                    put("omit_messages", true)
                    if (known != null) put("session_id", known) else put("title", SessionTarget.BOT_CHAT)
                }).jsonObject
            }
            liveId = snap.str("session_id")
            storedId = snap.str("stored_session_id") ?: liveId
            subscriptionId = snap.str("subscription_id")
            if (creating) {
                _state.update { it.copy(loading = false, error = null) }
                return
            }
            val title = (snap["info"] as? JsonObject)?.str("title")?.takeIf { it.isNotBlank() } ?: target.title
            val prompts = (snap["prompts"] as? JsonArray)?.mapNotNull { parsePrompt(it.jsonObject) }.orEmpty()
            _state.update {
                it.copy(title = title, prompts = prompts, running = snap.bool("running") ?: false, error = null)
            }
            loadHistory()
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "セッションを開けませんでした（${e.message}）") }
        }
    }

    private suspend fun loadHistory() {
        val id = storedId ?: return
        runCatching { conn.api.messages(id, target.profile, limit = 60) }
            .onSuccess { resp ->
                val history = resp.messages.flatMap(::toItems)
                _state.update { s ->
                    // Keep live rows that history has not caught up with yet (an answer still streaming).
                    val live = s.items.filter { it is ChatItem.Assistant && it.streaming || it is ChatItem.User && it.pending }
                    s.copy(items = history + live, loading = false)
                }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = "履歴を読めませんでした（${e.message}）") } }
    }

    fun send(text: String) {
        val id = liveId ?: return
        val body = text.trim().ifEmpty { return }
        val submissionId = "android-" + UUID.randomUUID()
        _state.update { it.copy(items = it.items + ChatItem.User(submissionId, body, pending = true)) }
        viewModelScope.launch {
            runCatching {
                conn.socket.request("prompt.submit", buildJsonObject {
                    put("profile", target.profile); put("session_id", id)
                    put("text", body); put("submission_id", submissionId)
                })
            }.onFailure { e -> notice("送信できませんでした（${e.message}）") }
        }
    }

    suspend fun readFile(path: String) = conn.api.readFile(path, target.profile)

    fun interrupt() {
        val id = liveId ?: return
        viewModelScope.launch {
            runCatching {
                conn.socket.request("session.interrupt", buildJsonObject { put("profile", target.profile); put("session_id", id) })
            }.onFailure { e -> notice("止められませんでした（${e.message}）") }
        }
    }

    /** [questionId] picks the question of a [PendingPrompt.ClarifyBatch]; the other kinds hold one. */
    fun answer(prompt: PendingPrompt, value: String, questionId: String = "") {
        val id = liveId ?: return
        viewModelScope.launch {
            runCatching {
                when (prompt) {
                    is PendingPrompt.Approval -> respond("approval.respond", id, prompt.promptId, prompt.generation, "choice", value)
                    is PendingPrompt.Clarify -> respond("clarify.respond", id, prompt.promptId, prompt.generation, "answer", value)
                    is PendingPrompt.ClarifyBatch -> lockAnswer(prompt, questionId, value)
                }
            }.onFailure { e -> notice("回答を送れませんでした（${(e as? RpcException)?.message ?: e.message}）") }
        }
    }

    private suspend fun respond(method: String, sessionId: String, promptId: String, generation: Long, field: String, value: String) {
        conn.socket.request(method, buildJsonObject {
            put("profile", target.profile); put("session_id", sessionId)
            put("execution_generation", generation); put("prompt_id", promptId)
            put(field, value)
        })
        removePrompt(promptId)
    }

    private suspend fun lockAnswer(batch: PendingPrompt.ClarifyBatch, questionId: String, value: String) {
        val res = conn.socket.request("clarify.lock", buildJsonObject {
            put("profile", target.profile); put("request_id", batch.promptId)
            put("question_id", questionId); put("answer", value)
        }).jsonObject
        val remaining = (res["remaining"] as? JsonArray)?.size ?: 0
        if (res.str("status") != "ok" || remaining == 0) removePrompt(batch.promptId)
        else _state.update { s ->
            s.copy(prompts = s.prompts.map {
                if (it is PendingPrompt.ClarifyBatch && it.promptId == batch.promptId) it.copy(answered = it.answered + (questionId to value)) else it
            })
        }
    }

    private fun onServerRequest(r: ServerRequest) {
        if (r.method != "clarify") return
        val sessionId = r.params.str("session_id")
        if (sessionId != liveId && sessionId != storedId) return
        val questions = (r.params["questions"] as? JsonArray)?.mapNotNull { q ->
            val o = q as? JsonObject ?: return@mapNotNull null
            ClarifyQuestion(o.str("qid") ?: return@mapNotNull null, o.str("question") ?: "", o.choices(), o.bool("multi_select") == true)
        }.orEmpty()
        if (questions.isEmpty()) return
        // A replayed request carries the answers already locked (null = skipped).
        val answered = (r.params["answers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: "" }.orEmpty()
        val batch = PendingPrompt.ClarifyBatch(r.id, questions, answered)
        _state.update { s -> s.copy(prompts = s.prompts.filterNot { it.promptId == r.id } + batch) }
    }

    override fun onCleared() {
        val id = liveId ?: return
        val subscription = subscriptionId ?: return
        val socket = conn.socket
        app.scope.launch {
            runCatching {
                socket.request("session.detach", buildJsonObject {
                    put("profile", target.profile); put("session_id", id); put("subscription_id", subscription)
                })
            }
        }
    }

    private fun onEvent(e: GatewayEvent) {
        if (e.sessionId != liveId && e.sessionId != storedId) return
        val p = e.payload
        when (e.type) {
            "message.start" -> _state.update { s ->
                val items = s.items.map { if (it is ChatItem.User && it.pending) it.copy(pending = false) else it }
                s.copy(running = true, items = items + ChatItem.Assistant("live-${e.seq}", "", streaming = true))
            }
            "message.delta" -> {
                val text = p.str("text") ?: return
                updateStreaming { it.copy(text = it.text + text) }
            }
            "message.complete" -> {
                val text = (p["text"] as? JsonPrimitive)?.contentOrNull
                updateStreaming { it.copy(text = text ?: it.text, streaming = false) }
                _state.update { it.copy(running = false) }
                viewModelScope.launch { loadHistory() }
            }
            "tool.start" -> {
                val detail = p.str("preview") ?: p.str("args_text") ?: ""
                _state.update {
                    it.copy(items = it.items + ChatItem.Tool("tool-" + (p.str("tool_id") ?: e.seq), p.str("name") ?: "tool", detail.take(160), ToolState.Running))
                }
            }
            "tool.complete" -> {
                val key = "tool-" + (p.str("tool_id") ?: "")
                _state.update { s ->
                    s.copy(items = s.items.map {
                        if (it is ChatItem.Tool && it.key == key) it.copy(state = ToolState.Done, detail = p.str("summary")?.take(160) ?: it.detail) else it
                    })
                }
            }
            "approval.request", "clarify.request" -> parsePrompt(p)?.let { prompt ->
                _state.update { s -> s.copy(prompts = s.prompts.filterNot { it.promptId == prompt.promptId } + prompt) }
            }
            "approval.settled", "clarify.settled" -> p.str("prompt_id")?.let(::removePrompt)
            "approval.cancelled" -> _state.update { it.copy(prompts = it.prompts.filterNot { p -> p is PendingPrompt.Approval }) }
            // A server request answered elsewhere, timed out or interrupted.
            "request.cancel" -> p.str("id")?.let(::removePrompt)
            "session.info" -> _state.update { s ->
                s.copy(running = p.bool("running") ?: s.running, title = p.str("title")?.takeIf { it.isNotBlank() } ?: s.title)
            }
            "session.title" -> p.str("title")?.let { t -> _state.update { it.copy(title = t) } }
            "error" -> p.str("message")?.let(::notice)
        }
    }

    private fun updateStreaming(change: (ChatItem.Assistant) -> ChatItem.Assistant) = _state.update { s ->
        val index = s.items.indexOfLast { it is ChatItem.Assistant && it.streaming }
        if (index < 0) s else s.copy(items = s.items.toMutableList().also { it[index] = change(it[index] as ChatItem.Assistant) })
    }

    private fun removePrompt(promptId: String) = _state.update { s -> s.copy(prompts = s.prompts.filterNot { it.promptId == promptId }) }

    private fun notice(text: String) = _state.update { it.copy(items = it.items + ChatItem.Notice("notice-" + System.nanoTime(), text)) }

    private fun parsePrompt(p: JsonObject): PendingPrompt? {
        val id = p.str("prompt_id") ?: return null
        val generation = p["execution_generation"]?.jsonPrimitive?.longOrNull ?: return null
        return when (p.str("kind")) {
            "approval" -> PendingPrompt.Approval(id, generation, p.str("command") ?: "", p.str("description") ?: "", p.choices())
            "clarify" -> PendingPrompt.Clarify(id, generation, ClarifyQuestion(id, p.str("question") ?: "", p.choices(), p.bool("multi_select") == true))
            else -> null
        }
    }

    private fun toItems(m: StoredMessage): List<ChatItem> {
        if (m.displayKind == "hidden") return emptyList()
        val key = "m-${m.id}"
        return when (m.role) {
            "user" -> listOfNotNull(textOf(m)?.let { ChatItem.User(key, it) })
            "assistant" -> buildList {
                textOf(m)?.let { add(ChatItem.Assistant(key, it)) }
                (m.toolCalls as? JsonArray)?.forEachIndexed { i, call ->
                    val fn = (call as? JsonObject)?.get("function") as? JsonObject
                    val name = fn?.str("name") ?: return@forEachIndexed
                    add(ChatItem.Tool("$key-t$i", name, fn.str("arguments")?.take(160) ?: "", ToolState.Done))
                }
            }
            else -> emptyList()
        }
    }

    private fun textOf(m: StoredMessage): String? {
        m.displayContent?.takeIf { it.isNotBlank() }?.let { return it }
        return when (val c = m.content) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { part -> (part as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("\n")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.choices(): List<String> = (this["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
