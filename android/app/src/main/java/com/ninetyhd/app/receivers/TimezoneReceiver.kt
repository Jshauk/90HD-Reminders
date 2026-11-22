package com.ninetyhd.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninetyhd.app.notifications.NotificationScheduler
import kotlinx.coroutines.runBlocking

class TimezoneReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            Log.d(TAG, "Timezone changed — rescheduling all reminders for new timezone")
            val pendingResult = goAsync()
            Thread {
                try {
                    val scheduler = NotificationScheduler(context)
                    // Cancel all existing alarms first (they were scheduled in old timezone)
                    runBlocking { scheduler.cancelAll() }
                    // Reschedule in new timezone
                    runBlocking { scheduler.scheduleAll() }
                    Log.d(TAG, "All reminders rescheduled for new timezone")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule on timezone change: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}
