package com.pixelstandby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "standby_settings")

private const val BackendUrlKey = "backend_url"
private const val BearerTokenKey = "bearer_token"
private const val MediaEnabledKey = "media_enabled"
private const val NtfyServerKey = "ntfy_server"
private const val NtfyTopicKey = "ntfy_topic"
private const val NtfyTokenKey = "ntfy_token"
private const val DimLevelKey = "dim_level"

/**
 * User settings. Defaults are the "calm bedside clock" defaults: 12 hour, no seconds.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    private val preferences: Flow<Preferences> = store.data
        .catch { cause ->
            // A corrupt or unreadable store should degrade to defaults, not kill the dream.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }

    val clock24Hour: Flow<Boolean> = preferences.map { it[booleanPreferencesKey("clock_24_hour")] ?: false }

    val showSeconds: Flow<Boolean> = preferences.map { it[booleanPreferencesKey("clock_show_seconds")] ?: false }

    val autoDimEnabled: Flow<Boolean> = preferences.map { it[booleanPreferencesKey("auto_dim_enabled")] ?: true }

    val dashboardEnabled: Flow<Boolean> =
        preferences.map { it[booleanPreferencesKey("dashboard_enabled")] ?: true }

    val backendUrl: Flow<String> =
        preferences.map { it[stringPreferencesKey(BackendUrlKey)] ?: "" }

    val bearerToken: Flow<String> =
        preferences.map { it[stringPreferencesKey(BearerTokenKey)] ?: "" }

    val mediaEnabled: Flow<Boolean> =
        preferences.map { it[booleanPreferencesKey(MediaEnabledKey)] ?: true }

    val ntfyServer: Flow<String> =
        preferences.map { it[stringPreferencesKey(NtfyServerKey)] ?: "https://ntfy.sh" }

    val ntfyTopic: Flow<String> =
        preferences.map { it[stringPreferencesKey(NtfyTopicKey)] ?: "" }

    val ntfyToken: Flow<String> =
        preferences.map { it[stringPreferencesKey(NtfyTokenKey)] ?: "" }

    val dimLevel: Flow<String> =
        preferences.map { it[stringPreferencesKey(DimLevelKey)] ?: "medium" }

    suspend fun setMediaEnabled(value: Boolean) = store.edit { it[booleanPreferencesKey(MediaEnabledKey)] = value }

    suspend fun setNtfyServer(value: String) = store.edit { it[stringPreferencesKey(NtfyServerKey)] = value }

    suspend fun setNtfyTopic(value: String) = store.edit { it[stringPreferencesKey(NtfyTopicKey)] = value }

    suspend fun setNtfyToken(value: String) = store.edit { it[stringPreferencesKey(NtfyTokenKey)] = value }

    suspend fun setDimLevel(value: String) = store.edit { it[stringPreferencesKey(DimLevelKey)] = value }

    suspend fun setClock24Hour(value: Boolean) = store.edit { it[booleanPreferencesKey("clock_24_hour")] = value }

    suspend fun setShowSeconds(value: Boolean) = store.edit { it[booleanPreferencesKey("clock_show_seconds")] = value }

    suspend fun setAutoDimEnabled(value: Boolean) = store.edit { it[booleanPreferencesKey("auto_dim_enabled")] = value }

    suspend fun setDashboardEnabled(value: Boolean) = store.edit { it[booleanPreferencesKey("dashboard_enabled")] = value }

    suspend fun setBackendUrl(value: String) = store.edit { it[stringPreferencesKey(BackendUrlKey)] = value }

    suspend fun setBearerToken(value: String) = store.edit { it[stringPreferencesKey(BearerTokenKey)] = value }
}