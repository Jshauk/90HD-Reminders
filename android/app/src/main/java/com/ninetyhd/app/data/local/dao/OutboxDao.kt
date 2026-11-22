package com.ninetyhd.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ninetyhd.app.data.local.entity.OutboxEntity

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    // Pending pushes, oldest first — flushed to the server in order.
    @Query("SELECT * FROM outbox WHERE synced = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun getUnsynced(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM outbox WHERE synced = 1")
    suspend fun clearSynced()

    @Query("SELECT COUNT(*) FROM outbox WHERE synced = 0")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
