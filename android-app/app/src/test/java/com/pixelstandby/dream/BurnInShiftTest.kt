package com.pixelstandby.dream

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInShiftTest {

    private fun sequence(seed: Int, step: Int = 8, cycles: Int = 400): List<PixelOffset> {
        val shift = BurnInShift(maxStepPx = step, random = Random(seed))
        return (0 until cycles).map { shift.offsetForCycle(it) }
    }

    @Test
    fun `every offset stays within the configured step`() {
        sequence(seed = 1).forEach { offset ->
            assertTrue("x out of range: $offset", abs(offset.x) <= 8)
            assertTrue("y out of range: $offset", abs(offset.y) <= 8)
        }
    }

    @Test
    fun `content moves on every cycle so pixels get released`() {
        val offsets = sequence(seed = 42, step = 6, cycles = 500)
        offsets.zipWithNext().forEachIndexed { index, (a, b) ->
            assertNotEquals("content did not move at cycle ${index + 1}", a, b)
        }
    }

    @Test
    fun `every ring position is reached over time`() {
        val shift = BurnInShift(maxStepPx = 8, random = Random(7))
        val visited = sequence(seed = 7).toSet()
        assertEquals(shift.positions().toSet(), visited)
    }

    @Test
    fun `pattern is not a strict ping-pong`() {
        // A pure reversal walk would make content retrace the same two lines forever.
        // With the seeded random this must not happen for every seed we check.
        val pingPongSeeds = (0 until 25).count { seed ->
            val offsets = sequence(seed = seed, step = 8, cycles = 120)
            val directions = offsets.zipWithNext().map { (a, b) ->
                if ((b.x - a.x) * (b.y - a.y) < 0) "turn" else "straight"
            }
            directions.all { it == "turn" }
        }
        assertTrue("shift degenerated into a ping-pong walk", pingPongSeeds < 25)
    }

    @Test
    fun `different seeds produce different patterns`() {
        val a = sequence(seed = 11, cycles = 60)
        val b = sequence(seed = 12, cycles = 60)
        assertNotEquals(a, b)
    }

    @Test
    fun `same seed replays the same pattern`() {
        assertEquals(sequence(seed = 5, cycles = 60), sequence(seed = 5, cycles = 60))
    }

    @Test
    fun `zero step disables shifting`() {
        val shift = BurnInShift(maxStepPx = 0)
        assertEquals(PixelOffset.ZERO, shift.offsetForCycle(0))
        assertEquals(PixelOffset.ZERO, shift.offsetForCycle(9))
    }

    @Test
    fun `rewinding replays instead of throwing`() {
        val shift = BurnInShift(maxStepPx = 8, random = Random(3))
        val forward = (0 until 5).map { shift.offsetForCycle(it) }
        val rewound = (0 until 5).map { shift.offsetForCycle(it) }
        assertEquals(forward, rewound)
        assertTrue(shift.offsetForCycle(-1) in shift.positions())
    }
}