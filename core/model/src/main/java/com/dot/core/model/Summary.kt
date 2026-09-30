package com.dot.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What matters right now on the home screen. Computed locally, never via AI. */
data class TodaySummary(
    val date: LocalDate,
    val events: List<CachedEvent>,
    val openTasks: List<Task>,
    val reminders: List<Reminder>,
) {
    val eventCount: Int get() = events.size
    val openTaskCount: Int get() = openTasks.size
    val reminderCount: Int get() = reminders.size
}

/** Result code taxonomy. Every external action resolves to exactly one of these. */
enum class ActionOutcome {
    SUCCESS,
    RECOVERABLE_FAILURE,
    PERMISSION_REQUIRED,
    AUTH_REQUIRED,
    UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    AMBIGUOUS,
}

data class ActionResult<T>(
    val outcome: ActionOutcome,
    val value: T? = null,
    val message: String? = null,
    val errorCode: String? = null,
) {
    val isSuccess: Boolean get() = outcome == ActionOutcome.SUCCESS

    companion object {
        fun <T> success(value: T, message: String? = null) = ActionResult(ActionOutcome.SUCCESS, value, message)
        fun <T> failure(outcome: ActionOutcome, errorCode: String, message: String? = null) =
            ActionResult<T>(outcome, null, message, errorCode)
    }
}

/** User-facing response classes per PRD 4.1. */
sealed interface DirectResponse {
    /** "Done." / "Added." / "Reminder set for 8 PM." */
    data class Action(val message: String) : DirectResponse

    /** "3 tasks remaining." */
    data class Information(val message: String) : DirectResponse

    /** "Checking." */
    data class Progress(val message: String) : DirectResponse

    /** "Today or tomorrow?" */
    data class Clarification(val question: String) : DirectResponse
}

/** Aggregates today's view from local data only. */
object TodayAggregator {
    fun aggregate(
        zoneId: ZoneId,
        now: Instant,
        allTasks: List<Task>,
        allReminders: List<Reminder>,
        allEvents: List<CachedEvent>,
    ): TodaySummary {
        val today = now.atZone(zoneId).toLocalDate()
        return TodaySummary(
            date = today,
            events = allEvents.filter { it.startAt.atZone(zoneId).toLocalDate() == today }
                .sortedBy { it.startAt },
            openTasks = allTasks.filter { it.isOpen }.sortedWith(
                compareBy<Task> { it.dueAt == null }.thenBy { it.dueAt ?: Instant.MAX },
            ),
            reminders = allReminders.filter { it.state == ReminderState.SCHEDULED }
                .filter { it.remindAt.atZone(zoneId).toLocalDate() == today }
                .sortedBy { it.remindAt },
        )
    }
}
