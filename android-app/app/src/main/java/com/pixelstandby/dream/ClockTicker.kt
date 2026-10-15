package com.pixelstandby.dream

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Emits the current time aligned to the next second or minute boundary.
 *
 * Aligning to the boundary instead of firing every 1000ms means the clock does not drift
 * and, when seconds are hidden, the dashboard wakes up once a minute instead of sixty times.
 */
class ClockTicker(
    private val nowMillis: () -> Long,
    private val zone: ZoneId,
) {

    fun ticks(showSeconds: Boolean): Flow<ZonedDateTime> = flow {
        while (currentCoroutineContext().isActive) {
            val now = nowMillis()
            emit(ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone))
            delay(ClockSchedule.tickMillis(now, showSeconds, zone))
        }
    }
}

object ClockSchedule {

    /** Never busy-spin, even if the boundary lands in the past. */
    const val MIN_TICK_MILLIS = 100L

    fun nextBoundaryMillis(nowMillis: Long, showSeconds: Boolean, zone: ZoneId): Long {
        val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val next = if (showSeconds) {
            zoned.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
        } else {
            zoned.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        }
        return next.toInstant().toEpochMilli()
    }

    fun tickMillis(nowMillis: Long, showSeconds: Boolean, zone: ZoneId): Long =
        (nextBoundaryMillis(nowMillis, showSeconds, zone) - nowMillis).coerceAtLeast(MIN_TICK_MILLIS)
}