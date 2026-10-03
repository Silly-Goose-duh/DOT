package com.dot.app.hardening

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.agent.llm.AiFallback
import com.dot.agent.llm.ModelErrorCodes
import com.dot.agent.llm.ModelPlanParser
import com.dot.agent.llm.ModelReply
import com.dot.agent.llm.ModelRequest
import com.dot.agent.llm.ModelProvider
import com.dot.agent.memory.MemoryPolicy
import com.dot.agent.memory.MemoryRepository
import com.dot.agent.policy.PolicyEngine
import com.dot.agent.router.DateTimeParser
import com.dot.agent.router.DeterministicRouter
import com.dot.agent.router.DirectResponseFormatter
import com.dot.agent.tools.CompleteTaskTool
import com.dot.agent.tools.CreateNoteTool
import com.dot.agent.tools.CreateReminderTool
import com.dot.agent.tools.CreateTaskTool
import com.dot.agent.tools.GetTodayTool
import com.dot.agent.tools.OpenAppTool
import com.dot.agent.tools.SearchLocalTool
import com.dot.agent.tools.ToolRegistry
import com.dot.app.command.CommandRuntime
import com.dot.app.reminder.ReminderSchedulerPort
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomMemoryStore
import com.dot.core.data.RoomNoteRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DotDatabase
import com.dot.core.database.MIGRATION_1_2
import com.dot.core.model.CachedEvent
import com.dot.core.model.DirectResponse
import com.dot.core.model.Note
import com.dot.core.model.Reminder
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
import java.time.ZoneId

/**
 * PRD 24: must work offline — task CRUD, notes, cached event display, local
 * reminders, local search, settings, local memory, deterministic commands. May
 * require network — remote LLM, inbox sync. When offline, say so directly rather
 * than failing silently.
 *
 * The interesting half of that requirement is the negative one. A silent failure
 * and a fake success are both worse than an honest refusal, and both are easy to
 * ship. So these tests assert three distinct things:
 *
 *  1. every offline-required capability works against a real SQLite file, with
 *     the AI fallback configured as absent (the honest offline state);
 *  2. a network-dependent path degrades to a *typed* failure code, never an
 *     empty-but-successful result;
 *  3. a provider that throws is treated as a failure, not a success.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineBehaviorTest {

    private val dbName = "offline-test.db"
    private val zone = ZoneId.of("UTC")
    private lateinit var db: DotDatabase
    private lateinit var tasks: RoomTaskRepository
    private lateinit var reminders: RoomReminderRepository
    private lateinit var notes: RoomNoteRepository
    private lateinit var events: RoomEventRepository
    private lateinit var memory: MemoryRepository
    private lateinit var registry: ToolRegistry
    private lateinit var runtime: CommandRuntime
    private lateinit var scheduler: RecordingScheduler

    /** Records alarm arms without touching AlarmManager. */
    private class RecordingScheduler : ReminderSchedulerPort {
        val armed = mutableListOf<Reminder>()
        override fun schedule(reminder: Reminder) {
            armed += reminder
        }
    }

    private fun open() = Room
        .databaseBuilder(ApplicationProvider.getApplicationContext(), DotDatabase::class.java, dbName)
        .addMigrations(MIGRATION_1_2)
        .allowMainThreadQueries()
        .build()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
        db = open()

        tasks = RoomTaskRepository(db)
        reminders = RoomReminderRepository(db)
        notes = RoomNoteRepository(db)
        events = RoomEventRepository(db)
        memory = MemoryRepository(RoomMemoryStore(db), MemoryPolicy())

        registry = ToolRegistry(
            listOf(
                CreateTaskTool(tasks, zone),
                CompleteTaskTool(tasks),
                CreateReminderTool(reminders),
                CreateNoteTool(notes),
                SearchLocalTool(tasks, notes),
                GetTodayTool(tasks, reminders, events, zone),
                OpenAppTool(),
            ),
        )
        registry.permissionGranted = { true }
        registry.authenticated = { true }

        scheduler = RecordingScheduler()
        runtime = CommandRuntime(
            router = DeterministicRouter(DateTimeParser(zone), zone),
            toolRegistry = registry,
            policyEngine = PolicyEngine(),
            formatter = DirectResponseFormatter(zone),
            appLauncher = AppLauncher(
                ApplicationProvider.getApplicationContext(),
                PackageVisibility(ApplicationProvider.getApplicationContext()),
            ),
            reminderScheduler = scheduler,
            // Deliberately null: no provider configured is the honest offline
            // state, not a stub. Mirrors AppContainer today.
            aiFallback = null,
        )
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    // ------------------------------------------------- offline-required features

    @Test
    fun `task CRUD works offline`() = runTest {
        tasks.insert(Task(title = "buy milk"))
        assertThat(tasks.all().map { it.title }).containsExactly("buy milk")

        val id = tasks.all().single().id
        tasks.update(tasks.byId(id)!!.copy(status = TaskStatus.DONE))
        assertThat(tasks.byId(id)!!.isOpen).isFalse()

        val title = tasks.search("milk").single().first
        assertThat(title).isEqualTo("buy milk")
    }

    @Test
    fun `notes work offline and are searchable by body`() = runTest {
        notes.insert(Note(title = "groceries", body = "milk and eggs"))
        assertThat(notes.search("eggs")).hasSize(1)
        assertThat(notes.list()).hasSize(1)
    }

    @Test
    fun `cached events display offline with no sync`() = runTest {
        events.replaceForProvider(
            "local",
            listOf(
                CachedEvent(provider = "local", title = "College", startAt = Instant.parse("2026-10-02T09:00:00Z")),
                CachedEvent(provider = "local", title = "Project review", startAt = Instant.parse("2026-10-02T14:00:00Z")),
            ),
        )

        // Reads come from the cache. No provider is consulted and none exists.
        assertThat(events.all()).hasSize(2)
        val summary = com.dot.core.model.TodayAggregator.aggregate(
            zoneId = zone,
            now = Instant.parse("2026-10-02T12:00:00Z"),
            allTasks = emptyList(),
            allReminders = emptyList(),
            allEvents = events.all(),
        )
        assertThat(summary.eventCount).isEqualTo(2)
    }

    @Test
    fun `local reminders are created and armed offline`() = runTest {
        val turn = runtime.handle("remind me to stretch at 8")

        assertThat(turn).isInstanceOf(CommandRuntime.CommandTurn.Answer::class.java)
        assertThat(scheduler.armed).hasSize(1)
        assertThat(reminders.all()).hasSize(1)
    }

    @Test
    fun `local search works offline`() = runTest {
        tasks.insert(Task(title = "read the quarterly report"))

        val response = (runtime.handle("search report") as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("1 match.")
    }

    @Test
    fun `local memory works offline and survives a restart`() = runTest {
        memory.remember("user_facts", "name", "Sam")

        db.close()
        db = open()
        val reopened = MemoryRepository(RoomMemoryStore(db), MemoryPolicy())

        val item = reopened.observeAll().first().single()
        assertThat(item.value).isEqualTo("Sam")
    }

    @Test
    fun `deterministic commands need no network`() = runTest {
        tasks.insert(Task(title = "one"))
        tasks.insert(Task(title = "two"))

        val response = (runtime.handle("what do i have today") as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("2 tasks.")
    }

    @Test
    fun `the whole offline surface works with no provider configured at all`() = runTest {
        // One pass over every PRD 24 offline capability, with aiFallback = null.
        assertThat(tasks.all()).isEmpty()

        runtime.handle("add write the report")
        runtime.handle("take a note about the report")
        runtime.handle("remind me to send the report at 6")

        assertThat(tasks.all()).hasSize(1)
        assertThat(notes.list()).hasSize(1)
        assertThat(reminders.all()).hasSize(1)
        assertThat(scheduler.armed).hasSize(1)
    }

    // ------------------------------------------------ network-dependent paths

    @Test
    fun `an offline AI request degrades to an honest refusal, not a fake answer`() = runTest {
        val response = (runtime.handle("draft a haiku about deadlines") as CommandRuntime.CommandTurn.Answer).response

        // Not a clarification dressed up as an answer, and definitely not invented
        // content: the app says it cannot do this offline.
        assertThat((response as DirectResponse.Clarification).question)
            .isEqualTo("I can only handle simple commands offline.")
    }

    @Test
    fun `a missing provider is a typed failure code, not an empty success`() = runTest {
        val fallback = AiFallback(provider = null, toolRegistry = registry, policyEngine = PolicyEngine())

        val outcome = fallback.handle("summarise my inbox")

        assertThat(outcome).isInstanceOf(AiFallback.Outcome.Failed::class.java)
        assertThat((outcome as AiFallback.Outcome.Failed).code)
            .isEqualTo(ModelErrorCodes.NOT_CONFIGURED)
    }

    @Test
    fun `a network error becomes a typed failure code`() = runTest {
        // Exactly what an offline device produces from a real provider call.
        val offline = object : ModelProvider {
            override val id = "offline"
            override val displayName = "Offline"
            override suspend fun complete(request: ModelRequest) =
                ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
        }
        val fallback = AiFallback(offline, registry, PolicyEngine())

        val outcome = fallback.handle("summarise my inbox")

        assertThat(outcome).isInstanceOf(AiFallback.Outcome.Failed::class.java)
        assertThat((outcome as AiFallback.Outcome.Failed).code)
            .isEqualTo(ModelErrorCodes.UNAVAILABLE)
    }

    @Test
    fun `a throwing provider is a failure, never a success`() = runTest {
        val exploding = object : ModelProvider {
            override val id = "boom"
            override val displayName = "Boom"
            override suspend fun complete(request: ModelRequest): ModelReply =
                throw java.net.UnknownHostException("api.example.invalid")
        }
        val fallback = AiFallback(exploding, registry, PolicyEngine())

        val outcome = fallback.handle("summarise my inbox")

        assertThat(outcome).isInstanceOf(AiFallback.Outcome.Failed::class.java)
        assertThat((outcome as AiFallback.Outcome.Failed).code)
            .isEqualTo(ModelErrorCodes.UNAVAILABLE)
    }

    @Test
    fun `a provider returning an expired credential reports auth required`() = runTest {
        // OAuth revoke / expiry, as the network layer would report it.
        val revoked = object : ModelProvider {
            override val id = "revoked"
            override val displayName = "Revoked"
            override suspend fun complete(request: ModelRequest) =
                ModelReply.Failure(ModelErrorCodes.AUTH_REQUIRED)
        }
        val fallback = AiFallback(revoked, registry, PolicyEngine())

        val outcome = fallback.handle("summarise my inbox")

        assertThat((outcome as AiFallback.Outcome.Failed).code)
            .isEqualTo(ModelErrorCodes.AUTH_REQUIRED)
        // Explicitly not a retryable "try again" and not an empty success.
        assertThat(outcome.code).isNotEqualTo(ModelErrorCodes.UNAVAILABLE)
    }

    @Test
    fun `no offline path ever returns an empty result that reads as success`() = runTest {
        // Search with no hits must say so rather than reporting a count of zero as
        // though something was found.
        val response = (runtime.handle("search nonexistent") as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("Nothing found.")
    }

    @Test
    fun `a timeout is reported as a timeout`() = runTest {
        val slow = object : ModelProvider {
            override val id = "slow"
            override val displayName = "Slow"
            override suspend fun complete(request: ModelRequest) =
                ModelReply.Failure(ModelErrorCodes.TIMEOUT, retryable = true)
        }
        val fallback = AiFallback(slow, registry, PolicyEngine())

        val outcome = fallback.handle("summarise my inbox") as AiFallback.Outcome.Failed

        assertThat(outcome.code).isEqualTo(ModelErrorCodes.TIMEOUT)
    }

    @Test
    fun `the plan parser is enforced before any tool runs on an offline path`() {
        // A reply that arrived from somewhere (cached, replayed) still cannot name
        // an unregistered tool. This is the boundary that does not depend on the
        // network being up.
        val parser = ModelPlanParser(registry.names())

        val injected = parser.parse(
            """{"tool":"run_shell","args":{"cmd":"rm -rf /"}}""",
        )

        assertThat(injected).isInstanceOf(com.dot.agent.llm.ParseOutcome.Invalid::class.java)
    }
}
