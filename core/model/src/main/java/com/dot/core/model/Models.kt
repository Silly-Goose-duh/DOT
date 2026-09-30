package com.dot.core.model

import java.time.Instant
import java.util.UUID

enum class TaskStatus { OPEN, DONE }

enum class Priority { LOW, NORMAL, HIGH }

data class Task(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val description: String? = null,
    val status: TaskStatus = TaskStatus.OPEN,
    val priority: Priority? = null,
    val dueAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
) {
    val isOpen: Boolean get() = status == TaskStatus.OPEN
}

enum class ReminderState { SCHEDULED, FIRED, CANCELLED, DONE }

data class Reminder(
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val remindAt: Instant,
    val taskId: UUID? = null,
    val state: ReminderState = ReminderState.SCHEDULED,
    val createdAt: Instant = Instant.now(),
)

data class CachedEvent(
    val id: UUID = UUID.randomUUID(),
    val provider: String,
    val providerEventId: String? = null,
    val title: String,
    val startAt: Instant,
    val endAt: Instant? = null,
    val location: String? = null,
    val lastSyncedAt: Instant = Instant.now(),
)

data class Note(
    val id: UUID = UUID.randomUUID(),
    val title: String? = null,
    val body: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class AgentRunStatus { RUNNING, SUCCESS, FAILURE, CANCELLED }

data class AgentDefinition(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val frequencyMinutes: Int? = null,
    val lastRunAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastErrorCode: String? = null,
)

data class AgentRun(
    val id: UUID = UUID.randomUUID(),
    val agentId: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val status: AgentRunStatus = AgentRunStatus.RUNNING,
    /** Compact, user-presentable result. Never stores raw external bodies. */
    val compactResult: String? = null,
    val errorCode: String? = null,
)

/**
 * Persistent local memory item. [value] holds user-approved stable facts/preferences.
 * Secrets are never stored here; tokens live in Keystore-backed storage.
 */
data class MemoryItem(
    val id: UUID = UUID.randomUUID(),
    val namespace: String,
    val key: String,
    val value: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val expiresAt: Instant? = null,
    val source: String = "local",
    val userVisible: Boolean = true,
)
