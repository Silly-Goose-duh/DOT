package com.dot.agent.agents

import android.content.Context
import androidx.work.BackoffPolicy as WorkBackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.core.model.AgentDefinition
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Decides what scheduled work a single [AgentDefinition] should have.
 *
 * Pure, so the enable/disable/frequency rules are testable without WorkManager.
 * The WorkManager-backed scheduler is then a thin adapter over these decisions —
 * it must not be where "is this agent allowed to run?" is answered.
 */
class AgentSchedulePlanner(
    /** Agent types whose work is meaningless offline and must carry a network constraint. */
    private val networkRequiredTypes: Set<String> = setOf(AgentTypes.INBOX),
) {

    fun plan(agent: AgentDefinition): SchedulePlan {
        // Disabled agents must have *no* work, not merely idle work: a stale
        // periodic request that fires after the user switched the agent off is
        // exactly the "disabled agent that still runs" failure PRD 16 forbids.
        if (!agent.enabled) {
            return SchedulePlan.Cancel(reason = ScheduleReasons.DISABLED)
        }
        val requested = agent.frequencyMinutes
            ?: return SchedulePlan.ManualOnly(reason = ScheduleReasons.NO_FREQUENCY)
        if (requested < MIN_PERIODIC_INTERVAL_MINUTES) {
            // Clamp rather than reject: WorkManager cannot honour anything under
            // 15 minutes, and PRD 16 forbids polling faster than the platform
            // reasonably permits. Rejecting would leave the agent unschedulable.
            return SchedulePlan.Periodic(
                ScheduledWork(
                    agentId = agent.id,
                    uniqueName = AgentWorkNames.periodic(agent.id),
                    intervalMinutes = MIN_PERIODIC_INTERVAL_MINUTES,
                    requiresNetwork = agent.type in networkRequiredTypes,
                ),
                clampedFromMinutes = requested,
            )
        }
        return SchedulePlan.Periodic(
            ScheduledWork(
                agentId = agent.id,
                uniqueName = AgentWorkNames.periodic(agent.id),
                intervalMinutes = requested.toLong(),
                requiresNetwork = agent.type in networkRequiredTypes,
            ),
            clampedFromMinutes = null,
        )
    }

    private companion object {
        /** WorkManager's own floor for periodic work. Going below it is not expressible. */
        const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
    }
}

/**
 * One periodic job. [uniqueName] is the idempotency key: WorkManager guarantees at
 * most one enqueued chain per unique name, which is what stops a re-sync of
 * settings from multiplying jobs.
 */
data class ScheduledWork(
    val agentId: String,
    val uniqueName: String,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
)

sealed interface SchedulePlan {
    data class Periodic(val work: ScheduledWork, val clampedFromMinutes: Int?) : SchedulePlan
    data class Cancel(val reason: String) : SchedulePlan

    /** Enabled but no frequency: reachable via "Run now" only, never on a timer. */
    data class ManualOnly(val reason: String) : SchedulePlan
}

object ScheduleReasons {
    const val DISABLED = "agent_disabled"
    const val NO_FREQUENCY = "no_frequency_configured"
}

/** Stable agent type identifiers. Kept as constants so no string literal drifts. */
object AgentTypes {
    const val INBOX = "inbox"
}

/**
 * Unique work names. Two namespaces: the repeating chain and the one-shot
 * "Run now" chain. Keeping them separate means a manual run cannot be mistaken
 * for the periodic one when cancelling, and vice versa.
 */
object AgentWorkNames {
    const val PERIODIC_PREFIX = "dot-agent-periodic-"
    const val RUN_NOW_PREFIX = "dot-agent-run-now-"

    fun periodic(agentId: String): String = PERIODIC_PREFIX + agentId
    fun runNow(agentId: String): String = RUN_NOW_PREFIX + agentId
}

/** Scheduling seam. The UI and AppContainer depend on this, not on WorkManager. */
interface AgentSchedulerPort {

    /** Enqueues, replaces or cancels so the scheduled set matches [agents] exactly. */
    suspend fun sync(agents: List<AgentDefinition>)

    /** Idempotent enqueue for one agent. Safe to call repeatedly. */
    suspend fun schedule(agent: AgentDefinition)

    /** Replaces the periodic chain, e.g. after the user changes the frequency. */
    suspend fun reschedule(agent: AgentDefinition)

    /** Cancels every job for [agentId]. Must actually stop future execution. */
    suspend fun cancel(agentId: String)

    suspend fun isScheduled(agentId: String): Boolean

    /** Explicit user-requested refresh. Not deduplicated against the periodic chain. */
    suspend fun runNow(agentId: String)
}

/**
 * WorkManager-backed scheduler.
 *
 * Unique work plus KEEP gives the "re-enqueue never duplicates" guarantee for
 * free; REPLACE is reserved for [reschedule], where the old request's interval is
 * genuinely stale and keeping it would silently ignore the user's new setting.
 */
class WorkManagerAgentScheduler(
    private val workManager: WorkManager,
    private val planner: AgentSchedulePlanner = AgentSchedulePlanner(),
) : AgentSchedulerPort {

    override suspend fun sync(agents: List<AgentDefinition>) {
        agents.forEach { schedule(it) }
    }

    override suspend fun schedule(agent: AgentDefinition) {
        when (val plan = planner.plan(agent)) {
            is SchedulePlan.Periodic ->
                workManager.enqueueUniquePeriodicWork(
                    plan.work.uniqueName,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest(plan.work),
                )
            is SchedulePlan.Cancel -> workManager.cancelUniqueWork(AgentWorkNames.periodic(agent.id))
            is SchedulePlan.ManualOnly ->
                workManager.cancelUniqueWork(AgentWorkNames.periodic(agent.id))
        }
    }

    override suspend fun reschedule(agent: AgentDefinition) {
        when (val plan = planner.plan(agent)) {
            is SchedulePlan.Periodic ->
                workManager.enqueueUniquePeriodicWork(
                    plan.work.uniqueName,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    periodicRequest(plan.work),
                )
            is SchedulePlan.Cancel,
            is SchedulePlan.ManualOnly,
            -> workManager.cancelUniqueWork(AgentWorkNames.periodic(agent.id))
        }
    }

    override suspend fun cancel(agentId: String) {
        workManager.cancelUniqueWork(AgentWorkNames.periodic(agentId))
        workManager.cancelUniqueWork(AgentWorkNames.runNow(agentId))
    }

    override suspend fun isScheduled(agentId: String): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(AgentWorkNames.periodic(agentId)).get()
        return infos.any { !it.state.isFinished }
    }

    override suspend fun runNow(agentId: String) {
        // KEEP, not REPLACE: a second tap while a run is pending must not queue a
        // duplicate. Once the existing work finishes, KEEP lets a later tap run again.
        workManager.enqueueUniqueWork(
            AgentWorkNames.runNow(agentId),
            ExistingWorkPolicy.KEEP,
            runNowRequest(agentId),
        )
    }

    private fun periodicRequest(work: ScheduledWork): PeriodicWorkRequest {
        val builder = PeriodicWorkRequestBuilder<AgentRunWorker>(
            work.intervalMinutes,
            TimeUnit.MINUTES,
        )
        builder.setConstraints(constraintsFor(work.requiresNetwork))
        builder.setInputData(inputDataFor(work.agentId, AgentRunTrigger.SCHEDULED))
        // The platform's own retry backoff is deliberately modest: our BackoffPolicy
        // decides whether a *result* is worth repeating, and this only covers a
        // worker that was killed before it could report.
        builder.setBackoffCriteria(WorkBackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        return builder.build()
    }

    private fun runNowRequest(agentId: String) = OneTimeWorkRequestBuilder<AgentRunWorker>()
        .setConstraints(constraintsFor(requiresNetwork = true))
        .setInputData(inputDataFor(agentId, AgentRunTrigger.MANUAL))
        .build()

    private fun constraintsFor(requiresNetwork: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
        )
        .setRequiresBatteryNotLow(true)
        .build()
}

/**
 * WorkManager input-data accessors.
 *
 * File-level rather than nested in a companion: the worker is a different class
 * and needs to read the agent id and trigger out of its own `inputData`, and a
 * companion-scoped extension would not be in scope there.
 */
private const val KEY_AGENT_ID = "dot.agentId"
private const val KEY_TRIGGER = "dot.trigger"

private fun inputDataFor(agentId: String, trigger: AgentRunTrigger): Data =
    workDataOf(KEY_AGENT_ID to agentId, KEY_TRIGGER to trigger.name)

private fun Data.agentId(): String? = getString(KEY_AGENT_ID)

/** An unrecognised trigger string falls back to SCHEDULED: the conservative reading,
 * since a periodic run must not be mistaken for an explicit user request. */
private fun Data.trigger(): AgentRunTrigger =
    getString(KEY_TRIGGER)
        ?.let { name -> AgentRunTrigger.entries.firstOrNull { it.name == name } }
        ?: AgentRunTrigger.SCHEDULED

/**
 * Why a run started. Scheduled runs are deferrable and may silently no-op;
 * a manual run was an explicit user request and is reported back to the UI.
 */
enum class AgentRunTrigger { SCHEDULED, MANUAL }

/**
 * Worker shim. All authority lives in [AgentRunner]; this only translates a
 * WorkManager invocation into a run and a [ListenableWorker.Result] back.
 */
class AgentRunWorker(
    context: Context,
    params: WorkerParameters,
    private val runnerProvider: () -> AgentRunner?,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agentId = inputData.agentId() ?: return Result.failure()
        // A null runner means the composition root was not wired. Failing loudly is
        // the whole point: returning success would record a RUNNING run that never
        // finishes, which is the dangling-row failure mode.
        val runner = runnerProvider() ?: return Result.failure()
        val trigger = inputData.trigger()
        return when (val outcome = runner.run(agentId, trigger, runAttemptCount)) {
            is AgentRunOutcome.Success,
            is AgentRunOutcome.Skipped,
            -> Result.success()
            is AgentRunOutcome.Requeue -> Result.retry()
            // failure() rather than success(): a refused or failed run must not read
            // as "the agent did its job".
            is AgentRunOutcome.Failed, is AgentRunOutcome.Cancelled -> Result.failure()
        }
    }
}

/**
 * Factory so [AgentRunWorker] can receive the runner without a static singleton.
 * The parent registers this via `Configuration.Provider` in the Application.
 */
class DotWorkerFactory(private val runnerProvider: () -> AgentRunner?) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        AgentRunWorker::class.java.name ->
            AgentRunWorker(appContext, workerParameters, runnerProvider)
        else -> null
    }
}

/** Named so callers do not reach for WorkManager's own Duration-based overloads. */
internal fun Duration.toMinutesCeil(): Long = toMinutes().coerceAtLeast(1)