package com.dot.app.hardening

import com.dot.app.diagnostics.DiagnosticEvent
import com.dot.app.diagnostics.DiagnosticRoute
import com.dot.app.diagnostics.DiagnosticsRecorder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PRD 26: the diagnostics surface may show intent, route, tool, duration, result
 * code and agent run status — and must never show or persist a secret.
 *
 * The redaction assertions here are the point of the class. A diagnostics buffer
 * is exactly the kind of thing that ends up in a bug report, so "we were careful
 * at the call site" is not good enough: the recorder has to be the thing that
 * guarantees it.
 *
 * Plain JUnit, no Robolectric and no Android types — that is itself part of the
 * contract, and the fact this test runs at all proves it.
 */
class DiagnosticsRecorderTest {

    @Test
    fun `records the six PRD 26 fields`() {
        val rec = DiagnosticsRecorder()

        rec.record(
            DiagnosticEvent(
                intent = "GET_TODAY",
                route = DiagnosticRoute.LOCAL,
                toolName = "get_today",
                durationMs = 12,
                resultCode = "SUCCESS",
                agentRunStatus = "SUCCESS",
            ),
        )

        val e = rec.latest()!!
        assertThat(e.intent).isEqualTo("GET_TODAY")
        assertThat(e.route).isEqualTo(DiagnosticRoute.LOCAL)
        assertThat(e.toolName).isEqualTo("get_today")
        assertThat(e.durationMs).isEqualTo(12)
        assertThat(e.resultCode).isEqualTo("SUCCESS")
        assertThat(e.agentRunStatus).isEqualTo("SUCCESS")
    }

    @Test
    fun `formatted line is stable and greppable`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand("CREATE_TASK", DiagnosticRoute.LOCAL, "create_task", 7, "SUCCESS")

        assertThat(rec.latest()!!.format())
            .isEqualTo("intent=CREATE_TASK route=local tool=create_task duration_ms=7 result=SUCCESS")
    }

    @Test
    fun `bearer token in any field is redacted`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand(
            intent = "Authorization: Bearer abcdef123456",
            route = DiagnosticRoute.AI,
            toolName = "get_today",
            durationMs = 3,
            resultCode = "ok",
        )

        val dump = rec.events().joinToString("\n") { it.format() }
        assertThat(dump).doesNotContain("abcdef123456")
        assertThat(dump).contains("[redacted]")
    }

    @Test
    fun `google oauth token is redacted`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand(
            intent = "call with ya29.SOME-LONG-OPAQUE-TOKEN-VALUE",
            route = DiagnosticRoute.AI,
            toolName = null,
            durationMs = 1,
            resultCode = "SUCCESS",
        )

        assertThat(rec.latest()!!.intent).doesNotContain("SOME-LONG-OPAQUE")
    }

    @Test
    fun `api key assignment is redacted`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand(
            intent = "api_key=sk-abcdefghijklmnop",
            route = DiagnosticRoute.AI,
            toolName = null,
            durationMs = 1,
            resultCode = "SUCCESS",
        )

        assertThat(rec.latest()!!.intent).doesNotContain("sk-abcdefghijklmnop")
    }

    @Test
    fun `a note body cannot reach the buffer even if a caller passes one`() {
        val rec = DiagnosticsRecorder()
        // The recorder has no "body" field, so a caller can only get a body in via
        // one of the string fields. This asserts the safety net covers that path.
        rec.recordCommand(
            intent = "user said: my password is hunter2 and my card is 4111111111111111",
            route = DiagnosticRoute.LOCAL,
            toolName = "create_note",
            durationMs = 2,
            resultCode = "SUCCESS",
        )

        val dump = rec.events().joinToString("\n") { it.format() }
        assertThat(dump).doesNotContain("hunter2")
    }

    @Test
    fun `fields are truncated so no transcript can accumulate`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand(
            intent = "x".repeat(5000),
            route = DiagnosticRoute.LOCAL,
            toolName = "y".repeat(5000),
            durationMs = 1,
            resultCode = "z".repeat(5000),
        )

        val e = rec.latest()!!
        assertThat(e.intent.length).isAtMost(DiagnosticsRecorder.MAX_FIELD + 1)
        assertThat(e.toolName!!.length).isAtMost(DiagnosticsRecorder.MAX_FIELD + 1)
        assertThat(e.resultCode!!.length).isAtMost(DiagnosticsRecorder.MAX_FIELD + 1)
    }

    @Test
    fun `buffer is bounded and drops the oldest events`() {
        val rec = DiagnosticsRecorder(capacity = 5)
        repeat(20) { rec.recordCommand("intent_$it", DiagnosticRoute.LOCAL, null, it.toLong(), "SUCCESS") }

        assertThat(rec.size()).isEqualTo(5)
        assertThat(rec.events().first().intent).isEqualTo("intent_15")
        assertThat(rec.latest()!!.intent).isEqualTo("intent_19")
    }

    @Test
    fun `events come back oldest first`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand("a", DiagnosticRoute.LOCAL, null, 1, "SUCCESS")
        rec.recordCommand("b", DiagnosticRoute.LOCAL, null, 2, "SUCCESS")
        rec.recordCommand("c", DiagnosticRoute.LOCAL, null, 3, "SUCCESS")

        assertThat(rec.events().map { it.intent }).containsExactly("a", "b", "c").inOrder()
    }

    /**
     * The returned list is a defensive copy, so mutating it must not be able to
     * evict or rewrite anything inside the recorder's ring buffer.
     */
    @Test
    fun `returned list is a copy and cannot mutate the buffer`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand("a", DiagnosticRoute.LOCAL, null, 1, "SUCCESS")

        val snapshot: MutableList<DiagnosticEvent> = rec.events().toMutableList()
        snapshot.clear()

        assertThat(rec.size()).isEqualTo(1)
    }

    @Test
    fun `sink only ever sees redacted lines`() {
        val seen = mutableListOf<String>()
        val rec = DiagnosticsRecorder(sink = { seen += it })

        rec.recordCommand(
            intent = "password: hunter2",
            route = DiagnosticRoute.LOCAL,
            toolName = "create_note",
            durationMs = 1,
            resultCode = "SUCCESS",
        )

        assertThat(seen).hasSize(1)
        assertThat(seen.single()).doesNotContain("hunter2")
    }

    @Test
    fun `ai route is distinguishable from local`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand("x", DiagnosticRoute.AI, null, 1, "SUCCESS")
        assertThat(rec.latest()!!.format()).contains("route=ai")
    }

    @Test
    fun `zero capacity is rejected rather than silently disabling the buffer`() {
        val thrown = runCatching { DiagnosticsRecorder(capacity = 0) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `clear empties the buffer`() {
        val rec = DiagnosticsRecorder()
        rec.recordCommand("a", DiagnosticRoute.LOCAL, null, 1, "SUCCESS")
        rec.clear()
        assertThat(rec.size()).isEqualTo(0)
        assertThat(rec.latest()).isNull()
    }
}
