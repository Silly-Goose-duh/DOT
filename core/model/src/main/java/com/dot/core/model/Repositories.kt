package com.dot.core.model

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * Repository contracts. These live in core:model (not in agent:tools) so that a
 * data-layer module can implement them without depending on the agent layer —
 * agent:tools already depends on core:model, so ports placed there would create
 * a cycle for :core:data.
 *
 * Tools depend only on these ports; the Room implementations in :core:data bind
 * them to DAOs. That inversion is what keeps the tool layer testable without a
 * database.
 */

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
    suspend fun all(): List<CachedEvent>
}

interface NoteRepositoryPort {
    suspend fun insert(note: Note)
    suspend fun search(q: String): List<Pair<String, UUID>>
}

/**
 * Storage contract for the memory module. :core:data implements it over Room;
 * agent:memory's MemoryRepository depends only on this.
 */
interface MemoryStorePort {
    fun observeAll(): Flow<List<MemoryItem>>
    suspend fun put(item: MemoryItem)
    suspend fun expired(now: Instant): List<MemoryItem>
    suspend fun purgeExpired(now: Instant): Int
    suspend fun clearNamespace(namespace: String)
    suspend fun clearAll()
}
