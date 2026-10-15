package com.pixelstandby

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import com.pixelstandby.data.QuotesClient
import com.pixelstandby.data.QuotesRepository
import com.pixelstandby.data.SettingsRepository
import com.pixelstandby.data.WatchlistRepository
import com.pixelstandby.dream.BurnInShift
import com.pixelstandby.dream.ClockFormatter
import com.pixelstandby.dream.ClockTicker
import com.pixelstandby.dream.DimLevel
import com.pixelstandby.dream.DreamEnvironment
import com.pixelstandby.dream.PixelOffset
import com.pixelstandby.ui.StandbyDream
import com.pixelstandby.ui.StandbyTheme
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect

/**
 * The bedside dashboard.
 *
 * Everything time-driven is a cold [Flow] collected inside the composition, so the loops
 * are started when the dream is drawn and cancelled the moment the window goes away. There
 * is no service-lifetime scope to leak, and nothing runs while the dream is off screen.
 *
 * The quotes refresh loop is a coroutine launched in the dream's own scope; it is cancelled
 * when the window detaches.
 */
class StandbyDreamService : DreamService() {

    private var environment: DreamEnvironment? = null
    private var contentView: ComposeView? = null
    private var dreamJob: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setFullscreen(true)
        setInteractive(false)
        setScreenBright(false)

        val env = DreamEnvironment(this).also { it.start() }
        environment = env

        val settings = SettingsRepository(this)
        val watchlistRepo = WatchlistRepository(this)

        val shift = BurnInShift(
            maxStepPx = (BurnInShift.DEFAULT_STEP_PX * resources.displayMetrics.density).roundToInt(),
        )

        val job = SupervisorJob()
        dreamJob = job
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        contentView = view
        setContentView(view)

        view.setContent {
            val state by env.state.collectAsState()
            val use24Hour by settings.clock24Hour.collectAsState(initial = false)
            val showSeconds by settings.showSeconds.collectAsState(initial = false)
            val autoDim by settings.autoDimEnabled.collectAsState(initial = true)
            val dashboardOn by settings.dashboardEnabled.collectAsState(initial = true)
            val backendUrl by settings.backendUrl.collectAsState(initial = "")
            val bearerToken by settings.bearerToken.collectAsState(initial = "")

            val quotesClient = remember(backendUrl, bearerToken) {
                QuotesClient(baseUrl = backendUrl, bearerToken = bearerToken)
            }
            val quotesRepository = remember(quotesClient) {
                QuotesRepository(quotesClient, watchlistRepo.watchlist, scope)
            }

            val zoned by remember(showSeconds) {
                ClockTicker(
                    nowMillis = { System.currentTimeMillis() },
                    zone = ZoneId.systemDefault(),
                ).ticks(showSeconds)
            }.collectAsState(initial = ZonedDateTime.now())

            val dimLevel by remember(autoDim) {
                env.dimLevels(enabled = { autoDim })
            }.collectAsState(initial = DimLevel.BRIGHT)

            val burnCycle by remember { burnInCycles() }.collectAsState(initial = 0)
            val burnOffset = remember(burnCycle) { shift.offsetForCycle(burnCycle) }

            val clockText = ClockFormatter.format(
                time = zoned.toLocalTime(),
                use24Hour = use24Hour,
                showSeconds = showSeconds,
            )

            val quotes by quotesRepository.quotes.collectAsState(initial = emptyList())

            // Start the quote refresh loop tied to the dream's lifecycle.
            LaunchedEffect(Unit) {
                quotesRepository.startRefresh()
            }

            StandbyTheme {
                StandbyDream(
                    charging = state.charging,
                    landscape = state.landscape,
                    clockText = clockText,
                    clockContentDescription = stringResource(
                        R.string.clock_content_description,
                        clockText,
                    ),
                    dimLevel = dimLevel,
                    burnOffset = burnOffset,
                    quotes = if (dashboardOn) quotes else emptyList(),
                    onInteraction = env::noteInteraction,
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        dreamJob?.cancel()
        dreamJob = null
        environment?.stop()
        environment = null
        contentView = null
        super.onDetachedFromWindow()
    }

    override fun onWakeUp() {
        environment?.noteInteraction()
        super.onWakeUp()
    }

    /**
     * Emits an incrementing burn-in cycle counter, aligned to the minute so the pixel shift
     * does not slowly walk away from the wall clock.
     */
    private fun burnInCycles(): Flow<Int> = flow {
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            emit(cycle)
            val drift = System.currentTimeMillis() % BurnInShift.CYCLE_MILLIS
            delay(BurnInShift.CYCLE_MILLIS - drift)
            cycle++
        }
    }.flowOn(Dispatchers.Default)
}