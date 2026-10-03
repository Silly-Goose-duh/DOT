package com.dot.app.diagnostics

import com.dot.core.model.Reminder
import com.dot.core.model.ReminderState
import java.time.Duration
import java.time.Instant

/**
 * Milestone 7 hardening logic. Everything here is a pure function of persisted
 * state plus a clock, with no Android imports, because the properties PRD 23
 * demands ("resume scheduled work after process death, but do not resume
 * consequential user actions silently") are only testable if the decision is
 * separated from the side effect.
 *
 * STAGING NOTE FOR THE PARENT AGENT: this file lives in
 * com.dot.app.diagnostics only because Milestone 7's file-ownership boundary
 * allowed exactly one new main-source directory under :app. It is recovery
 * logic, not diagnostics. Move it to com.dot.app.recovery (or fold it into
 * com.dot.app.reminder) when the ownership boundary is lifted. Nothing here
 * imports android.*, so the move is a package rename.
 */

/** What to do with one persisted reminder on restore. */
sealed interface ReminderRestoreAction {
    /** Re-arm the alarm. [reminder] is the exact value to hand the scheduler. */
    data class Rearm(val reminder: Reminder) : ReminderRestoreAction

    /**
     * Past-due but still SCHEDULED. The alarm was lost with the process/reboot and
     * the moment has passed, so re-arming is pointless. It is surfaced rather than
     * dropped so the user learns the reminder was missed instead of assuming it
     * fired.
     */
    data class Missed(val reminder: Reminder) : ReminderRestoreAction

    /** FIRED / CANCELLED / DONE, or past-due *and* terminal. Nothing to do. */
    data class Skip(val reminder: Reminder, val reason: String) : ReminderRestoreAction
}

/** Outcome of planning a restore. Split three ways so no case is implicit. */
data class ReminderRestorePlan(
    val rearm: List<Reminder>,
    val missed: List<Reminder>,
    val skipped: List<Reminder>,
) {
    val isEmpty: Boolean get() = rearm.isEmpty() && missed.isEmpty() && skipped.isEmpty()
}

/**
 * Rebuilds the alarm set from the database.
 *
 * The rule that matters: the database is the source of truth, and re-arming is a
 * pure function of (rows, now). That makes reboot reschedule idempotent — a boot
 * broadcast delivered twice, or a user force-stopping and reopening the app,
 * produces byte-identical plans, because nothing about the second call depends on
 * the first having happened.
 */
object ReminderRecovery {

    /**
     * A crash or force-stop can leave a reminder armed for a moment that has
     * already passed. Anything older than this is a miss, not a re-arm: the user is
     * told, rather than receiving a stale notification minutes after launch.
     */
    val DEFAULT_MISSED_AFTER: Duration = Duration.ofMinutes(15)

    /**
     * Per-reminder decision, with the reason kept. Terminal state is checked first
     * on purpose: a CANCELLED reminder must be skipped regardless of its timestamp,
     * or cancelling and then rebooting would resurrect it.
     */
    fun actions(
        reminders: List<Reminder>,
        now: Instant,
        missedAfter: Duration = DEFAULT_MISSED_AFTER,
    ): List<ReminderRestoreAction> = reminders.map { r ->
        when {
            r.state != ReminderState.SCHEDULED ->
                ReminderRestoreAction.Skip(r, "state_${r.state.name.lowercase()}")

            r.remindAt.isAfter(now) ->
                ReminderRestoreAction.Rearm(r)

            Duration.between(r.remindAt, now) > missedAfter ->
                ReminderRestoreAction.Missed(r)

            // Just inside the window. Re-arming is still right: AlarmManager takes
            // an absolute RTC_WAKEUP time, so a trigger a few seconds in the past
            // fires promptly rather than never.
            else -> ReminderRestoreAction.Rearm(r)
        }
    }

    /** Bulk view for the caller that only needs the three lists. */
    fun plan(
        reminders: List<Reminder>,
        now: Instant,
        missedAfter: Duration = DEFAULT_MISSED_AFTER,
    ): ReminderRestorePlan {
        val rearm = mutableListOf<Reminder>()
        val missed = mutableListOf<Reminder>()
        val skipped = mutableListOf<Reminder>()

        for (action in actions(reminders, now, missedAfter)) {
            when (action) {
                is ReminderRestoreAction.Rearm -> rearm += action.reminder
                is ReminderRestoreAction.Missed -> missed += action.reminder
                is ReminderRestoreAction.Skip -> skipped += action.reminder
            }
        }

        return ReminderRestorePlan(
            // Chronological: the caller arms in sequence, and a stable order is part
            // of what makes two boots produce the same alarm set.
            rearm = rearm.sortedBy { it.remindAt },
            missed = missed.sortedBy { it.remindAt },
            skipped = skipped,
        )
    }
}
