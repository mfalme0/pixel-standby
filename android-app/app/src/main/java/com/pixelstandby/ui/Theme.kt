package com.pixelstandby.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Near-black OLED-friendly palette. Nothing here needs a light variant. */
val StandbyBackground = Color(0xFF000000)
val StandbyClock = Color(0xFFF2F5FF)
val StandbyMuted = Color(0xFF6E7686)
val StandbyAccent = Color(0xFF7FD4FF)

private val StandbyTypography = Typography(
    bodyLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light),
    titleMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Light),
)

@Composable
fun StandbyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            background = StandbyBackground,
            onBackground = StandbyClock,
        ),
        typography = StandbyTypography,
        content = content,
    )
}