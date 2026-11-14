package io.github.k02miu.hermesmobile.ui

import io.github.k02miu.hermesmobile.data.Profile
import io.github.k02miu.hermesmobile.data.SessionSummary
import io.github.k02miu.hermesmobile.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotsTest {
    private fun status(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun serversComeFromTheListOrTheOneServerOfOlderVersions() {
        assertEquals(listOf("https://a.ts.net", "https://b.ts.net"), Settings.decodeServers("""["https://a.ts.net","https://b.ts.net"]""", "https://old.ts.net"))
        assertEquals(listOf("https://old.ts.net"), Settings.decodeServers(null, "https://old.ts.net"))
        assertEquals(emptyList<String>(), Settings.decodeServers(null, null))
        // Every server removed is an empty list, not a fall back to the old one.
        assertEquals(emptyList<String>(), Settings.decodeServers("[]", "https://old.ts.net"))
    }

    @Test
    fun serverUrlsAreNormalizedAndNamedByHost() {
        assertEquals("https://sb3.tail1234.ts.net", normalizeServerUrl(" sb3.tail1234.ts.net/ "))
        assertEquals("http://127.0.0.1:9119", normalizeServerUrl("http://127.0.0.1:9119"))
        assertEquals("", normalizeServerUrl("  "))
        assertEquals("sb3", hostOf("https://sb3.tail1234.ts.net"))
    }

    @Test
    fun theSameProfileNameOnTwoServersIsTwoBots() {
        val a = BotRef("https://a.ts.net", "default")
        val b = BotRef("https://b.ts.net", "default")
        assertFalse(a.key == b.key)
        val state = AppState(servers = listOf(
            ServerState(a.server, bots = listOf(Bot(a, Profile("default", botTitle = "A")))),
            ServerState(b.server, bots = listOf(Bot(b, Profile("default", botTitle = "B")))),
        ))
        assertEquals("B", state.bot(b)?.label)
        assertNull(state.bot(BotRef(a.server, "missing")))
    }

    @Test
    fun aBotIsRunningWhenAnyConversationIsAndShowsItsLatest() {
        val bot = Bot(BotRef("s", "p"), Profile("p"), sessions = listOf(
            SessionSummary("old", title = "古い", lastActive = 10.0),
            SessionSummary("new", title = "新しい", lastActive = 20.0, isActive = true),
        ))
        assertTrue(bot.running)
        assertEquals("新しい", bot.latest?.displayTitle)
        assertFalse(Bot(BotRef("s", "p"), Profile("p")).running)
    }

    @Test
    fun onlyAHostThatCanRunAScreenGetsABadge() {
        assertNull(screenBadge(status("""{"supported":false,"installed":false,"running":false,"profile_key":"k"}""")))
        assertNull(screenBadge(status("""{"supported":true,"installed":false,"running":false,"profile_key":"k"}""")))
        val stopped = screenBadge(status("""{"supported":true,"installed":true,"running":false,"profile_key":"k","lease":{"holder":"agent"}}"""))
        assertEquals(ScreenBadge("k", running = false, humanHolds = false), stopped)
        val held = screenBadge(status("""{"supported":true,"installed":true,"running":true,"profile_key":"k","lease":{"holder":"human","viewer_hash":"abc"}}"""))
        assertEquals(ScreenBadge("k", running = true, humanHolds = true), held)
    }
}

class BotChatPreviewTest {
    private fun msg(role: String, text: String?, at: Double, kind: String? = null) = io.github.k02miu.hermesmobile.data.StoredMessage(
        role = role, content = text?.let { kotlinx.serialization.json.JsonPrimitive(it) }, displayKind = kind, timestamp = at)

    @Test
    fun thePreviewIsTheLastThingSaidNotAToolResultOrAnInjectedNotice() {
        val preview = chatPreview(listOf(
            msg("user", "見積りを出して", 10.0),
            msg("assistant", "", 11.0),
            msg("tool", """{"output":"..."}""", 12.0),
            msg("assistant", "## **PROD** の作成が走っています。\n続き", 13.0),
            msg("user", "[IMPORTANT: Background process ended]", 20.0, kind = "process_complete"),
        ))
        // Markdown markers go; the time is that of the newest row, so the bot reads as active until then.
        assertEquals("PROD の作成が走っています。" to 20.0, preview)
    }

    @Test
    fun aPromptStillWaitingForItsAnswerIsShownAsTheUsers() {
        assertEquals("あなた: これを直して" to 5.0, chatPreview(listOf(msg("assistant", "了解です", 1.0), msg("user", "これを直して", 5.0))))
        assertNull(chatPreview(listOf(msg("tool", "x", 1.0), msg("assistant", null, 2.0))))
        assertEquals("本文" to 9.0, chatPreview(listOf(msg("assistant", "本文", 3.0), msg("assistant", "[SILENT]", 9.0))))
        assertNull(chatPreview(emptyList()))
    }

    @Test
    fun aBotRowShowsWhicheverIsNewerOfItsChatAndItsOtherConversations() {
        val older = SessionSummary("s", title = "古い会話", lastActive = 10.0)
        val bot = Bot(BotRef("srv", "p"), Profile("p"), sessions = listOf(older), chat = BotChatSummary("最新の返答", 50.0, running = true))
        assertEquals("最新の返答", bot.summary)
        assertEquals(50.0, bot.lastAt, 0.0)
        assertTrue(bot.running)
        // Five minutes without anything new and the chat no longer counts as running.
        assertTrue(BotChatSummary.of("x", lastAt = 1000.0, now = 1200.0).running)
        assertFalse(BotChatSummary.of("x", lastAt = 1000.0, now = 1400.0).running)
        assertFalse(BotChatSummary.of("", lastAt = 0.0, now = 100.0).running)
        val quiet = bot.copy(chat = BotChatSummary("昔の返答", 5.0, running = false))
        assertEquals("古い会話", quiet.summary)
        assertEquals(10.0, quiet.lastAt, 0.0)
        assertEquals("説明", Bot(BotRef("srv", "p"), Profile("p", description = "説明")).summary)
    }
}
