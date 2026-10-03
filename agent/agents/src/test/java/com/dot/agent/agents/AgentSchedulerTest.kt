package com.dot.agent.agents

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dot.core.model.AgentDefinition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Planner tests are pure; the WorkManager tests use the platform's own
 * SynchronousExecutor so enqueue/cancel is observable immediately rather than
 * after a drain, which keeps "did it duplicate?" a deterministic assertion.
 */
@RunWith(RobolectricTestRunner::class)
class AgentSchedulePlannerTest {

    private val planner = AgentSchedulePlanner()

    @Test
    fun `an enabled agent with a frequency gets periodic work`() {
        val plan = planner.plan(def(enabled = true, frequencyMinutes = 60))

        assertThat(plan).isInstanceOf(SchedulePlan.Periodic::class.java)
        val periodic = (plan as SchedulePlan.Periodic).work
        assertThat(periodic.agentId).isEqualTo("inbox.primary")
        assertThat(periodic.intervalMinutes).isEqualTo(60L)
        assertThat(periodic.uniqueName).isEqualTo(AgentWorkNames.periodic("inbox.primary"))
    }

    @Test
    fun `a disabled agent gets no work and a cancel instruction`() {
        val plan = planner.plan(def(enabled = false, frequencyMinutes = 60))

        assertThat(plan).isInstanceOf(SchedulePlan.Cancel::class.java)
        assertThat((plan as SchedulePlan.Cancel).reason).isEqualTo(ScheduleReasons.DISABLED)
    }

    @Test
    fun `an enabled agent with no frequency is manual only`() {
        val plan = planner.plan(def(enabled = true, frequencyMinutes = null))

        assertThat(plan).isInstanceOf(SchedulePlan.ManualOnly::class.java)
        assertThat((plan as SchedulePlan.ManualOnly).reason).isEqualTo(ScheduleReasons.NO_FREQUENCY)
    }

    @Test
    fun `a sub-15-minute frequency is clamped to the platform floor`() {
        val plan = planner.plan(def(enabled = true, frequencyMinutes = 5))

        val periodic = (plan as SchedulePlan.Periodic)
        assertThat(periodic.work.intervalMinutes).isEqualTo(15L)
        assertThat(periodic.clampedFromMinutes).isEqualTo(5)
    }

    @Test
    fun `an inbox agent requires network but a local agent does not`() {
        val inbox = (planner.plan(def(type = "inbox")) as SchedulePlan.Periodic).work
        val local = (planner.plan(def(type = "notes")) as SchedulePlan.Periodic).work

        assertThat(inbox.requiresNetwork).isTrue()
        assertThat(local.requiresNetwork).isFalse()
    }

    @Test
    fun `the periodic and run-now work names never collide`() {
        assertThat(AgentWorkNames.periodic("inbox.primary"))
            .isNotEqualTo(AgentWorkNames.runNow("inbox.primary"))
    }

    @Test
    fun `the same definition always plans the same work`() {
        val a = planner.plan(def()) as SchedulePlan.Periodic
        val b = planner.plan(def()) as SchedulePlan.Periodic
        assertThat(a.work).isEqualTo(b.work)
    }

    private fun def(
        enabled: Boolean = true,
        type: String = "inbox",
        frequencyMinutes: Int? = 60,
    ) = AgentDefinition("inbox.primary", type, enabled, frequencyMinutes)
}

@RunWith(RobolectricTestRunner::class)
class WorkManagerAgentSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerAgentScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerAgentScheduler(workManager)
    }

    @Test
    fun `scheduling an agent enqueues exactly one unique chain`() = runTest {
        scheduler.schedule(def(enabled = true, frequencyMinutes = 60))

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.periodic("inbox.primary")).get()

        assertThat(infos).hasSize(1)
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `re-enqueueing does not duplicate the job`() = runTest {
        repeat(5) { scheduler.schedule(def(enabled = true, frequencyMinutes = 60)) }

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.periodic("inbox.primary")).get()

        // This is the whole point of unique work: N sync calls, one chain.
        assertThat(infos).hasSize(1)
    }

    @Test
    fun `two different agents get two distinct chains`() = runTest {
        scheduler.schedule(AgentDefinition("inbox.primary", "inbox", true, 60))
        scheduler.schedule(AgentDefinition("notes.daily", "notes", true, 60))

        assertThat(
            workManager.getWorkInfosForUniqueWork(AgentWorkNames.periodic("inbox.primary")).get(),
        ).hasSize(1)
        assertThat(
            workManager.getWorkInfosForUniqueWork(AgentWorkNames.periodic("notes.daily")).get(),
        ).hasSize(1)
    }

    @Test
    fun `disabling an agent cancels its scheduled work`() = runTest {
        scheduler.schedule(def(enabled = true, frequencyMinutes = 60))
        assertThat(scheduler.isScheduled("inbox.primary")).isTrue()

        scheduler.schedule(def(enabled = false, frequencyMinutes = 60))

        // Cancelled work must be gone, not merely idle: a chain left in place would
        // keep firing and the runner's disabled check would have to catch every one.
        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.periodic("inbox.primary")).get()
        assertThat(infos.all { it.state == WorkInfo.State.CANCELLED }).isTrue()
        assertThat(scheduler.isScheduled("inbox.primary")).isFalse()
    }

    @Test
    fun `explicit cancel clears the agent`() = runTest {
        scheduler.schedule(def(enabled = true, frequencyMinutes = 60))

        scheduler.cancel("inbox.primary")

        assertThat(scheduler.isScheduled("inbox.primary")).isFalse()
    }

    @Test
    fun `cancel also clears a pending run now`() = runTest {
        scheduler.runNow("inbox.primary")
        assertThat(
            workManager.getWorkInfosForUniqueWork(AgentWorkNames.runNow("inbox.primary")).get(),
        ).hasSize(1)

        scheduler.cancel("inbox.primary")

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.runNow("inbox.primary")).get()
        assertThat(infos.all { it.state == WorkInfo.State.CANCELLED }).isTrue()
    }

    @Test
    fun `run now enqueues its own one-shot chain`() = runTest {
        scheduler.runNow("inbox.primary")

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.runNow("inbox.primary")).get()

        assertThat(infos).hasSize(1)
        // Must not register as periodic, or "Run now" would become a repeating job.
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `a second run now does not queue a duplicate`() = runTest {
        repeat(3) { scheduler.runNow("inbox.primary") }

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.runNow("inbox.primary")).get()

        assertThat(infos).hasSize(1)
    }

    @Test
    fun `reschedule replaces rather than accumulating`() = runTest {
        scheduler.schedule(def(frequencyMinutes = 60))
        scheduler.reschedule(def(frequencyMinutes = 120))

        val infos = workManager
            .getWorkInfosForUniqueWork(AgentWorkNames.periodic("inbox.primary")).get()

        assertThat(infos).hasSize(1)
        assertThat(scheduler.isScheduled("inbox.primary")).isTrue()
    }

    @Test
    fun `sync leaves the schedule matching the definitions given`() = runTest {
        scheduler.sync(
            listOf(
                AgentDefinition("inbox.primary", "inbox", true, 60),
                AgentDefinition("notes.daily", "notes", false, 60),
            ),
        )

        assertThat(scheduler.isScheduled("inbox.primary")).isTrue()
        // Disabled in the input set -> no work scheduled for it at all.
        assertThat(scheduler.isScheduled("notes.daily")).isFalse()
    }

    @Test
    fun `an enabled agent with no frequency schedules nothing periodic`() = runTest {
        scheduler.schedule(def(enabled = true, frequencyMinutes = null))

        assertThat(scheduler.isScheduled("inbox.primary")).isFalse()
        // But it is still runnable on demand.
        scheduler.runNow("inbox.primary")
        assertThat(
            workManager.getWorkInfosForUniqueWork(AgentWorkNames.runNow("inbox.primary")).get(),
        ).hasSize(1)
    }

    private fun def(
        enabled: Boolean = true,
        type: String = "inbox",
        frequencyMinutes: Int? = 60,
    ) = AgentDefinition("inbox.primary", type, enabled, frequencyMinutes)
}