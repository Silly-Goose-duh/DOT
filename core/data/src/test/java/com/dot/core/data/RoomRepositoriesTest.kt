package com.dot.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.core.database.DotDatabase
import com.dot.core.model.MemoryItem
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import com.dot.core.model.Task
import com.dot.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RoomRepositoriesTest {

    private lateinit var db: DotDatabase
    private lateinit var tasks: RoomTaskRepository
    private lateinit var reminders: RoomReminderRepository
    private lateinit var events: RoomEventRepository
    private lateinit var notes: RoomNoteRepository
    private lateinit var memory: RoomMemoryStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DotDatabase::class.java,
        ).allowMainThreadQueries().build()
        tasks = RoomTaskRepository(db)
        reminders = RoomReminderRepository(db)
        events = RoomEventRepository(db)
        notes = RoomNoteRepository(db)
        memory = RoomMemoryStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `task round trips through room`() = runTest {
        val task = Task(title = "Write spec", description = "v0.2")
        tasks.insert(task)

        val loaded = tasks.byId(task.id)

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.title).isEqualTo("Write spec")
        assertThat(loaded.description).isEqualTo("v0.2")
        assertThat(loaded.status).isEqualTo(TaskStatus.OPEN)
        assertThat(loaded.dueAt).isNull()
    }

    @Test
    fun `byId returns null for unknown id`() = runTest {
        assertThat(tasks.byId(UUID.randomUUID())).isNull()
    }

    @Test
    fun `update persists status change`() = runTest {
        val task = Task(title = "Ship it")
        tasks.insert(task)

        val done = task.copy(status = TaskStatus.DONE, completedAt = Instant.now())
        tasks.update(done)

        val loaded = tasks.byId(task.id)!!
        assertThat(loaded.status).isEqualTo(TaskStatus.DONE)
        assertThat(loaded.completedAt).isNotNull()
    }

    @Test
    fun `all returns tasks ordered by due date with undated last`() = runTest {
        val now = Instant.now()
        tasks.insert(Task(title = "no due date"))
        tasks.insert(Task(title = "later", dueAt = now.plusSeconds(7200)))
        tasks.insert(Task(title = "sooner", dueAt = now.plusSeconds(60)))

        assertThat(tasks.all().map { it.title })
            .containsExactly("sooner", "later", "no due date").inOrder()
    }

    @Test
    fun `search matches on title fragment`() = runTest {
        tasks.insert(Task(title = "Buy milk"))
        tasks.insert(Task(title = "Call dentist"))

        val hits = tasks.search("milk")

        assertThat(hits).hasSize(1)
        assertThat(hits.first().first).isEqualTo("Buy milk")
    }

    @Test
    fun `findByTitleContains prefers the shortest matching title`() = runTest {
        tasks.insert(Task(title = "Buy milk and eggs"))
        tasks.insert(Task(title = "Buy milk"))

        val found = tasks.findByTitleContains("milk")

        assertThat(found?.title).isEqualTo("Buy milk")
    }

    @Test
    fun `findByTitleContains ignores completed tasks`() = runTest {
        tasks.insert(Task(title = "Buy milk", status = TaskStatus.DONE))

        assertThat(tasks.findByTitleContains("milk")).isNull()
    }

    @Test
    fun `reminder cancel marks it cancelled rather than deleting`() = runTest {
        val reminder = Reminder(title = "Stand up", remindAt = Instant.now().plusSeconds(600))
        reminders.insert(reminder)

        reminders.cancel(reminder.id)

        val loaded = reminders.all().single()
        assertThat(loaded.state).isEqualTo(ReminderState.CANCELLED)
    }

    @Test
    fun `reminder cancel for unknown id is a no-op`() = runTest {
        reminders.cancel(UUID.randomUUID())
        assertThat(reminders.all()).isEmpty()
    }

    @Test
    fun `event cache is scoped per provider`() = runTest {
        val start = Instant.now()
        events.replaceForProvider(
            "local",
            listOf(
                com.dot.core.model.CachedEvent(
                    provider = "local", title = "Standup", startAt = start,
                ),
            ),
        )
        events.replaceForProvider(
            "gcal",
            listOf(
                com.dot.core.model.CachedEvent(
                    provider = "gcal", title = "Review", startAt = start,
                ),
            ),
        )

        val titles = events.all().map { it.title }.sorted()

        assertThat(titles).containsExactly("Review", "Standup")
        assertThat(events.all().map { it.provider }.distinct()).containsExactly("gcal", "local")
    }

    @Test
    fun `replacing a provider does not touch the other provider`() = runTest {
        val start = Instant.now()
        events.replaceForProvider("local", listOf(
            com.dot.core.model.CachedEvent(provider = "local", title = "A", startAt = start),
        ))
        events.replaceForProvider("gcal", listOf(
            com.dot.core.model.CachedEvent(provider = "gcal", title = "B", startAt = start),
        ))

        events.replaceForProvider("local", emptyList())

        assertThat(events.all().map { it.title }).containsExactly("B")
    }

    @Test
    fun `note search matches body when title is absent`() = runTest {
        notes.insert(Note(title = null, body = "wifi password is on the fridge"))
        notes.insert(Note(title = "Groceries", body = "milk, eggs"))

        val hits = notes.search("fridge")

        assertThat(hits).hasSize(1)
        assertThat(hits.first().first).contains("wifi password")
    }

    @Test
    fun `memory purge removes items expiring exactly now`() = runTest {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        memory.put(
            com.dot.core.model.MemoryItem(
                namespace = "session_context", key = "k", value = "v", expiresAt = now,
            ),
        )
        memory.put(
            com.dot.core.model.MemoryItem(
                namespace = "user_facts", key = "name", value = "T",
            ),
        )

        val purged = memory.purgeExpired(now)

        assertThat(purged).isEqualTo(1)
        assertThat(memory.observeAllFirst()).hasSize(1)
    }

    @Test
    fun `memory purge leaves an unexpired cache item alone`() = runTest {
        val now = Instant.parse("2026-01-01T10:00:00Z")
        memory.put(
            com.dot.core.model.MemoryItem(
                namespace = "session_context", key = "k", value = "v",
                expiresAt = now.plusSeconds(60),
            ),
        )

        assertThat(memory.purgeExpired(now)).isEqualTo(0)
        assertThat(memory.observeAllFirst()).hasSize(1)
    }

    @Test
    fun `clearAll wipes every namespace`() = runTest {
        memory.put(com.dot.core.model.MemoryItem(namespace = "user_facts", key = "a", value = "1"))
        memory.put(com.dot.core.model.MemoryItem(namespace = "preferences", key = "b", value = "2"))

        memory.clearAll()

        assertThat(memory.observeAllFirst()).isEmpty()
    }

    @Test
    fun `clearNamespace leaves other namespaces intact`() = runTest {
        memory.put(com.dot.core.model.MemoryItem(namespace = "user_facts", key = "a", value = "1"))
        memory.put(com.dot.core.model.MemoryItem(namespace = "preferences", key = "b", value = "2"))

        memory.clearNamespace("user_facts")

        val left = memory.observeAllFirst()
        assertThat(left).hasSize(1)
        assertThat(left.first().namespace).isEqualTo("preferences")
    }

    /** First emission from the store's Flow, so assertions see persisted rows. */
    private suspend fun RoomMemoryStore.observeAllFirst(): List<MemoryItem> =
        observeAll().first()
}
