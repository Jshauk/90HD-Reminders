package com.ninetyhd.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.notifications.NotificationScheduler
import com.ninetyhd.app.services.PersistentNotificationService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted — rescheduling all reminders")
            val pendingResult = goAsync()
            Thread {
                try {
                    // Reschedule alarms
                    val scheduler = NotificationScheduler(context)
                    runBlocking { scheduler.scheduleAll() }
                    Log.d(TAG, "All reminders rescheduled after boot")

                    // Restart persistent notification if enabled
                    val prefs = PreferencesManager(context)
                    val enabled = runBlocking { prefs.persistentNotificationEnabled.first() }
                    if (enabled) {
                        val serviceIntent = Intent(context, PersistentNotificationService::class.java)
                        context.startForegroundService(serviceIntent)
                        Log.d(TAG, "Persistent notification service restarted after boot")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed after boot: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}
