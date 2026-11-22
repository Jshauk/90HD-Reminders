package com.ninetyhd.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninetyhd.app.receivers.EscalationReceiver

/**
 * Handles scheduling the escalation chain:
 * - After 5 minutes with no confirmation, start re-notifying every 1 minute
 * - Up to 10 additional notifications total
 */
object EscalationScheduler {

    private const val TAG = "EscalationScheduler"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMINDER_TEXT = "reminder_text"
    const val EXTRA_INSTANCE_TIME = "instance_time"
    const val EXTRA_ESCALATION_COUNT = "escalation_count"
    const val MAX_ESCALATIONS = 10
    private const val INITIAL_DELAY_MS = 5 * 60 * 1000L // 5 minutes
    private const val REPEAT_DELAY_MS = 1 * 60 * 1000L // 1 minute
    private const val ESCALATION_REQUEST_BASE = 60000

    /** Stable Int request code for a reminder's escalation PendingIntent (UUID -> Int). */
    private fun escalationRequestCode(reminderId: String): Int =
        ESCALATION_REQUEST_BASE + (reminderId.hashCode() and 0x7FFFFFFF) % 1_000_000

    /**
     * Schedule the first escalation for a reminder (fires after 5 minutes).
     */
    fun scheduleEscalation(
        context: Context,
        reminderId: String,
        reminderText: String,
        instanceTime: String
    ) {
        scheduleAt(context, reminderId, reminderText, instanceTime, 1, INITIAL_DELAY_MS)
        Log.d(TAG, "Scheduled first escalation for reminder $reminderId in 5 minutes")
    }

    /**
     * Schedule the next escalation in the chain.
     */
    fun scheduleNextEscalation(
        context: Context,
        reminderId: String,
        reminderText: String,
        instanceTime: String,
        nextCount: Int
    ) {
        if (nextCount > MAX_ESCALATIONS) {
            Log.d(TAG, "Max escalations reached for reminder $reminderId")
            return
        }
        scheduleAt(context, reminderId, reminderText, instanceTime, nextCount, REPEAT_DELAY_MS)
        Log.d(TAG, "Scheduled escalation #$nextCount for reminder $reminderId in 1 minute")
    }

    /**
     * Cancel all pending escalations for a reminder.
     */
    fun cancelEscalation(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, EscalationReceiver::class.java)
        // Use FLAG_UPDATE_CURRENT to get/create the matching PendingIntent, then cancel
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            escalationRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel() // Also cancel the PendingIntent itself
        Log.d(TAG, "Cancelled escalation for reminder $reminderId")
    }

    private fun scheduleAt(
        context: Context,
        reminderId: String,
        reminderText: String,
        instanceTime: String,
        escalationCount: Int,
        delayMs: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, EscalationReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TEXT, reminderText)
            putExtra(EXTRA_INSTANCE_TIME, instanceTime)
            putExtra(EXTRA_ESCALATION_COUNT, escalationCount)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            escalationRequestCode(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + delayMs

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot schedule exact alarm for escalation: ${e.message}")
        }
    }
}
