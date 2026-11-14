package io.github.k02miu.hermesmobile.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Parcelable
import android.util.Log
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.k02miu.hermesmobile.HermesApplication
import io.github.k02miu.hermesmobile.data.HermesHttpException
import io.github.k02miu.hermesmobile.data.Profile
import io.github.k02miu.hermesmobile.data.SessionSummary
import io.github.k02miu.hermesmobile.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class ServerStatus { Connecting, Ready, Failed }

/** A bot is a Hermes profile on one server; the same profile name can exist on two servers. */
@Parcelize
data class BotRef(val server: String, val profile: String) : Parcelable {
    @IgnoredOnParcel val key = "$server|$profile"
}

/** What a bot's row says about its Bot Desktop; only bots whose host can run one have it. */
data class ScreenBadge(val profileKey: String, val running: Boolean, val humanHolds: Boolean)

/** Where a bot's standing chat stands: the sidebar's session list leaves that chat out, so it is read on its own. */
data class BotChatSummary(val preview: String, val lastAt: Double, val running: Boolean) {
    companion object {
        /**
         * "Running" as the sidebar means it for other conversations: something happened within five minutes.
         * (`session.list` has a `running` of its own, but that only says the chat is loaded in the gateway.)
         */
        fun of(preview: String, lastAt: Double, now: Double) = BotChatSummary(preview, lastAt, running = lastAt > 0 && now - lastAt < 300)
    }
}

data class Bot(
    val ref: BotRef,
    val profile: Profile,
    val sessions: List<SessionSummary> = emptyList(),
    val screen: ScreenBadge? = null,
    val chat: BotChatSummary? = null,
) {
    val label get() = profile.label
    val running get() = chat?.running == true || sessions.any { it.isActive }
    val latest get() = sessions.maxByOrNull { it.activeAt }
    /** When anything last happened with this bot, in its standing chat or any other conversation. */
    val lastAt get() = maxOf(chat?.lastAt ?: 0.0, latest?.activeAt ?: 0.0)
    /** One line saying what that was: whichever of the two is newer. */
    val summary: String get() {
        val other = latest
        val line = if (chat != null && chat.preview.isNotBlank() && chat.lastAt >= (other?.activeAt ?: 0.0)) chat.preview else other?.displayTitle
        return line ?: profile.description.ifBlank { profile.model }
    }
}

data class ServerState(
    val url: String,
    val status: ServerStatus = ServerStatus.Connecting,
    val user: String = "",
    val bots: List<Bot> = emptyList(),
    val error: String? = null,
) {
    val host get() = hostOf(url)
}

data class AppState(
    /** False until the saved servers have been read. */
    val loaded: Boolean = false,
    val servers: List<ServerState> = emptyList(),
    val refreshing: Boolean = false,
    val notice: String? = null,
) {
    fun bot(ref: BotRef): Bot? = servers.firstOrNull { it.url == ref.server }?.bots?.firstOrNull { it.ref == ref }
}

/** The "add a server" form: shown on first run, and from the server list afterwards. */
data class AddServerState(val url: String = "", val busy: Boolean = false, val error: String? = null)

/** `https://kamiya-mac.tail1234.ts.net` → `kamiya-mac`. */
fun hostOf(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('.')

fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HermesApplication

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private val _adding = MutableStateFlow<AddServerState?>(null)
    val adding: StateFlow<AddServerState?> = _adding

    /** Bot key → the bot's avatar (`<profile home>/assets/avatar.*`), when it has one. */
    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars

    val themeMode: StateFlow<ThemeMode> = app.settings.theme
        .map { saved -> ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { app.settings.setTheme(mode.name) }
    }

    private val watchers = mutableMapOf<String, Job>()
    private val reloads = mutableMapOf<String, MutableSharedFlow<Unit>>()

    private val loading: Job = viewModelScope.launch {
        app.cookies.load()
        val urls = app.settings.servers()
        _state.update { it.copy(loaded = true, servers = urls.map(::ServerState)) }
        urls.forEach { url -> launch { connect(url, interactive = false) } }
    }

    // ---- servers ----------------------------------------------------------------------------

    fun startAdding() { _adding.value = AddServerState() }
    fun cancelAdding() { _adding.value = null }

    /** Signs in to a new server and keeps it; a connect link (`hermesmobile://connect?url=`) lands here too. */
    fun addServer(rawUrl: String) {
        val url = normalizeServerUrl(rawUrl)
        if (url.isEmpty()) {
            _adding.value = AddServerState(rawUrl, error = "サーバー URL を入力してください"); return
        }
        _adding.value = AddServerState(url, busy = true)
        viewModelScope.launch {
            loading.join()
            if (_state.value.servers.any { it.url == url }) {
                _adding.value = null
                connect(url, interactive = true)
                return@launch
            }
            try {
                val user = signIn(url)
                _state.update { it.copy(servers = it.servers + ServerState(url, user = user)) }
                _adding.value = null
                app.settings.setServers(_state.value.servers.map { it.url })
                connect(url, interactive = true)
            } catch (e: Exception) {
                app.forget(url)
                _adding.value = AddServerState(url, error = describe(e))
            }
        }
    }

    fun removeServer(url: String) {
        watchers.remove(url)?.cancel()
        reloads.remove(url)
        app.forget(url)
        _state.update { it.copy(servers = it.servers.filterNot { s -> s.url == url }) }
        viewModelScope.launch { app.settings.setServers(_state.value.servers.map { it.url }) }
    }

    fun retry(url: String) {
        viewModelScope.launch { connect(url, interactive = true) }
    }

    /** Reuses the stored session; when it has lapsed, the Tailscale provider signs in again with no prompt. */
    private suspend fun signIn(url: String): String {
        val api = app.connection(url).api
        val me = runCatching { api.me() }.getOrElse { e ->
            if (e is HermesHttpException && e.code == 401) {
                api.loginWithTailscale(); api.me()
            } else throw e
        }
        return me.displayName.ifBlank { me.userId }
    }

    private suspend fun connect(url: String, interactive: Boolean) {
        updateServer(url) { it.copy(status = ServerStatus.Connecting, error = null) }
        try {
            val user = signIn(url)
            val profiles = app.connection(url).api.profiles()
            updateServer(url) { s ->
                s.copy(status = ServerStatus.Ready, user = user, error = null, bots = profiles.map { p ->
                    val ref = BotRef(url, p.name)
                    s.bots.firstOrNull { it.ref == ref }?.copy(profile = p) ?: Bot(ref, p)
                })
            }
            watch(url)
            loadActivity(url)
            loadAvatars(url)
        } catch (e: Exception) {
            updateServer(url) { it.copy(status = ServerStatus.Failed, error = describe(e).takeIf { interactive || e !is HermesHttpException || e.code != 401 } ?: "サインインし直してください") }
        }
    }

    private fun updateServer(url: String, change: (ServerState) -> ServerState) =
        _state.update { it.copy(servers = it.servers.map { s -> if (s.url == url) change(s) else s }) }

    private fun updateBot(ref: BotRef, change: (Bot) -> Bot) =
        updateServer(ref.server) { s -> s.copy(bots = s.bots.map { if (it.ref == ref) change(it) else it }) }

    // ---- what each bot is doing ---------------------------------------------------------------

    /** Back from the background or a pull on the refresh button: failed servers try again, the rest reload. */
    fun refresh() {
        if (!_state.value.loaded) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            coroutineScope {
                _state.value.servers.forEach { s ->
                    launch { if (s.status == ServerStatus.Ready) loadActivity(s.url) else if (s.status == ServerStatus.Failed) connect(s.url, interactive = false) }
                }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Every bot's recent conversations (for "running" and the latest title) and its Bot Desktop, side by side. */
    private suspend fun loadActivity(url: String) = coroutineScope {
        val conn = app.connection(url)
        val bots = _state.value.servers.firstOrNull { it.url == url }?.bots.orEmpty()
        bots.map { bot ->
            async {
                runCatching { conn.api.recentSessions(bot.ref.profile) }
                    .onSuccess { sessions ->
                        updateBot(bot.ref) { it.copy(sessions = sessions.sortedByDescending { s -> s.activeAt }) }
                        // The server answers again: whatever an earlier attempt complained about is over.
                        if (bots.first() === bot) updateServer(url) { it.copy(error = null) }
                    }
                    .onFailure { e ->
                        Log.w("Hermes", "sessions of ${bot.ref.profile} failed", e)
                        if (bots.first() === bot) updateServer(url) { it.copy(error = describe(e)) }
                    }
            }
            async {
                // The standing chat is hidden from every list; an exact-title lookup finds it without opening it.
                runCatching {
                    val found = conn.socket.request("session.list", buildJsonObject { put("profile", bot.ref.profile); put("title", SessionTarget.BOT_CHAT) })
                        .jsonObject["sessions"]?.let { it as? kotlinx.serialization.json.JsonArray }?.firstOrNull() as? JsonObject ?: return@runCatching null
                    val id = found.str("resolved_id") ?: found.str("id") ?: return@runCatching null
                    val last = chatPreview(conn.api.messages(id, bot.ref.profile, limit = 12).messages)
                    val now = System.currentTimeMillis() / 1000.0
                    // A turn in progress says so in the session itself, whichever Hermes process is running it.
                    val busy = runCatching { conn.api.sessionActivity(id, bot.ref.profile).running(now) }.getOrNull() != null
                    BotChatSummary.of(last?.first.orEmpty(), last?.second ?: 0.0, now).let { if (busy) it.copy(running = true) else it }
                }.onSuccess { chat -> updateBot(bot.ref) { it.copy(chat = chat) } }
            }
            async {
                val status = runCatching {
                    conn.socket.request("display.status", buildJsonObject { put("profile", bot.ref.profile) }).jsonObject
                }.getOrNull()
                updateBot(bot.ref) { it.copy(screen = status?.let(::screenBadge)) }
            }
        }.awaitAll()
    }

    private fun loadAvatars(url: String) {
        val conn = app.connection(url)
        val bots = _state.value.servers.firstOrNull { it.url == url }?.bots.orEmpty()
        viewModelScope.launch {
            bots.filterNot { it.ref.key in _avatars.value }.forEach { bot ->
                runCatching {
                    val res = conn.socket.request("profiles.get_asset", buildJsonObject { put("name", bot.ref.profile); put("asset", "avatar") }).jsonObject
                    if ((res["found"] as? JsonPrimitive)?.booleanOrNull != true) return@runCatching
                    val data = (res["data"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching
                    val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                    val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return@runCatching
                    _avatars.update { it + (bot.ref.key to bitmap.asImageBitmap()) }
                }
            }
        }
    }

    /** Follows one server's socket: conversations changing anywhere, and Bot Desktops starting, stopping or changing hands. */
    private fun watch(url: String) {
        if (watchers[url]?.isActive == true) return
        val conn = app.connection(url)
        val reload = reloads.getOrPut(url) { MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
        watchers[url] = viewModelScope.launch {
            launch { runCatching { conn.socket.ensureConnected() } }
            // A turn fires several of these in a row; one reload shortly after the last is enough.
            launch { reload.collectLatest { delay(400); loadActivity(url) } }
            // Nothing is replayed after a drop, so whatever happened meanwhile is read again.
            launch { conn.socket.connected.collect { reload.tryEmit(Unit) } }
            conn.socket.events.collect { e ->
                when (e.type) {
                    "sessions.changed", "session.title" -> reload.tryEmit(Unit)
                    "display.status" -> screenBadge(e.payload)?.let { badge -> updateScreen(url, badge.profileKey) { badge } }
                    "display.lease" -> {
                        val key = e.payload.str("profile_key") ?: return@collect
                        val human = (e.payload["lease"] as? JsonObject)?.str("holder") == "human"
                        updateScreen(url, key) { it.copy(humanHolds = human) }
                    }
                }
            }
        }
    }

    private fun updateScreen(url: String, profileKey: String, change: (ScreenBadge) -> ScreenBadge) = updateServer(url) { s ->
        s.copy(bots = s.bots.map { b -> if (b.screen?.profileKey == profileKey) b.copy(screen = change(b.screen)) else b })
    }

    /** Pins or unpins at once on screen, then on the gateway (where Desktop reads the same flag). */
    fun setPinned(ref: BotRef, session: SessionSummary, pinned: Boolean) {
        val conn = app.connection(ref.server)
        fun mark(value: Boolean) = updateBot(ref) { b -> b.copy(sessions = b.sessions.map { if (it.id == session.id) it.copy(pinned = value) else it }) }
        mark(pinned)
        viewModelScope.launch {
            runCatching { conn.api.setPinned(session.id, session.profile.ifBlank { ref.profile }, pinned) }
                .onSuccess { reloads[ref.server]?.tryEmit(Unit) }
                .onFailure { e ->
                    mark(!pinned)
                    _state.update { it.copy(notice = "ピン留めを変えられませんでした（${e.message}）") }
                }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun describe(e: Throwable): String = when {
        e is HermesHttpException && e.code == 403 -> "この Tailscale アカウントは許可されていません"
        e is HermesHttpException && e.code == 401 -> "サインインできませんでした。サーバー側のセットアップを確かめてください"
        e is HermesHttpException -> "サーバーがエラーを返しました（${e.code}）"
        else -> "接続できませんでした。Tailscale が ON か、サーバーが起動しているか確認してください"
    }
}

/** `display.status` (the RPC result or the event payload) → the row's badge; null where the host cannot run a screen. */
fun screenBadge(status: JsonObject): ScreenBadge? {
    if ((status["supported"] as? JsonPrimitive)?.booleanOrNull != true) return null
    if ((status["installed"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val key = status.str("profile_key") ?: return null
    return ScreenBadge(
        profileKey = key,
        running = (status["running"] as? JsonPrimitive)?.booleanOrNull == true,
        humanHolds = (status["lease"] as? JsonObject)?.str("holder") == "human",
    )
}

/**
 * The newest thing said in a conversation as one line, with when: a reply from the bot, or a prompt the user typed.
 * Tool results, empty tool-calling turns and injected notices (a background process ending) say nothing a list
 * row can use and are passed over. [messages] is oldest first, as the gateway returns it.
 */
fun chatPreview(messages: List<io.github.k02miu.hermesmobile.data.StoredMessage>): Pair<String, Double>? {
    for (m in messages.asReversed()) {
        if (m.role != "assistant" && m.role != "user") continue
        if (m.role == "user" && m.displayKind != null) continue
        val text = (m.displayContent ?: (m.content as? JsonPrimitive)?.takeIf { it.isString }?.content).orEmpty()
        val line = text.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "") }.firstOrNull { it.isNotBlank() } ?: continue
        if (line == "[SILENT]") continue // the bot's way of answering nothing
        // The time of the last message of all, so a turn still calling tools reads as recent.
        val at = messages.lastOrNull()?.timestamp ?: m.timestamp ?: 0.0
        return (if (m.role == "user") "あなた: $line" else line) to at
    }
    return null
}
