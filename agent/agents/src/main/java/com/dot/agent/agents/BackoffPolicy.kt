package com.dot.agent.agents

import com.dot.core.model.ActionOutcome
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with a hard ceiling and a bounded attempt count.
 *
 * Pure and deterministic by construction: the only inputs are the attempt number
 * and the injected [jitter] source. It never reads a clock and never touches
 * `Random` directly, so the same attempt number always produces the same delay
 * for a given source — which is what makes the retry contract testable at all.
 */
class BackoffPolicy(
    /** Delay before the first retry. */
    val baseDelay: Duration = Duration.ofSeconds(30),
    /** Ceiling applied after exponentiation, before jitter. */
    val maxDelay: Duration = Duration.ofHours(6),
    /** Total attempts allowed, i.e. the initial try plus [maxAttempts] - 1 retries. */
    val maxAttempts: Int = 5,
    /** Growth per attempt. 2.0 is the conventional doubling. */
    val multiplier: Double = 2.0,
    /**
     * Symmetric jitter fraction: 0.2 spreads each delay across +/-20%. Exists to
     * stop every device retrying an unavailable provider on the same second; the
     * value is injected so tests pin it.
     */
    val jitterRatio: Double = 0.2,
    /** Returns a value in [0,1). Implementations must be side-effect free. */
    private val jitter: () -> Double,
) {

    init {
        require(!baseDelay.isNegative && !baseDelay.isZero) { "baseDelay must be positive" }
        require(maxDelay >= baseDelay) { "maxDelay must be >= baseDelay" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be within 0..1" }
    }

    /**
     * Unexponentiated, uncapped? No — capped, un-jittered delay for [attempt].
     * Attempt 0 is the delay before the *first* retry, i.e. it already assumes the
     * initial try failed. This is the value asserted in tests, so jitter never
     * hides an off-by-one in the exponent.
     */
    fun baseDelayMsFor(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0" }
        val scaled = baseDelay.toMillis().toDouble() * multiplier.pow(attempt)
        // min() before the toLong() so a huge attempt number cannot overflow to a
        // negative delay.
        return min(scaled, maxDelay.toMillis().toDouble()).toLong()
    }

    /** Jittered delay actually handed to the scheduler. */
    fun delayFor(attempt: Int): Duration {
        val base = baseDelayMsFor(attempt)
        if (jitterRatio == 0.0) return Duration.ofMillis(base)
        val offset = (jitter().coerceIn(0.0, 1.0) * 2.0 - 1.0) * jitterRatio
        val jittered = (base * (1.0 + offset)).toLong()
        // Jitter may push slightly past the ceiling or below zero; clamp both ends
        // so a caller can never be handed a negative delay.
        return Duration.ofMillis(jittered.coerceIn(1L, maxDelay.toMillis()))
    }

    fun hasAttemptsLeft(attemptsMade: Int): Boolean = attemptsMade < maxAttempts

    /**
     * Decides retry vs. give-up for one failure.
     *
     * Retry is offered only for outcomes that are safe to repeat. AMBIGUOUS and
     * CANCELLED are deliberately excluded: PRD 23 requires that we not silently
     * resume a consequential action whose effect we cannot observe, and a repeat
     * of an ambiguous effect is exactly that. AUTH_REQUIRED and PERMISSION_REQUIRED
     * are excluded because retrying cannot fix a missing grant.
     */
    fun decide(attemptsMade: Int, outcome: ActionOutcome, errorCode: String): BackoffDecision {
        if (!hasAttemptsLeft(attemptsMade)) {
            return BackoffDecision.GiveUp(AgentErrorCodes.RETRY_EXHAUSTED, attemptsMade)
        }
        if (outcome !in RETRYABLE_OUTCOMES) {
            return BackoffDecision.GiveUp(errorCode, attemptsMade)
        }
        return BackoffDecision.Retry(delayFor(attemptsMade), attemptsMade + 1)
    }

    private companion object {
        val RETRYABLE_OUTCOMES = setOf(ActionOutcome.RECOVERABLE_FAILURE, ActionOutcome.UNAVAILABLE)
    }
}

sealed interface BackoffDecision {

    /** Requeue after [delay]; [attemptsMade] is the running total after this failure. */
    data class Retry(val delay: Duration, val attemptsMade: Int) : BackoffDecision

    /** Stop and persist [errorCode]. No further attempt is made automatically. */
    data class GiveUp(val errorCode: String, val attemptsMade: Int) : BackoffDecision
}

/**
 * Durable retry counter, so a process death between two attempts cannot reset the
 * budget and turn bounded retries into an infinite loop (SECURITY.md rule 14).
 *
 * Backed by [MemoryRepository]'s RECENT_TOOL_RESULTS namespace: PRD 12 classes
 * retry state as temporary cache, and the 15-minute TTL means a counter for an
 * agent nobody has touched in a while is discarded rather than remembered forever.
 */
interface AgentRetryStateStore {
    suspend fun attemptsMade(agentId: String): Int
    suspend fun recordFailure(agentId: String): Int
    suspend fun clear(agentId: String)
}

/** In-memory store. Correct within one process; use [MemoryAgentRetryStateStore] to survive death. */
class InMemoryAgentRetryStateStore : AgentRetryStateStore {

    private val counts = mutableMapOf<String, Int>()

    override suspend fun attemptsMade(agentId: String): Int = counts[agentId] ?: 0

    override suspend fun recordFailure(agentId: String): Int {
        val next = (counts[agentId] ?: 0) + 1
        counts[agentId] = next
        return next
    }

    override suspend fun clear(agentId: String) {
        counts.remove(agentId)
    }
}

fun retryStateKey(agentId: String): String = "agent_retry_state:$agentId"

/** Reads/writes the counter through the expiring cache. Never stores anything sensitive. */
class MemoryAgentRetryStateStore(
    private val attempts: suspend (String) -> Int,
    private val write: suspend (String, Int) -> Unit,
    private val drop: suspend (String) -> Unit,
) : AgentRetryStateStore {

    override suspend fun attemptsMade(agentId: String): Int = runCatching { attempts(agentId) }
        .getOrDefault(0)
        .coerceAtLeast(0)

    override suspend fun recordFailure(agentId: String): Int {
        val next = attemptsMade(agentId) + 1
        runCatching { write(agentId, next) }
        return next
    }

    override suspend fun clear(agentId: String) {
        runCatching { drop(agentId) }
    }
}

/** Convenience for callers that already know the current time. */
fun BackoffDecision.nextAttemptAt(from: Instant): Instant? =
    (this as? BackoffDecision.Retry)?.let { from.plusMillis(it.delay.toMillis()) }