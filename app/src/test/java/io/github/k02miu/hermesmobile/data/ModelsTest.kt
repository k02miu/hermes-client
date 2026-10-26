package io.github.k02miu.hermesmobile.data

import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's JSON, decoded with the same settings the app uses (unknown fields ignored). */
class ModelsTest {
    private val json = HermesApi(OkHttpClient(), "https://mac.example.ts.net").json

    @Test fun `history rows keep the call id that ties a result to its call`() {
        val page = json.decodeFromString<MessagesResponse>("""{"session_id": "s", "profile": "p", "messages": [
            {"id": 7, "role": "tool", "content": "{\"output\": \"ok\"}", "tool_call_id": "call_1", "tool_name": "terminal", "extra": 1},
            {"id": 8, "role": "assistant", "content": null, "tool_calls": [{"id": "call_2"}], "timestamp": 1.5}
        ], "pagination": {"limit": 60}}""")
        assertEquals("call_1", page.messages[0].toolCallId)
        assertTrue(page.messages[1].toolCalls is JsonArray)
        assertEquals(1.5, page.messages[1].timestamp)
    }

    @Test fun `sidebar rows carry the pin`() {
        val sidebar = json.decodeFromString<SidebarResponse>("""{"recents": {"sessions": [
            {"id": "a", "title": "pinned one", "pinned": true, "message_count": 3},
            {"id": "b", "preview": "no title"}
        ]}, "cron": {"sessions": []}}""")
        val (a, b) = sidebar.recents.sessions
        assertTrue(a.pinned)
        assertFalse(b.pinned)
        assertEquals("no title", b.displayTitle)
    }

    @Test fun `mutation snapshot gives the revision an edit must name`() {
        assertEquals(41L, json.decodeFromString<MutationSnapshot>("""{"session_id": "a", "exists": true, "runtime_revision": 41, "runtime_generation": 2}""").runtimeRevision)
    }

    @Test fun `web socket url swaps the scheme and carries the ticket`() {
        assertEquals("wss://mac.example.ts.net/api/ws?ticket=t1", HermesApi(OkHttpClient(), "https://mac.example.ts.net/").webSocketUrl("t1"))
    }
}
