package com.ninetyhd.app.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninetyhd.app.data.repository.ReminderRepository
import com.ninetyhd.app.notifications.EscalationScheduler
import com.ninetyhd.app.notifications.NotificationScheduler
import kotlinx.coroutines.runBlocking

class ConfirmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConfirmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(NotificationScheduler.EXTRA_REMINDER_ID)
        val instanceTime = intent.getStringExtra(NotificationScheduler.EXTRA_INSTANCE_TIME) ?: ""

        if (reminderId.isNullOrBlank()) return

        Log.d(TAG, "Confirmation received for reminder $reminderId")

        // Cancel the escalation chain
        EscalationScheduler.cancelEscalation(context, reminderId)

        // Dismiss the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NotificationScheduler.notificationIdFor(reminderId))

        // Sync confirmation to server
        val pendingResult = goAsync()
        Thread {
            try {
                val repository = ReminderRepository(context)
                runBlocking {
                    repository.confirmReminder(reminderId, instanceTime)
                }
                Log.d(TAG, "Confirmation synced to server for reminder $reminderId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync confirmation: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
