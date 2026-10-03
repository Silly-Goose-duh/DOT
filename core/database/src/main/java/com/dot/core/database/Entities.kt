package com.dot.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.dot.core.model.CachedEvent
import com.dot.core.model.MemoryItem
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import com.dot.core.model.Task
import com.dot.core.model.TaskStatus
import java.util.UUID

/** Tolerant UUID parse: entities written by an older schema may hold non-UUID ids. */
internal fun parseUuidOrNull(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()

/** Tolerant enum parse: an unknown persisted value must not crash a query. */
internal inline fun <reified T : Enum<T>> parseEnumOr(raw: String?, fallback: T): T =
    raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

/**
 * v2 added `index_tasks_dueAtEpochMs`. Every home-screen read orders by
 * `dueAtEpochMs` and the UI collects that as a Flow, so without the index SQLite
 * re-sorts the whole table on each write. See Migrations.kt for the full rationale.
 */
@Entity(tableName = "tasks", indices = [Index(value = ["dueAtEpochMs"])])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: String,
    val priority: String?,
    val dueAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
) {
    fun toDomain() = Task(
        // Malformed legacy rows must not crash a query; skip the id instead of throwing.
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        title = title,
        description = description,
        status = parseEnumOr(status, TaskStatus.OPEN),
        priority = priority?.let { parseEnumOr(it, com.dot.core.model.Priority.NORMAL) },
        dueAt = dueAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        createdAt = java.time.Instant.ofEpochMilli(createdAtEpochMs),
        updatedAt = java.time.Instant.ofEpochMilli(updatedAtEpochMs),
        completedAt = completedAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
    )

    companion object {
        fun from(t: Task) = TaskEntity(
            id = t.id.toString(),
            title = t.title,
            description = t.description,
            status = t.status.name,
            priority = t.priority?.name,
            dueAtEpochMs = t.dueAt?.toEpochMilli(),
            createdAtEpochMs = t.createdAt.toEpochMilli(),
            updatedAtEpochMs = t.updatedAt.toEpochMilli(),
            completedAtEpochMs = t.completedAt?.toEpochMilli(),
        )
    }
}

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val remindAtEpochMs: Long,
    val taskId: String?,
    val state: String,
    val createdAtEpochMs: Long,
) {
    fun toDomain() = Reminder(
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        title = title,
        remindAt = java.time.Instant.ofEpochMilli(remindAtEpochMs),
        taskId = taskId?.let { parseUuidOrNull(it) },
        state = parseEnumOr(state, ReminderState.SCHEDULED),
        createdAt = java.time.Instant.ofEpochMilli(createdAtEpochMs),
    )

    companion object {
        fun from(r: Reminder) = ReminderEntity(
            id = r.id.toString(),
            title = r.title,
            remindAtEpochMs = r.remindAt.toEpochMilli(),
            taskId = r.taskId?.toString(),
            state = r.state.name,
            createdAtEpochMs = r.createdAt.toEpochMilli(),
        )
    }
}

@Entity(tableName = "event_cache")
data class EventCacheEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val providerEventId: String?,
    val title: String,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long?,
    val location: String?,
    val lastSyncedAtEpochMs: Long,
) {
    fun toDomain() = CachedEvent(
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        provider = provider,
        providerEventId = providerEventId,
        title = title,
        startAt = java.time.Instant.ofEpochMilli(startAtEpochMs),
        endAt = endAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        location = location,
        lastSyncedAt = java.time.Instant.ofEpochMilli(lastSyncedAtEpochMs),
    )

    companion object {
        fun from(e: CachedEvent) = EventCacheEntity(
            id = e.id.toString(),
            provider = e.provider,
            providerEventId = e.providerEventId,
            title = e.title,
            startAtEpochMs = e.startAt.toEpochMilli(),
            endAtEpochMs = e.endAt?.toEpochMilli(),
            location = e.location,
            lastSyncedAtEpochMs = e.lastSyncedAt.toEpochMilli(),
        )
    }
}

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val body: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Added in schema v2. The DDL default is mandatory, not cosmetic: a NOT NULL
     * column added by `ALTER TABLE` needs a default or SQLite refuses the
     * statement on a non-empty table, and Room's post-migration schema check
     * compares this string against the real column default.
     */
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
) {
    fun toDomain() = Note(
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        title = title,
        body = body,
        createdAt = java.time.Instant.ofEpochMilli(createdAtEpochMs),
        updatedAt = java.time.Instant.ofEpochMilli(updatedAtEpochMs),
    )

    companion object {
        fun from(n: Note) = NoteEntity(
            id = n.id.toString(),
            title = n.title,
            body = n.body,
            createdAtEpochMs = n.createdAt.toEpochMilli(),
            updatedAtEpochMs = n.updatedAt.toEpochMilli(),
        )
    }
}

@Entity(tableName = "agent_definitions")
data class AgentDefinitionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val enabled: Boolean,
    val frequencyMinutes: Int?,
    val lastRunAtEpochMs: Long?,
    val lastSuccessAtEpochMs: Long?,
    val lastErrorCode: String?,
) {
    fun toDomain() = AgentDefinition(
        id = id,
        type = type,
        enabled = enabled,
        frequencyMinutes = frequencyMinutes,
        lastRunAt = lastRunAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        lastSuccessAt = lastSuccessAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        lastErrorCode = lastErrorCode,
    )

    companion object {
        fun from(a: AgentDefinition) = AgentDefinitionEntity(
            id = a.id,
            type = a.type,
            enabled = a.enabled,
            frequencyMinutes = a.frequencyMinutes,
            lastRunAtEpochMs = a.lastRunAt?.toEpochMilli(),
            lastSuccessAtEpochMs = a.lastSuccessAt?.toEpochMilli(),
            lastErrorCode = a.lastErrorCode,
        )
    }
}

@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val status: String,
    val compactResult: String?,
    val errorCode: String?,
) {
    fun toDomain() = AgentRun(
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        agentId = agentId,
        startedAt = java.time.Instant.ofEpochMilli(startedAtEpochMs),
        finishedAt = finishedAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        status = parseEnumOr(status, AgentRunStatus.RUNNING),
        compactResult = compactResult,
        errorCode = errorCode,
    )

    companion object {
        fun from(r: AgentRun) = AgentRunEntity(
            id = r.id.toString(),
            agentId = r.agentId,
            startedAtEpochMs = r.startedAt.toEpochMilli(),
            finishedAtEpochMs = r.finishedAt?.toEpochMilli(),
            status = r.status.name,
            compactResult = r.compactResult,
            errorCode = r.errorCode,
        )
    }
}

@Entity(tableName = "memory_items")
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val namespace: String,
    val key: String,
    val value: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val source: String,
    val userVisible: Boolean,
) {
    fun toDomain() = MemoryItem(
        id = parseUuidOrNull(id) ?: UUID.randomUUID(),
        namespace = namespace,
        key = key,
        value = value,
        createdAt = java.time.Instant.ofEpochMilli(createdAtEpochMs),
        updatedAt = java.time.Instant.ofEpochMilli(updatedAtEpochMs),
        expiresAt = expiresAtEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        source = source,
        userVisible = userVisible,
    )

    companion object {
        fun from(m: MemoryItem) = MemoryItemEntity(
            id = m.id.toString(),
            namespace = m.namespace,
            key = m.key,
            value = m.value,
            createdAtEpochMs = m.createdAt.toEpochMilli(),
            updatedAtEpochMs = m.updatedAt.toEpochMilli(),
            expiresAtEpochMs = m.expiresAt?.toEpochMilli(),
            source = m.source,
            userVisible = m.userVisible,
        )
    }
}
