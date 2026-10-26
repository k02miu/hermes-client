package io.github.k02miu.hermesmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupToolsTest {
    private fun tool(key: String) = ChatItem.Tool(key, "terminal", "{}", ToolState.Done)
    private fun text(key: String) = ChatItem.Assistant(key, "text")

    @Test fun `consecutive calls fold into one row, a lone call stays as it is`() {
        val rows = groupTools(listOf(text("a"), tool("t1"), tool("t2"), tool("t3"), text("b"), tool("t4")))
        assertEquals(4, rows.size)
        assertEquals(TranscriptRow.Single(text("a")), rows[0])
        assertEquals(listOf("t1", "t2", "t3"), (rows[1] as TranscriptRow.Tools).tools.map { it.key })
        assertEquals(TranscriptRow.Single(text("b")), rows[2])
        assertEquals(TranscriptRow.Single(tool("t4")), rows[3])
    }

    @Test fun `a group is keyed by its first call, so it keeps its place as calls are added`() {
        val before = groupTools(listOf(tool("t1"), tool("t2")))
        val after = groupTools(listOf(tool("t1"), tool("t2"), tool("t3")))
        assertEquals(before.single().key, after.single().key)
    }

    @Test fun `no items, no rows`() {
        assertEquals(emptyList<TranscriptRow>(), groupTools(emptyList()))
    }
}
