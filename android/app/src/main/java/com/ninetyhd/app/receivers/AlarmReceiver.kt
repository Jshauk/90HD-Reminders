package com.ninetyhd.app.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninetyhd.app.notifications.EscalationScheduler
import com.ninetyhd.app.notifications.NotificationScheduler
import com.ninetyhd.app.notifications.ReminderNotifier

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(NotificationScheduler.EXTRA_REMINDER_ID)
        val reminderText = intent.getStringExtra(NotificationScheduler.EXTRA_REMINDER_TEXT) ?: "Reminder"
        val instanceTime = intent.getStringExtra(NotificationScheduler.EXTRA_INSTANCE_TIME) ?: ""

        if (reminderId.isNullOrBlank()) {
            Log.e(TAG, "Received alarm with invalid reminder ID")
            return
        }

        Log.d(TAG, "Alarm fired for reminder $reminderId: $reminderText")

        val pendingResult = goAsync()
        Thread {
            try {
                val db = com.ninetyhd.app.data.local.NinetyHDDatabase.getInstance(context)
                val reminder = kotlinx.coroutines.runBlocking {
                    db.reminderDao().getReminderById(reminderId)
                }

                // Guard: if the reminder was deleted, no longer exists, or was
                // paused, do NOT show anything. Clean up any leftover alarm,
                // escalation chain, and notification, then stop. This prevents a
                // deleted reminder from continuing to fire.
                if (reminder == null || reminder.deleted || !reminder.active) {
                    Log.d(TAG, "Reminder $reminderId is gone/deleted/paused — cancelling and skipping")
                    val scheduler = NotificationScheduler(context)
                    scheduler.cancelReminder(reminderId)
                    EscalationScheduler.cancelEscalation(context, reminderId)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NotificationScheduler.notificationIdFor(reminderId))
                    return@Thread
                }

                val requiresConfirmation = reminder.requiresConfirmation

                // Show the notification + escalation via the shared notifier so
                // the alarm path and the in-process timer can't diverge.
                ReminderNotifier.fire(context, reminderId, reminderText, instanceTime, requiresConfirmation)

                // Record this instance so the in-process timer won't re-fire it.
                kotlinx.coroutines.runBlocking {
                    db.reminderDao().setLastFiredAt(reminderId, System.currentTimeMillis())
                }

                // Schedule the next occurrence (AlarmManager backup path)
                val settings = kotlinx.coroutines.runBlocking {
                    db.settingsDao().getSettingsSync()
                }
                val scheduler = NotificationScheduler(context)
                scheduler.scheduleReminder(reminder, settings)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing alarm: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
