package com.ninetyhd.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "90hd_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val PERSISTENT_NOTIFICATION_KEY = booleanPreferencesKey("persistent_notification")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        // "seen tombstones up to" checkpoint returned by the server (server clock).
        private val LAST_SYNC_AT_KEY = stringPreferencesKey("last_sync_at")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[SERVER_URL_KEY]
    }

    val persistentNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PERSISTENT_NOTIFICATION_KEY] ?: true // default on
    }

    val lastSyncAt: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[LAST_SYNC_AT_KEY]
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL_KEY] = url
        }
    }

    suspend fun clearServerUrl() {
        context.dataStore.edit { prefs ->
            prefs.remove(SERVER_URL_KEY)
        }
    }

    suspend fun setPersistentNotification(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PERSISTENT_NOTIFICATION_KEY] = enabled
        }
    }

    /**
     * Stable per-install device id, generated once on first access. Used so the
     * server can track how far this device has synced (for tombstone cleanup).
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.first()[DEVICE_ID_KEY]
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { prefs -> prefs[DEVICE_ID_KEY] = newId }
        return newId
    }

    suspend fun getLastSyncAt(): String? = context.dataStore.data.first()[LAST_SYNC_AT_KEY]

    suspend fun setLastSyncAt(serverTime: String) {
        context.dataStore.edit { prefs -> prefs[LAST_SYNC_AT_KEY] = serverTime }
    }

    /** Clears sync checkpoint (e.g. on disconnect) so the next connect bootstraps fresh. */
    suspend fun clearSyncState() {
        context.dataStore.edit { prefs -> prefs.remove(LAST_SYNC_AT_KEY) }
    }
}
