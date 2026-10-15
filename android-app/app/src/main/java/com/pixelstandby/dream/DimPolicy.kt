package com.pixelstandby.dream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * How brightly the dashboard draws, based on how long it has been left alone.
 *
 * The screen itself is already knocked down with `setScreenBright(false)`; this fades the
 * *content* on top of that, so the clock stays legible for a long time and then recedes
 * rather than the panel being driven to black outright.
 */
enum class DimLevel(val contentAlpha: Float) {
    BRIGHT(1f),
    DIMMED(0.45f),
    NEAR_BLACK(0.06f),
}

object DimPolicy {

    const val DIM_AFTER_MILLIS = 30L * 60L * 1000L
    const val NEAR_BLACK_AFTER_MILLIS = 2L * 60L * 60L * 1000L
    const val DEFAULT_TICK_MILLIS = 5_000L

    fun levelFor(idleMillis: Long, enabled: Boolean = true): DimLevel = when {
        !enabled -> DimLevel.BRIGHT
        idleMillis < 0L -> DimLevel.BRIGHT
        idleMillis >= NEAR_BLACK_AFTER_MILLIS -> DimLevel.NEAR_BLACK
        idleMillis >= DIM_AFTER_MILLIS -> DimLevel.DIMMED
        else -> DimLevel.BRIGHT
    }

    /**
     * Emits the current [DimLevel] and re-evaluates every [tickMillis]. The clock is
     * injected so tests can drive it with virtual time.
     */
    fun levels(
        idleMillis: () -> Long,
        enabled: () -> Boolean = { true },
        tickMillis: Long = DEFAULT_TICK_MILLIS,
        initial: DimLevel = DimLevel.BRIGHT,
    ): Flow<DimLevel> = flow {
        emit(initial)
        while (currentCoroutineContext().isActive) {
            delay(tickMillis)
            emit(levelFor(idleMillis(), enabled()))
        }
    }
}