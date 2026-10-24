package dev.kamiya.hermesmobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes")

/** App-private persisted settings: the server origin, the last selected profile, the theme and session cookies. */
class Settings(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val profile = stringPreferencesKey("profile")
        val cookies = stringPreferencesKey("cookies")
        val theme = stringPreferencesKey("theme")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[Keys.serverUrl] }
    val profile: Flow<String?> = context.dataStore.data.map { it[Keys.profile] }
    val theme: Flow<String?> = context.dataStore.data.map { it[Keys.theme] }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[Keys.serverUrl] = url }
    suspend fun setProfile(name: String) = context.dataStore.edit { it[Keys.profile] = name }
    suspend fun setTheme(name: String) = context.dataStore.edit { it[Keys.theme] = name }

    suspend fun readCookies(): String? = context.dataStore.data.first()[Keys.cookies]
    suspend fun writeCookies(json: String) = context.dataStore.edit { it[Keys.cookies] = json }
    suspend fun clearCookies() = context.dataStore.edit { it.remove(Keys.cookies) }
}
