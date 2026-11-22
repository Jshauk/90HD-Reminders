package com.ninetyhd.app.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ninetyhd.app.MainActivity
import com.ninetyhd.app.NinetyHDApp
import com.ninetyhd.app.data.local.NinetyHDDatabase
import com.ninetyhd.app.notifications.NotificationScheduler
import com.ninetyhd.app.notifications.ReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Foreground service that:
 *  1. Shows the persistent "tap to add a reminder" notification.
 *  2. Runs an in-process timer (the TICK loop) that fires due reminders directly.
 *
 * The timer is the primary, reliable firing path. AlarmManager's
 * setExactAndAllowWhileIdle is throttled to ~once per 10-15 min under Doze, so
 * it cannot drive a 1-minute cadence on its own. A foreground service is exempt
 * from that throttling, so a cheap periodic DB check here fires recurrences on
 * time. AlarmManager alarms remain scheduled as a backup wake path (e.g. if the
 * service is temporarily down) and funnel through the same ReminderNotifier.
 */
class PersistentNotificationService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        private const val TAG = "PersistentNotifSvc"
        private const val TICK_INTERVAL_MS = 30_000L
        // An instance older than this is considered missed, not "due".
        private const val STALE_GRACE_MS = 5 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var tickStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.ninetyhd.app.NEW_REMINDER"
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NinetyHDApp.PERSISTENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle("90HD")
            .setContentText("Tap to add a new reminder")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startTickLoop()

        return START_STICKY
    }

    private fun startTickLoop() {
        if (tickStarted) return
        tickStarted = true
        scope.launch {
            Log.d(TAG, "Reminder tick loop started (every ${TICK_INTERVAL_MS}ms)")
            while (isActive) {
                try {
                    fireDueReminders()
                } catch (e: Exception) {
                    Log.e(TAG, "Tick error: ${e.message}")
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * One pass of the timer: fire every active reminder whose latest scheduled
     * instance is newer than the last instance we fired for it.
     */
    private suspend fun fireDueReminders() {
        val db = NinetyHDDatabase.getInstance(this)
        val reminders = db.reminderDao().getActiveReminders() // active && !deleted
        if (reminders.isEmpty()) return
        val settings = db.settingsDao().getSettingsSync()
        val zone = ZoneId.systemDefault()

        for (reminder in reminders) {
            val due = NotificationScheduler(this).lastDueFireTime(reminder, settings) ?: continue
            val dueMillis = due.atZone(zone).toInstant().toEpochMilli()

            // Already fired this (or a later) instance? Skip.
            if (dueMillis <= reminder.lastFiredAt) continue

            // This timer only fires instances that just came due. It must not
            // "catch up" on instances from before the reminder existed (e.g. an
            // hourly 08:00-19:00 reminder created at 9:34pm must not fire the
            // 7pm instance) or that are long overdue. Mark them handled and move on;
            // the next real instance will fire on schedule.
            val createdMillis = try { java.time.Instant.parse(reminder.createdAt).toEpochMilli() } catch (_: Exception) { 0L }
            val nowMillis = System.currentTimeMillis()
            if (dueMillis < createdMillis || nowMillis - dueMillis > STALE_GRACE_MS) {
                db.reminderDao().setLastFiredAt(reminder.id, dueMillis)
                continue
            }

            Log.d(TAG, "Firing reminder ${reminder.id} '${reminder.text}' for instance $due")
            ReminderNotifier.fire(
                context = this,
                reminderId = reminder.id,
                reminderText = reminder.text,
                instanceTime = due.toString(),
                requiresConfirmation = reminder.requiresConfirmation
            )
            db.reminderDao().setLastFiredAt(reminder.id, dueMillis)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tickStarted = false
    }
}
