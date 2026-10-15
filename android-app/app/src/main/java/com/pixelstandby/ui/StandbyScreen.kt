package com.pixelstandby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import com.pixelstandby.R
import com.pixelstandby.data.Quote
import com.pixelstandby.dream.DimLevel
import com.pixelstandby.dream.PixelOffset
import com.pixelstandby.media.MediaSessionDiscovery.MediaSessionInfo

/**
 * The whole dream, in one place.
 *
 * Two gates decide what is drawn: power first, then orientation. Only when both are happy
 * does the [dashboard] slot render. Burn-in shift and dimming are applied by the caller as
 * an offset and an alpha on the whole thing, so neither gate is ever shifted or faded out
 * of recognition.
 */
@Composable
fun StandbyDream(
    charging: Boolean,
    landscape: Boolean,
    clockText: String,
    clockContentDescription: String?,
    dimLevel: DimLevel,
    burnOffset: PixelOffset,
    quotes: List<Quote>,
    media: MediaSessionInfo?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    dashboard: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StandbyBackground),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(burnOffset.x, burnOffset.y) }
                .alpha(dimLevel.contentAlpha)
                .pointerInput(Unit) { detectTapGestures { onInteraction() } },
        ) {
            when {
                !charging -> StandbyMessage(stringResource(R.string.message_connect_to_power))
                !landscape -> StandbyMessage(stringResource(R.string.message_rotate_to_landscape))
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ClockPane(
                            text = clockText,
                            contentDescription = clockContentDescription,
                        )
                        if (quotes.isNotEmpty()) {
                            PriceCard(quotes = quotes)
                        }
                        if (media != null) {
                            MediaCard(
                                media = media,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                onPrevious = onPrevious,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        dashboard()
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun StandbyDreamPreview() {
    StandbyTheme {
        StandbyDream(
            charging = true,
            landscape = true,
            clockText = "9:41 PM",
            clockContentDescription = "Current time 9:41 PM",
            dimLevel = DimLevel.BRIGHT,
            burnOffset = PixelOffset.ZERO,
            quotes = emptyList(),
            media = null,
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onInteraction = {},
        )
    }
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun StandbyDreamUnpluggedPreview() {
    StandbyTheme {
        StandbyDream(
            charging = false,
            landscape = true,
            clockText = "9:41 PM",
            clockContentDescription = null,
            dimLevel = DimLevel.BRIGHT,
            burnOffset = PixelOffset.ZERO,
            quotes = emptyList(),
            media = null,
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onInteraction = {},
        )
    }
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun StandbyDreamMediaPreview() {
    StandbyTheme {
        StandbyDream(
            charging = true,
            landscape = true,
            clockText = "9:41 PM",
            clockContentDescription = "Current time 9:41 PM",
            dimLevel = DimLevel.BRIGHT,
            burnOffset = PixelOffset.ZERO,
            quotes = emptyList(),
            media = null,
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onInteraction = {},
        )
    }
}