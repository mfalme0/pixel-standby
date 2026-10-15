package com.pixelstandby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelstandby.media.MediaSessionDiscovery.MediaSessionInfo

private val CardBackground = Color(0xFF1A1A1A)
private val CardBorder = Color(0xFF333333)
private val ControlColor = Color(0xFF7FD4FF)

/**
 * Media card showing the currently playing track with play/pause, next, previous controls.
 *
 * Hidden when [media] is null.
 */
@Composable
fun MediaCard(
    media: MediaSessionInfo?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media == null) return

    Surface(
        color = CardBackground,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .clickable { onPlayPause() },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Music note placeholder for album art
            Text(
                text = "♪",
                fontSize = 32.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.size(48.dp),
            )

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            ) {
                Text(
                    text = media.title ?: "Unknown track",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    text = media.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9E9E9E),
                    maxLines = 1,
                )
            }

            // Transport controls using text glyphs instead of Icons (Icons requires
            // material3-icons-core which isn't in the BOM).
            Row(
                modifier = Modifier.width(120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onPrevious) {
                    Text(
                        text = "«",
                        color = ControlColor,
                        fontSize = 20.sp,
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Text(
                        text = if (media.isPlaying) "⏸" else "▶",
                        color = ControlColor,
                        fontSize = 20.sp,
                    )
                }
                IconButton(onClick = onNext) {
                    Text(
                        text = "»",
                        color = ControlColor,
                        fontSize = 20.sp,
                    )
                }
            }
        }
    }
}