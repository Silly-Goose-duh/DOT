package com.dot.agent.agents

import java.time.Instant

/**
 * Minimal metadata about one message.
 *
 * There is deliberately no `body` field. PRD 12 forbids silent permanent storage
 * of full email bodies, and the cheapest way to honour that is for the type the
 * agent layer handles to have no way to carry one at all. [snippet] is a
 * provider-supplied short extract, already truncated upstream, and is treated as
 * untrusted data by every consumer.
 */
data class InboxMessage(
    val id: String,
    val from: String,
    val subject: String,
    val receivedAt: Instant,
    /** Short, provider-truncated preview. Untrusted text. Never stored verbatim. */
    val snippet: String? = null,
)

/**
 * Typed failures a provider must return instead of throwing.
 *
 * A revoked or expired grant is [Revoked]; a missing one is [NotConnected]. They
 * are separate codes because the user's next action differs: reconnect vs. connect.
 */
sealed interface InboxFetchResult {

    data class Success(val messages: List<InboxMessage>) : InboxFetchResult

    /** No grant stored locally. */
    data object NotConnected : InboxFetchResult

    /** Grant exists but is expired, revoked, or was rejected by the provider. */
    data class Revoked(val reason: String) : InboxFetchResult

    /** Network/transport failure, or the device is offline. */
    data class Unavailable(val reason: String) : InboxFetchResult

    /** Provider replied with something we cannot parse into messages. */
    data class Malformed(val reason: String) : InboxFetchResult

    /** Provider-side throttling. Retryable with backoff. */
    data class Throttled(val reason: String) : InboxFetchResult
}

/**
 * The replaceable seam for inbox access (PRD 31: provider abstractions must
 * prevent lock-in).
 *
 * Implementations own authentication, transport and provider-specific paging.
 * They must not summarise, classify, cache or persist: the agent does that, so
 * the policy around *what DOT retains* stays in one place.
 */
interface InboxProviderPort {

    /** Stable identifier for diagnostics. Never contains a secret or an account address. */
    val id: String

    /** Whether a usable grant is stored. Cheap, local, no network. */
    fun isConnected(): Boolean

    /**
     * Fetches messages received strictly after [since], newest first, capped at
     * [limit]. Implementations must apply their own timeout and must never throw
     * for an expected condition — every failure is one of the typed results.
     */
    suspend fun fetchRecent(since: Instant?, limit: Int): InboxFetchResult

    /** Removes locally retained credentials. Does not revoke server-side. */
    suspend fun disconnect()
}