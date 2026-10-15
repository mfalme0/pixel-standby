package com.pixelstandby.dream

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockFormatter {

    fun pattern(use24Hour: Boolean, showSeconds: Boolean): String = buildString {
        append(if (use24Hour) "HH:mm" else "h:mm")
        if (showSeconds) append(":ss")
        if (!use24Hour) append(" a")
    }

    fun format(time: LocalTime, use24Hour: Boolean, showSeconds: Boolean): String =
        DateTimeFormatter
            .ofPattern(pattern(use24Hour, showSeconds), Locale.getDefault())
            .format(time)
}