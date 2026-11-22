package com.ninetyhd.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ninetyhd.app.data.local.NinetyHDDatabase
import com.ninetyhd.app.data.local.entity.ReminderEntity
import com.ninetyhd.app.data.local.entity.SettingsEntity
import com.ninetyhd.app.receivers.AlarmReceiver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class NotificationScheduler(private val context: Context) {

    companion object {
        private const val TAG = "NotificationScheduler"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        const val EXTRA_INSTANCE_TIME = "instance_time"
        private const val REQUEST_CODE_BASE = 10000

        // Offset for reminder notification IDs so they never collide with
        // the persistent notification (ID 1) or test notifications (99998, 99999)
        const val NOTIFICATION_ID_OFFSET = 100000

        /**
         * Reminder ids are UUID strings, but Android's notification IDs, alarm
         * request codes, and PendingIntent request codes require Ints. Derive a
         * stable, non-negative Int from the UUID so the same reminder always maps
         * to the same numeric slots across app restarts.
         */
        fun stableIntId(reminderId: String): Int = reminderId.hashCode() and 0x7FFFFFFF

        fun notificationIdFor(reminderId: String): Int =
            NOTIFICATION_ID_OFFSET + (stableIntId(reminderId) % 1_000_000)

        /** Request code for the reminder's main alarm PendingIntent. */
        fun alarmRequestCode(reminderId: String): Int =
            REQUEST_CODE_BASE + (stableIntId(reminderId) % 1_000_000)

        /** Request code for the reminder's confirm-action PendingIntent. */
        fun confirmRequestCode(reminderId: String): Int =
            50000 + (stableIntId(reminderId) % 1_000_000)
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule alarms for all active reminders based on local Room cache.
     */
    suspend fun scheduleAll() {
        val db = NinetyHDDatabase.getInstance(context)
        val reminders = db.reminderDao().getActiveReminders()
        val settings = db.settingsDao().getSettingsSync()

        Log.d(TAG, "Scheduling alarms for ${reminders.size} active reminders")

        for (reminder in reminders) {
            scheduleReminder(reminder, settings)
        }
    }

    /**
     * Schedule the next alarm for a single reminder.
     */
    fun scheduleReminder(reminder: ReminderEntity, settings: SettingsEntity?) {
        val nextFireTime = calculateNextFireTime(reminder, settings) ?: run {
            Log.d(TAG, "No valid next fire time for reminder ${reminder.id}")
            return
        }

        val zoneId = ZoneId.systemDefault()
        val epochMillis = nextFireTime.atZone(zoneId).toInstant().toEpochMilli()

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TEXT, reminder.text)
            putExtra(EXTRA_INSTANCE_TIME, nextFireTime.toString())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmRequestCode(reminder.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                epochMillis,
                pendingIntent
            )
            Log.d(TAG, "Scheduled reminder ${reminder.id} '${reminder.text}' for $nextFireTime (${zoneId.id})")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot schedule exact alarm: ${e.message}")
        }
    }

    /**
     * Cancel a scheduled alarm for a reminder.
     */
    fun cancelReminder(reminderId: String) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm for reminder $reminderId")
    }

    /**
     * Cancel all scheduled alarms.
     */
    suspend fun cancelAll() {
        val db = NinetyHDDatabase.getInstance(context)
        val reminders = db.reminderDao().getActiveReminders()
        for (reminder in reminders) {
            cancelReminder(reminder.id)
        }
        Log.d(TAG, "Cancelled all ${reminders.size} alarms")
    }

    /**
     * Calculate the next valid fire time for a reminder.
     * Times are interpreted in the device's current local timezone.
     *
     * Active hours only apply to frequency-derived intermediate times.
     * If the user explicitly sets a start_time, it fires at that time regardless.
     */
    fun calculateNextFireTime(
        reminder: ReminderEntity,
        settings: SettingsEntity?
    ): LocalDateTime? {
        val now = LocalDateTime.now()
        val startTime = parseTime(reminder.startTime) ?: return null

        // If start_date is set and is in the future, use that as the first fire
        val startDate = parseDate(reminder.startDate)
        val baseDate = if (startDate != null && startDate.isAfter(LocalDate.now())) {
            startDate
        } else {
            LocalDate.now()
        }

        // If end_date is set and has passed, don't schedule
        val endDate = parseDate(reminder.endDate)
        if (endDate != null && endDate.isBefore(LocalDate.now())) {
            return null
        }

        // Calculate the base next occurrence
        var candidate = getNextOccurrence(reminder, now, startTime, baseDate)

        // If end_date is set and candidate is past it, don't fire
        if (endDate != null && candidate.toLocalDate().isAfter(endDate)) {
            return null
        }

        // If end_time is set and candidate time is past it on the end_date
        if (reminder.endTime != null && endDate != null && candidate.toLocalDate() == endDate) {
            val endTime = parseTime(reminder.endTime)
            if (endTime != null && candidate.toLocalTime().isAfter(endTime)) {
                return null
            }
        }

        // For sub-daily reminders (minutes/hours), apply active hours clamping
        if (reminder.frequencyUnit == "hours" || reminder.frequencyUnit == "minutes") {
            val activeStart = parseTime(reminder.activeHoursStart)
                ?: parseTime(settings?.defaultActiveHoursStart)
                ?: LocalTime.of(8, 0)
            val activeEnd = parseTime(reminder.activeHoursEnd)
                ?: parseTime(settings?.defaultActiveHoursEnd)
                ?: LocalTime.of(22, 0)
            candidate = adjustToActiveHours(candidate, activeStart, activeEnd)
        }

        // Safety: don't schedule more than 30 days out
        if (candidate.isAfter(now.plusDays(30))) {
            return null
        }

        return candidate
    }

    /**
     * Compute the most recent scheduled fire instant that is at or before [now],
     * for use by the in-process timer. Returns null if the reminder has no due
     * instance yet (hasn't started) or is past its end. Respects start date/time,
     * end date/time, and — for minutes/hours units — active-hours windows.
     *
     * The caller compares the returned instant (epoch millis) against the
     * reminder's lastFiredAt to decide whether a new instance is due.
     */
    fun lastDueFireTime(
        reminder: ReminderEntity,
        settings: SettingsEntity?,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime? {
        val startTime = parseTime(reminder.startTime) ?: return null
        val startDate = parseDate(reminder.startDate) ?: LocalDate.now()

        // First scheduled occurrence.
        val first = startDate.atTime(startTime)
        // Not started yet.
        if (first.isAfter(now)) return null

        // End-date guard.
        val endDate = parseDate(reminder.endDate)
        if (endDate != null && endDate.isBefore(now.toLocalDate())) {
            // Entire schedule is in the past; last valid instance is on endDate.
        }

        // Walk forward from the first occurrence to the latest instance <= now.
        // Bounded: for minutes cadence we clamp the look-back so this stays cheap.
        var candidate = first
        // For minute/hour cadences, jump close to now instead of stepping from the
        // start date (which could be millions of steps). Keep a 2-day look-back so
        // active-hours clamping still finds the latest valid instance.
        val stepMinutes = when (reminder.frequencyUnit) {
            "minutes" -> reminder.frequencyValue.toLong()
            "hours" -> reminder.frequencyValue.toLong() * 60
            else -> 0L
        }
        if (stepMinutes > 0) {
            val skip = (ChronoUnit.MINUTES.between(first, now.minusDays(2)) / stepMinutes)
            if (skip > 0) candidate = first.plusMinutes(skip * stepMinutes)
        }
        var latest: LocalDateTime? = null
        var iterations = 0
        val maxIterations = 100_000
        while (!candidate.isAfter(now) && iterations < maxIterations) {
            var effective = candidate
            if (reminder.frequencyUnit == "hours" || reminder.frequencyUnit == "minutes") {
                val activeStart = parseTime(reminder.activeHoursStart)
                    ?: parseTime(settings?.defaultActiveHoursStart)
                    ?: LocalTime.of(8, 0)
                val activeEnd = parseTime(reminder.activeHoursEnd)
                    ?: parseTime(settings?.defaultActiveHoursEnd)
                    ?: LocalTime.of(22, 0)
                effective = adjustToActiveHours(candidate, activeStart, activeEnd)
            }
            // Only count it if, after active-hours clamping, it's still <= now.
            if (!effective.isAfter(now)) {
                // Respect end date/time.
                val withinEnd = run {
                    if (endDate != null && effective.toLocalDate().isAfter(endDate)) return@run false
                    if (reminder.endTime != null && endDate != null &&
                        effective.toLocalDate() == endDate
                    ) {
                        val et = parseTime(reminder.endTime)
                        if (et != null && effective.toLocalTime().isAfter(et)) return@run false
                    }
                    true
                }
                if (withinEnd) {
                    if (latest == null || effective.isAfter(latest)) latest = effective
                }
            }
            candidate = advanceByFrequency(candidate, reminder.frequencyValue, reminder.frequencyUnit)
            iterations++
        }
        return latest
    }

    private fun getNextOccurrence(
        reminder: ReminderEntity,
        now: LocalDateTime,
        startTime: LocalTime,
        baseDate: LocalDate
    ): LocalDateTime {
        // First candidate: baseDate at start_time
        var candidate = baseDate.atTime(startTime)

        // If it's already passed, advance by frequency
        if (!candidate.isAfter(now)) {
            candidate = advanceByFrequency(candidate, reminder.frequencyValue, reminder.frequencyUnit)
        }

        // Keep advancing until we're strictly in the future
        while (!candidate.isAfter(now)) {
            candidate = advanceByFrequency(candidate, reminder.frequencyValue, reminder.frequencyUnit)
        }

        return candidate
    }

    private fun advanceByFrequency(
        dateTime: LocalDateTime,
        value: Int,
        unit: String
    ): LocalDateTime {
        return when (unit) {
            "minutes" -> dateTime.plusMinutes(value.toLong())
            "hours" -> dateTime.plusHours(value.toLong())
            "days" -> dateTime.plusDays(value.toLong())
            "weeks" -> dateTime.plusWeeks(value.toLong())
            "months" -> dateTime.plusMonths(value.toLong())
            else -> dateTime.plusDays(value.toLong())
        }
    }

    private fun adjustToActiveHours(
        candidate: LocalDateTime,
        activeStart: LocalTime,
        activeEnd: LocalTime
    ): LocalDateTime {
        val time = candidate.toLocalTime()

        return when {
            // Before active hours start: push to active hours start same day
            time.isBefore(activeStart) -> candidate.with(activeStart)
            // After active hours end: push to active hours start next day
            time.isAfter(activeEnd) -> candidate.plusDays(1).with(activeStart)
            // Within active hours: use as-is
            else -> candidate
        }
    }

    private fun parseTime(timeStr: String?): LocalTime? {
        if (timeStr.isNullOrBlank()) return null
        return try {
            LocalTime.parse(timeStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}
