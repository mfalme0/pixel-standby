package com.pixelstandby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelstandby.R
import com.pixelstandby.data.Quote
import com.pixelstandby.dream.DimLevel
import com.pixelstandby.dream.PixelOffset
import com.pixelstandby.media.MediaSessionDiscovery.MediaSessionInfo
import com.pixelstandby.ntfy.NtfyBanner

private val BannerBackground = Color(0xFF1A1A1A)
private val BannerBorder = Color(0xFF333333)
private val ConnectionOnline = Color(0xFF4CAF50)
private val ConnectionOffline = Color(0xFFF44336)

/**
 * A small connection indicator dot shown in the top-right corner.
 */
@Composable
private fun ConnectionIndicator(connected: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(if (connected) ConnectionOnline else ConnectionOffline),
        contentAlignment = Alignment.Center,
    ) {
        if (!connected) {
            Text(text = "!", color = Color(0xFFFFFFFF), fontSize = 8.sp)
        }
    }
}

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
    banners: List<NtfyBanner>,
    connected: Boolean,
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
                        // NTFY banners: show current (under 15s old) banners.
                        val now = System.currentTimeMillis()
                        banners.filter { it.isCurrent(now) }.forEach { banner ->
                            NtfyBannerRow(banner = banner)
                        }
                        dashboard()
                    }
                    ConnectionIndicator(connected = connected)
                }
            }
        }
    }
}

/**
 * A single ntfy banner row shown beneath the clock/prices/media.
 */
@Composable
private fun NtfyBannerRow(banner: NtfyBanner) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp)
            .background(BannerBackground)
            .border(1.dp, BannerBorder, RoundedCornerShape(8.dp))
            .clickable { /* tap could dismiss or expand */ },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = banner.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(banner.priorityColor),
                maxLines = 1,
            )
            Text(
                text = banner.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
            )
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
            banners = emptyList(),
            connected = true,
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
            banners = emptyList(),
            connected = true,
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onInteraction = {},
        )
    }
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun StandbyDreamBannerPreview() {
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
            banners = listOf(
                NtfyBanner(
                    id = System.currentTimeMillis(),
                    title = "opencode",
                    message = "finished in 4m12s, exit 0",
                    priority = "default",
                    time = System.currentTimeMillis(),
                ),
                NtfyBanner(
                    id = System.currentTimeMillis() + 1,
                    title = "deploy",
                    message = "failed, exit 1",
                    priority = "high",
                    time = System.currentTimeMillis(),
                ),
            ),
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onInteraction = {},
            connected = true,
        )
    }
}