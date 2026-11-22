package com.ninetyhd.app.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ninetyhd.app.MainActivity
import com.ninetyhd.app.NinetyHDApp
import com.ninetyhd.app.notifications.EscalationScheduler
import com.ninetyhd.app.notifications.NotificationScheduler

class EscalationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EscalationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EscalationScheduler.EXTRA_REMINDER_ID)
        val reminderText = intent.getStringExtra(EscalationScheduler.EXTRA_REMINDER_TEXT) ?: "Reminder"
        val instanceTime = intent.getStringExtra(EscalationScheduler.EXTRA_INSTANCE_TIME) ?: ""
        val escalationCount = intent.getIntExtra(EscalationScheduler.EXTRA_ESCALATION_COUNT, 1)

        if (reminderId.isNullOrBlank()) return

        Log.d(TAG, "Escalation #$escalationCount for reminder $reminderId")

        // Guard: if the reminder was deleted/paused while escalating, stop the
        // chain and clear the notification instead of continuing to nag.
        val pendingResult = goAsync()
        Thread {
            try {
                val db = com.ninetyhd.app.data.local.NinetyHDDatabase.getInstance(context)
                val reminder = kotlinx.coroutines.runBlocking {
                    db.reminderDao().getReminderById(reminderId)
                }
                if (reminder == null || reminder.deleted || !reminder.active) {
                    Log.d(TAG, "Reminder $reminderId gone/deleted — stopping escalation")
                    EscalationScheduler.cancelEscalation(context, reminderId)
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NotificationScheduler.notificationIdFor(reminderId))
                    return@Thread
                }

                showNotification(context, reminderId, reminderText, instanceTime, escalationCount)

                if (escalationCount < EscalationScheduler.MAX_ESCALATIONS) {
                    EscalationScheduler.scheduleNextEscalation(
                        context, reminderId, reminderText, instanceTime, escalationCount + 1
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Escalation error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun showNotification(
        context: Context,
        reminderId: String,
        text: String,
        instanceTime: String,
        escalationCount: Int
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, NotificationScheduler.stableIntId(reminderId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confirmIntent = Intent(context, ConfirmReceiver::class.java).apply {
            putExtra(NotificationScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(NotificationScheduler.EXTRA_INSTANCE_TIME, instanceTime)
        }
        val confirmPendingIntent = PendingIntent.getBroadcast(
            context, NotificationScheduler.confirmRequestCode(reminderId), confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NinetyHDApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("90HD Reminder (unconfirmed)")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n⚠️ Unconfirmed ($escalationCount/${EscalationScheduler.MAX_ESCALATIONS})"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Confirm",
                confirmPendingIntent
            )
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationScheduler.notificationIdFor(reminderId), notification)
    }
}
