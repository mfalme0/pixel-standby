package com.pixelstandby.dream

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingTest {

    @Test
    fun `zero means unplugged`() {
        assertFalse(Charging.isPluggedIn(0))
    }

    @Test
    fun `any plugged source means charging`() {
        // BATTERY_PLUGGED_AC = 1, USB = 2, WIRELESS = 4, DOCK = 8.
        assertTrue(Charging.isPluggedIn(1))
        assertTrue(Charging.isPluggedIn(2))
        assertTrue(Charging.isPluggedIn(4))
        assertTrue(Charging.isPluggedIn(8))
    }

    @Test
    fun `combined plug sources still read as charging`() {
        assertTrue(Charging.isPluggedIn(1 or 4))
    }
}

class OrientationTest {

    @Test
    fun `landscape is accepted`() {
        assertTrue(Orientation.isLandscape(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `portrait and undefined are rejected`() {
        assertFalse(Orientation.isLandscape(Configuration.ORIENTATION_PORTRAIT))
        assertFalse(Orientation.isLandscape(Configuration.ORIENTATION_UNDEFINED))
    }
}