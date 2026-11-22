package com.ninetyhd.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val text: String,
    val frequencyValue: Int,
    val frequencyUnit: String,
    val startTime: String,
    val startDate: String?,
    val endTime: String?,
    val endDate: String?,
    val activeHoursStart: String?,
    val activeHoursEnd: String?,
    val requiresConfirmation: Boolean,
    val active: Boolean,
    val deleted: Boolean = false,
    val deletedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    // Local-only: epoch millis of the last instance we actually fired. Used by
    // the in-process timer (PersistentNotificationService) to avoid double-firing
    // and to drive reliable minute-cadence recurrences under Doze. Not synced.
    val lastFiredAt: Long = 0L
)
