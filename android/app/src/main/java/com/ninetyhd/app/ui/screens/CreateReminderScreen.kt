package com.ninetyhd.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.data.repository.ReminderRepository
import com.ninetyhd.app.notifications.NotificationScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateReminderScreen(
    reminderId: String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ReminderRepository(context) }
    val timeSettings by repository.getSettings().collectAsState(initial = null)
    val use24h = com.ninetyhd.app.ui.TimeDisplay.is24h(timeSettings?.timeFormat)
    val isEditing = reminderId != null

    var text by remember { mutableStateOf("") }
    var frequencyValue by remember { mutableStateOf("1") }
    var frequencyUnit by remember { mutableStateOf("days") }
    var startTimeHour by remember { mutableIntStateOf(8) }
    var startTimeMinute by remember { mutableIntStateOf(0) }
    var hasStartDate by remember { mutableStateOf(false) }
    var startDateMillis by remember { mutableStateOf<Long?>(null) }
    var hasEndTime by remember { mutableStateOf(false) }
    var endTimeHour by remember { mutableIntStateOf(22) }
    var endTimeMinute by remember { mutableIntStateOf(0) }
    var hasEndDate by remember { mutableStateOf(false) }
    var endDateMillis by remember { mutableStateOf<Long?>(null) }
    var hasActiveHoursOverride by remember { mutableStateOf(false) }
    var activeStartHour by remember { mutableIntStateOf(8) }
    var activeStartMinute by remember { mutableIntStateOf(0) }
    var activeEndHour by remember { mutableIntStateOf(22) }
    var activeEndMinute by remember { mutableIntStateOf(0) }
    var requiresConfirmation by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Time picker dialog states
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showActiveStartPicker by remember { mutableStateOf(false) }
    var showActiveEndPicker by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(reminderId) {
        if (reminderId != null) {
            isLoading = true
            try {
                val reminder = repository.getReminderById(reminderId)
                if (reminder != null) {
                    text = reminder.text
                    frequencyValue = reminder.frequencyValue.toString()
                    frequencyUnit = reminder.frequencyUnit
                    parseTimeInto(reminder.startTime) { h, m -> startTimeHour = h; startTimeMinute = m }
                    if (reminder.endTime != null) {
                        hasEndTime = true
                        parseTimeInto(reminder.endTime) { h, m -> endTimeHour = h; endTimeMinute = m }
                    }
                    if (reminder.startDate != null) {
                        hasStartDate = true
                        startDateMillis = parseDateToMillis(reminder.startDate)
                    }
                    if (reminder.endDate != null) {
                        hasEndDate = true
                        endDateMillis = parseDateToMillis(reminder.endDate)
                    }
                    if (reminder.activeHoursStart != null) {
                        hasActiveHoursOverride = true
                        parseTimeInto(reminder.activeHoursStart) { h, m -> activeStartHour = h; activeStartMinute = m }
                        parseTimeInto(reminder.activeHoursEnd) { h, m -> activeEndHour = h; activeEndMinute = m }
                    }
                    requiresConfirmation = reminder.requiresConfirmation
                }
            } catch (e: Exception) {
                error = "Failed to load reminder"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Reminder" else "New Reminder", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Reminder text
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Reminder Text") },
                    placeholder = { Text("What do you need to remember?") },
                    modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3
                )

                // Frequency row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = frequencyValue,
                        onValueChange = { frequencyValue = it.filter { c -> c.isDigit() } },
                        label = { Text("Every") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(2f)) {
                        OutlinedTextField(
                            value = frequencyUnit.replaceFirstChar { it.uppercase() },
                            onValueChange = {}, readOnly = true, label = { Text("Unit") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("minutes", "hours", "days", "weeks", "months").forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.replaceFirstChar { it.uppercase() }) },
                                    onClick = { frequencyUnit = unit; expanded = false }
                                )
                            }
                        }
                    }
                }

                // Start time picker
                TimePickerField(
                    label = "Start Time",
                    hour = startTimeHour,
                    minute = startTimeMinute,
                    use24h = use24h,
                    onClick = { showStartTimePicker = true }
                )

                // Start date (optional)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasStartDate, onCheckedChange = { hasStartDate = it })
                    Text("Set start date", style = MaterialTheme.typography.bodyMedium)
                }
                if (hasStartDate) {
                    DatePickerField(
                        label = "Start Date",
                        dateMillis = startDateMillis,
                        onClick = { showStartDatePicker = true }
                    )
                }

                // End time (optional)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasEndTime, onCheckedChange = { hasEndTime = it })
                    Text("Set end time", style = MaterialTheme.typography.bodyMedium)
                }
                if (hasEndTime) {
                    TimePickerField(
                        label = "End Time",
                        hour = endTimeHour,
                        minute = endTimeMinute,
                        use24h = use24h,
                        onClick = { showEndTimePicker = true }
                    )
                }

                // End date (optional)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasEndDate, onCheckedChange = { hasEndDate = it })
                    Text("Set end date", style = MaterialTheme.typography.bodyMedium)
                }
                if (hasEndDate) {
                    DatePickerField(
                        label = "End Date",
                        dateMillis = endDateMillis,
                        onClick = { showEndDatePicker = true }
                    )
                }

                HorizontalDivider()

                // Active hours override
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hasActiveHoursOverride, onCheckedChange = { hasActiveHoursOverride = it })
                    Column {
                        Text("Override active hours", style = MaterialTheme.typography.bodyMedium)
                        Text("Leave unchecked to use default from settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (hasActiveHoursOverride) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            TimePickerField(label = "From", hour = activeStartHour, minute = activeStartMinute, use24h = use24h, onClick = { showActiveStartPicker = true })
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TimePickerField(label = "Until", hour = activeEndHour, minute = activeEndMinute, use24h = use24h, onClick = { showActiveEndPicker = true })
                        }
                    }
                }

                if (error != null) { Text(error!!, color = MaterialTheme.colorScheme.error) }

                HorizontalDivider()

                // Requires confirmation toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = requiresConfirmation, onCheckedChange = { requiresConfirmation = it })
                    Column {
                        Text("Require confirmation", style = MaterialTheme.typography.bodyMedium)
                        Text("If not confirmed within 5 min, re-notifies every 1 min (up to 10x)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save button
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true; error = null
                            try {
                                val freq = frequencyValue.toIntOrNull() ?: 1
                                if (text.isBlank()) { error = "Text is required"; isSaving = false; return@launch }
                                val startTimeStr = formatTime24(startTimeHour, startTimeMinute)
                                val startDateStr = if (hasStartDate && startDateMillis != null) millisToDateStr(startDateMillis!!) else null
                                val endTimeStr = if (hasEndTime) formatTime24(endTimeHour, endTimeMinute) else null
                                val endDateStr = if (hasEndDate && endDateMillis != null) millisToDateStr(endDateMillis!!) else null
                                val activeStartStr = if (hasActiveHoursOverride) formatTime24(activeStartHour, activeStartMinute) else null
                                val activeEndStr = if (hasActiveHoursOverride) formatTime24(activeEndHour, activeEndMinute) else null

                                if (isEditing) {
                                    repository.updateReminder(
                                        id = reminderId!!,
                                        text = text,
                                        frequencyValue = freq,
                                        frequencyUnit = frequencyUnit,
                                        startTime = startTimeStr,
                                        startDate = startDateStr,
                                        endTime = endTimeStr,
                                        endDate = endDateStr,
                                        activeHoursStart = activeStartStr,
                                        activeHoursEnd = activeEndStr,
                                        requiresConfirmation = requiresConfirmation,
                                        active = true
                                    )
                                } else {
                                    repository.createReminder(
                                        text = text,
                                        frequencyValue = freq,
                                        frequencyUnit = frequencyUnit,
                                        startTime = startTimeStr,
                                        startDate = startDateStr,
                                        endTime = endTimeStr,
                                        endDate = endDateStr,
                                        activeHoursStart = activeStartStr,
                                        activeHoursEnd = activeEndStr,
                                        requiresConfirmation = requiresConfirmation,
                                        active = true
                                    )
                                }
                                // Reschedule alarms from the updated local data
                                NotificationScheduler(context).scheduleAll()
                                onSaved()
                            } catch (e: Exception) {
                                error = "Failed to save: ${e.localizedMessage}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !isSaving
                ) { Text(if (isSaving) "Saving..." else "Save Reminder") }
            }
        }
    }

    // Time picker dialogs - each wrapped in key() to ensure independent state
    if (showStartTimePicker) {
        key("start_time_picker") {
            TimePickerDialog(
                initialHour = startTimeHour,
                initialMinute = startTimeMinute,
                use24h = use24h,
                onConfirm = { h, m -> startTimeHour = h; startTimeMinute = m; showStartTimePicker = false },
                onDismiss = { showStartTimePicker = false }
            )
        }
    }
    if (showEndTimePicker) {
        key("end_time_picker") {
            TimePickerDialog(
                initialHour = endTimeHour,
                initialMinute = endTimeMinute,
                use24h = use24h,
                onConfirm = { h, m -> endTimeHour = h; endTimeMinute = m; showEndTimePicker = false },
                onDismiss = { showEndTimePicker = false }
            )
        }
    }
    if (showActiveStartPicker) {
        key("active_start_picker") {
            TimePickerDialog(
                initialHour = activeStartHour,
                initialMinute = activeStartMinute,
                use24h = use24h,
                onConfirm = { h, m -> activeStartHour = h; activeStartMinute = m; showActiveStartPicker = false },
                onDismiss = { showActiveStartPicker = false }
            )
        }
    }
    if (showActiveEndPicker) {
        key("active_end_picker") {
            TimePickerDialog(
                initialHour = activeEndHour,
                initialMinute = activeEndMinute,
                use24h = use24h,
                onConfirm = { h, m -> activeEndHour = h; activeEndMinute = m; showActiveEndPicker = false },
                onDismiss = { showActiveEndPicker = false }
            )
        }
    }

    // Date picker dialogs
    if (showStartDatePicker) {
        DatePickerDialog(
            initialMillis = startDateMillis,
            onConfirm = { millis -> startDateMillis = millis; showStartDatePicker = false },
            onDismiss = { showStartDatePicker = false }
        )
    }
    if (showEndDatePicker) {
        DatePickerDialog(
            initialMillis = endDateMillis,
            onConfirm = { millis -> endDateMillis = millis; showEndDatePicker = false },
            onDismiss = { showEndDatePicker = false }
        )
    }

    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Reminder") },
            text = { Text("Are you sure you want to delete this reminder?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            reminderId?.let {
                                NotificationScheduler(context).cancelReminder(it)
                                repository.deleteReminder(it)
                            }
                            onSaved()
                        } catch (_: Exception) { error = "Failed to delete" }
                    }; showDeleteDialog = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TimePickerField(label: String, hour: Int, minute: Int, use24h: Boolean, onClick: () -> Unit) {
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
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    use24h: Boolean,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        
        is24Hour = use24h
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour, timePickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Select time") },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timePickerState)
            }
        }
    )
}

private fun formatTime24(hour: Int, minute: Int): String {
    return "%02d:%02d".format(hour, minute)
}

private fun formatTime12(hour: Int, minute: Int): String {
    val ampm = if (hour >= 12) "PM" else "AM"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(h12, minute, ampm)
}

private fun parseTimeInto(timeStr: String?, block: (Int, Int) -> Unit) {
    if (timeStr.isNullOrBlank()) return
    try {
        val parts = timeStr.split(":")
        block(parts[0].toInt(), parts[1].toInt())
    } catch (_: Exception) {}
}

private fun parseDateToMillis(dateStr: String?): Long? {
    if (dateStr.isNullOrBlank()) return null
    return try {
        val date = java.time.LocalDate.parse(dateStr)
        date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) { null }
}

private fun millisToDateStr(millis: Long): String {
    val instant = java.time.Instant.ofEpochMilli(millis)
    val date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return date.toString() // YYYY-MM-DD
}

private fun formatDateDisplay(millis: Long?): String {
    if (millis == null) return "Not set"
    val instant = java.time.Instant.ofEpochMilli(millis)
    val date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
    return date.format(formatter)
}

@Composable
private fun DatePickerField(label: String, dateMillis: Long?, onClick: () -> Unit) {
    OutlinedTextField(
        value = formatDateDisplay(dateMillis),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.AccessTime, contentDescription = "Pick date") },
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
private fun DatePickerDialog(
    initialMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { onConfirm(it) }
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}
