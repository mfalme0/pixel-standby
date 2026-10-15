package com.pixelstandby.dream

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val UTC: ZoneId = ZoneOffset.UTC

/** 2024-03-10T06:07:33.500Z */
private const val BASE_MILLIS = 1_710_050_853_500L

class ClockScheduleTest {

    @Test
    fun `with seconds hidden the next boundary is the next minute`() {
        assertEquals(
            1_710_050_880_000L,
            ClockSchedule.nextBoundaryMillis(BASE_MILLIS, showSeconds = false, zone = UTC),
        )
    }

    @Test
    fun `with seconds shown the next boundary is the next second`() {
        assertEquals(
            1_710_050_854_000L,
            ClockSchedule.nextBoundaryMillis(BASE_MILLIS, showSeconds = true, zone = UTC),
        )
    }

    @Test
    fun `a tick is exactly the distance to the boundary`() {
        assertEquals(
            500L,
            ClockSchedule.tickMillis(BASE_MILLIS, showSeconds = true, zone = UTC),
        )
        assertEquals(
            26_500L,
            ClockSchedule.tickMillis(BASE_MILLIS, showSeconds = false, zone = UTC),
        )
    }

    @Test
    fun `an exact boundary still waits a whole step`() {
        // Guards against a zero-length delay turning the ticker into a spin loop.
        val onTheSecond = 1_710_050_854_000L
        assertEquals(
            1_000L,
            ClockSchedule.tickMillis(onTheSecond, showSeconds = true, zone = UTC),
        )
        assertEquals(1_000L, ClockSchedule.tickMillis(onTheSecond, showSeconds = true, zone = UTC))
    }

    @Test
    fun `ticks never fall below the floor`() {
        val justBefore = 1_710_050_854_000L - 1
        assertEquals(
            ClockSchedule.MIN_TICK_MILLIS,
            ClockSchedule.tickMillis(justBefore, showSeconds = true, zone = UTC),
        )
    }

    @Test
    fun `minute mode ticks far less often than second mode`() {
        val minuteTicks = (0 until 60).sumOf {
            ClockSchedule.tickMillis(BASE_MILLIS + it * 1_000L, showSeconds = false, zone = UTC)
        }
        assertTrue("minute mode should idle most of the time", minuteTicks > 60_000L - 1_000L)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClockTickerTest {

    @Test
    fun `emits the supplied time and then advances on the boundary`() = runTest {
        var now = BASE_MILLIS
        val seen = mutableListOf<ZonedDateTime>()

        val job = launch {
            ClockTicker(nowMillis = { now }, zone = UTC)
                .ticks(showSeconds = true)
                .take(2)
                .toList(seen)
        }
        runCurrent()
        assertEquals(33, seen.single().second)

        // The ticker is parked on a 500ms delay until the next whole second.
        now = BASE_MILLIS + 600L
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(2, seen.size)
        assertEquals(33, seen[0].second)
        assertEquals(34, seen[1].second)
        job.cancel()
    }
}

class ClockFormatterTest {

    private lateinit var previousLocale: Locale

    @Before
    fun pinLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `patterns match the selected options`() {
        assertEquals("h:mm a", ClockFormatter.pattern(use24Hour = false, showSeconds = false))
        assertEquals("h:mm:ss a", ClockFormatter.pattern(use24Hour = false, showSeconds = true))
        assertEquals("HH:mm", ClockFormatter.pattern(use24Hour = true, showSeconds = false))
        assertEquals("HH:mm:ss", ClockFormatter.pattern(use24Hour = true, showSeconds = true))
    }

    @Test
    fun `twelve hour format shows a meridiem`() {
        val evening = ClockFormatter.format(LocalTime.of(21, 41), use24Hour = false, showSeconds = false)
        val morning = ClockFormatter.format(LocalTime.of(9, 5), use24Hour = false, showSeconds = false)
        assertEquals("9:41 PM", evening)
        assertEquals("9:05 AM", morning)
    }

    @Test
    fun `twenty four hour format pads and omits meridiem`() {
        assertEquals(
            "21:41",
            ClockFormatter.format(LocalTime.of(21, 41), use24Hour = true, showSeconds = false),
        )
        assertEquals(
            "09:05",
            ClockFormatter.format(LocalTime.of(9, 5), use24Hour = true, showSeconds = false),
        )
    }

    @Test
    fun `seconds are opt in and always padded`() {
        assertEquals(
            "21:41:07",
            ClockFormatter.format(LocalTime.of(21, 41, 7), use24Hour = true, showSeconds = true),
        )
        assertEquals(
            "9:41:07 AM",
            ClockFormatter.format(LocalTime.of(9, 41, 7), use24Hour = false, showSeconds = true),
        )
    }
}