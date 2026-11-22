package com.ninetyhd.app.data.local.dao

import androidx.room.*
import com.ninetyhd.app.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    // UI/notification queries exclude tombstones (deleted rows).
    @Query("SELECT * FROM reminders WHERE deleted = 0 ORDER BY createdAt DESC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE active = 1 AND deleted = 0")
    suspend fun getActiveReminders(): List<ReminderEntity>

    // Returns a reminder even if it's a tombstone (used by sync reconciliation).
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: String): ReminderEntity?

    // All rows including tombstones — used to build the sync payload / diff.
    @Query("SELECT * FROM reminders")
    suspend fun getAllIncludingDeleted(): List<ReminderEntity>

    @Upsert
    suspend fun upsertAll(reminders: List<ReminderEntity>)

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    // Soft delete: mark as tombstone rather than removing the row.
    @Query("UPDATE reminders SET deleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String)

    // Record the instant we last fired an instance (local-only; drives the
    // in-process timer so recurrences don't double-fire).
    @Query("UPDATE reminders SET lastFiredAt = :firedAt WHERE id = :id")
    suspend fun setLastFiredAt(id: String, firedAt: Long)

    @Query("DELETE FROM reminders WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: String)

    // Physically remove a tombstone once it has been purged server-side.
    @Query("DELETE FROM reminders WHERE deleted = 1 AND id = :id")
    suspend fun purgeTombstone(id: String)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}
