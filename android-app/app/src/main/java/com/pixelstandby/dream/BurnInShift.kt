package com.pixelstandby.dream

import kotlin.random.Random

/**
 * Burn-in protection by pixel shift.
 *
 * Every [CYCLE_MILLIS] the whole dashboard is nudged to a neighbouring position on a small
 * ring, so no pixel stays lit for long. The walk steps one place at a time, which guarantees
 * content actually moves between cycles, and every so often it jumps across the ring instead
 * of turning back, so the motion never settles into a strict ping-pong that would trace a
 * visible figure.
 *
 * Offsets are in raw pixels; the caller scales density into the step size.
 */
class BurnInShift(
    maxStepPx: Int = DEFAULT_STEP_PX,
    private val random: Random = Random.Default,
) {
    private val ring: List<PixelOffset> = buildRing(maxStepPx)

    private var index = 0
    private var direction = 1
    private var step = 0

    /**
     * The offset to apply for a given zero-based cycle. Cycles normally arrive in order;
     * asking for an earlier one rewinds and replays rather than throwing.
     */
    fun offsetForCycle(cycle: Int): PixelOffset {
        if (ring.isEmpty()) return PixelOffset.ZERO
        if (cycle < step) {
            index = 0
            direction = 1
            step = 0
        }
        while (step < cycle) advance()
        return ring[index]
    }

    private fun advance() {
        val candidate = index + direction
        if (candidate in ring.indices) {
            index = candidate
        } else if (random.nextInt(JUMP_CHANCE_DENOMINATOR) == 0) {
            // Jump to an arbitrary point on the ring rather than turning back.
            direction = if (random.nextBoolean()) 1 else -1
            var target = ring.indices.random(random)
            if (target == index) target = (target + 1) % ring.size
            index = target
        } else {
            direction = -direction
            index += direction
        }
        step++
    }

    /** Every position the shift can produce. Useful for tests and debugging. */
    fun positions(): List<PixelOffset> = ring

    private fun buildRing(step: Int): List<PixelOffset> {
        if (step <= 0) return emptyList()
        return listOf(
            PixelOffset(0, 0),
            PixelOffset(step, 0),
            PixelOffset(step, step),
            PixelOffset(0, step),
            PixelOffset(-step, 0),
            PixelOffset(-step, -step),
            PixelOffset(0, -step),
            PixelOffset(step, -step),
        )
    }

    companion object {
        const val CYCLE_MILLIS = 60_000L
        const val DEFAULT_STEP_PX = 8

        /** One turn in four is a jump rather than a reversal. */
        private const val JUMP_CHANCE_DENOMINATOR = 4
    }
}

/** A content offset in pixels. */
data class PixelOffset(val x: Int, val y: Int) {
    companion object {
        val ZERO = PixelOffset(0, 0)
    }
}