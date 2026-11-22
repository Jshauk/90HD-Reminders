package com.ninetyhd.app.notifications

import com.ninetyhd.app.data.local.entity.ReminderEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object NextFireTimeCalculator {

    /**
     * Calculate next fire time for display purposes.
     * Returns a human-readable string like "Today 8:00 AM" or "Tomorrow 3:30 PM"
     */
    fun getNextFireTimeDisplay(reminder: ReminderEntity, defaultActiveStart: String?, defaultActiveEnd: String?, use24h: Boolean = false): String? {
        if (!reminder.active) return null

        val now = LocalDateTime.now()
        val startTime = parseTime(reminder.startTime) ?: return null

        val activeStart = parseTime(reminder.activeHoursStart)
            ?: parseTime(defaultActiveStart)
            ?: LocalTime.of(8, 0)
        val activeEnd = parseTime(reminder.activeHoursEnd)
            ?: parseTime(defaultActiveEnd)
            ?: LocalTime.of(22, 0)

        var candidate = LocalDate.now().atTime(startTime)

        // If it's already passed, advance by frequency
        if (!candidate.isAfter(now)) {
            candidate = advanceByFrequency(candidate, reminder.frequencyValue, reminder.frequencyUnit)
        }
        while (!candidate.isAfter(now)) {
            candidate = advanceByFrequency(candidate, reminder.frequencyValue, reminder.frequencyUnit)
        }

        // Only apply active hours for sub-daily reminders (minutes/hours)
        if (reminder.frequencyUnit == "hours" || reminder.frequencyUnit == "minutes") {
            val time = candidate.toLocalTime()
            candidate = when {
                time.isBefore(activeStart) -> candidate.with(activeStart)
                time.isAfter(activeEnd) -> candidate.plusDays(1).with(activeStart)
                else -> candidate
            }
        }

        // Don't show if more than 30 days out
        if (candidate.isAfter(now.plusDays(30))) return null

        return formatRelative(candidate, now, use24h)
    }

    private fun formatRelative(target: LocalDateTime, now: LocalDateTime, use24h: Boolean): String {
        val today = now.toLocalDate()
        val targetDate = target.toLocalDate()
        val timeStr = target.toLocalTime().format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))

        return when {
            targetDate == today -> "Today $timeStr"
            targetDate == today.plusDays(1) -> "Tomorrow $timeStr"
            targetDate.isBefore(today.plusWeeks(1)) -> {
                val day = targetDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                "$day $timeStr"
            }
            else -> target.format(DateTimeFormatter.ofPattern(if (use24h) "MMM d, HH:mm" else "MMM d, h:mm a"))
        }
    }

    private fun advanceByFrequency(dateTime: LocalDateTime, value: Int, unit: String): LocalDateTime {
        return when (unit) {
            "minutes" -> dateTime.plusMinutes(value.toLong())
            "hours" -> dateTime.plusHours(value.toLong())
            "days" -> dateTime.plusDays(value.toLong())
            "weeks" -> dateTime.plusWeeks(value.toLong())
            "months" -> dateTime.plusMonths(value.toLong())
            else -> dateTime.plusDays(value.toLong())
        }
    }

    private fun parseTime(timeStr: String?): LocalTime? {
        if (timeStr.isNullOrBlank()) return null
        return try { LocalTime.parse(timeStr) } catch (_: Exception) { null }
    }
}
