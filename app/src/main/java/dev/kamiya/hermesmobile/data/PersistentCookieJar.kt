package dev.kamiya.hermesmobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
)

/**
 * In-memory cookie jar mirrored to [Settings] so the gateway session survives app restarts.
 * The gateway's session cookies (hermes_session_at / _rt) are the only credential the app holds.
 */
class PersistentCookieJar(
    private val settings: Settings,
    private val scope: CoroutineScope,
) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()

    suspend fun load() {
        val raw = settings.readCookies() ?: return
        val stored = runCatching { json.decodeFromString<List<StoredCookie>>(raw) }.getOrDefault(emptyList())
        synchronized(cookies) {
            cookies.clear()
            stored.forEach { s ->
                cookies += Cookie.Builder()
                    .name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
                    .apply { if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain) }
                    .apply { if (s.secure) secure() }
                    .apply { if (s.httpOnly) httpOnly() }
                    .build()
            }
        }
    }

    fun clear() {
        synchronized(cookies) { cookies.clear() }
        scope.launch { settings.clearCookies() }
    }

    fun hasSession(): Boolean = synchronized(cookies) {
        val now = System.currentTimeMillis()
        cookies.any { it.name.endsWith("hermes_session_rt") && it.expiresAt > now }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this.cookies) {
            cookies.forEach { incoming ->
                this.cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                this.cookies += incoming
            }
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        cookies.filter { it.matches(url) }
    }

    private fun persist() {
        val snapshot = synchronized(cookies) {
            cookies.filter { it.persistent }.map {
                StoredCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly, it.persistent)
            }
        }
        scope.launch { settings.writeCookies(json.encodeToString(snapshot)) }
    }
}
