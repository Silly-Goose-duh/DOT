package com.dot.app.diagnostics

import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.dot.core.model.Reminder
import java.time.Duration
import java.time.Instant

/**
 * Process-death reconciliation. See ProcessRecovery.kt for why this sits in
 * com.dot.app.diagnostics (ownership staging) rather than its own package.
 *
 * The property under test is PRD 23's: "persist enough state to safely resume
 * scheduled work after process death, but do not resume consequential user
 * actions silently." Two halves, and the second half is the one that is easy to
 * get wrong:
 *
 *  - A row stuck in RUNNING is a lie. After a kill, nothing is executing it. Left
 *    alone it makes the agent look permanently busy and hides real failures.
 *  - A pending confirmation is NOT resumed. The user was asked "Confirm: ...?" and
 *    never answered. Replaying that on restart would perform an action the user
 *    never authorised, which is the exact failure PRD 23 forbids.
 */

/** Why an agent run is being reconciled. Surfaced as a stable code, not prose. */
object ReconciliationCodes {
    const val PROCESS_DIED = "process_died"
    const val STALE = "stale"
}

/** One row to write back. Pure data — this type performs no I/O. */
data class AgentRunReconciliation(
    val runId: String,
    val agentId: String,
    val terminalStatus: AgentRunStatus,
    val finishedAt: Instant,
    val errorCode: String,
)

/** What happened to a pending confirmation across a process death. */
sealed interface PendingActionRestore {
    /**
     * Nothing was pending. The common case, and the one that must be free of side
     * effects: a restore must not create work out of nothing.
     */
    data object None : PendingActionRestore

    /**
     * A confirmation was pending. It is surfaced, never executed. [toolName] and
     * [args] are carried so the UI can re-ask with the same wording, but there is
     * deliberately no "execute" affordance on this type.
     */
    data class ReaskRequired(
        val toolName: String,
        val args: Map<String, String?>,
        val intentLabel: String,
    ) : PendingActionRestore
}

object ProcessDeathRecovery {

    /**
     * A RUNNING row older than this is assumed orphaned. Must exceed the longest
     * plausible legitimate run so a slow-but-alive agent is not cancelled by a
     * concurrent reconcile pass.
     */
    val DEFAULT_STALE_AFTER = Duration.ofMinutes(30)

    /**
     * Decides the terminal state of every RUNNING row. Idempotent by
     * construction: a run that is already terminal is returned as `null`, so
     * running this twice over the same table produces the same writes and then no
     * writes at all.
     */
    fun reconcileAgentRuns(
        runs: List<AgentRun>,
        now: Instant,
        staleAfter: Duration = DEFAULT_STALE_AFTER,
    ): List<AgentRunReconciliation> = runs.mapNotNull { run ->
        // Only RUNNING can be reconciled. A SUCCESS/FAILURE/CANCELLED row is
        // already the truth and must not be rewritten — in particular a FAILURE
        // must not be relabelled as a crash.
        if (run.status != AgentRunStatus.RUNNING) return@mapNotNull null

        val age = Duration.between(run.startedAt, now)

        // Negative age means startedAt is in the future: the clock moved backwards,
        // or the row is corrupt. Leave it alone — cancelling work that may have
        // legitimately just started is worse than leaving a row to be reaped later.
        if (age < Duration.ZERO) return@mapNotNull null

        if (age < staleAfter) return@mapNotNull null

        AgentRunReconciliation(
            runId = run.id.toString(),
            agentId = run.agentId,
            // CANCELLED, not FAILURE: the process died, the work did not fail on
            // its own merits. Recording it as FAILURE would poison the agent's
            // success rate and could suppress future scheduling.
            terminalStatus = AgentRunStatus.CANCELLED,
            finishedAt = now,
            errorCode = ReconciliationCodes.PROCESS_DIED,
        )
    }

    /**
     * Decides what happens to an in-flight confirmation after a restart.
     *
     * Returns [PendingActionRestore.ReaskRequired] and nothing else — there is no
     * branch that returns "run it". That is the whole point: the type system
     * makes silent resumption unrepresentable rather than merely discouraged.
     */
    fun restorePendingAction(
        toolName: String?,
        args: Map<String, String?>?,
        intentLabel: String?,
    ): PendingActionRestore {
        if (toolName == null || intentLabel == null) return PendingActionRestore.None
        return PendingActionRestore.ReaskRequired(
            toolName = toolName,
            args = args.orEmpty(),
            intentLabel = intentLabel,
        )
    }
}

/**
 * Post-death sweep of a fresh process. Bundles the two decisions so a caller
 * restoring state has one obvious entry point, and so the ordering is fixed:
 * reconcile agent runs first, then re-arm reminders.
 *
 * Pure. The caller owns the database writes and the AlarmManager calls.
 */
data class RestoreOutcome(
    val agentRuns: List<AgentRunReconciliation>,
    val reminders: ReminderRestorePlan,
)

fun planRestore(
    agentRuns: List<AgentRun>,
    reminders: List<Reminder>,
    now: Instant,
    staleAfter: Duration = ProcessDeathRecovery.DEFAULT_STALE_AFTER,
    missedAfter: Duration = ReminderRecovery.DEFAULT_MISSED_AFTER,
): RestoreOutcome = RestoreOutcome(
    agentRuns = ProcessDeathRecovery.reconcileAgentRuns(agentRuns, now, staleAfter),
    reminders = ReminderRecovery.plan(reminders, now, missedAfter),
)
