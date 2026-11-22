package com.ninetyhd.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val defaultActiveHoursStart: String,
    val defaultActiveHoursEnd: String,
    val timeFormat: String = "12h"
)
