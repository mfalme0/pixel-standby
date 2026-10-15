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

/** User-selectable dimming aggressiveness. */
enum class DimLevelSetting {
    NONE,      // never dim
    MEDIUM,    // default: dim at 30 min, near-black at 2 h
    AGGRESSIVE, // dim at 10 min, near-black at 30 min
}

object DimPolicy {

    const val DEFAULT_TICK_MILLIS = 5_000L

    /** Thresholds for the MEDIUM setting. */
    private const val MEDIUM_DIM_AFTER_MILLIS = 30L * 60L * 1000L
    private const val MEDIUM_NEAR_BLACK_AFTER_MILLIS = 2L * 60L * 60L * 1000L

    /** Thresholds for the AGGRESSIVE setting. */
    private const val AGGRESSIVE_DIM_AFTER_MILLIS = 10L * 60L * 1000L
    private const val AGGRESSIVE_NEAR_BLACK_AFTER_MILLIS = 30L * 60L * 1000L

    fun levelFor(idleMillis: Long, enabled: Boolean = true, setting: DimLevelSetting = DimLevelSetting.MEDIUM): DimLevel {
        if (!enabled) return DimLevel.BRIGHT
        if (idleMillis < 0L) return DimLevel.BRIGHT
        if (setting == DimLevelSetting.NONE) return DimLevel.BRIGHT

        val (dimAfter, nearBlackAfter) = when (setting) {
            DimLevelSetting.AGGRESSIVE -> AGGRESSIVE_DIM_AFTER_MILLIS to AGGRESSIVE_NEAR_BLACK_AFTER_MILLIS
            else -> MEDIUM_DIM_AFTER_MILLIS to MEDIUM_NEAR_BLACK_AFTER_MILLIS
        }

        return when {
            idleMillis >= nearBlackAfter -> DimLevel.NEAR_BLACK
            idleMillis >= dimAfter -> DimLevel.DIMMED
            else -> DimLevel.BRIGHT
        }
    }

    /**
     * Emits the current [DimLevel] and re-evaluates every [tickMillis]. The clock is
     * injected so tests can drive it with virtual time.
     */
    fun levels(
        idleMillis: () -> Long,
        enabled: () -> Boolean = { true },
        setting: () -> DimLevelSetting = { DimLevelSetting.MEDIUM },
        tickMillis: Long = DEFAULT_TICK_MILLIS,
        initial: DimLevel = DimLevel.BRIGHT,
    ): Flow<DimLevel> = flow {
        emit(initial)
        while (currentCoroutineContext().isActive) {
            delay(tickMillis)
            emit(levelFor(idleMillis(), enabled(), setting()))
        }
    }
}