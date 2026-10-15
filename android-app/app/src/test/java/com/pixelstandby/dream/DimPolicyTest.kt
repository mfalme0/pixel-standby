package com.pixelstandby.dream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimPolicyTest {

    @Test
    fun `bright before thirty minutes (medium)`() {
        assertEquals(DimLevel.BRIGHT, DimPolicy.levelFor(0, setting = DimLevelSetting.MEDIUM))
        assertEquals(
            DimLevel.BRIGHT,
            DimPolicy.levelFor(30L * 60L * 1000L - 1, setting = DimLevelSetting.MEDIUM),
        )
    }

    @Test
    fun `dimmed at thirty minutes (medium)`() {
        assertEquals(DimLevel.DIMMED, DimPolicy.levelFor(30L * 60L * 1000L, setting = DimLevelSetting.MEDIUM))
        assertEquals(
            DimLevel.DIMMED,
            DimPolicy.levelFor(2L * 60L * 60L * 1000L - 1, setting = DimLevelSetting.MEDIUM),
        )
    }

    @Test
    fun `near black at two hours (medium)`() {
        assertEquals(DimLevel.NEAR_BLACK, DimPolicy.levelFor(2L * 60L * 60L * 1000L, setting = DimLevelSetting.MEDIUM))
        assertEquals(
            DimLevel.NEAR_BLACK,
            DimPolicy.levelFor(2L * 60L * 60L * 1000L + 1, setting = DimLevelSetting.MEDIUM),
        )
    }

    @Test
    fun `aggressive dims at ten minutes`() {
        assertEquals(DimLevel.DIMMED, DimPolicy.levelFor(10L * 60L * 1000L, setting = DimLevelSetting.AGGRESSIVE))
        assertEquals(
            DimLevel.NEAR_BLACK,
            DimPolicy.levelFor(30L * 60L * 1000L, setting = DimLevelSetting.AGGRESSIVE),
        )
    }

    @Test
    fun `none never dims`() {
        assertEquals(DimLevel.BRIGHT, DimPolicy.levelFor(2L * 60L * 60L * 1000L * 10, setting = DimLevelSetting.NONE))
        assertEquals(DimLevel.BRIGHT, DimPolicy.levelFor(Long.MAX_VALUE, setting = DimLevelSetting.NONE))
    }

    @Test
    fun `disabled stays bright forever`() {
        assertEquals(
            DimLevel.BRIGHT,
            DimPolicy.levelFor(2L * 60L * 60L * 1000L * 10, enabled = false),
        )
    }

    @Test
    fun `clock going backwards does not blank the screen`() {
        assertEquals(DimLevel.BRIGHT, DimPolicy.levelFor(-5_000))
    }

    @Test
    fun `alpha decreases monotonically`() {
        val bright = DimLevel.BRIGHT.contentAlpha
        val dimmed = DimLevel.DIMMED.contentAlpha
        val nearBlack = DimLevel.NEAR_BLACK.contentAlpha
        assertTrue(bright > dimmed)
        assertTrue(dimmed > nearBlack)
        assertTrue(nearBlack >= 0f && nearBlack <= 0.1f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DimPolicyFlowTest {

    @Test
    fun `emits immediately then steps through the levels`() = runTest {
        var idle = 0L
        val levels = mutableListOf<DimLevel>()

        val job = launch {
            DimPolicy.levels(idleMillis = { idle }, tickMillis = 1_000L).toList(levels)
        }

        runCurrent()
        assertEquals(listOf(DimLevel.BRIGHT), levels)

        idle = 30L * 60L * 1000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(DimLevel.BRIGHT, DimLevel.DIMMED), levels)

        idle = 2L * 60L * 60L * 1000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(DimLevel.NEAR_BLACK, levels.last())

        job.cancel()
    }

    @Test
    fun `does not re-emit before a tick elapses`() = runTest {
        var idle = 0L
        val levels = mutableListOf<DimLevel>()

        val job = launch {
            DimPolicy.levels(idleMillis = { idle }, tickMillis = 5_000L).toList(levels)
        }

        runCurrent()
        idle = 2L * 60L * 60L * 1000L
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(1, levels.size)

        job.cancel()
    }
}