package com.dot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun TasksScreen(
    state: DotUiState,
    onAddTask: (String) -> Unit,
    onToggleTask: (com.dot.core.model.Task) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val zone = ZoneId.systemDefault()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Tasks", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("New task") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    onAddTask(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Add") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.tasks, key = { it.id.toString() }) { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onToggleTask(task) }) {
                        Text(if (task.status == com.dot.core.model.TaskStatus.DONE) "[x]" else "[ ]")
                    }
                    Column {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge)
                        task.dueAt?.let {
                            Text(
                                "due ${TIME_FMT.format(it.atZone(zone))}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotesScreen(state: DotUiState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notes", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.notes, key = { it.id.toString() }) { note ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = note.title ?: note.body.take(40),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (note.title != null) {
                            Text(note.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: DotUiState,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onClearMemory: () -> Unit,
    onPurgeExpired: () -> Unit,
    onToggleConfirmations: (Boolean) -> Unit,
    onToggleAiFallback: (Boolean) -> Unit,
    aiAvailable: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Confirm risky actions")
            Switch(
                checked = state.settings.confirmationsEnabled,
                onCheckedChange = onToggleConfirmations,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Notifications")
                Text(
                    text = if (notificationsGranted) "Allowed" else "Not granted",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (!notificationsGranted) {
                TextButton(onClick = onRequestNotificationPermission) { Text("Allow") }
            }
        }

        Text("AI fallback", style = MaterialTheme.typography.titleMedium)
        if (aiAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use AI for complex requests")
                    Text(
                        "Simple commands still run locally. The assistant can only " +
                            "propose an action you have already allowed.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = state.settings.aiFallbackEnabled,
                    onCheckedChange = onToggleAiFallback,
                )
            }
        } else {
            // Say why it is unavailable rather than showing a dead switch: the key
            // is absent from this build, so the honest state is "not configured".
            Text(
                "No assistant key is configured in this build, so anything the " +
                    "offline router cannot match is refused rather than guessed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("Local memory", style = MaterialTheme.typography.titleMedium)
        Text("Purge expired cache items", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onPurgeExpired) { Text("Purge expired") }
        TextButton(onClick = onClearMemory) { Text("Clear all local memory") }
    }
}
