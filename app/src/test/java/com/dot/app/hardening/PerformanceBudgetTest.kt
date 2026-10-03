package com.dot.app.hardening

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.agent.router.DateTimeParser
import com.dot.agent.router.DeterministicRouter
import com.dot.agent.tools.CreateTaskTool
import com.dot.agent.tools.GetTodayTool
import com.dot.agent.tools.ToolRegistry
import com.dot.app.command.CommandRuntime
import com.dot.app.reminder.ReminderSchedulerPort
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomNoteRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DotDatabase
import com.dot.core.database.MIGRATION_1_2
import com.dot.core.model.CachedEvent
import com.dot.core.model.Reminder
import com.dot.core.model.Task
import com.dot.core.model.TodayAggregator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import kotlin.system.measureNanoTime

/**
 * PRD 25 performance budgets, measured for real rather than asserted on faith.
 *
 * There is no macrobenchmark module (Milestone 7 is not permitted to add one),
 * so these are JVM/Robolectric numbers. Read them for what they are: they prove
 * no accidental O(n^2) or an unindexed sort crept into the local path, and they
 * will catch a regression of an order of magnitude. They are NOT on-device
 * numbers — cold start, real flash I/O and ART/JIT behaviour are all absent. The
 * measured values are printed into the test log under `PERF:` so the report can
 * quote them.
 *
 * Budgets are deliberately loose (10-50x the expected cost). A budget that only
 * passes on an idle machine is a flaky test, not a guard.
 */
@RunWith(RobolectricTestRunner::class)
class PerformanceBudgetTest {

    private val dbName = "perf-test.db"
    private val zone = ZoneId.of("UTC")
    private lateinit var db: DotDatabase
    private lateinit var tasks: RoomTaskRepository
    private lateinit var reminders: RoomReminderRepository
    private lateinit var notes: RoomNoteRepository
    private lateinit var events: RoomEventRepository
    private lateinit var registry: ToolRegistry

    private val router = DeterministicRouter(DateTimeParser(zone), zone)
    private val fixedNow = Instant.parse("2026-10-02T09:00:00Z")

    private val utterances = listOf(
        "What do I have today?",
        "Remind me to submit my project at 8 PM",
        "Add finish seminar to tomorrow",
        "Complete submit assignment",
        "Search for the quarterly report",
        "Open maps",
    )

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
        db = Room
            .databaseBuilder(ApplicationProvider.getApplicationContext(), DotDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        tasks = RoomTaskRepository(db)
        reminders = RoomReminderRepository(db)
        notes = RoomNoteRepository(db)
        events = RoomEventRepository(db)
        registry = ToolRegistry(
            listOf(
                CreateTaskTool(tasks, zone),
                GetTodayTool(tasks, reminders, events, zone),
            ),
        )
        registry.permissionGranted = { true }
        registry.authenticated = { true }
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private fun percentile(sortedMs: List<Double>, pct: Int): Double =
        sortedMs[(sortedMs.size * pct / 100).coerceAtMost(sortedMs.size - 1)]

    private fun report(label: String, samplesMs: List<Double>) {
        val sorted = samplesMs.sorted()
        val p50 = percentile(sorted, 50)
        val p95 = percentile(sorted, 95)
        val max = sorted.last()
        println(
            "PERF: $label p50=${"%.3f".format(p50)}ms " +
                "p95=${"%.3f".format(p95)}ms max=${"%.3f".format(max)}ms n=${sorted.size}",
        )
    }

    // ------------------------------------------------------- command routing

    @Test
    fun `command routing stays under the 150ms budget`() {
        repeat(30) { router.route(utterances[it % utterances.size], fixedNow) }

        val samples = mutableListOf<Double>()
        repeat(500) { i ->
            val u = utterances[i % utterances.size]
            samples += measureNanoTime { router.route(u, fixedNow) } / 1_000_000.0
        }
        report("command_routing", samples)

        assertThat(percentile(samples.sorted(), 95)).isLessThan(150.0)
    }

    @Test
    fun `routing latency does not grow with the number of prior calls`() {
        // Guards against an accidental per-call compile or a growing collection.
        repeat(50) { router.route(utterances[it % utterances.size], fixedNow) }

        val early = (0 until 100).map { i ->
            measureNanoTime { router.route(utterances[i % utterances.size], fixedNow) } / 1_000_000.0
        }
        val late = (0 until 100).map { i ->
            measureNanoTime { router.route(utterances[(i + 100) % utterances.size], fixedNow) } / 1_000_000.0
        }
        report("command_routing_early", early)
        report("command_routing_late", late)

        // 10x, not 1.05x: the intent is "no unbounded growth", not a tight
        // constant-factor comparison that would be flaky on a shared machine.
        val earlyMax = early.max()
        val lateMax = late.max()
        assertThat(lateMax).isLessThan(earlyMax * 10 + 5.0)
    }

    // --------------------------------------------------- local DB / Today query

    @Test
    fun `Today aggregation over a realistic local dataset is fast`() = runTest {
        val base = fixedNow
        repeat(400) { i ->
            tasks.insert(
                Task(
                    title = "task $i",
                    dueAt = base.plusSeconds(i * 600L),
                ),
            )
        }
        repeat(200) { i ->
            reminders.insert(
                Reminder(
                    title = "reminder $i",
                    remindAt = base.plusSeconds(i * 900L),
                ),
            )
        }
        repeat(100) { i ->
            notes.insert(com.dot.core.model.Note(title = "note $i", body = "body $i"))
        }
        events.replaceForProvider(
            "local",
            (0 until 50).map {
                CachedEvent(provider = "local", title = "event $it", startAt = base.plusSeconds(it * 1800L))
            },
        )

        // Warm the JIT and the SQLite page cache; we are measuring steady state.
        repeat(5) { aggregateNow(base) }

        val samples = (0 until 200).map {
            measureNanoTime { aggregateNow(base) } / 1_000_000.0
        }
        report("today_aggregation_750_rows", samples)

        // Generous but real: 750 rows, four tables, in-memory SQLite. 250 ms is
        // ~100x the expected cost, so this fires on a genuine regression (an
        // unindexed sort per row, an N+1) and not on a busy CI box.
        assertThat(percentile(samples.sorted(), 95)).isLessThan(250.0)
    }

    private suspend fun aggregateNow(now: Instant) = TodayAggregator.aggregate(
        zoneId = zone,
        now = now,
        allTasks = tasks.all(),
        allReminders = reminders.all(),
        allEvents = events.all(),
    )

    @Test
    fun `the dueAt index makes the ordered task query scale sanely`() = runTest {
        // v2 added index_tasks_dueAtEpochMs. This asserts the query is at least
        // not pathological at a size a real user could reach in a year.
        repeat(2000) { i ->
            tasks.insert(Task(title = "t$i", dueAt = fixedNow.plusSeconds(i * 60L)))
        }
        db.taskDao().all() // warm

        val samples = (0 until 50).map {
            measureNanoTime { db.taskDao().all() } / 1_000_000.0
        }
        report("task_query_2000_rows_ordered", samples)

        assertThat(percentile(samples.sorted(), 95)).isLessThan(200.0)
    }

    @Test
    fun `memory expiry purge is fast enough to run on every launch`() = runTest {
        val store = com.dot.core.data.RoomMemoryStore(db)
        repeat(1000) { i ->
            db.memoryDao().upsert(
                com.dot.core.database.MemoryItemEntity(
                    id = "m$i",
                    namespace = "session_context",
                    key = "k$i",
                    value = "v$i",
                    createdAtEpochMs = 0,
                    updatedAtEpochMs = 0,
                    expiresAtEpochMs = if (i % 2 == 0) 1000 else null,
                    source = "local",
                    userVisible = false,
                ),
            )
        }

        val cutoff = Instant.ofEpochMilli(2000)
        val samples = mutableListOf<Double>()
        repeat(20) {
            val n = measureNanoTime { store.purgeExpired(cutoff) } / 1_000_000.0
            samples += n
            // Re-seed half so the next pass has work to do.
            repeat(1000) { i ->
                db.memoryDao().upsert(
                    com.dot.core.database.MemoryItemEntity(
                        id = "m$i",
                        namespace = "session_context",
                        key = "k$i",
                        value = "v$i",
                        createdAtEpochMs = 0,
                        updatedAtEpochMs = 0,
                        expiresAtEpochMs = if (i % 2 == 0) 1000 else null,
                        source = "local",
                        userVisible = false,
                    ),
                )
            }
        }
        report("memory_purge_1000_rows", samples)

        assertThat(percentile(samples.sorted(), 95)).isLessThan(250.0)
    }

    // --------------------------------------------------------- tool dispatch

    @Test
    fun `tool dispatch through the registry is fast`() = runTest {
        tasks.insert(Task(title = "existing"))

        repeat(20) { registry.invoke("get_today", emptyMap()) }

        val samples = (0 until 200).map {
            measureNanoTime { registry.invoke("get_today", emptyMap()) } / 1_000_000.0
        }
        report("tool_dispatch_get_today", samples)

        assertThat(percentile(samples.sorted(), 95)).isLessThan(150.0)
    }

    @Test
    fun `a rejected tool call is cheap, so validation cannot become a DoS`() = runTest {
        repeat(20) { registry.invoke("no_such_tool", emptyMap()) }

        val samples = (0 until 500).map {
            measureNanoTime { registry.invoke("no_such_tool", emptyMap()) } / 1_000_000.0
        }
        report("tool_dispatch_rejected", samples)

        // An unknown tool must fail before doing any work, so this is the
        // cheapest path in the system. 50 ms is still ~1000x the real cost.
        assertThat(percentile(samples.sorted(), 95)).isLessThan(50.0)
    }

    @Test
    fun `end to end command handling is under the interactive budget`() = runTest {
        val runtime = CommandRuntime(
            router = router,
            toolRegistry = registry,
            policyEngine = com.dot.agent.policy.PolicyEngine(),
            formatter = com.dot.agent.router.DirectResponseFormatter(zone),
            appLauncher = AppLauncher(
                ApplicationProvider.getApplicationContext(),
                PackageVisibility(ApplicationProvider.getApplicationContext()),
            ),
            reminderScheduler = object : ReminderSchedulerPort {
                override fun schedule(reminder: Reminder) = Unit
            },
        )

        repeat(10) { runtime.handle("what do i have today") }

        val samples = mutableListOf<Double>()
        repeat(100) {
            val ns = measureNanoTime { kotlinx.coroutines.runBlocking { runtime.handle("what do i have today") } }
            samples += ns / 1_000_000.0
        }
        report("command_end_to_end", samples)

        // 500 ms is the ceiling for anything the user is waiting on. Generous,
        // but a 10x regression would still be caught.
        assertThat(percentile(samples.sorted(), 95)).isLessThan(500.0)
    }
}
