package com.dot.agent.router

import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import com.dot.core.model.DirectResponse
import com.dot.core.model.TodaySummary
import com.dot.core.model.Task
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PRD 4.1 — default response is one short sentence or compact card.
 * No greetings, filler, or padding.
 */
class DirectResponseFormatter(private val zoneId: ZoneId) {

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    fun format(outcome: RoutingOutcome, result: ActionResult<*>? = null): DirectResponse =
        when (outcome) {
            is RoutingOutcome.NeedsClarification -> DirectResponse.Clarification(outcome.question)
            is RoutingOutcome.NeedsAi ->
                DirectResponse.Clarification("I need more detail on that.")
            is RoutingOutcome.Routed -> formatRouted(outcome.command, result)
        }

    private fun formatRouted(cmd: RoutedCommand, result: ActionResult<*>?): DirectResponse {
        if (result != null && !result.isSuccess) {
            return DirectResponse.Action(failureMessage(result.outcome))
        }
        return when (cmd.intent) {
            CommandIntent.GET_TODAY ->
                DirectResponse.Information(summarizeToday(result?.value as? TodaySummary))
            CommandIntent.CREATE_TASK -> DirectResponse.Action("Added.")
            CommandIntent.CREATE_REMINDER -> DirectResponse.Action(
                cmd.arguments.remindAt?.let {
                    "Reminder set for ${timeFmt.format(java.time.Instant.parse(it).atZone(zoneId))}."
                } ?: "Reminder set.",
            )
            CommandIntent.CREATE_NOTE -> DirectResponse.Action("Note saved.")
            CommandIntent.SEARCH_LOCAL -> {
                val hits = result?.value as? List<*> ?: emptyList<Any>()
                if (hits.isEmpty()) DirectResponse.Information("Nothing found.")
                else DirectResponse.Information("${hits.size} match${if (hits.size == 1) "" else "es"}.")
            }
            CommandIntent.OPEN_APP -> DirectResponse.Action("Opening.")
            CommandIntent.RUN_AGENT -> DirectResponse.Progress("Checking.")
            CommandIntent.LIST_EVENTS -> DirectResponse.Information("No upcoming events.")
            CommandIntent.COMPLETE_TASK ->
                if (result?.value == null) DirectResponse.Information("No matching task.")
                else DirectResponse.Action("Done.")
            CommandIntent.UNKNOWN_COMPLEX ->
                DirectResponse.Clarification("I need more detail on that.")
        }
    }

    fun summarizeToday(summary: TodaySummary?): String {
        if (summary == null) return "Nothing scheduled."
        val parts = buildList {
            if (summary.eventCount > 0) {
                add("${summary.eventCount} event${if (summary.eventCount == 1) "" else "s"}")
            }
            if (summary.openTaskCount > 0) {
                add("${summary.openTaskCount} task${if (summary.openTaskCount == 1) "" else "s"}")
            }
            if (summary.reminderCount > 0) {
                add("${summary.reminderCount} reminder${if (summary.reminderCount == 1) "" else "s"}")
            }
        }
        return if (parts.isEmpty()) "Nothing scheduled." else parts.joinToString(" and ") + "."
    }

    private fun failureMessage(outcome: ActionOutcome): String = when (outcome) {
        ActionOutcome.SUCCESS -> "Done."
        ActionOutcome.RECOVERABLE_FAILURE -> "That didn't work. Try again?"
        ActionOutcome.PERMISSION_REQUIRED -> "I need permission for that."
        ActionOutcome.AUTH_REQUIRED -> "Connect your account first."
        ActionOutcome.UNAVAILABLE -> "That's not available right now."
        ActionOutcome.TIMEOUT -> "That took too long."
        ActionOutcome.CANCELLED -> "Cancelled."
        ActionOutcome.AMBIGUOUS -> "I'm not sure that went through."
    }
}
