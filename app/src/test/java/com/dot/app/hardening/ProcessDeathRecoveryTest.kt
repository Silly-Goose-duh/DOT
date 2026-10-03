package com.dot.app.hardening

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.app.diagnostics.PendingActionRestore
import com.dot.app.diagnostics.ProcessDeathRecovery
import com.dot.app.diagnostics.ReconciliationCodes
import com.dot.app.diagnostics.ReminderRecovery
import com.dot.app.diagnostics.ReminderRestoreAction
import com.dot.app.diagnostics.planRestore
import com.dot.core.data.RoomReminderRepository
import com.dot.core.database.AgentRunEntity
import com.dot.core.database.DotDatabase
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import com.dot.core.model.Task
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * PRD 23: "Persist enough state to safely resume scheduled work after process
 * death, but do not resume consequential user actions silently."
 *
 * Process death cannot be simulated on the JVM, so it is modelled the only way
 * that is actually meaningful: the *database outlives the object graph*. Each
 * test here closes the database, throws away every in-memory reference, reopens,
 * and asserts what the surviving state implies. Anything that only lived in a
 * Kotlin field is, by construction, gone — which is exactly the property PRD 23
 * is about.
 *
 * Robolectric is used only for the real SQLite file and real Room DAOs. The
 * reconciliation and restore decisions themselves are pure.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessDeathRecoveryTest {

    private val dbName = "process-death-test.db"
    private lateinit var db: DotDatabase
    private val now: Instant = Instant.parse("2026-10-02T10:00:00Z")

    private fun open(): DotDatabase = Room
        .databaseBuilder(ApplicationProvider.getApplicationContext(), DotDatabase::class.java, dbName)
        .addMigrations(com.dot.core.database.MIGRATION_1_2)
        .allowMainThreadQueries()
        .build()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(dbName)
    }

    /** Closes and reopens: everything in memory is lost, the file is not. */
    private suspend fun killAndRestart(): DotDatabase {
        db.close()
        db = open()
        return db
    }

    // ---------------------------------------------------------------- reminders

    @Test
    fun `a scheduled reminder survives process death and is re-armed identically`() = runTest {
        val repo = RoomReminderRepository(db)
        val remindAt = now.plus(Duration.ofHours(2))
        val original = Reminder(
            id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            title = "Send the report",
            remindAt = remindAt,
            state = ReminderState.SCHEDULED,
            createdAt = now.minus(Duration.ofHours(1)),
        )
        repo.insert(original)

        killAndRestart()

        // Rebuilt purely from the row. If any field of the alarm identity (id,
        // title, trigger instant) failed to round-trip, the re-armed alarm would
        // be a different alarm and the old PendingIntent would still be pending.
        val restored = RoomReminderRepository(db).all().single()
        assertThat(restored).isEqualTo(original)

        val actions = ReminderRecovery.actions(listOf(restored), now)
        assertThat(actions).hasSize(1)
        val rearm = actions.single() as ReminderRestoreAction.Rearm
        assertThat(rearm.reminder).isEqualTo(original)
        assertThat(rearm.reminder.remindAt).isEqualTo(remindAt)
    }

    @Test
    fun `process death does not resurrect a cancelled reminder`() = runTest {
        val repo = RoomReminderRepository(db)
        val reminder = Reminder(
            id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            title = "Do not do this",
            remindAt = now.plus(Duration.ofHours(3)),
        )
        repo.insert(reminder)
        repo.cancel(reminder.id)

        killAndRestart()

        val restored = RoomReminderRepository(db).all().single()
        assertThat(restored.state).isEqualTo(ReminderState.CANCELLED)

        val action = ReminderRecovery.actions(listOf(restored), now).single()
        assertThat(action).isInstanceOf(ReminderRestoreAction.Skip::class.java)
        assertThat((action as ReminderRestoreAction.Skip).reason).isEqualTo("state_cancelled")
    }

    @Test
    fun `a reminder whose moment passed while the process was dead is reported missed`() = runTest {
        val stale = Reminder(
            id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            title = "Missed while dead",
            remindAt = now.minus(Duration.ofHours(4)),
        )
        db.reminderDao().upsert(com.dot.core.database.ReminderEntity.from(stale))

        killAndRestart()

        val restored = db.reminderDao().all().single().toDomain()
        val action = ReminderRecovery.actions(listOf(restored), now).single()

        // Not re-armed (it would fire stale) and not silently dropped.
        assertThat(action).isInstanceOf(ReminderRestoreAction.Missed::class.java)

        // The miss must name the reminder the user actually scheduled, so the UI
        // can tell them which one was missed. Compared field-by-field on purpose:
        // `createdAt` defaults to Instant.now() at nanosecond precision, and the
        // round trip through the INTEGER epoch-millis column legitimately
        // truncates it, so whole-object equality would fail for a reason that has
        // nothing to do with the property under test.
        val missed = (action as ReminderRestoreAction.Missed).reminder
        assertThat(missed.id).isEqualTo(stale.id)
        assertThat(missed.title).isEqualTo(stale.title)
        assertThat(missed.remindAt).isEqualTo(stale.remindAt)
        assertThat(missed.state).isEqualTo(ReminderState.SCHEDULED)

        // Asserted through the bulk API as well, because the caller restores via
        // planRestore(), not per-row. This is the half that would catch a
        // regression where the miss is computed per-row but then dropped on the
        // way into the plan — the exact "silently dropped" failure PRD 23 forbids.
        val plan = ReminderRecovery.plan(listOf(restored), now)
        assertThat(plan.missed.map { it.id }).containsExactly(stale.id)
        assertThat(plan.rearm).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    // ------------------------------------------------------- pending confirmation

    @Test
    fun `a pending confirmation is not auto-executed on restore`() {
        val restored = ProcessDeathRecovery.restorePendingAction(
            toolName = "complete_task",
            args = mapOf("taskRef" to "delete production database"),
            intentLabel = "complete task",
        )

        // The only reachable outcome is "ask again". There is no branch that runs
        // it, so silent resumption is not merely avoided but unrepresentable.
        assertThat(restored).isInstanceOf(PendingActionRestore.ReaskRequired::class.java)
        val reask = restored as PendingActionRestore.ReaskRequired
        assertThat(reask.toolName).isEqualTo("complete_task")
        assertThat(reask.intentLabel).isEqualTo("complete task")

        // Structural check: the returned type must expose no way to execute.
        //
        // The original version of this assertion compared the *entire* reflected
        // method list against exactly [toString, hashCode, equals]. That is
        // meaningless and could never pass: `getMethods()` on a Kotlin data class
        // also returns the generated getters, componentN/copy (which it then
        // filtered by hand) and the members Object contributes. The list it
        // asserted on said nothing about whether executing was possible.
        //
        // What actually matters is narrow: no member of the type may be an
        // execution affordance. So assert that positively -- every public member
        // is either a data-class artefact or a read of persisted state, and none
        // of them is named like something that runs the action. If someone later
        // adds `fun execute()` to satisfy a caller, this fails, which was the
        // entire intent of the original check.
        val executionish = Regex("^(execute|run|invoke|apply|perform|confirm|commit|dispatch|fire).*", RegexOption.IGNORE_CASE)
        val members = PendingActionRestore.ReaskRequired::class.java.methods
            .map { it.name }
            .filterNot { it.startsWith("component") || it.startsWith("copy") || it.startsWith("access$") }
            .distinct()
        assertThat(members.filter { executionish.matches(it) }).isEmpty()

        // The same property on the sealed type: a pending action is surfaced, never
        // carried into something runnable. The interface itself must expose no
        // function at all.
        assertThat(PendingActionRestore::class.java.methods.map { it.name })
            .containsNoneOf("execute", "run", "invoke", "perform")
    }

    @Test
    fun `restore with nothing pending does nothing`() {
        val restored = ProcessDeathRecovery.restorePendingAction(
            toolName = null,
            args = null,
            intentLabel = null,
        )
        assertThat(restored).isInstanceOf(PendingActionRestore.None::class.java)
    }

    @Test
    fun `a half-written pending action is treated as nothing pending`() {
        // toolName present but no intentLabel: the action cannot be re-asked
        // coherently, so it must not be half-restored either.
        val restored = ProcessDeathRecovery.restorePendingAction(
            toolName = "complete_task",
            args = mapOf("taskRef" to "x"),
            intentLabel = null,
        )
        assertThat(restored).isInstanceOf(PendingActionRestore.None::class.java)
    }

    // ------------------------------------------------------------- agent runs

    @Test
    fun `a RUNNING agent run left by a kill is reconciled to a terminal state`() = runTest {
        val staleStart = now.minus(Duration.ofHours(2))
        db.agentRunDao().upsert(
            AgentRunEntity(
                id = "44444444-4444-4444-4444-444444444444",
                agentId = "inbox",
                startedAtEpochMs = staleStart.toEpochMilli(),
                finishedAtEpochMs = null,
                status = AgentRunStatus.RUNNING.name,
                compactResult = null,
                errorCode = null,
            ),
        )

        killAndRestart()

        // The orphaned row is still RUNNING on disk — the process died mid-run and
        // nothing wrote a terminal state.
        val orphan = db.agentRunDao().running().single()
        assertThat(orphan.status).isEqualTo("RUNNING")

        val outcome = ProcessDeathRecovery.reconcileAgentRuns(
            runs = db.agentRunDao().recent().map { it.toDomain() },
            now = now,
        )
        assertThat(outcome).hasSize(1)
        val fix = outcome.single()
        assertThat(fix.runId).isEqualTo("44444444-4444-4444-4444-444444444444")
        assertThat(fix.terminalStatus).isEqualTo(AgentRunStatus.CANCELLED)
        assertThat(fix.finishedAt).isEqualTo(now)
        assertThat(fix.errorCode).isEqualTo(ReconciliationCodes.PROCESS_DIED)

        // Persist it, as the real restore path must.
        db.agentRunDao().upsert(orphan.copy(status = fix.terminalStatus.name, finishedAtEpochMs = fix.finishedAt.toEpochMilli(), errorCode = fix.errorCode))
        assertThat(db.agentRunDao().running()).isEmpty()
    }

    @Test
    fun `a genuinely fresh RUNNING run is left alone`() {
        val justStarted = AgentRun(
            id = UUID.fromString("55555555-5555-5555-5555-555555555555"),
            agentId = "inbox",
            startedAt = now.minus(Duration.ofSeconds(5)),
            status = AgentRunStatus.RUNNING,
        )
        // A concurrent reconcile pass must not cancel work that is actually alive.
        val outcome = ProcessDeathRecovery.reconcileAgentRuns(listOf(justStarted), now)
        assertThat(outcome).isEmpty()
    }

    @Test
    fun `a terminal run is never rewritten`() {
        val runs = listOf(
            AgentRun(id = UUID.randomUUID(), agentId = "a", startedAt = now.minus(Duration.ofDays(1)), status = AgentRunStatus.SUCCESS),
            AgentRun(id = UUID.randomUUID(), agentId = "a", startedAt = now.minus(Duration.ofDays(1)), status = AgentRunStatus.FAILURE, errorCode = "boom"),
            AgentRun(id = UUID.randomUUID(), agentId = "a", startedAt = now.minus(Duration.ofDays(1)), status = AgentRunStatus.CANCELLED),
        )
        // A real FAILURE must stay a FAILURE; relabelling it as a crash would
        // poison the agent's success rate.
        assertThat(ProcessDeathRecovery.reconcileAgentRuns(runs, now)).isEmpty()
    }

    @Test
    fun `reconciliation is idempotent`() {
        val run = AgentRun(
            id = UUID.fromString("66666666-6666-6666-6666-666666666666"),
            agentId = "inbox",
            startedAt = now.minus(Duration.ofHours(3)),
            status = AgentRunStatus.RUNNING,
        )
        val first = ProcessDeathRecovery.reconcileAgentRuns(listOf(run), now)
        val reconciled = run.copy(status = AgentRunStatus.CANCELLED, finishedAt = now)

        // Second pass over the already-reconciled table must write nothing.
        val second = ProcessDeathRecovery.reconcileAgentRuns(listOf(reconciled), now)
        assertThat(first).hasSize(1)
        assertThat(second).isEmpty()
    }

    @Test
    fun `a run with a future start time is not cancelled`() {
        // Clock moved backwards, or a corrupt row. Cancelling live work is worse
        // than leaving the row for a later sweep.
        val run = AgentRun(
            id = UUID.randomUUID(),
            agentId = "inbox",
            startedAt = now.plus(Duration.ofHours(1)),
            status = AgentRunStatus.RUNNING,
        )
        assertThat(ProcessDeathRecovery.reconcileAgentRuns(listOf(run), now)).isEmpty()
    }

    // ------------------------------------------------------------- combined

    @Test
    fun `a full restore plans both halves and does not invent work`() = runTest {
        db.reminderDao().upsert(
            com.dot.core.database.ReminderEntity.from(
                Reminder(id = UUID.randomUUID(), title = "Later", remindAt = now.plus(Duration.ofHours(1))),
            ),
        )
        db.agentRunDao().upsert(
            AgentRunEntity(
                id = "77777777-7777-7777-7777-777777777777",
                agentId = "inbox",
                startedAtEpochMs = now.minus(Duration.ofHours(5)).toEpochMilli(),
                finishedAtEpochMs = null,
                status = "RUNNING",
                compactResult = null,
                errorCode = null,
            ),
        )

        killAndRestart()

        val outcome = planRestore(
            agentRuns = db.agentRunDao().recent().map { it.toDomain() },
            reminders = db.reminderDao().all().map { it.toDomain() },
            now = now,
        )

        assertThat(outcome.agentRuns).hasSize(1)
        assertThat(outcome.reminders.rearm).hasSize(1)
        assertThat(outcome.reminders.missed).isEmpty()
        assertThat(outcome.reminders.skipped).isEmpty()

        // A restore with no scheduled work plans nothing at all.
        val empty = planRestore(emptyList(), emptyList(), now)
        assertThat(empty.reminders.isEmpty).isTrue()
        assertThat(empty.agentRuns).isEmpty()
    }

    @Test
    fun `an orphaned run does not cause the reminder to be dropped`() = runTest {
        // Guards the interaction: a naive implementation that threw on the agent
        // reconciliation would skip the reminder re-arm entirely.
        db.reminderDao().upsert(
            com.dot.core.database.ReminderEntity.from(
                Reminder(id = UUID.randomUUID(), title = "Still armed", remindAt = now.plus(Duration.ofHours(1))),
            ),
        )
        db.agentRunDao().upsert(
            AgentRunEntity(
                id = "88888888-8888-8888-8888-888888888888",
                agentId = "inbox",
                startedAtEpochMs = now.minus(Duration.ofHours(9)).toEpochMilli(),
                finishedAtEpochMs = null,
                status = "RUNNING",
                compactResult = null,
                errorCode = null,
            ),
        )
        killAndRestart()

        val outcome = planRestore(
            agentRuns = db.agentRunDao().running().map { it.toDomain() },
            reminders = db.reminderDao().all().map { it.toDomain() },
            now = now,
        )

        assertThat(outcome.agentRuns).hasSize(1)
        assertThat(outcome.reminders.rearm.map { it.title }).containsExactly("Still armed")
    }

    @Test
    fun `task data is untouched by a restore that only reconciles`() = runTest {
        val repo = com.dot.core.data.RoomTaskRepository(db)
        repo.insert(Task(title = "survivor"))

        killAndRestart()

        assertThat(com.dot.core.data.RoomTaskRepository(db).all().map { it.title })
            .containsExactly("survivor")
    }
}
