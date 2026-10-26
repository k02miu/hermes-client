package io.github.k02miu.hermesmobile.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k02miu.hermesmobile.HermesApplication
import io.github.k02miu.hermesmobile.data.GatewayEvent
import io.github.k02miu.hermesmobile.data.RpcException
import io.github.k02miu.hermesmobile.data.ServerRequest
import io.github.k02miu.hermesmobile.data.StoredMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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
    /** [args] is the call's arguments as JSON; [output] the readable result, once it has finished. */
    data class Tool(
        override val key: String, val name: String, val args: String, val state: ToolState,
        val output: String? = null, val durationS: Double? = null, val diff: String? = null,
        /** Epoch seconds the call was made, for the elapsed time while it runs. */
        val startedAt: Double? = null,
    ) : ChatItem
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

/**
 * A background process this session started (`terminal` with `background: true`), from `process.list`:
 * typically a watcher Hermes left running while it waits for Claude Code or a CI run to finish.
 */
data class BackgroundProcess(
    val id: String, val command: String, val status: String, val startedAt: Double?,
    val notifyOnComplete: Boolean, val watchPatterns: List<String>, val output: String, val exitCode: Int? = null,
) {
    val running get() = status == "running"
}

data class SessionState(
    val title: String,
    val items: List<ChatItem> = emptyList(),
    val prompts: List<PendingPrompt> = emptyList(),
    val running: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val processes: List<BackgroundProcess> = emptyList(),
) {
    /** Idle, but a process will wake Hermes when it ends (or prints what it watches for). */
    val waiting get() = !running && processes.any { it.running && (it.notifyOnComplete || it.watchPatterns.isNotEmpty()) }
}

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

    // Declared before init: its launches run immediately on the main dispatcher and reach open() at once.
    private val opening = Mutex()

    init {
        viewModelScope.launch { conn.socket.events.collect(::onEvent) }
        viewModelScope.launch { conn.socket.requests.collect(::onServerRequest) }
        viewModelScope.launch { conn.socket.connected.collect { if (liveId != null) open() } }
        viewModelScope.launch { open() }
    }

    /** Resumes (again, after a reconnect or a refresh: the gateway does not double the events) and reloads history. */
    private suspend fun open() = opening.withLock {
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

    /**
     * Re-reads the stored history when the pane comes back (reopened, or the app returns to the foreground):
     * this view model outlives the pane, and a socket that quietly died while the phone slept misses events.
     */
    fun refresh() {
        if (liveId == null) return // still opening; open() loads the history itself
        viewModelScope.launch {
            // A live socket is resumed directly so the running flag and pending prompts are current too; a dead one
            // reconnects, and its `connected` signal reopens every session.
            if (conn.socket.ping()) open() else runCatching { conn.socket.ensureConnected() }
        }
    }

    private suspend fun loadHistory() {
        val id = storedId ?: return
        runCatching { conn.api.messages(id, target.profile, limit = 60) }
            .onSuccess { resp ->
                val history = historyItems(resp.messages, running = _state.value.running)
                wakeCalls = backgroundCalls(history)
                _state.update { s ->
                    // Keep live rows that history has not caught up with yet (an answer still streaming), unless the
                    // turn is over: then a streaming row is one whose completion was missed while disconnected.
                    val live = s.items.filter { it is ChatItem.Assistant && it.streaming && s.running || it is ChatItem.User && it.pending }
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

    /** Polls the session's background processes while the pane is on screen, as Desktop's status stack does. */
    suspend fun watchProcesses() {
        while (true) {
            loadProcesses()
            delay(PROCESS_POLL_MS)
        }
    }

    /** Background `terminal` calls seen in history: command → (notify when done, output patterns that wake Hermes). */
    private var wakeCalls: Map<String, Pair<Boolean, List<String>>> = emptyMap()

    private suspend fun loadProcesses() {
        val id = liveId ?: return
        val res = runCatching {
            conn.socket.request("process.list", buildJsonObject { put("profile", target.profile); put("session_id", id) }).jsonObject
        }.onFailure { Log.w(TAG, "process.list failed", it) }.getOrNull() ?: return
        val processes = parseProcesses(res, wakeCalls, now = System.currentTimeMillis() / 1000.0)
        _state.update { it.copy(processes = processes) }
    }

    fun killProcess(processId: String) {
        val id = liveId ?: return
        viewModelScope.launch {
            runCatching {
                conn.socket.request("process.kill", buildJsonObject {
                    put("profile", target.profile); put("session_id", id); put("process_id", processId)
                })
            }.onFailure { e -> notice("止められませんでした（${e.message}）") }
            loadProcesses()
        }
    }

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
                val args = (p["args"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.toString() ?: p.str("args_text") ?: p.str("preview") ?: ""
                _state.update {
                    it.copy(items = it.items + ChatItem.Tool("tool-" + (p.str("tool_id") ?: e.seq), p.str("name") ?: "tool", args, ToolState.Running,
                        startedAt = System.currentTimeMillis() / 1000.0))
                }
            }
            "tool.complete" -> {
                val key = "tool-" + (p.str("tool_id") ?: "")
                val result = p["result"]
                _state.update { s ->
                    s.copy(items = s.items.map {
                        if (it is ChatItem.Tool && it.key == key) it.copy(
                            state = if (toolFailed(result)) ToolState.Failed else ToolState.Done,
                            output = toolOutput(result) ?: p.str("result_text") ?: p.str("summary"),
                            durationS = p["duration_s"]?.jsonPrimitive?.doubleOrNull,
                            diff = p.str("inline_diff")?.takeIf { d -> d.isNotBlank() },
                        ) else it
                    })
                }
                // A call that started a background process shows up in the process list right away.
                if (p.str("name") == "terminal") viewModelScope.launch { loadProcesses() }
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
}

private const val PROCESS_POLL_MS = 10_000L
private const val TAG = "HermesSession"
