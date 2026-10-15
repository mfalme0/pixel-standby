package com.pixelstandby.dream

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the dream needs to know about its surroundings: is it charging, is it
 * landscape, and how long since anyone touched it.
 *
 * All three are pushed rather than polled. Charging comes from battery broadcasts and
 * orientation from the process-wide [ComponentCallbacks2], so a rotating phone or a yanked
 * cable updates the dashboard immediately without waking anything up on a timer.
 */
class DreamEnvironment(
    context: Context,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(
        DreamState(
            charging = false,
            landscape = Orientation.isLandscape(appContext.resources.configuration),
            lastInteractionAt = elapsedRealtime(),
        ),
    )
    val state: StateFlow<DreamState> = _state.asStateFlow()

    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> applyBatteryIntent(intent)
                Intent.ACTION_POWER_CONNECTED -> _state.update { it.copy(charging = true) }
                Intent.ACTION_POWER_DISCONNECTED -> _state.update { it.copy(charging = false) }
            }
        }
    }

    private val configCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            _state.update { it.copy(landscape = Orientation.isLandscape(newConfig)) }
        }

        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        // ACTION_BATTERY_CHANGED is sticky, so this returns the current battery state
        // synchronously and there is no window where we guess.
        val sticky = ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::applyBatteryIntent)

        appContext.registerComponentCallbacks(configCallbacks)

        // Re-read orientation in case it changed between service start and attach.
        _state.update { it.copy(landscape = Orientation.isLandscape(appContext.resources.configuration)) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        runCatching { appContext.unregisterComponentCallbacks(configCallbacks) }
    }

    private fun applyBatteryIntent(intent: Intent) {
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        _state.update { it.copy(charging = Charging.isPluggedIn(plugged)) }
    }

    fun noteInteraction() {
        _state.update { it.copy(lastInteractionAt = elapsedRealtime()) }
    }

    fun idleMillis(): Long = elapsedRealtime() - _state.value.lastInteractionAt

    fun dimLevels(
        enabled: () -> Boolean = { true },
        setting: () -> DimLevelSetting = { DimLevelSetting.MEDIUM },
        tickMillis: Long = DimPolicy.DEFAULT_TICK_MILLIS,
    ): Flow<DimLevel> = DimPolicy.levels(idleMillis = ::idleMillis, enabled = enabled, setting = setting, tickMillis = tickMillis)
}

data class DreamState(
    val charging: Boolean,
    val landscape: Boolean,
    val lastInteractionAt: Long,
)