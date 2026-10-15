package com.pixelstandby.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

/**
 * `tnum` forces tabular figures so the glyph widths never change as the digits roll over,
 * which stops the whole line from twitching every second.
 */
private val ClockTextStyle = TextStyle(
    color = StandbyClock,
    fontSize = 150.sp,
    lineHeight = 160.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = (-3).sp,
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.Center,
)

@Composable
fun ClockPane(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Text(
        text = text,
        style = ClockTextStyle,
        maxLines = 1,
        modifier = modifier.semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
    )
}

@Preview(widthDp = 1040, heightDp = 480)
@Composable
private fun ClockPanePreview() {
    StandbyTheme {
        ClockPane(text = "9:41 PM")
    }
}