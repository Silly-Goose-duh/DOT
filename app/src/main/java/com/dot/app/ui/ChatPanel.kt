package com.dot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dot.app.voice.VoiceSessionState

/**
 * Conversation surface. Chat lines carry the response sentence only — no note
 * bodies and no raw transcript are rendered into the log.
 */
@Composable
fun ChatPanel(
    lines: List<ChatLine>,
    busy: Boolean,
    pendingConfirm: Boolean,
    onSubmit: (String) -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides push-to-talk entirely, e.g. when no recogniser exists. */
    voice: VoiceSessionState? = null,
    onVoiceTap: () -> Unit = {},
    onVoiceRequestPermission: () -> Unit = {},
    onVoiceOpenSettings: () -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(lines, key = { it.id }) { line ->
                ChatBubble(line)
            }
            if (busy) {
                item {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (pendingConfirm) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissConfirm) { Text("Cancel") }
                TextButton(onClick = { onSubmit("yes") }) { Text("Confirm") }
            }
        }

        if (voice != null) {
            VoicePanel(
                state = voice,
                onTap = onVoiceTap,
                onRequestPermission = onVoiceRequestPermission,
                onOpenSettings = onVoiceOpenSettings,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("What do you need?") },
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    onSubmit(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !busy,
            ) { Text("Send") }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (line.fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = line.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = if (line.fromUser) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

@Composable
fun TodayScreen(state: DotUiState, onToggleTask: (com.dot.core.model.Task) -> Unit) {
    val summary = state.summary
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Today", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            val line = buildString {
                append(summary?.eventCount ?: 0)
                append(" events, ")
                append(summary?.openTaskCount ?: 0)
                append(" open tasks, ")
                append(summary?.reminderCount ?: 0)
                append(" reminders")
            }
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }

        if (!state.events.isEmpty()) {
            item { Text("Events", style = MaterialTheme.typography.titleMedium) }
            items(state.events, key = { it.id.toString() }) { event ->
                Text(event.title, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { Text("Tasks", style = MaterialTheme.typography.titleMedium) }
        items(state.tasks, key = { it.id.toString() }) { task ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onToggleTask(task) }) {
                    Text(if (task.status == com.dot.core.model.TaskStatus.DONE) "[x]" else "[ ]")
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.status == com.dot.core.model.TaskStatus.DONE) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
