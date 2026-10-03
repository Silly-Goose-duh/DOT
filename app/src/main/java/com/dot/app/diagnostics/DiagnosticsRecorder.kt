package com.dot.app.diagnostics

import com.dot.core.common.LogRedactor

/**
 * PRD 26 permits a local debug diagnostics surface showing exactly six things:
 * command intent, route chosen (local vs AI), tool invoked, duration, result
 * code, and agent run status. Everything else is out of scope by construction —
 * this type has no field a caller could stuff a note body, a transcript or a
 * token into and have it reach the buffer.
 *
 * [intent], [tool] and [resultCode] are still redacted on the way in, because a
 * tool name or result code is a string the rest of the app controls and the
 * redaction must not depend on every future caller remembering to sanitise.
 */

/** Where a command was answered from. PRD 26 wording. */
enum class DiagnosticRoute { LOCAL, AI, NONE }

/**
 * One diagnostics record. Every field is either an enum, a number, or a string
 * that has been through [LogRedactor].
 */
data class DiagnosticEvent(
    val intent: String,
    val route: DiagnosticRoute,
    val toolName: String? = null,
    val durationMs: Long? = null,
    val resultCode: String? = null,
    val agentRunStatus: String? = null,
) {
    /**
     * Stable, greppable, one line. Field names mirror DotLogEvent so a log line
     * and a diagnostics row read the same way.
     */
    fun format(): String = buildString {
        append("intent=").append(intent)
        append(" route=").append(route.name.lowercase())
        toolName?.let { append(" tool=").append(it) }
        durationMs?.let { append(" duration_ms=").append(it) }
        resultCode?.let { append(" result=").append(it) }
        agentRunStatus?.let { append(" agent_run=").append(it) }
    }
}

/**
 * Conversational credential shapes [LogRedactor]'s patterns cannot see.
 *
 * [LogRedactor] matches the *assignment* forms a caller writes — `password: x`,
 * `api_key=x`, `Bearer y`. A caller that forwards spoken user text produces none
 * of those separators: "my password is hunter2" and "my card is 4111..." reach
 * [DiagnosticsRecorder.recordCommand]'s `intent` intact, and the buffer then
 * echoes the credential verbatim into a surface that ends up in bug reports.
 *
 * These patterns exist so the recorder stays safe even when a caller forgets
 * that `intent` is supposed to be a label. They redact the credential and keep
 * the surrounding sentence, because "my [redacted]" is still diagnostic while
 * the secret itself is not.
 */
private val SPEECH_CREDENTIAL_PATTERNS = listOf(
    // "my password is hunter2", "the card is 4111...", "pin: 1234"
    Regex(
        """(?i)\b(password|passcode|passphrase|secret|pin|cvv|cvc|card|otp|api[_ ]?key)""" +
            """\b\s*(?:is|was|are|:|=|to)\s+\S+""",
    ),
    // 12-20 digits in a row, optionally grouped: a payment card number. Matches
    // only long runs, so short ids like "intent_15" are untouched.
    Regex("""(?<!\d)\d{4}(?:[ -]?\d{4}){2,4}(?!\d)"""),
)

/**
 * Bounded, in-memory, redacting recorder. Deliberately not a singleton and
 * deliberately not backed by disk: diagnostics are a debug affordance, and a
 * process that dies takes them with it, which is the correct behaviour for data
 * derived from user content.
 *
 * No Android types, so it is unit-testable on a plain JVM.
 */
class DiagnosticsRecorder(
    /** Oldest events are dropped once this many are buffered. */
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Optional sink for logcat/debug builds. Receives already-redacted lines. */
    private val sink: ((String) -> Unit)? = null,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // ArrayDeque as a ring: addLast/removeFirst gives O(1) evict-oldest.
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)

    /**
     * Records one event. All free text passes through [scrub] first.
     *
     * Scrubbing happens here rather than at the call sites on purpose: a
     * diagnostics buffer is exactly the kind of thing that ships inside a bug
     * report, so "the caller was careful" is not a guarantee strong enough to
     * rest a secret on.
     */
    fun record(event: DiagnosticEvent): DiagnosticEvent {
        val safe = event.copy(
            intent = scrub(event.intent),
            toolName = event.toolName?.let { scrub(it) },
            resultCode = event.resultCode?.let { scrub(it) },
            agentRunStatus = event.agentRunStatus?.let { scrub(it) },
        )
        buffer.addLast(safe)
        while (buffer.size > capacity) buffer.removeFirst()
        sink?.invoke(safe.format())
        return safe
    }

    /**
     * Redacts, then bounds, a single free-text field.
     *
     * Order matters: redaction runs on the *whole* value first. Truncating
     * first could cut a credential down to something that no longer matches a
     * pattern, which would let the surviving fragment through.
     */
    private fun scrub(value: String): String {
        var out = value
        for (pattern in SPEECH_CREDENTIAL_PATTERNS) out = pattern.replace(out, LogRedactor.REDACTED)
        return LogRedactor.safeValue(out, MAX_FIELD)
    }

    /** Convenience for the common "one command, start to finish" case. */
    fun recordCommand(
        intent: String,
        route: DiagnosticRoute,
        toolName: String?,
        durationMs: Long,
        resultCode: String,
    ): DiagnosticEvent = record(
        DiagnosticEvent(
            intent = intent,
            route = route,
            toolName = toolName,
            durationMs = durationMs,
            resultCode = resultCode,
        ),
    )

    /** Oldest first. Defensive copy: callers must not mutate the buffer. */
    fun events(): List<DiagnosticEvent> = buffer.toList()

    fun latest(): DiagnosticEvent? = buffer.lastOrNull()

    fun size(): Int = buffer.size

    fun clear() = buffer.clear()

    companion object {
        /**
         * 200 is roughly one screen of scrolling. Large enough to debug a session,
         * small enough that nothing resembling a transcript can accumulate.
         */
        const val DEFAULT_CAPACITY = 200

        /** Field cap. LogRedactor also truncates; this is the outer bound. */
        const val MAX_FIELD = 60
    }
}
