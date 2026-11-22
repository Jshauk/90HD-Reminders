package com.ninetyhd.app.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Formats stored "HH:mm" times according to the user's 12h/24h setting. */
object TimeDisplay {
    fun is24h(timeFormat: String?): Boolean = timeFormat == "24h"

    fun pattern(use24h: Boolean): String = if (use24h) "HH:mm" else "h:mm a"

    fun format(time: LocalTime, use24h: Boolean): String =
        time.format(DateTimeFormatter.ofPattern(pattern(use24h), java.util.Locale.getDefault()))

    fun format(hour: Int, minute: Int, use24h: Boolean): String =
        format(LocalTime.of(hour, minute), use24h)

    /** Format a stored "HH:mm" string; returns the input unchanged if it can't be parsed. */
    fun format(stored: String?, use24h: Boolean): String {
        if (stored.isNullOrBlank()) return ""
        return try { format(LocalTime.parse(stored), use24h) } catch (_: Exception) { stored }
    }
}
