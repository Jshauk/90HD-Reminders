package com.ninetyhd.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.data.sync.SyncWorker
import com.ninetyhd.app.services.PersistentNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NinetyHDApp : Application() {

    companion object {
        const val CHANNEL_ID = "90hd_reminders"
        const val CHANNEL_NAME = "90HD Reminders"
        const val PERSISTENT_CHANNEL_ID = "90hd_persistent"
        const val PERSISTENT_CHANNEL_NAME = "90HD Quick Add"
        private const val PERSISTENT_NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        SyncWorker.schedule(this)
        // Check preference then start/stop service
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = PreferencesManager(this@NinetyHDApp)
            val enabled = prefs.persistentNotificationEnabled.first()
            if (enabled) {
                startPersistentService()
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Reminder notifications (high priority)
        val reminderChannel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Persistent reminder notifications from 90HD"
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(reminderChannel)

        // Persistent quick-add notification (low priority, silent)
        val persistentChannel = NotificationChannel(
            PERSISTENT_CHANNEL_ID,
            PERSISTENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tap to quickly add a new reminder"
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(persistentChannel)
    }

    private fun startPersistentService() {
        val intent = Intent(this, com.ninetyhd.app.services.PersistentNotificationService::class.java)
        startForegroundService(intent)
    }
}
