package com.ninetyhd.app.data.local.dao

import androidx.room.*
import com.ninetyhd.app.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun getSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettingsSync(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}
