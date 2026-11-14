package io.github.k02miu.hermesmobile

import android.app.Application
import io.github.k02miu.hermesmobile.data.GatewaySocket
import io.github.k02miu.hermesmobile.data.HermesApi
import io.github.k02miu.hermesmobile.data.PersistentCookieJar
import io.github.k02miu.hermesmobile.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Holds the process-wide clients: one API and one socket per server, sharing the HTTP client and cookie jar. */
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

    private val connections = mutableMapOf<String, Connection>()

    class Connection(val baseUrl: String, val api: HermesApi, val socket: GatewaySocket)

    @Synchronized
    fun connection(baseUrl: String): Connection = connections.getOrPut(baseUrl) {
        val api = HermesApi(http, baseUrl)
        Connection(baseUrl, api, GatewaySocket(http, api, scope))
    }

    /** Drops a server: closes its socket and signs out of it. */
    @Synchronized
    fun forget(baseUrl: String) {
        val connection = connections.remove(baseUrl) ?: return
        connection.socket.close()
        cookies.clear(connection.api.base)
    }
}
