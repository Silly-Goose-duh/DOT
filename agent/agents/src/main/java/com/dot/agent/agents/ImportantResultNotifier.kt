package com.dot.agent.agents

import java.time.Instant

/**
 * What counts as worth interrupting the user for.
 *
 * Split from the posting mechanism so the *decision* is a pure function and can be
 * tested without a device. The decision to notify is stricter than the decision to
 * record a run: a successful run that found nothing notable must stay silent.
 */
enum class NotifyDecision {

    /** Post the notification. */
    NOTIFY,

    /** Nothing worth interrupting for; the result stays in the cache. */
    SUPPRESS,

    /**
     * Same as [SUPPRESS], but the run was significant enough that the UI should
     * surface it on next open (PRD 16: "show last-run state").
     */
    RECORD_ONLY,
}

/** Input to the notify decision. Carries no raw message content. */
data class ImportantResult(
    val agentId: String,
    /** One short sentence. Never a body — see [NotificationContent]. */
    val compactResult: String,
    val generatedAt: Instant,
    /** Message ids the agent classified as important, for logging counts only. */
    val importantMessageCount: Int = 0,
)

data class NotificationContent(
    val title: String,
    val body: String,
    /** Stable code for diagnostics, e.g. which agent produced it. */
    val sourceCode: String,
)

sealed interface NotifyResult {
    data class Posted(val content: NotificationContent) : NotifyResult
    data class Suppressed(val reason: String) : NotifyResult
}

interface ImportantResultNotifier {
    suspend fun notifyImportant(result: ImportantResult): NotifyResult
}

/**
 * Builds notification text.
 *
 * Takes only the agent's already-compact summary and the subject line the agent
 * chose to surface. It never accepts a body or a snippet, so no raw email or note
 * text can reach a notification even if a caller passes one in the summary
 * (SECURITY.md: forbidden in logs and by extension in notification text).
 */
object NotificationContentFactory {

    /**
     * Notification bodies are glanceable; anything longer belongs in the app.
     *
     * Sized so that a *sentence* does not fit. PRD 21 forbids a full email body
     * in notification text, and a 75-character "Dear team, here are the
     * confidential figures..." is exactly that — short enough to look harmless,
     * long enough to be a body. At 64 characters what survives is a clause, not
     * prose, and every one-line agent summary still passes through intact.
     */
    const val MAX_BODY_CHARS = 64
    const val TITLE = "DOT"

    fun build(result: ImportantResult): NotificationContent {
        // The first non-blank line is taken from the *raw* string, before
        // sanitisation. Sanitising first would collapse the newlines into
        // spaces, leaving a single line and making this rule unreachable.
        val firstLine = result.compactResult.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val body = sanitize(firstLine).take(MAX_BODY_CHARS)
        return NotificationContent(
            title = TITLE,
            body = body,
            sourceCode = "agent:${hashSource(result.agentId)}",
        )
    }

    /**
     * Collapses whitespace and strips characters outside a fixed allow-list.
     *
     * Order matters: whitespace is normalised *first*, because the character filter
     * would otherwise delete tab and newline outright and silently glue adjacent
     * words together. A zero-width or control character in a summariser's output
     * is also a way to disguise injected content, so it must not survive.
     */
    fun sanitize(value: String): String = value
        .replace(WHITESPACE_RUN, " ")
        .filter { it.isLetterOrDigit() || it in ALLOWED_PUNCTUATION || it == ' ' }
        .trim()

    private val ALLOWED_PUNCTUATION = setOf('.', ',', ':', '\'', '-', '(', ')')
    private val WHITESPACE_RUN = Regex("\\s+")

    private fun hashSource(agentId: String): String = agentId.hashCode().toUInt().toString(16)
}

/**
 * The notify policy, as a pure function.
 *
 * Kept separate from [ImportantResultNotifier] deliberately: an agent that
 * succeeds quietly must be provably quiet, and that is only checkable here.
 */
object NotificationPolicy {

    /** Below this, a run is not worth telling the user about at all. */
    const val IMPORTANT_THRESHOLD = 1

    fun decide(importance: ResultImportance, importantMessageCount: Int): NotifyDecision = when {
        importance != ResultImportance.IMPORTANT -> NotifyDecision.SUPPRESS
        importantMessageCount >= IMPORTANT_THRESHOLD -> NotifyDecision.NOTIFY
        // Flagged important with nothing behind the flag: keep the state, skip the alert.
        else -> NotifyDecision.RECORD_ONLY
    }

    fun isNotificationTextSafe(candidate: String, knownSecretFragments: List<String> = emptyList()): Boolean {
        val text = NotificationContentFactory.sanitize(candidate)
        return text.isNotBlank() && text.length <= NotificationContentFactory.MAX_BODY_CHARS &&
            knownSecretFragments.none { fragment -> fragment.isNotBlank() && text.contains(fragment) }
    }
}