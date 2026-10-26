package io.github.k02miu.hermesmobile.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTextTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun `summary is the command, on one line`() {
        assertEquals("git status && git log -1", toolSummary("terminal", """{"command": "git status &&\n  git log -1", "timeout": 30}"""))
    }

    @Test fun `summary of code is its first non-blank line`() {
        assertEquals("import os", toolSummary("execute_code", """{"code": "\n\n  import os\nprint(os.getcwd())"}"""))
    }

    @Test fun `summary falls back to a path, then to every argument`() {
        assertEquals("/tmp/a.md", toolSummary("write_file", """{"path": "/tmp/a.md", "content": "hello"}"""))
        assertEquals("a=1 b=x", toolSummary("other", """{"a": 1, "b": "x"}""").replace("  ", " "))
    }

    @Test fun `summary of arguments that are not JSON is the text itself`() {
        assertEquals("ls -la", toolSummary("terminal", "ls   -la"))
    }

    @Test fun `input shows a command or code in full, anything else laid out`() {
        assertEquals("コマンド" to "echo 1\necho 2", toolInput("""{"command": "echo 1\necho 2"}"""))
        assertEquals("コード", toolInput("""{"code": "print(1)"}""").first)
        val (label, text) = toolInput("""{"path": "/tmp/a"}""")
        assertEquals("入力", label)
        assertTrue(text, text.contains("\"path\": \"/tmp/a\""))
    }

    @Test fun `terminal output is its text, with the error and a failing exit code`() {
        assertEquals("hello", toolOutput(json("""{"output": "hello", "exit_code": 0}""")))
        assertEquals("partial\n\nboom\n\nexit code 2", toolOutput(json("""{"output": "partial", "error": "boom", "exit_code": 2}""")))
    }

    @Test fun `history results come as JSON strings and are parsed`() {
        val stored = JsonPrimitive("""{"output": "done", "exit_code": 0}""")
        assertEquals("done", toolOutput(stored))
        assertFalse(toolFailed(stored))
    }

    @Test fun `plain text and missing results`() {
        assertEquals("just text", toolOutput(JsonPrimitive("just text")))
        assertNull(toolOutput(null))
        assertNull(toolOutput(json("null")))
    }

    @Test fun `objects without a text field are laid out`() {
        val out = toolOutput(json("""{"files": 3}"""))!!
        assertTrue(out, out.contains("\"files\": 3"))
    }

    @Test fun `failure is a non-zero exit, an error or success false`() {
        assertTrue(toolFailed(json("""{"output": "", "exit_code": 1}""")))
        assertTrue(toolFailed(json("""{"error": "not found"}""")))
        assertTrue(toolFailed(json("""{"success": false}""")))
        assertFalse(toolFailed(json("""{"output": "ok", "exit_code": 0, "error": null}""")))
        assertFalse(toolFailed(json("""{"error": ""}""")))
        assertFalse(toolFailed(null))
    }

    @Test fun `durations read in seconds, minutes and hours`() {
        assertEquals("2.5 秒", describeSeconds(2.5))
        assertEquals("42 秒", describeSeconds(42.0))
        assertEquals("8 分", describeSeconds(8 * 60 + 59.0))
        assertEquals("1 時間 5 分", describeSeconds(3600 + 5 * 60.0))
    }
}
