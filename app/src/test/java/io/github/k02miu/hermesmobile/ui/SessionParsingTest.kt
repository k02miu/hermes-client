package io.github.k02miu.hermesmobile.ui

import io.github.k02miu.hermesmobile.data.StoredMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionParsingTest {
    private fun call(id: String, name: String, args: String) =
        """{"id": "$id", "type": "function", "function": {"name": "$name", "arguments": ${JsonPrimitive(args)}}}"""

    private fun assistant(id: Long, text: String?, vararg calls: String) = StoredMessage(
        id = id, role = "assistant", content = text?.let(::JsonPrimitive),
        toolCalls = if (calls.isEmpty()) null else Json.parseToJsonElement(calls.joinToString(",", "[", "]")), timestamp = 1000.0 + id,
    )

    private fun result(id: Long, callId: String, content: String) =
        StoredMessage(id = id, role = "tool", content = JsonPrimitive(content), toolCallId = callId, toolName = "terminal")

    private val page = listOf(
        StoredMessage(id = 1, role = "user", content = JsonPrimitive("run the tests")),
        assistant(2, "Running them.", call("c1", "terminal", """{"command": "npm test"}"""), call("c2", "terminal", """{"command": "false"}""")),
        result(3, "c1", """{"output": "12 passed", "exit_code": 0}"""),
        result(4, "c2", """{"output": "", "exit_code": 1}"""),
        assistant(5, null, call("c3", "terminal", """{"command": "sleep 270; gh run view"}""")),
    )

    @Test fun `results join their calls by id`() {
        val tools = historyItems(page, running = false).filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf("npm test", "false", "sleep 270; gh run view"), tools.map { toolSummary(it.name, it.args) })
        assertEquals("12 passed", tools[0].output)
        assertEquals(ToolState.Done, tools[0].state)
        assertEquals(ToolState.Failed, tools[1].state)
        assertEquals(1005.0, tools[2].startedAt)
    }

    @Test fun `result rows are not shown on their own`() {
        val items = historyItems(page, running = false)
        assertEquals(listOf("m-1", "m-2", "m-2-t0", "m-2-t1", "m-5-t0"), items.map { it.key })
    }

    @Test fun `while running, the last call without a result is still running`() {
        val tools = historyItems(page, running = true).filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf(ToolState.Done, ToolState.Failed, ToolState.Running), tools.map { it.state })
        assertNull(tools[2].output)
    }

    @Test fun `once the turn is over, a call without a result is not shown as running`() {
        val tools = historyItems(page, running = false).filterIsInstance<ChatItem.Tool>()
        assertEquals(ToolState.Done, tools[2].state)
    }

    @Test fun `hidden rows, display text and text parts`() {
        val items = historyItems(listOf(
            StoredMessage(id = 1, role = "user", content = JsonPrimitive("[System: nudge]"), displayKind = "hidden"),
            StoredMessage(id = 2, role = "user", content = JsonPrimitive("raw"), displayContent = "shown"),
            StoredMessage(id = 3, role = "assistant", content = Json.parseToJsonElement("""[{"type": "text", "text": "a"}, {"type": "image"}, {"type": "text", "text": "b"}]""")),
        ), running = false)
        assertEquals(listOf(ChatItem.User("m-2", "shown"), ChatItem.Assistant("m-3", "a\nb")), items)
    }

    private val watcher = "R=org/repo; for i in \$(seq 1 60); do gh api repos/\$R/commits/abc/check-runs; sleep 30; done"

    private val calls = backgroundCalls(listOf(
        ChatItem.Tool("t1", "terminal", """{"command": ${JsonPrimitive(watcher)}, "background": true, "notify": true}""", ToolState.Done),
        ChatItem.Tool("t2", "terminal", """{"command": "pnpm dev", "background": true}""", ToolState.Done),
        ChatItem.Tool("t3", "terminal", """{"command": "tail -f log", "background": true, "watch_patterns": ["ERROR"]}""", ToolState.Done),
        ChatItem.Tool("t4", "terminal", """{"command": "ls"}""", ToolState.Done),
    ))

    @Test fun `only background terminal calls are remembered, with how they wake Hermes`() {
        assertEquals(setOf(watcher, "pnpm dev", "tail -f log"), calls.keys)
        assertEquals(true to emptyList<String>(), calls[watcher])
        assertEquals(false to emptyList<String>(), calls["pnpm dev"])
        assertEquals(false to listOf("ERROR"), calls["tail -f log"])
    }

    private fun processes(text: String) = parseProcesses(Json.parseToJsonElement(text).jsonObject, calls, now = 2000.0)

    @Test fun `wake-up flags come from the call when the row leaves them out`() {
        val list = processes("""{"processes": [
            {"session_id": "proc_a", "command": ${JsonPrimitive(watcher)}, "status": "running", "started_epoch": 1900.5, "output_tail": "waiting"},
            {"session_id": "proc_b", "command": "pnpm dev", "status": "running", "started_epoch": 1500.0}
        ]}""")
        val (a, b) = list
        assertTrue(a.notifyOnComplete)
        assertEquals(1900.5, a.startedAt)
        assertEquals("waiting", a.output)
        assertFalse(b.notifyOnComplete)
        assertTrue(SessionState("t", processes = list).waiting)
        assertFalse(SessionState("t", processes = listOf(b)).waiting)
        assertFalse(SessionState("t", running = true, processes = list).waiting)
    }

    @Test fun `commands cut at 200 characters still match their call`() {
        val long = "x".repeat(300)
        val calls = backgroundCalls(listOf(ChatItem.Tool("t", "terminal", """{"command": "$long", "background": true, "notify": true}""", ToolState.Done)))
        val row = parseProcesses(Json.parseToJsonElement("""{"processes": [{"session_id": "p", "command": "${long.take(200)}"}]}""").jsonObject, calls, 0.0)
        assertTrue(row.single().notifyOnComplete)
    }

    @Test fun `the row's own fields win, uptime fills in the start`() {
        val p = processes("""{"processes": [{"session_id": "p", "command": "pnpm dev", "status": "running",
            "uptime_seconds": 120, "notify_on_complete": true, "watch_patterns": ["ready"]}]}""").single()
        assertEquals(1880.0, p.startedAt)
        assertTrue(p.notifyOnComplete)
        assertEquals(listOf("ready"), p.watchPatterns)
    }

    @Test fun `running first, then newest first, and rows without an id are dropped`() {
        val list = processes("""{"processes": [
            {"session_id": "old", "command": "a", "status": "exited", "started_epoch": 10, "exit_code": 1},
            {"session_id": "run", "command": "b", "status": "running", "started_epoch": 5},
            {"session_id": "new", "command": "c", "status": "exited", "started_epoch": 20, "exit_code": 0},
            {"command": "no id"}
        ]}""")
        assertEquals(listOf("run", "new", "old"), list.map { it.id })
        assertEquals(1, list.last().exitCode)
    }

    @Test fun `no processes field, no processes`() {
        assertEquals(emptyList<BackgroundProcess>(), parseProcesses(JsonObject(emptyMap()), emptyMap(), 0.0))
    }
}
