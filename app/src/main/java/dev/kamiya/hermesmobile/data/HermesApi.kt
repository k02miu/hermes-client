package dev.kamiya.hermesmobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HermesHttpException(val code: Int, message: String) : IOException(message)

/**
 * REST side of the gateway's dashboard listener (the same surface Hermes Desktop's remote mode uses).
 * Every call carries the session cookies from [PersistentCookieJar]; `Origin` satisfies the CSRF guard.
 */
class HermesApi(private val client: OkHttpClient, private val baseUrl: String) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** Runs the `tailscale` provider's OAuth round trip by following its redirects; cookies land in the jar. */
    suspend fun loginWithTailscale() {
        val url = base.newBuilder().addPathSegments("auth/login").addQueryParameter("provider", "tailscale").build()
        execute(Request.Builder().url(url).get().build())
    }

    suspend fun me(): Me = get("api/auth/me")

    suspend fun profiles(): List<Profile> = get<ProfilesResponse>("api/profiles").profiles

    suspend fun recentSessions(profile: String, limit: Int = 50): List<SessionSummary> =
        get<SidebarResponse>("api/profiles/sessions/sidebar") {
            addQueryParameter("recents_profile", profile)
            addQueryParameter("recents_exclude", "cron,tool")
            addQueryParameter("recents_limit", limit.toString())
        }.recents.sessions

    /** The latest [limit] rows (skipping [offset] newer ones), oldest first; images come back as `[image]`. */
    suspend fun messages(sessionId: String, profile: String, limit: Int, offset: Int = 0): MessagesResponse =
        get("api/sessions/$sessionId/messages") {
            addQueryParameter("profile", profile)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())
            addQueryParameter("order", "latest")
            addQueryParameter("inline_images", "false")
        }

    suspend fun wsTicket(): WsTicket {
        val req = Request.Builder().url(base.resolve("api/auth/ws-ticket")!!).post(ByteArray(0).toRequestBody()).build()
        return json.decodeFromString(execute(req))
    }

    fun webSocketUrl(ticket: String): String =
        base.newBuilder().addPathSegments("api/ws").addQueryParameter("ticket", ticket).build()
            .toString().replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    private suspend inline fun <reified T> get(path: String, crossinline query: HttpUrl.Builder.() -> Unit = {}): T {
        val url = base.newBuilder().addPathSegments(path).apply { query() }.build()
        return json.decodeFromString(execute(Request.Builder().url(url).get().build()))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request.newBuilder().header("Origin", base.toString().trimEnd('/')).build()).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw HermesHttpException(resp.code, "HTTP ${resp.code} ${request.url.encodedPath}")
            body
        }
    }
}
