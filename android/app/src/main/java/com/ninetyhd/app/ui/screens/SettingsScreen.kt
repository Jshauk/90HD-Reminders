package com.ninetyhd.app.ui.screens

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.ninetyhd.app.NinetyHDApp
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.data.remote.ApiClient
import com.ninetyhd.app.data.remote.dto.SettingsUpdateDto
import com.ninetyhd.app.data.repository.ReminderRepository
import com.ninetyhd.app.notifications.EscalationScheduler
import com.ninetyhd.app.notifications.NotificationScheduler
import com.ninetyhd.app.receivers.ConfirmReceiver
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesManager = remember { PreferencesManager(context) }
    val repository = remember { ReminderRepository(context) }
    val serverUrl by preferencesManager.serverUrl.collectAsState(initial = null)
    val lastSyncAt by preferencesManager.lastSyncAt.collectAsState(initial = null)
    val isConnected = !serverUrl.isNullOrBlank()

    var activeStartHour by remember { mutableIntStateOf(8) }
    var activeStartMinute by remember { mutableIntStateOf(0) }
    var activeEndHour by remember { mutableIntStateOf(22) }
    var activeEndMinute by remember { mutableIntStateOf(0) }
    var timeFormat by remember { mutableStateOf("12h") }
    var isSaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showActiveStartPicker by remember { mutableStateOf(false) }
    var showActiveEndPicker by remember { mutableStateOf(false) }

    // Sync state
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var pendingCount by remember { mutableIntStateOf(0) }

    val persistentNotifEnabled by preferencesManager.persistentNotificationEnabled.collectAsState(initial = true)

    // Load settings from local Room (works offline); refresh pending count.
    LaunchedEffect(Unit) {
        val local = repository.getSettingsSync()
        if (local != null) {
            parseTimeInto(local.defaultActiveHoursStart) { h, m -> activeStartHour = h; activeStartMinute = m }
            parseTimeInto(local.defaultActiveHoursEnd) { h, m -> activeEndHour = h; activeEndMinute = m }
            timeFormat = local.timeFormat
        }
        pendingCount = repository.pendingSyncCount()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // Sync / server card
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Server Sync", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isConnected) {
                        Text(serverUrl!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (lastSyncAt != null) "Last synced: ${formatSyncTime(lastSyncAt!!, timeFormat == "24h")}" else "Not synced yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pendingCount > 0) {
                            Text(
                                "$pendingCount change${if (pendingCount == 1) "" else "s"} pending upload",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isSyncing = true; syncMessage = null
                                        val result = repository.sync()
                                        NotificationScheduler(context).scheduleAll()
                                        pendingCount = repository.pendingSyncCount()
                                        syncMessage = if (result.isSuccess) "Synced!" else "Sync failed"
                                        isSyncing = false
                                    }
                                },
                                enabled = !isSyncing
                            ) { Text(if (isSyncing) "Syncing..." else "Sync now") }
                            OutlinedButton(
                                onClick = { showDisconnectDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Disconnect") }
                        }
                        if (syncMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(syncMessage!!, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            "Not connected. Reminders are stored locally on this device. Optionally connect your own server to back up and sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onConnect) { Text("Connect to server") }
                    }
                }
            }

            // Active hours with time pickers (stored locally; pushed to server if connected)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Default Active Hours", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Hourly reminders only fire during these hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsTimeField(label = "From", hour = activeStartHour, minute = activeStartMinute, use24h = (timeFormat == "24h"), onClick = { showActiveStartPicker = true })
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsTimeField(label = "Until", hour = activeEndHour, minute = activeEndMinute, use24h = (timeFormat == "24h"), onClick = { showActiveEndPicker = true })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch {
                            isSaving = true
                            try {
                                val startStr = "%02d:%02d".format(activeStartHour, activeStartMinute)
                                val endStr = "%02d:%02d".format(activeEndHour, activeEndMinute)
                                // Save locally first (works offline).
                                repository.updateSettings(startStr, endStr, timeFormat)
                                // Push to server if connected.
                                val url = preferencesManager.serverUrl.firstOrNull()
                                if (!url.isNullOrBlank()) {
                                    val api = ApiClient.getApi(url)
                                    api.updateSettings(
                                        SettingsUpdateDto(
                                            defaultActiveHoursStart = startStr,
                                            defaultActiveHoursEnd = endStr,
                                            timeFormat = timeFormat
                                        )
                                    )
                                }
                                message = "Settings saved!"
                            } catch (_: Exception) { message = "Saved locally (server unreachable)" } finally { isSaving = false }
                        }
                    }, enabled = !isSaving) { Text(if (isSaving) "Saving..." else "Save") }
                    if (message != null) { Spacer(modifier = Modifier.height(8.dp)); Text(message!!, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // Time format card
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Time Format", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = timeFormat == "12h", onClick = { timeFormat = "12h" }, label = { Text("12-hour (AM/PM)") })
                        FilterChip(selected = timeFormat == "24h", onClick = { timeFormat = "24h" }, label = { Text("24-hour") })
                    }
                }
            }

            // Persistent notification toggle
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Quick Add Notification", style = MaterialTheme.typography.titleSmall)
                        Text("Persistent notification to quickly create reminders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = persistentNotifEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                preferencesManager.setPersistentNotification(enabled)
                                val serviceIntent = Intent(context, com.ninetyhd.app.services.PersistentNotificationService::class.java)
                                if (enabled) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.stopService(serviceIntent)
                                }
                            }
                        }
                    )
                }
            }

            // Troubleshooting
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Troubleshooting", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sendTestNotification(context) }) { Text("Test") }
                        Button(onClick = { sendTestNotificationWithConfirm(context) }) { Text("Test + Confirm") }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("'Test' sends a basic notification. 'Test + Confirm' sends one with escalation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Time picker dialogs
    if (showActiveStartPicker) {
        key("settings_active_start") {
            SettingsTimePickerDialog(
                initialHour = activeStartHour, initialMinute = activeStartMinute, use24h = (timeFormat == "24h"),
                onConfirm = { h, m -> activeStartHour = h; activeStartMinute = m; showActiveStartPicker = false },
                onDismiss = { showActiveStartPicker = false }
            )
        }
    }
    if (showActiveEndPicker) {
        key("settings_active_end") {
            SettingsTimePickerDialog(
                initialHour = activeEndHour, initialMinute = activeEndMinute, use24h = (timeFormat == "24h"),
                onConfirm = { h, m -> activeEndHour = h; activeEndMinute = m; showActiveEndPicker = false },
                onDismiss = { showActiveEndPicker = false }
            )
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect") },
            text = { Text("This stops syncing with the server. Your reminders stay on this device. You can reconnect anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        preferencesManager.clearServerUrl()
                        preferencesManager.clearSyncState()
                        onDisconnect()
                    }; showDisconnectDialog = false
                },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsTimeField(label: String, hour: Int, minute: Int, use24h: Boolean, onClick: () -> Unit) {
    val displayTime = com.ninetyhd.app.ui.TimeDisplay.format(hour, minute, use24h)

    OutlinedTextField(
        value = displayTime,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.AccessTime, contentDescription = "Pick time") },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        enabled = false,
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTimePickerDialog(
    initialHour: Int, initialMinute: Int, use24h: Boolean,
    onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = use24h)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Select time") },
        text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } }
    )
}

/** Format an ISO server timestamp into a short local time-ago-ish label. */
private fun formatSyncTime(iso: String, use24h: Boolean): String {
    return try {
        val instant = try { java.time.Instant.parse(iso) } catch (_: Exception) { java.time.Instant.parse(iso + "Z") }
        val dt = instant.atZone(java.time.ZoneId.systemDefault())
        dt.format(java.time.format.DateTimeFormatter.ofPattern(if (use24h) "MMM d, HH:mm" else "MMM d, h:mm a"))
    } catch (_: Exception) { iso }
}

private fun sendTestNotification(context: Context) {
    val notification = NotificationCompat.Builder(context, NinetyHDApp.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("90HD Test")
        .setContentText("Basic notification â€” no confirmation required.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(99999, notification)
}

private fun sendTestNotificationWithConfirm(context: Context) {
    val reminderId = "test-confirm-reminder"
    val instanceTime = java.time.LocalDateTime.now().toString()

    val confirmIntent = Intent(context, ConfirmReceiver::class.java).apply {
        putExtra(NotificationScheduler.EXTRA_REMINDER_ID, reminderId)
        putExtra(NotificationScheduler.EXTRA_INSTANCE_TIME, instanceTime)
    }
    val confirmPendingIntent = PendingIntent.getBroadcast(
        context, NotificationScheduler.confirmRequestCode(reminderId), confirmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, NinetyHDApp.CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("90HD Test (with confirmation)")
        .setContentText("Tap Confirm to acknowledge. If ignored, escalation starts in 5 min.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(false)
        .addAction(android.R.drawable.ic_menu_send, "Confirm", confirmPendingIntent)
        .build()

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NotificationScheduler.notificationIdFor(reminderId), notification)

    EscalationScheduler.scheduleEscalation(context, reminderId, "Test notification with confirmation", instanceTime)
}

private fun parseTimeInto(timeStr: String?, block: (Int, Int) -> Unit) {
    if (timeStr.isNullOrBlank()) return
    try {
        val parts = timeStr.split(":")
        block(parts[0].toInt(), parts[1].toInt())
    } catch (_: Exception) {}
}
