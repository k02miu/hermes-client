package io.github.k02miu.hermesmobile.ui

import android.app.Application
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

sealed interface AuthState {
    data object Loading : AuthState
    data class NeedsLogin(val serverUrl: String, val error: String? = null, val busy: Boolean = false) : AuthState
    data class Ready(val serverUrl: String, val user: String) : AuthState
}

data class ListState(
    val profiles: List<Profile> = emptyList(),
    val profile: String = "",
    val sessions: List<SessionSummary> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HermesApplication

    private val _auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val auth: StateFlow<AuthState> = _auth

    private val _list = MutableStateFlow(ListState())
    val list: StateFlow<ListState> = _list

    /** Profile name → the bot's avatar (`<profile home>/assets/avatar.*`), when it has one. */
    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars

    val themeMode: StateFlow<ThemeMode> = app.settings.theme
        .map { saved -> ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { app.settings.setTheme(mode.name) }
    }

    private var eventsJob: Job? = null

    init {
        viewModelScope.launch {
            app.cookies.load()
            val url = app.settings.serverUrl.first()
            // A connect link may have started a login meanwhile; leave that one in charge.
            if (_auth.value != AuthState.Loading) return@launch
            if (url.isNullOrBlank()) _auth.value = AuthState.NeedsLogin("") else signIn(url, interactive = false)
        }
    }

    val connection get() = (auth.value as? AuthState.Ready)?.let { app.connection(it.serverUrl) }

    fun login(rawUrl: String) {
        val url = normalize(rawUrl)
        if (url.isEmpty()) {
            _auth.value = AuthState.NeedsLogin(rawUrl, "サーバー URL を入力してください"); return
        }
        _auth.value = AuthState.NeedsLogin(url, busy = true)
        viewModelScope.launch { signIn(url, interactive = true) }
    }

    fun logout() {
        app.cookies.clear()
        connection?.socket?.close()
        _auth.value = AuthState.NeedsLogin((auth.value as? AuthState.Ready)?.serverUrl ?: "")
    }

    /** Reuses the stored session; when it has lapsed, the Tailscale provider signs in again with no prompt. */
    private suspend fun signIn(url: String, interactive: Boolean) {
        val api = app.connection(url).api
        try {
            val me = runCatching { api.me() }.getOrElse { e ->
                if (e is HermesHttpException && e.code == 401) {
                    api.loginWithTailscale(); api.me()
                } else throw e
            }
            app.settings.setServerUrl(url)
            _auth.value = AuthState.Ready(url, me.displayName.ifBlank { me.userId })
            loadProfiles()
            watchSessionChanges()
        } catch (e: Exception) {
            _auth.value = AuthState.NeedsLogin(url, describe(e, interactive))
        }
    }

    private suspend fun loadProfiles() {
        val conn = connection ?: return
        val profiles = runCatching { conn.api.profiles() }.getOrElse {
            _list.update { s -> s.copy(error = describe(it, true)) }; return
        }
        val saved = app.settings.profile.first()
        val chosen = profiles.firstOrNull { it.name == saved }?.name
            ?: profiles.firstOrNull { !it.isDefault }?.name
            ?: profiles.firstOrNull()?.name ?: ""
        _list.update { it.copy(profiles = profiles, profile = chosen) }
        refresh()
        loadAvatars(profiles.map { it.name })
    }

    private fun loadAvatars(names: List<String>) {
        val conn = connection ?: return
        viewModelScope.launch {
            names.filterNot { it in _avatars.value }.forEach { name ->
                runCatching {
                    val res = conn.socket.request("profiles.get_asset", buildJsonObject { put("name", name); put("asset", "avatar") }).jsonObject
                    if ((res["found"] as? JsonPrimitive)?.booleanOrNull != true) return@runCatching
                    val data = (res["data"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching
                    val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                    val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return@runCatching
                    _avatars.update { it + (name to bitmap.asImageBitmap()) }
                }
            }
        }
    }

    fun selectProfile(name: String) {
        if (name == _list.value.profile) return
        _list.update { it.copy(profile = name, sessions = emptyList()) }
        viewModelScope.launch { app.settings.setProfile(name) }
        refresh()
    }

    fun refresh() {
        val conn = connection ?: return
        val profile = _list.value.profile.ifBlank { return }
        _list.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            runCatching { conn.api.recentSessions(profile) }
                .onSuccess { sessions ->
                    _list.update {
                        if (it.profile != profile) it
                        else it.copy(sessions = sessions.sortedByDescending { s -> s.activeAt }, refreshing = false, error = null)
                    }
                }
                .onFailure { e -> _list.update { it.copy(refreshing = false, error = describe(e, true)) } }
        }
    }

    /** Pins or unpins at once on screen, then on the gateway (where Desktop reads the same flag). */
    fun setPinned(session: SessionSummary, pinned: Boolean) {
        val conn = connection ?: return
        val profile = session.profile.ifBlank { _list.value.profile }
        fun mark(value: Boolean) = _list.update { s -> s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(pinned = value) else it }) }
        mark(pinned)
        viewModelScope.launch {
            runCatching { conn.api.setPinned(session.id, profile, pinned) }
                .onSuccess { refresh() }
                .onFailure { e ->
                    mark(!pinned)
                    _list.update { it.copy(error = "ピン留めを変えられませんでした（${e.message}）") }
                }
        }
    }

    private fun watchSessionChanges() {
        val conn = connection ?: return
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            launch { runCatching { conn.socket.ensureConnected() } }
            conn.socket.events.collect { if (it.type == "sessions.changed" || it.type == "session.title") refresh() }
        }
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun describe(e: Throwable, interactive: Boolean): String? = when {
        !interactive && e is HermesHttpException && e.code == 401 -> null
        e is HermesHttpException && e.code == 403 -> "この Tailscale アカウントは許可されていません"
        e is HermesHttpException -> "サーバーがエラーを返しました（${e.code}）"
        else -> "接続できませんでした。Tailscale が ON か確認してください"
    }
}
