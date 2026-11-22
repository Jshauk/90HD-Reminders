package com.ninetyhd.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.data.local.NinetyHDDatabase
import com.ninetyhd.app.data.local.entity.OutboxEntity
import com.ninetyhd.app.data.local.entity.ReminderEntity
import com.ninetyhd.app.data.local.entity.SettingsEntity
import com.ninetyhd.app.data.remote.ApiClient
import com.ninetyhd.app.data.remote.dto.ConfirmationCreateDto
import com.ninetyhd.app.data.remote.dto.ReminderCreateDto
import com.ninetyhd.app.data.remote.dto.ReminderDto
import com.ninetyhd.app.notifications.EscalationScheduler
import com.ninetyhd.app.notifications.NotificationScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.time.Instant
import java.util.UUID

class ReminderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = NinetyHDDatabase.getInstance(context)
    private val reminderDao = db.reminderDao()
    private val settingsDao = db.settingsDao()
    private val outboxDao = db.outboxDao()
    private val preferencesManager = PreferencesManager(context)
    private val gson = Gson()

    /** Stop any pending alarm + escalation + visible notification for a reminder. */
    private fun cancelAllForReminder(id: String) {
        NotificationScheduler(appContext).cancelReminder(id)
        EscalationScheduler.cancelEscalation(appContext, id)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(NotificationScheduler.notificationIdFor(id))
    }

    companion object {
        private const val TAG = "ReminderRepository"
    }

    fun getReminders(): Flow<List<ReminderEntity>> = reminderDao.getAllReminders()

    fun getSettings(): Flow<SettingsEntity?> = settingsDao.getSettings()

    suspend fun getActiveReminders(): List<ReminderEntity> = reminderDao.getActiveReminders()

    suspend fun getReminderById(id: String): ReminderEntity? = reminderDao.getReminderById(id)

    suspend fun getSettingsSync(): SettingsEntity? = settingsDao.getSettingsSync()

    suspend fun pendingSyncCount(): Int = outboxDao.pendingCount()

    /** Save the shared default settings to local Room (offline-safe). */
    suspend fun updateSettings(
        defaultActiveHoursStart: String,
        defaultActiveHoursEnd: String,
        timeFormat: String
    ) {
        settingsDao.upsert(
            SettingsEntity(
                id = 1,
                defaultActiveHoursStart = defaultActiveHoursStart,
                defaultActiveHoursEnd = defaultActiveHoursEnd,
                timeFormat = timeFormat
            )
        )
    }

    // ---------------------------------------------------------------------
    // Local writes — the phone is the source of truth (local-first).
    // Each write updates Room immediately AND enqueues an outbox entry (full
    // snapshot) so the change can be pushed to the server on the next sync.
    // Works fully offline; the outbox drains whenever a server is reachable.
    // ---------------------------------------------------------------------

    suspend fun createReminder(
        text: String,
        frequencyValue: Int,
        frequencyUnit: String,
        startTime: String,
        startDate: String? = null,
        endTime: String? = null,
        endDate: String? = null,
        activeHoursStart: String? = null,
        activeHoursEnd: String? = null,
        requiresConfirmation: Boolean = false,
        active: Boolean = true
    ): String {
        val now = Instant.now().toString()
        val entity = ReminderEntity(
            id = UUID.randomUUID().toString(),
            text = text,
            frequencyValue = frequencyValue,
            frequencyUnit = frequencyUnit,
            startTime = startTime,
            startDate = startDate,
            endTime = endTime,
            endDate = endDate,
            activeHoursStart = activeHoursStart,
            activeHoursEnd = activeHoursEnd,
            requiresConfirmation = requiresConfirmation,
            active = active,
            createdAt = now,
            updatedAt = now
        )
        reminderDao.upsert(entity)
        enqueue(entity, OutboxEntity.OP_CREATE, now)
        return entity.id
    }

    suspend fun updateReminder(
        id: String,
        text: String,
        frequencyValue: Int,
        frequencyUnit: String,
        startTime: String,
        startDate: String?,
        endTime: String?,
        endDate: String?,
        activeHoursStart: String?,
        activeHoursEnd: String?,
        requiresConfirmation: Boolean,
        active: Boolean
    ) {
        val existing = reminderDao.getReminderById(id) ?: return
        val now = Instant.now().toString()
        val updated = existing.copy(
            text = text,
            frequencyValue = frequencyValue,
            frequencyUnit = frequencyUnit,
            startTime = startTime,
            startDate = startDate,
            endTime = endTime,
            endDate = endDate,
            activeHoursStart = activeHoursStart,
            activeHoursEnd = activeHoursEnd,
            requiresConfirmation = requiresConfirmation,
            active = active,
            updatedAt = now
        )
        reminderDao.upsert(updated)
        enqueue(updated, OutboxEntity.OP_UPDATE, now)
    }

    /**
     * Soft-delete a reminder: mark it as a tombstone locally and enqueue the
     * deletion so it propagates to the server. The row is retained (hidden from
     * the UI) until the server confirms it's safe to purge.
     */
    suspend fun deleteReminder(id: String) {
        val existing = reminderDao.getReminderById(id) ?: return
        val now = Instant.now().toString()
        reminderDao.softDelete(id, deletedAt = now, updatedAt = now)
        // Stop it firing immediately, regardless of how the delete was triggered.
        cancelAllForReminder(id)
        val tombstone = existing.copy(deleted = true, deletedAt = now, updatedAt = now)
        enqueue(tombstone, OutboxEntity.OP_DELETE, now)
    }

    private suspend fun enqueue(entity: ReminderEntity, op: String, timestamp: String) {
        outboxDao.insert(
            OutboxEntity(
                reminderId = entity.id,
                operation = op,
                snapshotJson = gson.toJson(entity),
                timestamp = timestamp
            )
        )
    }

    // ---------------------------------------------------------------------
    // Two-way sync (only runs when a server is connected).
    // 1. PUSH the outbox (each queued change) to the server, oldest first.
    // 2. PULL deltas since our last checkpoint (or a full bootstrap the first time).
    // 3. RECONCILE into Room: last-write-wins by updatedAt; deletes win.
    // 4. Save the server's clock as our new checkpoint.
    // ---------------------------------------------------------------------

    suspend fun sync(): Result<Unit> {
        val url = preferencesManager.serverUrl.firstOrNull()
            ?: return Result.success(Unit) // standalone: nothing to do
        return try {
            val api = ApiClient.getApi(url)
            val deviceId = preferencesManager.getOrCreateDeviceId()

            // 1. PUSH outbox — flush queued local changes to the server.
            val pending = outboxDao.getUnsynced()
            for (entry in pending) {
                val snapshot = gson.fromJson(entry.snapshotJson, ReminderEntity::class.java)
                when (entry.operation) {
                    OutboxEntity.OP_DELETE -> {
                        // Best-effort delete; ignore 404 (already gone server-side).
                        try {
                            api.deleteReminder(entry.reminderId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Delete push failed for ${entry.reminderId}: ${e.message}")
                        }
                    }
                    else -> {
                        // CREATE and UPDATE both use the upsert-on-create endpoint,
                        // sending the full snapshot with our client id.
                        api.createReminder(snapshot.toCreateDto())
                    }
                }
                outboxDao.deleteById(entry.id)
            }

            // 2. PULL — delta if we have a checkpoint, else a full bootstrap.
            val since = preferencesManager.getLastSyncAt()
            val tz = java.util.TimeZone.getDefault().id
            val syncResponse = api.sync(since = since, deviceId = deviceId, tz = tz)

            // 3. RECONCILE each remote reminder into Room.
            for (remote in syncResponse.reminders) {
                reconcile(remote)
            }

            // Settings: server is authoritative for the shared defaults.
            settingsDao.upsert(
                SettingsEntity(
                    id = 1,
                    defaultActiveHoursStart = syncResponse.settings.defaultActiveHoursStart,
                    defaultActiveHoursEnd = syncResponse.settings.defaultActiveHoursEnd,
                    timeFormat = syncResponse.settings.timeFormat
                )
            )

            // 4. Save the server's clock as our checkpoint for the next delta.
            preferencesManager.setLastSyncAt(syncResponse.serverTime)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Merge one server reminder into the local Room copy.
     * Rules: newest updatedAt wins; a delete (tombstone) wins over a
     * concurrent edit; unknown ids are inserted.
     */
    private suspend fun reconcile(remote: ReminderDto) {
        val local = reminderDao.getReminderById(remote.id)
        if (local == null) {
            // Unknown locally. Insert unless it's an already-purgeable tombstone
            // we've never seen (harmless to insert; it just won't show in the UI).
            reminderDao.upsert(remote.toEntity())
            return
        }

        val remoteTime = parseTime(remote.updatedAt)
        val localTime = parseTime(local.updatedAt)

        when {
            // Remote is a delete and is newer-or-equal → delete wins.
            remote.deleted && remoteTime >= localTime -> {
                reminderDao.upsert(remote.toEntity())
                // Stop any alarm/escalation for a reminder deleted elsewhere.
                cancelAllForReminder(remote.id)
            }
            // Local is a delete and is newer-or-equal → keep our tombstone.
            local.deleted && localTime >= remoteTime -> { /* keep local */ }
            // Otherwise newest write wins.
            remoteTime > localTime -> reminderDao.upsert(remote.toEntity())
            else -> { /* local is newer or equal; keep local */ }
        }
    }

    /**
     * Post a confirmation to the server. No-op (success) when no server is
     * connected — confirmations are a server-side history feature.
     */
    suspend fun confirmReminder(reminderId: String, instanceTime: String): Result<Unit> {
        return try {
            val url = preferencesManager.serverUrl.firstOrNull()
                ?: return Result.success(Unit)
            val api = ApiClient.getApi(url)
            api.postConfirmation(
                ConfirmationCreateDto(reminderId = reminderId, instanceTime = instanceTime)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- mapping helpers ---

    private fun parseTime(iso: String): Long = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        // Server sends "2026-09-30T18:03:50.759278" (no zone) — treat as UTC.
        try {
            Instant.parse(iso + "Z").toEpochMilli()
        } catch (e2: Exception) {
            0L
        }
    }

    private fun ReminderDto.toEntity() = ReminderEntity(
        id = id,
        text = text,
        frequencyValue = frequencyValue,
        frequencyUnit = frequencyUnit,
        startTime = startTime,
        startDate = startDate,
        endTime = endTime,
        endDate = endDate,
        activeHoursStart = activeHoursStart,
        activeHoursEnd = activeHoursEnd,
        requiresConfirmation = requiresConfirmation,
        active = active,
        deleted = deleted,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ReminderEntity.toCreateDto() = ReminderCreateDto(
        id = id,
        text = text,
        frequencyValue = frequencyValue,
        frequencyUnit = frequencyUnit,
        startTime = startTime,
        startDate = startDate,
        endTime = endTime,
        endDate = endDate,
        activeHoursStart = activeHoursStart,
        activeHoursEnd = activeHoursEnd,
        requiresConfirmation = requiresConfirmation,
        active = active
    )
}
