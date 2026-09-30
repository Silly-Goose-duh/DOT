package com.dot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dot.agent.memory.MemoryPolicy
import com.dot.app.AppContainer
import com.dot.app.command.CommandRuntime
import com.dot.app.data.DotSettings
import com.dot.core.model.CachedEvent
import com.dot.core.model.DirectResponse
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.Task
import com.dot.core.model.TaskStatus
import com.dot.core.model.TodayAggregator
import com.dot.core.model.TodaySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** One line of the conversation log. Never contains a note body or a transcript. */
data class ChatLine(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
)

data class DotUiState(
    val summary: TodaySummary? = null,
    val tasks: List<Task> = emptyList(),
    val notes: List<Note> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val events: List<CachedEvent> = emptyList(),
    val settings: DotSettings = DotSettings(),
    val busy: Boolean = false,
    val pendingConfirm: CommandRuntime.PendingAction? = null,
)

class DotViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val chat = MutableStateFlow<List<ChatLine>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val pending = MutableStateFlow<CommandRuntime.PendingAction?>(null)
    private var nextLineId = 0L

    val chatLines: StateFlow<List<ChatLine>> = chat.asStateFlow()

    val memoryPolicy = MemoryPolicy()

    private data class Data(
        val tasks: List<Task>,
        val notes: List<Note>,
        val reminders: List<Reminder>,
        val events: List<CachedEvent>,
    )

    private val data: Flow<Data> = combine(
        container.taskRepo.observe(),
        container.noteRepo.observe(),
        container.reminderRepo.observe(),
        container.eventRepo.observe(),
    ) { tasks, notes, reminders, events -> Data(tasks, notes, reminders, events) }

    val uiState: StateFlow<DotUiState> = combine(
        data,
        container.settings.settings,
        busy,
        pending,
    ) { d, settings, isBusy, pendingAction ->
        DotUiState(
            summary = TodayAggregator.aggregate(
                zoneId = container.zoneId,
                now = Instant.now(),
                allTasks = d.tasks,
                allReminders = d.reminders,
                allEvents = d.events,
            ),
            tasks = d.tasks,
            notes = d.notes,
            reminders = d.reminders,
            events = d.events,
            settings = settings,
            busy = isBusy,
            pendingConfirm = pendingAction,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DotUiState())

    /** Sends raw text through the runtime. A pending confirmation is answered here. */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        append(trimmed, fromUser = true)

        val awaiting = pending.value
        if (awaiting != null) {
            if (isAffirmative(trimmed)) {
                pending.value = null
                run { container.commandRuntime.confirm(awaiting) }
            } else {
                pending.value = null
                append("Cancelled.", fromUser = false)
            }
            return
        }

        run { container.commandRuntime.handle(trimmed) }
    }

    fun dismissConfirmation() {
        pending.value = null
        append("Cancelled.", fromUser = false)
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            val now = Instant.now()
            val next = if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE
            container.taskRepo.update(
                task.copy(
                    status = next,
                    completedAt = if (next == TaskStatus.DONE) now else null,
                    updatedAt = now,
                ),
            )
        }
    }

    fun addTask(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { container.taskRepo.insert(Task(title = clean)) }
    }

    fun setConfirmationsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setConfirmationsEnabled(enabled) }
    }

    fun clearLocalMemory() {
        viewModelScope.launch {
            container.memoryRepository.clearAll()
            append("Local memory cleared.", fromUser = false)
        }
    }

    fun purgeExpiredMemory() {
        viewModelScope.launch {
            val n = container.memoryRepository.purgeExpired()
            append("Purged $n expired item${if (n == 1) "" else "s"}.", fromUser = false)
        }
    }

    private fun run(block: suspend () -> CommandRuntime.CommandTurn) {
        viewModelScope.launch {
            busy.value = true
            try {
                when (val turn = block()) {
                    is CommandRuntime.CommandTurn.Answer -> respond(turn.response)
                    is CommandRuntime.CommandTurn.Confirm -> {
                        pending.value = turn.pending
                        append(turn.prompt, fromUser = false)
                    }
                }
            } finally {
                busy.value = false
            }
        }
    }

    private fun respond(response: DirectResponse) {
        val text = when (response) {
            is DirectResponse.Action -> response.message
            is DirectResponse.Information -> response.message
            is DirectResponse.Progress -> response.message
            is DirectResponse.Clarification -> response.question
        }
        append(text, fromUser = false)
    }

    private fun append(text: String, fromUser: Boolean) {
        chat.value = chat.value + ChatLine(nextLineId++, text, fromUser)
    }

    private fun isAffirmative(text: String): Boolean =
        text.lowercase().trim() in AFFIRMATIVE

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DotViewModel(container) as T
    }

    private companion object {
        val AFFIRMATIVE = setOf(
            "yes", "y", "yeah", "yep", "yes do it", "do it", "confirm", "ok", "okay",
        )
    }
}
