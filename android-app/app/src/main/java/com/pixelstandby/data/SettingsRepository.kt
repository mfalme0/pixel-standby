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

    suspend fun setClock24Hour(value: Boolean) = store.edit { it[booleanPreferencesKey("clock_24_hour")] = value }

    suspend fun setShowSeconds(value: Boolean) = store.edit { it[booleanPreferencesKey("clock_show_seconds")] = value }

    suspend fun setAutoDimEnabled(value: Boolean) = store.edit { it[booleanPreferencesKey("auto_dim_enabled")] = value }

    suspend fun setDashboardEnabled(value: Boolean) = store.edit { it[booleanPreferencesKey("dashboard_enabled")] = value }

    suspend fun setBackendUrl(value: String) = store.edit { it[stringPreferencesKey(BackendUrlKey)] = value }

    suspend fun setBearerToken(value: String) = store.edit { it[stringPreferencesKey(BearerTokenKey)] = value }
}