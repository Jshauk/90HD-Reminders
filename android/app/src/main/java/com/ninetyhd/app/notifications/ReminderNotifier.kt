package com.ninetyhd.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ninetyhd.app.MainActivity
import com.ninetyhd.app.NinetyHDApp
import com.ninetyhd.app.receivers.ConfirmReceiver

/**
 * Single place that actually posts a reminder notification and kicks off the
 * escalation chain. Shared by the AlarmReceiver (backup wake path) and the
 * in-process timer in PersistentNotificationService (primary firing path) so
 * the two can never diverge.
 */
object ReminderNotifier {

    /**
     * Post the reminder notification for one instance and, if confirmation is
     * required, start the escalation chain.
     */
    fun fire(
        context: Context,
        reminderId: String,
        reminderText: String,
        instanceTime: String,
        requiresConfirmation: Boolean
    ) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, NotificationScheduler.stableIntId(reminderId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NinetyHDApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("90HD Reminder")
            .setContentText(reminderText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPendingIntent)
            // Re-alert on every re-post so watch bridges forward each instance.
            .setOnlyAlertOnce(false)
            // Confirmation reminders must not be dismissed by a tap/swipe — only
            // the Confirm action clears them. We intentionally DO NOT use
            // setOngoing(true): the Pebble bridge won't forward ongoing
            // notifications, so an ongoing confirmation alert never reaches the
            // watch. The escalation chain (re-nag every minute) + the Confirm
            // action enforce confirmation instead.
            .setAutoCancel(!requiresConfirmation)

        if (requiresConfirmation) {
            val confirmIntent = Intent(context, ConfirmReceiver::class.java).apply {
                putExtra(NotificationScheduler.EXTRA_REMINDER_ID, reminderId)
                putExtra(NotificationScheduler.EXTRA_INSTANCE_TIME, instanceTime)
            }
            val confirmPendingIntent = PendingIntent.getBroadcast(
                context, NotificationScheduler.confirmRequestCode(reminderId), confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_send, "Confirm", confirmPendingIntent)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NotificationScheduler.notificationIdFor(reminderId), builder.build())

        if (requiresConfirmation) {
            EscalationScheduler.scheduleEscalation(context, reminderId, reminderText, instanceTime)
        }
    }
}
