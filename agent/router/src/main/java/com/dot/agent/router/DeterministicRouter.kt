package com.dot.agent.router

import java.time.Instant
import java.time.ZoneId

/**
 * Tier 1/2 of ARCHITECTURE.md: deterministic local intent + structured parsing.
 * Never calls a model. Target < 150 ms (measured in microseconds here).
 */
class DeterministicRouter(
    private val parser: DateTimeParser,
    private val zoneId: ZoneId,
) {

    fun route(utterance: String, now: Instant = Instant.now()): RoutingOutcome {
        val raw = utterance.trim()
        if (raw.isEmpty()) return RoutingOutcome.NeedsClarification("What should I do?")
        val text = raw.lowercase()

        return when {
            matches(text, "what.*(today|do i have|my day|schedule|upcoming)") ->
                local(CommandIntent.GET_TODAY, confidence = 0.95)

            matches(text, "remind me") || matches(text, "remind.*to") || matches(text, "set (a )?reminder") -> {
                val parsed = parser.parse(raw, now)
                val title = extractTitle(raw, listOf("remind me", "remind me to", "remind", "set a reminder", "set reminder to", "set reminder"))
                if (parsed == null) {
                    RoutingOutcome.NeedsClarification("When should I remind you?")
                } else if (title.isBlank()) {
                    RoutingOutcome.NeedsClarification("What should the reminder say?")
                } else {
                    local(
                        CommandIntent.CREATE_REMINDER,
                        args = CommandArguments(title = title, remindAt = parsed.instant.toString()),
                        confidence = 0.9,
                    )
                }
            }

            matches(text, "complete") || matches(text, "done with") || matches(text, "mark .* (as )?(done|complete)") -> {
                val ref = extractAfter(raw, listOf("complete", "mark", "done with", "finish")) ?: raw
                if (ref.isBlank()) RoutingOutcome.NeedsClarification("Which task?")
                else local(CommandIntent.COMPLETE_TASK, args = CommandArguments(taskRef = ref), confidence = 0.85)
            }

            matches(text, "add ") || matches(text, "create task") || matches(text, "new task") -> {
                val title = extractTitle(raw, listOf("add", "create task", "new task", "create a task", "add a task"))
                if (title.isBlank()) RoutingOutcome.NeedsClarification("What should I add?")
                else {
                    val parsed = parser.parse(raw, now)
                    local(
                        CommandIntent.CREATE_TASK,
                        args = CommandArguments(title = title, dueAt = parsed?.instant?.toString()),
                        confidence = if (parsed != null) 0.92 else 0.8,
                    )
                }
            }

            matches(text, "note") || matches(text, "jot") -> {
                val title = extractTitle(raw, listOf("take a note", "note", "jot down", "jot"))
                if (title.isBlank()) RoutingOutcome.NeedsClarification("What should the note say?")
                else local(CommandIntent.CREATE_NOTE, args = CommandArguments(title = title), confidence = 0.85)
            }

            // "which report" is only a local search when it stays a short lookup;
            // a multi-clause question ("...and why did it fail?") is genuine reasoning.
            matches(text, "search") || matches(text, "find") ||
                (matches(text, "which report") && !text.contains(" and ")) -> {
                val q = extractAfter(raw, listOf("search for", "search", "find", "look up", "which"))
                    ?.trim('?', ' ')
                if (q.isNullOrBlank()) RoutingOutcome.NeedsClarification("What should I search for?")
                else local(CommandIntent.SEARCH_LOCAL, args = CommandArguments(query = q), confidence = 0.82)
            }

            matches(text, "open ") || matches(text, "launch ") -> {
                val app = extractAfter(raw, listOf("open", "launch", "start"))?.trim()
                if (app.isNullOrBlank()) RoutingOutcome.NeedsClarification("Which app?")
                else local(CommandIntent.OPEN_APP, args = CommandArguments(packageOrAlias = app), confidence = 0.8)
            }

            matches(text, "run .*agent") || matches(text, "check my inbox") || matches(text, "check inbox") ->
                local(CommandIntent.RUN_AGENT, args = CommandArguments(agentId = "inbox"), confidence = 0.88)

            matches(text, "events") || matches(text, "calendar") || matches(text, "meetings") ->
                local(CommandIntent.LIST_EVENTS, confidence = 0.85)

            else -> RoutingOutcome.NeedsAi(raw)
        }
    }

    private fun local(
        intent: CommandIntent,
        args: CommandArguments = CommandArguments(),
        confidence: Double,
    ) = RoutingOutcome.Routed(
        RoutedCommand(intent = intent, arguments = args, confidence = confidence, route = Route.LOCAL),
    )

    private fun matches(text: String, vararg patterns: String): Boolean =
        patterns.any { Regex(it).containsMatchIn(text) }

    /** Strips leading command verbs and time expressions to get a clean title. */
    private fun extractTitle(raw: String, prefixes: List<String>): String {
        var s = raw.trim()
        for (p in prefixes.sortedByDescending { it.length }) {
            s = s.replaceFirst(Regex("^\\s*${Regex.escape(p)}\\b", RegexOption.IGNORE_CASE), "")
        }
        // drop trailing time expressions
        s = s.replace(
            Regex(
                """\b(at|on|by|tomorrow|today|tonight|yesterday)\b.*$""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trim()
        s = s.replace(
            Regex("""\b\d{1,2}(:\d{2})?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        return s.trim(' ', ',', '-').trim()
    }

    private fun extractAfter(raw: String, markers: List<String>): String? {
        val lower = raw.lowercase()
        for (m in markers.sortedByDescending { it.length }) {
            val idx = lower.indexOf(m)
            if (idx >= 0) return raw.substring(idx + m.length)
        }
        return null
    }
}
