package com.pixelstandby.dream

import android.content.Intent
import android.os.BatteryManager

/**
 * Charging detection.
 *
 * `EXTRA_PLUGGED` is a bitmask of `BatteryManager.BATTERY_PLUGGED_*` values. Anything other
 * than zero means a charger is connected, so the real test is "non-zero".
 */
object Charging {

    fun isPluggedIn(plugged: Int): Boolean = plugged != 0

    fun isPluggedIn(intent: Intent): Boolean =
        isPluggedIn(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
}