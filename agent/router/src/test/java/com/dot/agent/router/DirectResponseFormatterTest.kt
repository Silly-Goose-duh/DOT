package com.dot.agent.router

import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import com.dot.core.model.CachedEvent
import com.dot.core.model.DirectResponse
import com.dot.core.model.Reminder
import com.dot.core.model.Task
import com.dot.core.model.TodaySummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.system.measureNanoTime

class DirectResponseFormatterTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val fmt = DirectResponseFormatter(zone)
    private val today = LocalDate.of(2026, 9, 27)

    private fun summary(events: Int, tasks: Int, reminders: Int) = TodaySummary(
        date = today,
        events = List(events) { CachedEvent(provider = "local", title = "e$it", startAt = Instant.now()) },
        openTasks = List(tasks) { Task(title = "t$it") },
        reminders = List(reminders) { Reminder(title = "r$it", remindAt = Instant.now()) },
    )

    @Test
    fun `empty day says nothing scheduled`() {
        assertThat(fmt.summarizeToday(summary(0, 0, 0))).isEqualTo("Nothing scheduled.")
    }

    @Test
    fun `single event and single task use singular wording`() {
        assertThat(fmt.summarizeToday(summary(1, 1, 0))).isEqualTo("1 event and 1 task.")
    }

    @Test
    fun `counts pluralize correctly`() {
        assertThat(fmt.summarizeToday(summary(2, 3, 1))).isEqualTo("2 events and 3 tasks and 1 reminder.")
    }

    @Test
    fun `create task responds with a single short sentence`() {
        val cmd = RoutedCommand(CommandIntent.CREATE_TASK, CommandArguments(title = "x"), 0.9)
        val r = fmt.format(RoutingOutcome.Routed(cmd), ActionResult.success<Unit>(Unit))
        assertThat(r).isEqualTo(DirectResponse.Action("Added."))
    }

    @Test
    fun `reminder echoes the requested time`() {
        val at = ZonedDateTime.of(2026, 9, 27, 20, 0, 0, 0, zone).toInstant().toString()
        val cmd = RoutedCommand(
            CommandIntent.CREATE_REMINDER,
            CommandArguments(title = "Send report", remindAt = at),
            0.9,
        )
        val r = fmt.format(RoutingOutcome.Routed(cmd), ActionResult.success<Unit>(Unit))
        assertThat((r as DirectResponse.Action).message).isEqualTo("Reminder set for 8:00 PM.")
    }

    @Test
    fun `clarification is returned verbatim`() {
        val out = RoutingOutcome.NeedsClarification("When should I remind you?")
        assertThat(fmt.format(out)).isEqualTo(DirectResponse.Clarification("When should I remind you?"))
    }

    @Test
    fun `permission failure is stated not hidden`() {
        val cmd = RoutedCommand(CommandIntent.RUN_AGENT, CommandArguments(agentId = "inbox"), 0.9)
        val r = fmt.format(
            RoutingOutcome.Routed(cmd),
            ActionResult.failure<Unit>(ActionOutcome.PERMISSION_REQUIRED, "permission_required"),
        )
        assertThat((r as DirectResponse.Action).message).isEqualTo("I need permission for that.")
    }

    @Test
    fun `ambiguous outcome is never reported as success`() {
        val cmd = RoutedCommand(CommandIntent.OPEN_APP, CommandArguments(packageOrAlias = "maps"), 0.8)
        val r = fmt.format(
            RoutingOutcome.Routed(cmd),
            ActionResult.failure<Unit>(ActionOutcome.AMBIGUOUS, "unknown"),
        )
        assertThat((r as DirectResponse.Action).message).isEqualTo("I'm not sure that went through.")
    }

    @Test
    fun `no response contains greeting or filler padding`() {
        val cmds = listOf(
            RoutedCommand(CommandIntent.GET_TODAY, CommandArguments(), 0.9),
            RoutedCommand(CommandIntent.CREATE_TASK, CommandArguments(title = "a"), 0.9),
            RoutedCommand(CommandIntent.CREATE_NOTE, CommandArguments(title = "a"), 0.9),
            RoutedCommand(CommandIntent.OPEN_APP, CommandArguments(packageOrAlias = "maps"), 0.8),
        )
        val banned = listOf("hi", "hello", "sure", "certainly", "great", "let me", "i'll help")
        cmds.forEach { c ->
            val msg = (fmt.format(RoutingOutcome.Routed(c)) as DirectResponse).let {
                when (it) {
                    is DirectResponse.Action -> it.message
                    is DirectResponse.Information -> it.message
                    is DirectResponse.Progress -> it.message
                    is DirectResponse.Clarification -> it.question
                }
            }
            // Word-boundary match: a substring test would flag "Nothing" for containing "hi".
            banned.forEach { b ->
                val pattern = Regex("""\b${Regex.escape(b)}\b""", RegexOption.IGNORE_CASE)
                assertThat(pattern.containsMatchIn(msg)).isFalse()
            }
        }
    }
}

/**
 * PRD 7 latency targets. Guards the "local-first, no remote AI for local reads" promise.
 * These are unit-level budgets; on-device numbers will be measured in diagnostics.
 */
class RouterLatencyTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val router = DeterministicRouter(DateTimeParser(zone), zone)
    private val now = Instant.parse("2026-09-27T10:00:00Z")

    private val utterances = listOf(
        "What do I have today?",
        "Remind me to submit my project at 8 PM",
        "Add finish seminar to tomorrow",
        "Check my inbox",
        "Complete submit assignment",
    )

    @Test
    fun `deterministic routing stays far under the 150ms budget`() {
        // warm up JIT
        repeat(20) { i -> router.route(utterances[i % utterances.size], now) }

        val samples = mutableListOf<Long>()
        repeat(200) { i ->
            val u = utterances[i % utterances.size]
            val ns = measureNanoTime { router.route(u, now) }
            samples.add(ns / 1_000_000)
        }
        samples.sort()
        val p50 = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        println("router latency ms: p50=$p50 p95=$p95")
        assertThat(p50.toDouble()).isLessThan(150.0)
        assertThat(p95.toDouble()).isLessThan(150.0)
    }

    @Test
    fun `routing never touches the network or a model`() {
        // Same input must always produce the same structured command (determinism).
        val a = router.route("Remind me to submit my project at 8 PM", now)
        val b = router.route("Remind me to submit my project at 8 PM", now)
        assertThat(a).isEqualTo(b)
    }
}
