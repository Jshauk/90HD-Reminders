package com.ninetyhd.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    val status: String,
    val app: String
)

data class ReminderDto(
    val id: String,
    val text: String,
    @SerializedName("frequency_value") val frequencyValue: Int,
    @SerializedName("frequency_unit") val frequencyUnit: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("start_date") val startDate: String?,
    @SerializedName("end_time") val endTime: String?,
    @SerializedName("end_date") val endDate: String?,
    @SerializedName("active_hours_start") val activeHoursStart: String?,
    @SerializedName("active_hours_end") val activeHoursEnd: String?,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean,
    val active: Boolean,
    val deleted: Boolean = false,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ReminderCreateDto(
    val id: String? = null,
    val text: String,
    @SerializedName("frequency_value") val frequencyValue: Int,
    @SerializedName("frequency_unit") val frequencyUnit: String,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("start_date") val startDate: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("end_date") val endDate: String? = null,
    @SerializedName("active_hours_start") val activeHoursStart: String? = null,
    @SerializedName("active_hours_end") val activeHoursEnd: String? = null,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean = false,
    val active: Boolean = true
)

data class ReminderUpdateDto(
    val text: String? = null,
    @SerializedName("frequency_value") val frequencyValue: Int? = null,
    @SerializedName("frequency_unit") val frequencyUnit: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("start_date") val startDate: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("end_date") val endDate: String? = null,
    @SerializedName("active_hours_start") val activeHoursStart: String? = null,
    @SerializedName("active_hours_end") val activeHoursEnd: String? = null,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean? = null,
    val active: Boolean? = null
)

data class SettingsDto(
    val id: Int,
    @SerializedName("default_active_hours_start") val defaultActiveHoursStart: String,
    @SerializedName("default_active_hours_end") val defaultActiveHoursEnd: String,
    @SerializedName("time_format") val timeFormat: String
)

data class SettingsUpdateDto(
    @SerializedName("default_active_hours_start") val defaultActiveHoursStart: String? = null,
    @SerializedName("default_active_hours_end") val defaultActiveHoursEnd: String? = null,
    @SerializedName("time_format") val timeFormat: String? = null
)

data class ConfirmationCreateDto(
    @SerializedName("reminder_id") val reminderId: String,
    @SerializedName("instance_time") val instanceTime: String
)

data class ConfirmationDto(
    val id: Int,
    @SerializedName("reminder_id") val reminderId: String,
    @SerializedName("confirmed_at") val confirmedAt: String,
    @SerializedName("instance_time") val instanceTime: String
)

data class SyncResponse(
    val reminders: List<ReminderDto>,
    val settings: SettingsDto,
    @SerializedName("server_time") val serverTime: String
)
