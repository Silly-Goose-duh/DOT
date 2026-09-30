package com.dot.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.dot.core.model.CachedEvent
import com.dot.core.model.MemoryItem
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY dueAtEpochMs IS NULL, dueAtEpochMs ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status = 'OPEN'")
    suspend fun openTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY remindAtEpochMs ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface EventCacheDao {
    @Query("SELECT * FROM event_cache ORDER BY startAtEpochMs ASC")
    fun observeAll(): Flow<List<EventCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventCacheEntity>)

    @Query("DELETE FROM event_cache WHERE provider = :provider")
    suspend fun clearProvider(provider: String): Int

    @Query("DELETE FROM event_cache")
    suspend fun clearAll(): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM notes")
    suspend fun clearAll(): Int
}

@Dao
interface AgentDefinitionDao {
    @Query("SELECT * FROM agent_definitions")
    fun observeAll(): Flow<List<AgentDefinitionEntity>>

    @Query("SELECT * FROM agent_definitions WHERE id = :id")
    suspend fun byId(id: String): AgentDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentDefinitionEntity)
}

@Dao
interface AgentRunDao {
    @Query("SELECT * FROM agent_runs WHERE agentId = :agentId ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun latestFor(agentId: String): AgentRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: AgentRunEntity)

    /** Keeps history bounded; execution history is not permanent user memory. */
    @Query("DELETE FROM agent_runs WHERE agentId = :agentId AND id NOT IN (SELECT id FROM agent_runs WHERE agentId = :agentId ORDER BY startedAtEpochMs DESC LIMIT 20)")
    suspend fun trimHistory(agentId: String): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_items ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE namespace = :namespace")
    fun observeNamespace(namespace: String): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :nowMs")
    suspend fun expired(nowMs: Long): List<MemoryItemEntity>

    @Query("DELETE FROM memory_items WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs < :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItemEntity)

    @Query("DELETE FROM memory_items WHERE namespace = :namespace")
    suspend fun clearNamespace(namespace: String): Int

    @Query("DELETE FROM memory_items")
    suspend fun clearAll(): Int
}

@Database(
    entities = [
        TaskEntity::class,
        ReminderEntity::class,
        EventCacheEntity::class,
        NoteEntity::class,
        AgentDefinitionEntity::class,
        AgentRunEntity::class,
        MemoryItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DotDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun eventCacheDao(): EventCacheDao
    abstract fun noteDao(): NoteDao
    abstract fun agentDefinitionDao(): AgentDefinitionDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun memoryDao(): MemoryDao
}
