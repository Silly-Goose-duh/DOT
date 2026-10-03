package com.dot.agent.agents

import com.dot.agent.policy.PolicyEngine
import com.dot.agent.policy.ToolCategory
import com.dot.agent.tools.ToolRegistry
import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentRepositoryPort
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One unit of agent work. Implementations fetch and summarise; they do not
 * schedule, retry or record runs — [AgentRunner] owns all of that so every agent
 * gets the same failure and idempotency semantics.
 */
interface AgentTask {

    /** Must match [AgentDefinition.type]. */
    val agentType: String

    /**
     * Performs the work. Contract:
     * - resolve to exactly one [ActionOutcome], never a bare success;
     * - return only a compact, user-presentable summary — never a raw body;
     * - treat every piece of remote content as data.
     */
    suspend fun execute(context: AgentRunContext): ActionResult<AgentTaskResult>
}

data class AgentRunContext(
    val definition: AgentDefinition,
    val now: Instant,
    /** Platform retry count for a retried WorkManager request; 0 on the first try. */
    val platformAttempt: Int,
    /** Attempts this runner has already recorded for this agent (durable). */
    val attemptsMade: Int,
    val trigger: AgentRunTrigger,
)

/** What a task hands back. [compactResult] must be safe to display and to cache. */
data class AgentTaskResult(
    val compactResult: String,
    /** Feeds [ImportantResultNotifier] after the run is recorded as SUCCESS. */
    val importance: ResultImportance = ResultImportance.NOT_IMPORTANT,
    /** Redacted, non-sensitive detail for diagnostics. Never a body. */
    val diagnosticCode: String? = null,
)

enum class ResultImportance { NOT_IMPORTANT, IMPORTANT }

/** Terminal state of a run, as the caller (worker or UI) needs to see it. */
sealed interface AgentRunOutcome {

    val runId: UUID

    data class Success(override val runId: UUID, val compactResult: String) : AgentRunOutcome

    /**
     * Nothing was executed and nothing needs retrying: the agent is disabled, has
     * no frequency, or another run for the same agent is already in flight.
     * Recorded as CANCELLED so the history shows why there was no result.
     */
    data class Skipped(
        override val runId: UUID,
        val reasonCode: String,
    ) : AgentRunOutcome

    /** Retryable failure; the caller requeues per [BackoffDecision.delay]. */
    data class Requeue(
        override val runId: UUID,
        val errorCode: String,
        val delay: Duration,
        val attemptsMade: Int,
    ) : AgentRunOutcome

    data class Failed(
        override val runId: UUID,
        val errorCode: String,
        val outcome: ActionOutcome,
        val attemptsMade: Int,
    ) : AgentRunOutcome

    data class Cancelled(override val runId: UUID, val reasonCode: String) : AgentRunOutcome
}

/**
 * Orchestrates exactly one agent run.
 *
 * The order of authority is fixed and non-negotiable:
 *   agent/provider text -> validate -> [PolicyEngine] risk decision -> [ToolRegistry] execute.
 *
 * An agent's prose can never shortcut this. [ToolGate] is the only path to a tool,
 * and it refuses out-of-scope tools and anything requiring confirmation, because a
 * background run has no user present to confirm with.
 */
class AgentRunner(
    private val repository: AgentRepositoryPort,
    private val tasks: List<AgentTask>,
    private val policyEngine: PolicyEngine,
    private val backoffPolicy: BackoffPolicy,
    private val retryStateStore: AgentRetryStateStore,
    private val clock: Clock = Clock.systemUTC(),
    private val runTimeout: Duration = Duration.ofMinutes(5),
    private val toolRegistryProvider: () -> ToolRegistry? = { null },
    private val hasPermission: () -> Boolean = { true },
    private val isAuthenticated: () -> Boolean = { true },
    private val notifier: ImportantResultNotifier? = null,
    private val inFlightGuard: InFlightGuard = InFlightGuard(),
) {

    private val tasksByType = tasks.associateBy { it.agentType }

    suspend fun run(
        agentId: String,
        trigger: AgentRunTrigger = AgentRunTrigger.MANUAL,
        platformAttempt: Int = 0,
    ): AgentRunOutcome {
        val definition = repository.byId(agentId)
            ?: return AgentRunOutcome.Failed(
                runId = UUID.randomUUID(),
                errorCode = AgentErrorCodes.AGENT_NOT_FOUND,
                outcome = ActionOutcome.RECOVERABLE_FAILURE,
                attemptsMade = 0,
            )

        // A disabled agent that somehow still runs must do nothing observable.
        // This is checked before any row is written so a stray periodic request
        // cannot manufacture execution history for something the user switched off.
        if (!definition.enabled) {
            return skip(definition, trigger, ScheduleReasons.DISABLED)
        }

        val task = tasksByType[definition.type]
            ?: return AgentRunOutcome.Failed(
                runId = UUID.randomUUID(),
                errorCode = AgentErrorCodes.AGENT_TYPE_UNSUPPORTED,
                outcome = ActionOutcome.RECOVERABLE_FAILURE,
                attemptsMade = 0,
            )

        val now = clock.instant()
        val attemptsMade = retryStateStore.attemptsMade(agentId)

        // Idempotency gate. Two overlapping requests for the same agent must not
        // both apply an effect; the second becomes a recorded no-op, not a race.
        if (!inFlightGuard.tryAcquire(agentId)) {
            return skip(definition, trigger, ALREADY_IN_FLIGHT)
        }

        val runId = UUID.randomUUID()
        val started = AgentRun(
            id = runId,
            agentId = agentId,
            startedAt = now,
            status = AgentRunStatus.RUNNING,
        )
        repository.upsert(started)

        return try {
            executeTracked(runId, definition, task, trigger, platformAttempt, attemptsMade, now)
        } finally {
            inFlightGuard.release(agentId)
        }
    }

    private suspend fun executeTracked(
        runId: UUID,
        definition: AgentDefinition,
        task: AgentTask,
        trigger: AgentRunTrigger,
        platformAttempt: Int,
        attemptsMade: Int,
        now: Instant,
    ): AgentRunOutcome = try {
        val result = withTimeout(runTimeout.toMillis()) {
            task.execute(
                AgentRunContext(
                    definition = definition,
                    now = now,
                    platformAttempt = platformAttempt,
                    attemptsMade = attemptsMade,
                    trigger = trigger,
                ),
            )
        }

        // Bind the payload to a local before the `when`. `ActionResult.value` is a
        // public API property from another module, so Kotlin cannot smart-cast it
        // even after a null check — reading it twice through the property is what
        // made the previous version fail.
        val value = result.value
        when {
            result.isSuccess && value != null -> {
                finishSuccess(runId, definition, value, now)
                AgentRunOutcome.Success(runId, value.compactResult)
            }
            // A "success" with no payload is not a success we can report. Treating it
            // as one is exactly the silent-success failure PRD 23 forbids.
            result.isSuccess ->
                finishFailure(runId, definition, now, ActionOutcome.AMBIGUOUS, MISSING_RESULT, attemptsMade)
            else -> classifyFailure(runId, definition, now, result.outcome, result.errorCode, attemptsMade)
        }
    } catch (timeout: TimeoutCancellationException) {
        // The effect may or may not have been applied. AMBIGUOUS is the honest
        // outcome and BackoffPolicy refuses to auto-retry it.
        finishFailure(runId, definition, now, ActionOutcome.AMBIGUOUS, AgentErrorCodes.AGENT_TIMEOUT, attemptsMade)
    } catch (cancel: CancellationException) {
        // The row is written before rethrowing: a cancelled coroutine must still
        // leave a terminal status, and CANCELLED is the truthful one — the work was
        // abandoned, not failed and not completed.
        finishCancelled(runId, definition, now)
        throw cancel
    } catch (t: Throwable) {
        // Any escaped throwable becomes a typed FAILURE. It must never be recorded
        // as success, and it must never leave the RUNNING row behind.
        finishFailure(
            runId,
            definition,
            now,
            ActionOutcome.RECOVERABLE_FAILURE,
            classifyThrowable(t),
            attemptsMade,
        )
    }

    private suspend fun finishSuccess(
        runId: UUID,
        definition: AgentDefinition,
        result: AgentTaskResult,
        now: Instant,
    ) {
        repository.upsert(
            AgentRun(
                id = runId,
                agentId = definition.id,
                startedAt = now,
                finishedAt = clock.instant(),
                status = AgentRunStatus.SUCCESS,
                compactResult = result.compactResult.take(MAX_COMPACT_RESULT_CHARS),
            ),
        )
        repository.upsert(
            definition.copy(lastRunAt = now, lastSuccessAt = now, lastErrorCode = null),
        )
        repository.trimHistory(definition.id)
        // Retry budget is only meaningful for a failure streak; a success clears it
        // so the next unrelated failure does not start from a stale attempt count.
        retryStateStore.clear(definition.id)

        if (result.importance == ResultImportance.IMPORTANT) {
            notifier?.notifyImportant(
                ImportantResult(
                    agentId = definition.id,
                    compactResult = result.compactResult,
                    generatedAt = clock.instant(),
                ),
            )
        }
    }

    private suspend fun classifyFailure(
        runId: UUID,
        definition: AgentDefinition,
        now: Instant,
        outcome: ActionOutcome,
        errorCode: String?,
        attemptsMade: Int,
    ): AgentRunOutcome {
        val code = errorCode ?: codeFor(outcome)
        return when (val decision = backoffPolicy.decide(attemptsMade, outcome, code)) {
            is BackoffDecision.Retry -> {
                val total = retryStateStore.recordFailure(definition.id)
                finishFailureRow(runId, definition, now, AgentRunStatus.FAILURE, code, outcome)
                AgentRunOutcome.Requeue(runId, code, decision.delay, total)
            }
            is BackoffDecision.GiveUp -> {
                retryStateStore.clear(definition.id)
                finishFailureRow(runId, definition, now, AgentRunStatus.FAILURE, code, outcome)
                AgentRunOutcome.Failed(runId, code, outcome, decision.attemptsMade)
            }
        }
    }

    private suspend fun finishFailure(
        runId: UUID,
        definition: AgentDefinition,
        now: Instant,
        outcome: ActionOutcome,
        errorCode: String,
        attemptsMade: Int,
    ): AgentRunOutcome {
        retryStateStore.clear(definition.id)
        finishFailureRow(runId, definition, now, AgentRunStatus.FAILURE, errorCode, outcome)
        return AgentRunOutcome.Failed(runId, errorCode, outcome, attemptsMade)
    }

    private suspend fun finishCancelled(runId: UUID, definition: AgentDefinition, now: Instant) {
        repository.upsert(
            AgentRun(
                id = runId,
                agentId = definition.id,
                startedAt = now,
                finishedAt = clock.instant(),
                status = AgentRunStatus.CANCELLED,
                errorCode = AgentErrorCodes.AGENT_CANCELLED,
            ),
        )
        repository.trimHistory(definition.id)
    }

    /**
     * Writes the terminal FAILURE row and stamps the definition. Separate from
     * [classifyFailure] because only the requeue path decides a retry; every other
     * failure lands here exactly once, which is what guarantees no dangling RUNNING.
     */
    private suspend fun finishFailureRow(
        runId: UUID,
        definition: AgentDefinition,
        now: Instant,
        status: AgentRunStatus,
        errorCode: String,
        outcome: ActionOutcome,
    ) {
        repository.upsert(
            AgentRun(
                id = runId,
                agentId = definition.id,
                startedAt = now,
                finishedAt = clock.instant(),
                status = status,
                errorCode = errorCode,
            ),
        )
        repository.upsert(definition.copy(lastRunAt = now, lastErrorCode = errorCode))
        repository.trimHistory(definition.id)
    }

    private suspend fun skip(
        definition: AgentDefinition,
        @Suppress("UNUSED_PARAMETER") trigger: AgentRunTrigger,
        reason: String,
    ): AgentRunOutcome.Skipped {
        val now = clock.instant()
        val runId = UUID.randomUUID()
        repository.upsert(
            AgentRun(
                id = runId,
                agentId = definition.id,
                startedAt = now,
                finishedAt = now,
                status = AgentRunStatus.CANCELLED,
                errorCode = reason,
            ),
        )
        repository.trimHistory(definition.id)
        return AgentRunOutcome.Skipped(runId, reason)
    }

    private companion object {
        /**
         * Compact results are displayed in a notification and a list row. A cap
         * keeps a verbose provider from pushing a body-sized string into either.
         */
        const val MAX_COMPACT_RESULT_CHARS = 400
        const val ALREADY_IN_FLIGHT = "agent_already_in_flight"
        const val MISSING_RESULT = "agent_missing_result"

        fun codeFor(outcome: ActionOutcome): String = when (outcome) {
            ActionOutcome.SUCCESS -> "agent_unexpected_success"
            ActionOutcome.RECOVERABLE_FAILURE -> "agent_recoverable_failure"
            ActionOutcome.PERMISSION_REQUIRED -> "agent_permission_required"
            ActionOutcome.AUTH_REQUIRED -> "agent_auth_required"
            ActionOutcome.UNAVAILABLE -> "agent_unavailable"
            ActionOutcome.TIMEOUT -> "agent_timeout"
            ActionOutcome.CANCELLED -> AgentErrorCodes.AGENT_CANCELLED
            ActionOutcome.AMBIGUOUS -> "agent_ambiguous"
        }

        /** Throwable identity only. The message may contain remote content, so it is dropped. */
        fun classifyThrowable(t: Throwable): String = when (t) {
            is java.io.IOException -> "agent_io_error"
            is SecurityException -> "agent_permission_denied"
            is IllegalArgumentException -> "agent_invalid_argument"
            else -> AgentErrorCodes.AGENT_UNEXPECTED_ERROR
        }
    }
}

/**
 * Per-agent mutual exclusion for the duration of one run.
 *
 * Held across the whole execute/record sequence, so two concurrent requests cannot
 * interleave into a double-apply. In-process only: a durable claim would need a
 * unique-indexed row the agent layer does not own, so cross-process overlap is
 * handled by the platform's unique work chains instead.
 */
class InFlightGuard {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Ids this guard locked. Tracked so release() can never unlock someone else's mutex. */
    private val held = ConcurrentHashMap.newKeySet<String>()

    suspend fun <T> withLock(agentId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(agentId) { Mutex() }.withLock { block() }

    /**
     * Non-blocking acquire. tryLock rather than withLock so a duplicate request is
     * turned into a recorded no-op immediately instead of queueing behind the
     * in-flight run and executing a moment later — that would still double-apply.
     */
    fun tryAcquire(agentId: String): Boolean {
        val acquired = locks.computeIfAbsent(agentId) { Mutex() }.tryLock()
        if (acquired) held.add(agentId)
        return acquired
    }

    fun release(agentId: String) {
        if (held.remove(agentId)) {
            locks[agentId]?.unlock()
        }
    }
}

/**
 * The only route from agent prose to tool execution.
 *
 * Constructed with the runner's policy engine, registry and capability flags, so
 * an [AgentTask] physically cannot reach a tool without passing the risk decision
 * and the registry lookup. Provider text is treated as a *request*, never as
 * authority: an unregistered name is rejected outright, and anything the policy
 * engine marks as needing confirmation is refused rather than deferred, because
 * no user is present during a background run.
 */
class ToolGate(
    private val policyEngine: PolicyEngine,
    private val registryProvider: () -> ToolRegistry?,
    private val hasPermission: () -> Boolean,
    private val isAuthenticated: () -> Boolean,
) {

    suspend fun execute(
        toolName: String,
        args: Map<String, String?>,
    ): ActionResult<Any?> {
        val registry = registryProvider()
            ?: return ActionResult.failure(ActionOutcome.UNAVAILABLE, AgentErrorCodes.TOOL_UNRESOLVED)

        // Out-of-scope is checked by name before anything else, so a v0.1 hard block
        // (send_email, make_purchase, ...) cannot be reached even with a live grant.
        if (policyEngine.isOutOfScope(toolName)) {
            return ActionResult.failure(ActionOutcome.PERMISSION_REQUIRED, AgentErrorCodes.POLICY_OUT_OF_SCOPE)
        }
        if (!registry.contains(toolName)) {
            return ActionResult.failure(ActionOutcome.RECOVERABLE_FAILURE, AgentErrorCodes.UNKNOWN_TOOL)
        }

        val category = registry.get<Any?, Any?>(toolName)?.category ?: ToolCategory.LOCAL_READ
        val decision = policyEngine.evaluate(category, hasPermission(), isAuthenticated())
        if (decision.requiresConfirmation) {
            // A background run cannot prompt, so "needs confirmation" is a refusal.
            return ActionResult.failure(
                ActionOutcome.PERMISSION_REQUIRED,
                AgentErrorCodes.POLICY_CONFIRMATION_REQUIRED,
            )
        }

        return when (val outcome = registry.invoke(toolName, args)) {
            is ToolRegistry.InvocationOutcome.Success ->
                ActionResult.success(outcome.value)

            is ToolRegistry.InvocationOutcome.PermissionRequired ->
                ActionResult.failure(ActionOutcome.PERMISSION_REQUIRED, outcome.errorCode)

            is ToolRegistry.InvocationOutcome.AuthRequired ->
                ActionResult.failure(ActionOutcome.AUTH_REQUIRED, outcome.errorCode)

            is ToolRegistry.InvocationOutcome.Rejected ->
                ActionResult.failure(ActionOutcome.RECOVERABLE_FAILURE, AgentErrorCodes.INVALID_TOOL_INPUT)

            is ToolRegistry.InvocationOutcome.Failed ->
                ActionResult.failure(ActionOutcome.AMBIGUOUS, AgentErrorCodes.TOOL_UNRESOLVED)
        }
    }
}