package com.ninetyhd.app.data.remote

import com.ninetyhd.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface NinetyHDApi {

    @GET("/api/health")
    suspend fun health(): Response<HealthResponse>

    // Delta sync when `since` is provided (includes tombstones); full bootstrap
    // sync when omitted. `deviceId` registers this device's checkpoint; `tz`
    // reports the phone's current timezone for ring/MCP time resolution.
    @GET("/api/sync")
    suspend fun sync(
        @Query("since") since: String? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("tz") tz: String? = null
    ): SyncResponse

    @GET("/api/reminders")
    suspend fun getReminders(): List<ReminderDto>

    @POST("/api/reminders")
    suspend fun createReminder(@Body reminder: ReminderCreateDto): ReminderDto

    @PUT("/api/reminders/{id}")
    suspend fun updateReminder(@Path("id") id: String, @Body reminder: ReminderUpdateDto): ReminderDto

    @DELETE("/api/reminders/{id}")
    suspend fun deleteReminder(@Path("id") id: String): Response<Unit>

    @GET("/api/settings")
    suspend fun getSettings(): SettingsDto

    @PUT("/api/settings")
    suspend fun updateSettings(@Body settings: SettingsUpdateDto): SettingsDto

    @POST("/api/confirmations")
    suspend fun postConfirmation(@Body confirmation: ConfirmationCreateDto): ConfirmationDto
}
