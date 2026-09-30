package com.dot.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DotDatabaseTest {

    private lateinit var db: DotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DotDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `task round trips`() = runTest {
        val e = TaskEntity(
            id = "1", title = "Submit assignment", description = null,
            status = "OPEN", priority = null, dueAtEpochMs = null,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000, completedAtEpochMs = null,
        )
        db.taskDao().upsert(e)
        val got = db.taskDao().byId("1")
        assertThat(got).isNotNull()
        assertThat(got!!.title).isEqualTo("Submit assignment")
        assertThat(got.toDomain().isOpen).isTrue()
    }

    @Test
    fun `completed task round trips with completedAt`() = runTest {
        val e = TaskEntity(
            id = "2", title = "Finish slides", description = null,
            status = "DONE", priority = "HIGH", dueAtEpochMs = 5000,
            createdAtEpochMs = 1000, updatedAtEpochMs = 2000, completedAtEpochMs = 2000,
        )
        db.taskDao().upsert(e)
        val d = db.taskDao().byId("2")!!.toDomain()
        assertThat(d.isOpen).isFalse()
        assertThat(d.priority?.name).isEqualTo("HIGH")
        assertThat(d.completedAt).isEqualTo(Instant.ofEpochMilli(2000))
    }

    @Test
    fun `search matches title substring`() = runTest {
        db.taskDao().upsert(
            TaskEntity("3", "Submit project report", null, "OPEN", null, null, 1, 1, null),
        )
        val hits = db.taskDao().search("project")
        assertThat(hits).hasSize(1)
        assertThat(hits.first().title).contains("project")
    }

    @Test
    fun `reminder persists and lists`() = runTest {
        db.reminderDao().upsert(
            ReminderEntity("r1", "Send report", 2000, null, "SCHEDULED", 1000),
        )
        val got = db.reminderDao().byId("r1")
        assertThat(got!!.title).isEqualTo("Send report")
        assertThat(got.toDomain().state.name).isEqualTo("SCHEDULED")
    }

    @Test
    fun `note search covers title and body`() = runTest {
        db.noteDao().upsert(NoteEntity("n1", "grocery", "milk and eggs", 1, 1))
        assertThat(db.noteDao().search("grocery")).hasSize(1)
        assertThat(db.noteDao().search("eggs")).hasSize(1)
        assertThat(db.noteDao().search("nothing")).isEmpty()
    }

    @Test
    fun `memory expired items are queryable and deletable`() = runTest {
        val now = 10_000L
        db.memoryDao().upsert(
            MemoryItemEntity("m1", "session_context", "k", "v", 1, 1, now - 5, "local", false),
        )
        db.memoryDao().upsert(
            MemoryItemEntity("m2", "user_facts", "name", "Tom", 1, 1, null, "local", true),
        )
        assertThat(db.memoryDao().expired(now)).hasSize(1)
        db.memoryDao().deleteExpired(now)
        val remaining = db.memoryDao().observeAll().first()
        assertThat(remaining.map { it.id }).containsExactly("m2")
    }

    @Test
    fun `clear namespace only removes that namespace`() = runTest {
        db.memoryDao().upsert(MemoryItemEntity("a", "user_facts", "k", "v", 1, 1, null, "local", true))
        db.memoryDao().upsert(MemoryItemEntity("b", "preferences", "k", "v", 1, 1, null, "local", true))
        db.memoryDao().clearNamespace("preferences")
        assertThat(db.memoryDao().observeAll().first().map { it.id }).containsExactly("a")
    }

    @Test
    fun `clear all wipes every memory row`() = runTest {
        db.memoryDao().upsert(MemoryItemEntity("a", "user_facts", "k", "v", 1, 1, null, "local", true))
        db.memoryDao().clearAll()
        assertThat(db.memoryDao().observeAll().first()).isEmpty()
    }

    @Test
    fun `malformed ids do not crash the mapper`() {
        // A legacy/hand-edited row must degrade, not take down the whole query.
        val e = TaskEntity(
            id = "not-a-uuid", title = "Legacy row", description = null,
            status = "OPEN", priority = null, dueAtEpochMs = null,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000, completedAtEpochMs = null,
        )
        val d = e.toDomain()
        assertThat(d.title).isEqualTo("Legacy row")
        assertThat(d.id).isNotNull()
    }

    @Test
    fun `unknown persisted enum falls back instead of throwing`() {
        val e = TaskEntity(
            id = "00000000-0000-0000-0000-000000000009", title = "Weird status", description = null,
            status = "NOT_A_STATUS", priority = "NOT_A_PRIORITY", dueAtEpochMs = null,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000, completedAtEpochMs = null,
        )
        val d = e.toDomain()
        assertThat(d.status.name).isEqualTo("OPEN")
        assertThat(d.priority?.name).isEqualTo("NORMAL")
    }

    @Test
    fun `event cache replace by provider keeps upsert semantics`() = runTest {
        db.eventCacheDao().upsertAll(
            listOf(
                EventCacheEntity("e1", "local", null, "College", 1000, null, null, 1),
                EventCacheEntity("e2", "local", null, "Project review", 2000, null, null, 1),
            ),
        )
        assertThat(db.eventCacheDao().observeAll().first()).hasSize(2)
        db.eventCacheDao().clearProvider("local")
        assertThat(db.eventCacheDao().observeAll().first()).isEmpty()
    }
}
