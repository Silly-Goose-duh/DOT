package com.dot.app.hardening

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.app.diagnostics.ReminderRecovery
import com.dot.app.diagnostics.ReminderRestoreAction
import com.dot.core.database.DotDatabase
import com.dot.core.database.MIGRATION_1_2
import com.dot.core.database.ReminderEntity
import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
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
 * Reboot / reminder reschedule.
 *
 * Alarms do not survive a reboot, so `BootReceiver` rebuilds the whole alarm set
 * from the database. The logic in `BootReceiver` is not directly testable — it
 * opens a real database and calls AlarmManager — so Milestone 7 extracted the
 * *decision* into `ReminderRecovery` and this suite covers the decision against
 * a real SQLite file.
 *
 * The properties that matter, in the order a user would notice them:
 *  - a reminder still in the future is re-armed;
 *  - one already fired or cancelled is not;
 *  - one whose moment passed while the device was off is reported, not silently
 *    dropped and not fired stale;
 *  - doing it twice changes nothing.
 *
 * WIRING PENDING: see the parent report. `BootReceiver` still contains its own
 * inline filter and must be pointed at `ReminderRecovery.plan`.
 */
@RunWith(RobolectricTestRunner::class)
class BootRescheduleTest {

    private val dbName = "boot-reschedule-test.db"
    private lateinit var db: DotDatabase
    private val now: Instant = Instant.parse("2026-10-02T09:00:00Z")

    private fun reminder(
        title: String,
        at: Instant,
        state: ReminderState = ReminderState.SCHEDULED,
    ) = Reminder(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        title = title,
        remindAt = at,
        state = state,
        createdAt = now.minus(Duration.ofDays(1)),
    )

    private fun open() = Room
        .databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DotDatabase::class.java,
            dbName,
        )
        .addMigrations(MIGRATION_1_2)
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

    private suspend fun seed(vararg reminders: Reminder) {
        reminders.forEach { db.reminderDao().upsert(ReminderEntity.from(it)) }
    }

    private suspend fun persisted(): List<Reminder> = db.reminderDao().all().map { it.toDomain() }

    // ------------------------------------------------------------------ re-arm

    @Test
    fun `a reminder in the future is re-armed after reboot`() = runTest {
        seed(reminder("Standup", now.plus(Duration.ofMinutes(30))))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm.map { it.title }).containsExactly("Standup")
        assertThat(plan.missed).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `all future reminders are re-armed, not just the first`() = runTest {
        seed(
            reminder("Third", now.plus(Duration.ofHours(3))),
            reminder("First", now.plus(Duration.ofMinutes(5))),
            reminder("Second", now.plus(Duration.ofHours(1))),
        )

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm).hasSize(3)
        // Chronological, and deterministic: two boots must arm in the same order.
        assertThat(plan.rearm.map { it.title }).containsExactly("First", "Second", "Third").inOrder()
    }

    @Test
    fun `the re-armed reminder is the full persisted value, not a stub`() = runTest {
        val original = reminder("Send the report", now.plus(Duration.ofHours(2)))
        seed(original)

        val rearmed = ReminderRecovery.plan(persisted(), now).rearm.single()

        // Same id and same trigger instant is what makes the PendingIntent identity
        // match; a rebuilt-with-a-new-id reminder would leave the old alarm armed.
        assertThat(rearmed.id).isEqualTo(original.id)
        assertThat(rearmed.remindAt).isEqualTo(original.remindAt)
        assertThat(rearmed.title).isEqualTo(original.title)
        assertThat(rearmed.state).isEqualTo(ReminderState.SCHEDULED)
    }

    // ---------------------------------------------------------------- terminal

    @Test
    fun `a fired reminder is not re-armed`() = runTest {
        seed(reminder("Already fired", now.plus(Duration.ofHours(1)), ReminderState.FIRED))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm).isEmpty()
        assertThat(plan.skipped.map { it.title }).containsExactly("Already fired")
    }

    @Test
    fun `a cancelled reminder is not re-armed even when its time is still ahead`() = runTest {
        // The important asymmetry: CANCELLED wins over a future timestamp.
        seed(reminder("Cancelled", now.plus(Duration.ofHours(5)), ReminderState.CANCELLED))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm).isEmpty()
        val reason = ReminderRecovery.actions(persisted(), now).single()
        assertThat((reason as ReminderRestoreAction.Skip).reason).isEqualTo("state_cancelled")
    }

    @Test
    fun `a done reminder is not re-armed`() = runTest {
        seed(reminder("Done", now.plus(Duration.ofHours(1)), ReminderState.DONE))
        assertThat(ReminderRecovery.plan(persisted(), now).rearm).isEmpty()
    }

    // ------------------------------------------------------------- past due

    @Test
    fun `a long-past reminder is reported missed, not re-armed`() = runTest {
        seed(reminder("Missed overnight", now.minus(Duration.ofHours(9))))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm).isEmpty()
        assertThat(plan.missed.map { it.title }).containsExactly("Missed overnight")
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    fun `a reminder a few minutes past due is still re-armed`() = runTest {
        // AlarmManager takes an absolute RTC_WAKEUP time, so a trigger a couple of
        // minutes in the past still fires promptly. Dropping it would lose a
        // reminder the user is still in time for.
        seed(reminder("Just missed", now.minus(Duration.ofMinutes(2))))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm.map { it.title }).containsExactly("Just missed")
        assertThat(plan.missed).isEmpty()
    }

    @Test
    fun `the missed threshold is exactly where it is documented`() = runTest {
        val justInside = now.minus(ReminderRecovery.DEFAULT_MISSED_AFTER).plusSeconds(1)
        val justOutside = now.minus(ReminderRecovery.DEFAULT_MISSED_AFTER).minusSeconds(1)

        seed(reminder("Inside", justInside), reminder("Outside", justOutside))

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm.map { it.title }).containsExactly("Inside")
        assertThat(plan.missed.map { it.title }).containsExactly("Outside")
    }

    // ------------------------------------------------------------ mixed state

    @Test
    fun `a mixed table splits cleanly into the three buckets with no overlap`() = runTest {
        seed(
            reminder("future", now.plus(Duration.ofHours(1))),
            reminder("fired", now.plus(Duration.ofHours(2)), ReminderState.FIRED),
            reminder("cancelled", now.plus(Duration.ofHours(3)), ReminderState.CANCELLED),
            reminder("long past", now.minus(Duration.ofHours(5))),
            reminder("barely past", now.minus(Duration.ofMinutes(1))),
        )

        val plan = ReminderRecovery.plan(persisted(), now)

        assertThat(plan.rearm.map { it.title }).containsExactly("barely past", "future")
        assertThat(plan.missed.map { it.title }).containsExactly("long past")
        assertThat(plan.skipped.map { it.title }).containsExactly("fired", "cancelled")

        // Every row is accounted for exactly once — nothing is dropped on the floor.
        val total = plan.rearm.size + plan.missed.size + plan.skipped.size
        assertThat(total).isEqualTo(5)
    }

    @Test
    fun `an empty table plans nothing`() {
        val plan = ReminderRecovery.plan(emptyList(), now)
        assertThat(plan.isEmpty).isTrue()
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun `rescheduling twice produces an identical plan`() = runTest {
        seed(
            reminder("A", now.plus(Duration.ofHours(1))),
            reminder("B", now.plus(Duration.ofHours(2)), ReminderState.FIRED),
            reminder("C", now.minus(Duration.ofHours(4))),
        )

        val first = ReminderRecovery.plan(persisted(), now)
        // A second BOOT_COMPLETED, or a force-stop then reopen, re-reads the same
        // rows. Nothing about the second call may depend on the first having run.
        val second = ReminderRecovery.plan(persisted(), now)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `rearming after a reboot does not change the database`() = runTest {
        seed(reminder("Idempotent", now.plus(Duration.ofHours(1))))
        val before = persisted()

        repeat(3) { ReminderRecovery.plan(persisted(), now) }

        assertThat(persisted()).isEqualTo(before)
    }

    @Test
    fun `rearming is stable across a real process restart`() = runTest {
        seed(
            reminder("Survivor", now.plus(Duration.ofHours(1))),
            reminder("Gone", now.plus(Duration.ofHours(2)), ReminderState.CANCELLED),
        )
        val firstPlan = ReminderRecovery.plan(persisted(), now)

        db.close()
        db = open()
        val secondPlan = ReminderRecovery.plan(persisted(), now)

        assertThat(secondPlan).isEqualTo(firstPlan)
        assertThat(secondPlan.rearm.map { it.title }).containsExactly("Survivor")
    }
}
