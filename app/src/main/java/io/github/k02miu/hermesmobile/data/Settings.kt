package io.github.k02miu.hermesmobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "hermes")

/** App-private persisted settings: the servers signed in to, the theme and session cookies. */
class Settings(private val context: Context) {
    private object Keys {
        val servers = stringPreferencesKey("servers")
        /** The one server of versions before several could be added; read once as the first of [servers]. */
        val serverUrl = stringPreferencesKey("server_url")
        val cookies = stringPreferencesKey("cookies")
        val theme = stringPreferencesKey("theme")
    }

    val theme: Flow<String?> = context.dataStore.data.map { it[Keys.theme] }

    /** Server origins in the order they were added. */
    suspend fun servers(): List<String> {
        val prefs = context.dataStore.data.first()
        return decodeServers(prefs[Keys.servers], prefs[Keys.serverUrl])
    }

    suspend fun setServers(urls: List<String>) = context.dataStore.edit {
        it[Keys.servers] = Json.encodeToString(urls)
        it.remove(Keys.serverUrl)
    }

    suspend fun setTheme(name: String) = context.dataStore.edit { it[Keys.theme] = name }

    suspend fun readCookies(): String? = context.dataStore.data.first()[Keys.cookies]
    suspend fun writeCookies(json: String) = context.dataStore.edit { it[Keys.cookies] = json }

    companion object {
        fun decodeServers(stored: String?, legacy: String?): List<String> =
            stored?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: listOfNotNull(legacy?.takeIf { it.isNotBlank() })
    }
}
