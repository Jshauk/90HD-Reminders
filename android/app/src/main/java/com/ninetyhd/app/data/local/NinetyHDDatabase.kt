package com.ninetyhd.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ninetyhd.app.data.local.dao.OutboxDao
import com.ninetyhd.app.data.local.dao.ReminderDao
import com.ninetyhd.app.data.local.dao.SettingsDao
import com.ninetyhd.app.data.local.entity.OutboxEntity
import com.ninetyhd.app.data.local.entity.ReminderEntity
import com.ninetyhd.app.data.local.entity.SettingsEntity

@Database(
    entities = [ReminderEntity::class, SettingsEntity::class, OutboxEntity::class],
    version = 9,
    exportSchema = false
)
abstract class NinetyHDDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: NinetyHDDatabase? = null

        fun getInstance(context: Context): NinetyHDDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NinetyHDDatabase::class.java,
                    "90hd_database"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
