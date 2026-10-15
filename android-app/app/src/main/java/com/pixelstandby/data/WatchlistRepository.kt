package com.pixelstandby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val WatchlistKey = "watchlist"

/**
 * The user's watchlist, persisted in DataStore.
 *
 * Symbols are stored as a single comma-separated string so we can use the preferences
 * DataStore (no Protobuf schema needed). The list is canonicalised: upper-cased, trimmed,
 * and de-duplicated while preserving order.
 */
class WatchlistRepository(private val context: Context) {

    private val store: DataStore<Preferences> = context.watchlistDataStore

    private val preferences: Flow<Preferences> = store.data
        .catch { cause ->
            if (cause is java.io.IOException) emit(emptyPreferences()) else throw cause
        }

    /** Current watchlist, defaulting to the built-in symbols plus XAUUSD. */
    val watchlist: Flow<List<String>> = preferences.map { readWatchlist(it) }

    suspend fun setWatchlist(symbols: List<String>) {
        store.edit { it[stringPreferencesKey(WatchlistKey)] = canonicalise(symbols).joinToString(",") }
    }

    private fun readWatchlist(prefs: Preferences): List<String> {
        val raw = prefs[stringPreferencesKey(WatchlistKey)] ?: return DEFAULT_WATCHLIST
        return canonicalise(raw.split(",").map { it.trim() })
    }

    private fun canonicalise(symbols: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return symbols
            .map { it.uppercase() }
            .filter { it.isNotEmpty() && seen.add(it) }
    }

    companion object {
        private const val DEFAULT_WATCHLIST_STR = "AAPL,MSFT,GOOGL,AMZN,NVDA,XAUUSD"
        val DEFAULT_WATCHLIST: List<String> = DEFAULT_WATCHLIST_STR.split(",")
    }
}

private val Context.watchlistDataStore: DataStore<Preferences> by preferencesDataStore(name = "standby_settings")