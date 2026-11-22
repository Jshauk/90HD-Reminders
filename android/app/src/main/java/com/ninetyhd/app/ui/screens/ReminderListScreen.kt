package com.ninetyhd.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetyhd.app.data.local.entity.ReminderEntity
import com.ninetyhd.app.data.local.entity.SettingsEntity
import com.ninetyhd.app.data.repository.ReminderRepository
import com.ninetyhd.app.notifications.NextFireTimeCalculator
import com.ninetyhd.app.ui.TimeDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderListScreen(
    onCreateNew: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ReminderRepository(context) }

    // Local-first: reminders and settings come straight from Room and update
    // automatically as the underlying data changes.
    val reminders by repository.getReminders().collectAsState(initial = emptyList())
    val settings by repository.getSettings().collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("90HD", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNew,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Reminder")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (reminders.isEmpty()) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No reminders yet", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Tap + to create one", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(reminders) { reminder ->
                        ReminderCard(reminder = reminder, settings = settings, onClick = { onEdit(reminder.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(reminder: ReminderEntity, settings: SettingsEntity?, onClick: () -> Unit) {
    val use24h = TimeDisplay.is24h(settings?.timeFormat)
    val nextFire = NextFireTimeCalculator.getNextFireTimeDisplay(
        reminder,
        settings?.defaultActiveHoursStart,
        settings?.defaultActiveHoursEnd,
        use24h
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (reminder.active) 1f else 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = reminder.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (!reminder.active) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Paused", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                            labelColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Every ${reminder.frequencyValue} ${reminder.frequencyUnit} at ${TimeDisplay.format(reminder.startTime, use24h)}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (reminder.activeHoursStart != null) {
                Text(
                    text = "Active: ${TimeDisplay.format(reminder.activeHoursStart, use24h)} - ${TimeDisplay.format(reminder.activeHoursEnd, use24h)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (nextFire != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Next: $nextFire",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
