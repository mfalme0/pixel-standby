package com.pixelstandby.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.Result

/**
 * Holds the last-known quotes and refreshes them periodically from the backend.
 *
 * Quotes live in a [StateFlow] so the UI always reflects the latest values. A refresh
 * loop runs every 60 seconds while the dream is visible and is cancelled on detach.
 * On network failure the last values are kept and marked stale.
 */
class QuotesRepository(
    private val client: QuotesClient,
    private val watchlist: Flow<List<String>>,
    private val scope: CoroutineScope,
) {

    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Start the periodic refresh loop. Safe to call multiple times; only one loop runs.
     */
    fun startRefresh(intervalSeconds: Long = 60) {
        stopRefresh()
        refreshJob = scope.launch {
            while (isActive) {
                refreshOnce()
                withContext(Dispatchers.Default) {
                    kotlinx.coroutines.delay(intervalSeconds * 1000)
                }
            }
        }
    }

    /** Stop the refresh loop. Call from onDetachedFromWindow. */
    fun stopRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /** Perform one refresh pass against the backend, falling back to cache on failure. */
    suspend fun refreshOnce() {
        val symbols = watchlist.first()
        if (symbols.isEmpty()) return

        val result = client.fetchQuotes(symbols)

        val quotes = when {
            result.isSuccess -> {
                result.getOrThrow().map { dto ->
                    Quote(
                        symbol = dto.symbol,
                        price = dto.price,
                        change = dto.change,
                        changePercent = dto.changePercent,
                        currency = dto.currency,
                        asOf = dto.asOf,
                        source = dto.source,
                        stale = dto.stale,
                    )
                }
            }
            else -> {
                // result.isFailure — keep the last known values; mark them stale if we have any.
                val current = _quotes.value
                if (current.isNotEmpty()) {
                    current.map { it.copy(stale = true) }
                } else {
                    emptyList()
                }
            }
        }

        if (quotes.isNotEmpty()) {
            _quotes.value = quotes
        }
    }
}