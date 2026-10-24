package dev.kamiya.hermesmobile

import android.app.Application
import dev.kamiya.hermesmobile.data.GatewaySocket
import dev.kamiya.hermesmobile.data.HermesApi
import dev.kamiya.hermesmobile.data.PersistentCookieJar
import dev.kamiya.hermesmobile.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds the process-wide clients. The API and socket are rebuilt whenever the server URL changes. */
class HermesApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { Settings(this) }
    val cookies by lazy { PersistentCookieJar(settings, scope) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private var connection: Connection? = null

    class Connection(val baseUrl: String, val api: HermesApi, val socket: GatewaySocket)

    @Synchronized
    fun connection(baseUrl: String): Connection {
        connection?.takeIf { it.baseUrl == baseUrl }?.let { return it }
        connection?.socket?.close()
        val api = HermesApi(http, baseUrl)
        return Connection(baseUrl, api, GatewaySocket(http, api, scope)).also { connection = it }
    }
}
