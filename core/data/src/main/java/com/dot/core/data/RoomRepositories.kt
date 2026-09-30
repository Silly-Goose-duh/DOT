package com.dot.core.data

import com.dot.core.database.DotDatabase
import com.dot.core.database.EventCacheEntity
import com.dot.core.database.MemoryItemEntity
import com.dot.core.database.NoteEntity
import com.dot.core.database.ReminderEntity
import com.dot.core.database.TaskEntity
import com.dot.core.model.CachedEvent
import com.dot.core.model.EventRepositoryPort
import com.dot.core.model.MemoryItem
import com.dot.core.model.MemoryStorePort
import com.dot.core.model.Note
import com.dot.core.model.NoteRepositoryPort
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderRepositoryPort
import com.dot.core.model.ReminderState
import com.dot.core.model.Task
import com.dot.core.model.TaskRepositoryPort
import com.dot.core.model.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * Room-backed implementations of the ports declared in core:model.
 *
 * These are the only classes that know both Room and the domain model. Tools and
 * the router stay database-agnostic, which is what keeps them unit-testable.
 */

class RoomTaskRepository(private val db: DotDatabase) : TaskRepositoryPort {

    override suspend fun insert(task: Task) = db.taskDao().upsert(TaskEntity.from(task))

    override suspend fun update(task: Task) = db.taskDao().update(TaskEntity.from(task))

    override suspend fun byId(id: UUID): Task? = db.taskDao().byId(id.toString())?.toDomain()

    override suspend fun all(): List<Task> = db.taskDao().all().map { it.toDomain() }

    override suspend fun search(q: String): List<Pair<String, UUID>> =
        db.taskDao().search(q).mapNotNull { e ->
            e.toDomain().let { d -> if (d.title.contains(q, ignoreCase = true)) d.title to d.id else null }
        }

    /**
     * Title match used by complete_task. Prefers the shortest matching title so
     * "milk" resolves to "Buy milk" and not "Buy milk and eggs" when both contain
     * the fragment — the user named the shorter, more specific one.
     */
    override suspend fun findByTitleContains(fragment: String): Task? =
        db.taskDao().search(fragment)
            .map { it.toDomain() }
            .filter { it.status == TaskStatus.OPEN }
            .minByOrNull { it.title.length }

    fun observe(): Flow<List<Task>> = db.taskDao().observeAll().map { list -> list.map { it.toDomain() } }
}

class RoomReminderRepository(private val db: DotDatabase) : ReminderRepositoryPort {

    override suspend fun insert(reminder: Reminder) = db.reminderDao().upsert(ReminderEntity.from(reminder))

    override suspend fun all(): List<Reminder> =
        db.reminderDao().all().map { it.toDomain() }

    override suspend fun cancel(id: UUID) {
        val existing = db.reminderDao().byId(id.toString())?.toDomain() ?: return
        db.reminderDao().upsert(
            ReminderEntity.from(existing.copy(state = ReminderState.CANCELLED)),
        )
    }

    fun observe(): Flow<List<Reminder>> =
        db.reminderDao().observeAll().map { list -> list.map { it.toDomain() } }
}

class RoomEventRepository(private val db: DotDatabase) : EventRepositoryPort {

    override suspend fun all(): List<CachedEvent> =
        db.eventCacheDao().all().map { it.toDomain() }

    suspend fun replaceForProvider(provider: String, events: List<CachedEvent>) {
        db.eventCacheDao().clearProvider(provider)
        db.eventCacheDao().upsertAll(events.map { EventCacheEntity.from(it) })
    }

    fun observe(): Flow<List<CachedEvent>> =
        db.eventCacheDao().observeAll().map { list -> list.map { it.toDomain() } }
}

class RoomNoteRepository(private val db: DotDatabase) : NoteRepositoryPort {

    override suspend fun insert(note: Note) = db.noteDao().upsert(NoteEntity.from(note))

    override suspend fun search(q: String): List<Pair<String, UUID>> =
        db.noteDao().search(q).map { e ->
            val d = e.toDomain()
            (d.title ?: d.body.take(60)) to d.id
        }

    suspend fun list(): List<Note> = db.noteDao().all().map { it.toDomain() }

    fun observe(): Flow<List<Note>> =
        db.noteDao().observeAll().map { list -> list.map { it.toDomain() } }
}

class RoomMemoryStore(private val db: DotDatabase) : MemoryStorePort {

    override fun observeAll(): Flow<List<MemoryItem>> =
        db.memoryDao().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun put(item: MemoryItem) = db.memoryDao().upsert(MemoryItemEntity.from(item))

    override suspend fun expired(now: Instant): List<MemoryItem> =
        db.memoryDao().expired(now.toEpochMilli()).map { it.toDomain() }

    /** Inclusive of an exactly-equal expiry: expiring "now" means already expired. */
    override suspend fun purgeExpired(now: Instant): Int = db.memoryDao().deleteExpired(now.toEpochMilli())

    override suspend fun clearNamespace(namespace: String) {
        db.memoryDao().clearNamespace(namespace)
    }

    override suspend fun clearAll() {
        db.memoryDao().clearAll()
    }
}
