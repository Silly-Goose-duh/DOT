package com.dot.agent.tools

import com.dot.agent.policy.RiskLevel
import com.dot.agent.policy.ToolCategory
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import com.dot.core.model.Task
import com.dot.core.model.TodayAggregator
import com.dot.core.model.TodaySummary
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Local read/write tools. No network, no AI. */
class CreateTaskTool(
    private val taskRepo: TaskRepositoryPort,
    private val zoneId: ZoneId,
) : DotTool<Map<String, String?>, Task> {
    override val name = "create_task"
    override val description = "Create a local task with an optional due time."
    override val category = ToolCategory.LOCAL_WRITE
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_000L
    override val maxRetries = 1
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>): ValidationResult {
        val t = InputGuards.title(input["title"])
        if (t !is ValidationResult.Valid) return t
        val due = input["dueAt"]
        if (due != null && runCatching { Instant.parse(due) }.isFailure) {
            return ValidationResult.Invalid("dueAt", "must be ISO-8601 instant")
        }
        return ValidationResult.Valid
    }

    override suspend fun execute(input: Map<String, String?>): Task {
        val task = Task(
            title = input["title"]!!.trim(),
            description = input["description"],
            dueAt = input["dueAt"]?.let { Instant.parse(it) },
        )
        taskRepo.insert(task)
        return task
    }
}

class CompleteTaskTool(private val taskRepo: TaskRepositoryPort) : DotTool<Map<String, String?>, Task?> {
    override val name = "complete_task"
    override val description = "Mark a task complete by id or title."
    override val category = ToolCategory.LOCAL_WRITE
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_000L
    override val maxRetries = 1
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>) = InputGuards.title(input["taskRef"])

    override suspend fun execute(input: Map<String, String?>): Task? {
        val ref = input["taskRef"]!!.trim()
        val byId = parseUuidOrNull(ref)?.let { taskRepo.byId(it) }
        val task = byId ?: taskRepo.findByTitleContains(ref) ?: return null
        val done = task.copy(
            status = com.dot.core.model.TaskStatus.DONE,
            completedAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        taskRepo.update(done)
        return done
    }
}

class CreateReminderTool(private val reminderRepo: ReminderRepositoryPort) : DotTool<Map<String, String?>, Reminder> {
    override val name = "create_reminder"
    override val description = "Create a local reminder that fires at a given time."
    override val category = ToolCategory.LOCAL_WRITE
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_000L
    override val maxRetries = 1
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>): ValidationResult {
        val t = InputGuards.title(input["title"])
        if (t !is ValidationResult.Valid) return t
        val at = input["remindAt"]
            ?: return ValidationResult.Invalid("remindAt", "required")
        return runCatching { Instant.parse(at) }
            .fold({ ValidationResult.Valid }, { ValidationResult.Invalid("remindAt", "must be ISO-8601 instant") })
    }

    override suspend fun execute(input: Map<String, String?>): Reminder {
        val reminder = Reminder(
            title = input["title"]!!.trim(),
            remindAt = Instant.parse(input["remindAt"]!!),
        )
        reminderRepo.insert(reminder)
        return reminder
    }
}

class GetTodayTool(
    private val taskRepo: TaskRepositoryPort,
    private val reminderRepo: ReminderRepositoryPort,
    private val eventRepo: EventRepositoryPort,
    private val zoneId: ZoneId,
) : (DotTool<Unit, TodaySummary>) {
    override val name = "get_today"
    override val description = "Return today's events, open tasks and reminders from local data."
    override val category = ToolCategory.LOCAL_READ
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 800L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = false

    override fun validate(input: Unit) = ValidationResult.Valid

    override suspend fun execute(input: Unit): TodaySummary = TodayAggregator.aggregate(
        zoneId = zoneId,
        now = Instant.now(),
        allTasks = taskRepo.all(),
        allReminders = reminderRepo.all(),
        allEvents = eventRepo.all(),
    )
}

class CreateNoteTool(private val noteRepo: NoteRepositoryPort) : DotTool<Map<String, String?>, Note> {
    override val name = "create_note"
    override val description = "Save a quick local note."
    override val category = ToolCategory.LOCAL_WRITE
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_000L
    override val maxRetries = 1
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>): ValidationResult {
        val b = InputGuards.body(input["body"] ?: input["title"])
        return if (b is ValidationResult.Valid) InputGuards.title(input["body"] ?: input["title"]) else b
    }

    override suspend fun execute(input: Map<String, String?>): Note {
        val note = Note(title = input["title"], body = input["body"] ?: input["title"]!!)
        noteRepo.insert(note)
        return note
    }
}

class SearchLocalTool(
    private val taskRepo: TaskRepositoryPort,
    private val noteRepo: NoteRepositoryPort,
) : DotTool<Map<String, String?>, List<String>> {
    override val name = "search_local"
    override val description = "Search permitted local tasks and notes."
    override val category = ToolCategory.LOCAL_READ
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 800L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = false

    override fun validate(input: Map<String, String?>) = InputGuards.query(input["query"])

    override suspend fun execute(input: Map<String, String?>): List<String> {
        val q = input["query"]!!.trim()
        return (taskRepo.search(q) + noteRepo.search(q)).map { it.first }
    }
}

/**
 * v0.1 `open_app`. Uses an allowlist of aliases/packages, never raw model strings.
 * On-device resolution is a platform concern; this returns the resolved target only.
 */
class OpenAppTool : DotTool<Map<String, String?>, String> {
    override val name = "open_app"
    override val description = "Open a supported installed application by alias or package name."
    override val category = ToolCategory.SYSTEM_ACTION
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_500L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>) = InputGuards.packageRef(input["packageOrAlias"])

    override suspend fun execute(input: Map<String, String?>): String {
        val ref = input["packageOrAlias"]!!.trim().lowercase()
        return InputGuards.APP_ALIASES[ref] ?: input["packageOrAlias"]!!.trim()
    }
}

/** Repository ports. Implementations live in core:database; tools depend only on these. */
interface TaskRepositoryPort {
    suspend fun insert(task: Task)
    suspend fun update(task: Task)
    suspend fun byId(id: UUID): Task?
    suspend fun all(): List<Task>
    suspend fun search(q: String): List<Pair<String, UUID>>
    suspend fun findByTitleContains(fragment: String): Task?
}

interface ReminderRepositoryPort {
    suspend fun insert(reminder: Reminder)
    suspend fun all(): List<Reminder>
    suspend fun cancel(id: UUID)
}

interface EventRepositoryPort {
    suspend fun all(): List<com.dot.core.model.CachedEvent>
}

interface NoteRepositoryPort {
    suspend fun insert(note: Note)
    suspend fun search(q: String): List<Pair<String, UUID>>
}

internal fun parseUuidOrNull(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()
