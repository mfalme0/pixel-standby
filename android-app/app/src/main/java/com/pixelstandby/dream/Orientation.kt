package com.pixelstandby.dream

import android.content.res.Configuration

/**
 * Dream services cannot lock the display orientation reliably, so the dashboard adapts to
 * whatever orientation it was given instead of fighting the system.
 */
object Orientation {

    fun isLandscape(orientation: Int): Boolean =
        orientation == Configuration.ORIENTATION_LANDSCAPE

    fun isLandscape(configuration: Configuration): Boolean =
        isLandscape(configuration.orientation)
}