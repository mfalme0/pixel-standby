package com.pixelstandby.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The single "not ready yet" screen. Used for both the unplugged and the portrait case. */
@Composable
fun StandbyMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = StandbyMuted,
            fontSize = 34.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun StandbyMessagePreview() {
    StandbyTheme {
        StandbyMessage("Connect to power to activate")
    }
}