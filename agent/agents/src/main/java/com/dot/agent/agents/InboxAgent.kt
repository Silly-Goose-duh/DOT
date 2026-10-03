package com.dot.agent.agents

import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import java.time.Instant

/**
 * The reference specialized agent (PRD 16).
 *
 * Responsibilities, and nothing beyond them:
 *   fetch new message metadata -> classify/summarise into ONE sentence ->
 *   write a compact cache entry -> optionally notify.
 *
 * Explicitly absent, per PRD 16 "must not do in v0.1": sending, deleting,
 * archiving, or otherwise altering mailbox state. There is no code path here that
 * writes to the provider, and no tool is reachable from this agent — [summariser]
 * returns a string and nothing else.
 */
class InboxAgent(
    override val agentType: String = AgentTypes.INBOX,
    private val provider: InboxProviderPort,
    private val summariser: InboxSummariser,
    private val cache: InboxSummaryCache,
    /** Consulted before fetching so a dead grant costs no network call. */
    private val tokenStore: OAuthTokenStore? = null,
    private val accountId: String = OAuthTokenStore.DEFAULT_ACCOUNT,
    /** Maximum messages pulled per run. Bounds both the request and the summary. */
    private val fetchLimit: Int = DEFAULT_FETCH_LIMIT,
    private val notifier: ImportantResultNotifier? = null,
) : AgentTask {

    override suspend fun execute(context: AgentRunContext): ActionResult<AgentTaskResult> {
        // Local grant check first. A missing or expired credential must surface as
        // AUTH_REQUIRED before any network work, not as an empty successful fetch.
        tokenStore?.let { store ->
            when (val grant = store.accessToken(accountId, context.now)) {
                is OAuthTokenResult.NotConnected ->
                    return ActionResult.failure(
                        ActionOutcome.AUTH_REQUIRED,
                        AgentErrorCodes.INBOX_AUTH_REQUIRED,
                    )
                is OAuthTokenResult.AuthRequired ->
                    return ActionResult.failure(
                        ActionOutcome.AUTH_REQUIRED,
                        AgentErrorCodes.INBOX_AUTH_REQUIRED,
                    )
                is OAuthTokenResult.Unavailable ->
                    return ActionResult.failure(
                        ActionOutcome.UNAVAILABLE,
                        AgentErrorCodes.INBOX_UNAVAILABLE,
                    )
                is OAuthTokenResult.Valid -> Unit // proceed
            }
        }

        val since = cache.latest(context.now)?.let { runCatching { Instant.parse(it.generatedAtIso) }.getOrNull() }
        val messages = when (val fetch = fetchMessages(since)) {
            is InboxFetchResult.Success -> fetch.messages

            is InboxFetchResult.NotConnected -> return ActionResult.failure(
                ActionOutcome.AUTH_REQUIRED,
                AgentErrorCodes.INBOX_AUTH_REQUIRED,
            )

            is InboxFetchResult.Revoked -> return ActionResult.failure(
                ActionOutcome.AUTH_REQUIRED,
                AgentErrorCodes.INBOX_AUTH_REQUIRED,
            )

            is InboxFetchResult.Unavailable -> return ActionResult.failure(
                ActionOutcome.UNAVAILABLE,
                AgentErrorCodes.INBOX_UNAVAILABLE,
            )

            is InboxFetchResult.Malformed -> return ActionResult.failure(
                ActionOutcome.RECOVERABLE_FAILURE,
                AgentErrorCodes.INBOX_MALFORMED_RESPONSE,
            )

            is InboxFetchResult.Throttled -> return ActionResult.failure(
                ActionOutcome.RECOVERABLE_FAILURE,
                AgentErrorCodes.INBOX_UNAVAILABLE,
            )
        }

        val summarisation = summariser.summarise(messages, context.now)
        // Bind the payload once: `ActionResult.value` is a public API property from
        // another module, so Kotlin cannot smart-cast it and every later use of the
        // summary would still be nullable. A summariser that produced no sentence is
        // a failure, not an empty success.
        val headline = summarisation.value
        if (!summarisation.isSuccess || headline == null) {
            return ActionResult.failure(
                summarisation.outcome,
                AgentErrorCodes.SUMMARISER_UNAVAILABLE,
            )
        }

        val important = messages.count { isImportant(it) }

        // Only the one-line summary and opaque ids are persisted. No subject, no
        // snippet, no body (PRD 12 / SECURITY.md rule 9).
        cache.put(
            InboxSummary(
                headline = headline,
                importantMessageIds = messages.take(MAX_CACHED_IDS).map { it.id },
                totalNew = messages.size,
                generatedAtIso = context.now.toString(),
                providerId = provider.id,
            ),
        )

        val decision = NotificationPolicy.decide(
            importance = if (important > 0) ResultImportance.IMPORTANT else ResultImportance.NOT_IMPORTANT,
            importantMessageCount = important,
        )
        if (decision == NotifyDecision.NOTIFY) {
            notifier?.notifyImportant(
                ImportantResult(
                    agentId = context.definition.id,
                    compactResult = headline,
                    generatedAt = context.now,
                    importantMessageCount = important,
                ),
            )
        }

        return ActionResult.success(
            AgentTaskResult(
                compactResult = headline,
                importance = if (decision == NotifyDecision.NOTIFY) {
                    ResultImportance.IMPORTANT
                } else {
                    ResultImportance.NOT_IMPORTANT
                },
            ),
        )
    }

    /**
     * Fetches, containing a provider that breaks its own port contract.
     *
     * [InboxProviderPort] requires every failure to be a typed result. A provider that throws
     * anyway is a network fault, and it is retryable — but if the exception escapes to the runner
     * it is classified as an unknown `agent_io_error`, which the runner records as a hard failure
     * and never requeues. Catching here converts "the provider is broken" into the typed
     * `UNAVAILABLE` the rest of this function already handles, so a transient network blip gets
     * retried with backoff instead of ending the run.
     *
     * Cancellation is deliberately not caught: it is control flow, not a fault, and swallowing it
     * would break structured concurrency.
     */
    private suspend fun fetchMessages(since: Instant?): InboxFetchResult = try {
        provider.fetchRecent(since, fetchLimit)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (t: Exception) {
        InboxFetchResult.Unavailable("provider_threw")
    }

    /**
     * Shared classification so the agent and the summariser cannot disagree about
     * what counted as important.
     */
    private val localClassifier = LocalInboxSummariser()

    private fun isImportant(message: InboxMessage) = localClassifier.isImportant(message)

    /** Explicit disconnect: removes the local credential, per PRD 21. */
    suspend fun disconnect() {
        provider.disconnect()
        tokenStore?.revoke(accountId)
        cache.invalidate()
    }

    private companion object {
        const val DEFAULT_FETCH_LIMIT = 20

        /** Ids only, and bounded: the cache is a pointer to "what changed", not an archive. */
        const val MAX_CACHED_IDS = 50
    }
}