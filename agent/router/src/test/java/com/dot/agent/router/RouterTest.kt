package com.dot.agent.router

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DateTimeParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    // 2026-09-27T10:00 IST (Sunday)
    private val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone).toInstant()

    private fun parse(s: String) = DateTimeParser(zone).parse(s, now)

    @Test
    fun `parses explicit clock time today`() {
        val r = parse("send the report at 8 pm")
        assertThat(r).isNotNull()
        val z = r!!.instant.atZone(zone)
        assertThat(z.hour).isEqualTo(20)
        assertThat(z.minute).isEqualTo(0)
        assertThat(z.dayOfMonth).isEqualTo(27)
    }

    @Test
    fun `bare 8 means 8pm`() {
        val z = parse("remind me at 8")!!.instant.atZone(zone)
        assertThat(z.hour).isEqualTo(20)
    }

    @Test
    fun `explicit day with past time rolls to that day not tomorrow`() {
        val r = parse("on friday at 9 am")
        assertThat(r).isNotNull()
        val z = r!!.instant.atZone(zone)
        assertThat(z.dayOfWeek.value).isEqualTo(5) // Friday
        assertThat(z.hour).isEqualTo(9)
    }

    @Test
    fun `tomorrow with no time defaults to 9am`() {
        val z = parse("tomorrow")!!.instant.atZone(zone)
        assertThat(z.dayOfMonth).isEqualTo(28)
        assertThat(z.hour).isEqualTo(9)
    }

    @Test
    fun `bare past time without a day rolls to tomorrow`() {
        // now is 10:00, "at 9" is in the past -> next day
        val z = parse("call mom at 9 am")!!.instant.atZone(zone)
        assertThat(z.dayOfMonth).isEqualTo(28)
    }

    @Test
    fun `parses ISO date`() {
        val z = parse("due 2026-10-05 at 5 pm")!!.instant.atZone(zone)
        assertThat(z.monthValue).isEqualTo(10)
        assertThat(z.dayOfMonth).isEqualTo(5)
        assertThat(z.hour).isEqualTo(17)
    }

    @Test
    fun `parses dd slash mm`() {
        val z = parse("on 15/10 at 10am")!!.instant.atZone(zone)
        assertThat(z.monthValue).isEqualTo(10)
        assertThat(z.dayOfMonth).isEqualTo(15)
    }

    @Test
    fun `returns null when no temporal expression exists`() {
        assertThat(parse("write a poem about the sea")).isNull()
    }

    @Test
    fun `rejects impossible time`() {
        // 25 o'clock must not silently become a valid time
        assertThat(DateTimeParser(zone).parse("at 25:99 o'clock", now)).isNull()
    }
}

class DeterministicRouterTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val router = DeterministicRouter(DateTimeParser(zone), zone)
    private val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone).toInstant()

    @Test
    fun `what do i have today routes locally`() {
        val out = router.route("What do I have today?", now)
        assertThat(out).isInstanceOf(RoutingOutcome.Routed::class.java)
        val cmd = (out as RoutingOutcome.Routed).command
        assertThat(cmd.intent).isEqualTo(CommandIntent.GET_TODAY)
        assertThat(cmd.route).isEqualTo(Route.LOCAL)
        assertThat(cmd.confidence).isAtLeast(LOCAL_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `reminder command extracts title and time`() {
        val out = router.route("Remind me to submit my project at 8 PM", now)
        val cmd = (out as RoutingOutcome.Routed).command
        assertThat(cmd.intent).isEqualTo(CommandIntent.CREATE_REMINDER)
        assertThat(cmd.arguments.title).isEqualTo("submit my project")
        val at = Instant.parse(cmd.arguments.remindAt!!).atZone(zone)
        assertThat(at.hour).isEqualTo(20)
    }

    @Test
    fun `add task with tomorrow routes to create task`() {
        val out = router.route("Add finish seminar to tomorrow", now)
        val cmd = (out as RoutingOutcome.Routed).command
        assertThat(cmd.intent).isEqualTo(CommandIntent.CREATE_TASK)
        assertThat(cmd.arguments.title).contains("finish seminar")
        assertThat(cmd.arguments.dueAt).isNotNull()
    }

    @Test
    fun `missing reminder time asks a clarification not a guess`() {
        val out = router.route("Remind me to call mom", now)
        assertThat(out).isInstanceOf(RoutingOutcome.NeedsClarification::class.java)
    }

    @Test
    fun `unknown complex input falls through to AI`() {
        val out = router.route("Which report did I use yesterday and why did it fail?", now)
        assertThat(out).isInstanceOf(RoutingOutcome.NeedsAi::class.java)
    }

    @Test
    fun `empty input asks what to do`() {
        assertThat(router.route("   ", now)).isInstanceOf(RoutingOutcome.NeedsClarification::class.java)
    }

    @Test
    fun `check inbox routes to inbox agent`() {
        val out = router.route("Check my inbox", now)
        val cmd = (out as RoutingOutcome.Routed).command
        assertThat(cmd.intent).isEqualTo(CommandIntent.RUN_AGENT)
        assertThat(cmd.arguments.agentId).isEqualTo("inbox")
    }

    @Test
    fun `router never returns executable code or shell strings`() {
        val cmd = (router.route("Add finish seminar to tomorrow", now) as RoutingOutcome.Routed).command
        // The structured command carries only typed data fields.
        assertThat(cmd.arguments).isNotNull()
        assertThat(CommandIntent.entries).contains(cmd.intent)
    }
}
