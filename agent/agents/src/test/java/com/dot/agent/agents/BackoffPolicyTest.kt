package com.dot.agent.agents

import com.dot.core.model.ActionOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

/**
 * BackoffPolicy is pure, so every case here is exact rather than approximate.
 * A jitter source that returns 0.5 is the fixed point of the symmetric jitter
 * formula, which is what makes the expected values below readable.
 */
class BackoffPolicyTest {

    /** 0.5 -> offset 0, so delay == baseDelay exactly. */
    private fun policy(
        base: Duration = Duration.ofSeconds(30),
        max: Duration = Duration.ofHours(6),
        maxAttempts: Int = 5,
        multiplier: Double = 2.0,
        jitterRatio: Double = 0.2,
        jitter: () -> Double = { 0.5 },
    ) = BackoffPolicy(base, max, maxAttempts, multiplier, jitterRatio, jitter)

    @Test
    fun `attempt zero is the base delay`() {
        assertThat(policy().baseDelayMsFor(0)).isEqualTo(30_000L)
        assertThat(policy(base = Duration.ofSeconds(5)).baseDelayMsFor(0)).isEqualTo(5_000L)
    }

    @Test
    fun `each attempt doubles`() {
        val p = policy()
        assertThat(p.baseDelayMsFor(0)).isEqualTo(30_000L)
        assertThat(p.baseDelayMsFor(1)).isEqualTo(60_000L)
        assertThat(p.baseDelayMsFor(2)).isEqualTo(120_000L)
        assertThat(p.baseDelayMsFor(3)).isEqualTo(240_000L)
    }

    @Test
    fun `a large attempt index is capped not overflowed`() {
        val p = policy(max = Duration.ofHours(1))
        // 2^30 * 30s is astronomically larger than an hour; the cap must hold and
        // the result must stay positive (a naive toLong() on the raw power overflows
        // into a negative delay).
        assertThat(p.baseDelayMsFor(30)).isEqualTo(Duration.ofHours(1).toMillis())
        assertThat(p.baseDelayMsFor(1_000)).isEqualTo(Duration.ofHours(1).toMillis())
        assertThat(p.baseDelayMsFor(1_000)).isGreaterThan(0L)
    }

    @Test
    fun `a non-doubling multiplier is honoured`() {
        val p = policy(multiplier = 3.0)
        assertThat(p.baseDelayMsFor(1)).isEqualTo(90_000L)
        assertThat(p.baseDelayMsFor(2)).isEqualTo(270_000L)
    }

    @Test
    fun `delay is deterministic for an injected source`() {
        val fixed = policy(jitter = { 0.5 })
        assertThat(fixed.delayFor(2)).isEqualTo(fixed.delayFor(2))

        // Same sequence of draws -> same delays, every time.
        fun sequence() = policy(jitter = { 0.25 }).delayFor(3).toMillis()
        assertThat(sequence()).isEqualTo(sequence())
    }

    @Test
    fun `a different source value yields a different but bounded delay`() {
        val low = policy(jitter = { 0.0 }).delayFor(2)
        val high = policy(jitter = { 1.0 }).delayFor(2)
        assertThat(low).isNotEqualTo(high)
        // -20% and +20% of 120s.
        assertThat(low.toMillis()).isEqualTo(96_000L)
        assertThat(high.toMillis()).isEqualTo(144_000L)
    }

    @Test
    fun `jitter cannot push a delay past the ceiling`() {
        val p = policy(max = Duration.ofMinutes(10), jitter = { 1.0 })
        assertThat(p.delayFor(20).toMillis()).isAtMost(Duration.ofMinutes(10).toMillis())
    }

    @Test
    fun `jitter cannot produce a zero or negative delay`() {
        // 30s at -100% would be zero; the clamp must keep it usable.
        val p = policy(base = Duration.ofMillis(1), jitterRatio = 1.0, jitter = { 0.0 })
        assertThat(p.delayFor(0).toMillis()).isAtLeast(1L)
    }

    @Test
    fun `zero jitter ratio means no jitter at all`() {
        val p = policy(jitterRatio = 0.0, jitter = { 0.9 })
        assertThat(p.delayFor(2)).isEqualTo(Duration.ofMillis(p.baseDelayMsFor(2)))
    }

    @Test
    fun `retryable outcomes requeue with an increasing delay`() {
        val p = policy()
        val first = p.decide(attemptsMade = 0, outcome = ActionOutcome.UNAVAILABLE, errorCode = "x")
        val second = p.decide(attemptsMade = 1, outcome = ActionOutcome.UNAVAILABLE, errorCode = "x")

        assertThat(first).isInstanceOf(BackoffDecision.Retry::class.java)
        assertThat(second).isInstanceOf(BackoffDecision.Retry::class.java)
        assertThat((second as BackoffDecision.Retry).delay)
            .isGreaterThan((first as BackoffDecision.Retry).delay)
        assertThat(second.attemptsMade).isEqualTo(2)
    }

    @Test
    fun `recoverable failure also retries`() {
        val decision = policy().decide(0, ActionOutcome.RECOVERABLE_FAILURE, "x")
        assertThat(decision).isInstanceOf(BackoffDecision.Retry::class.java)
    }

    @Test
    fun `the attempt budget is exhausted and the error code is stable`() {
        val p = policy(maxAttempts = 3)
        assertThat(p.hasAttemptsLeft(2)).isTrue()
        assertThat(p.hasAttemptsLeft(3)).isFalse()

        val decision = p.decide(attemptsMade = 3, outcome = ActionOutcome.UNAVAILABLE, errorCode = "unused")
        assertThat(decision).isInstanceOf(BackoffDecision.GiveUp::class.java)
        assertThat((decision as BackoffDecision.GiveUp).errorCode)
            .isEqualTo(AgentErrorCodes.RETRY_EXHAUSTED)
    }

    @Test
    fun `auth required gives up immediately rather than burning retries`() {
        val decision = policy().decide(0, ActionOutcome.AUTH_REQUIRED, AgentErrorCodes.INBOX_AUTH_REQUIRED)
        assertThat(decision).isInstanceOf(BackoffDecision.GiveUp::class.java)
        // The provider's own code is kept: retrying cannot fix a missing grant.
        assertThat((decision as BackoffDecision.GiveUp).errorCode)
            .isEqualTo(AgentErrorCodes.INBOX_AUTH_REQUIRED)
    }

    @Test
    fun `permission required and unavailable-not-retryable give up`() {
        val p = policy()
        assertThat(p.decide(0, ActionOutcome.PERMISSION_REQUIRED, "perm"))
            .isInstanceOf(BackoffDecision.GiveUp::class.java)
    }

    @Test
    fun `ambiguous is never auto-retried`() {
        // PRD 23: do not resume an action whose effect cannot be observed.
        val decision = policy().decide(0, ActionOutcome.AMBIGUOUS, AgentErrorCodes.AGENT_TIMEOUT)
        assertThat(decision).isInstanceOf(BackoffDecision.GiveUp::class.java)
    }

    @Test
    fun `cancelled is never auto-retried`() {
        val decision = policy().decide(0, ActionOutcome.CANCELLED, AgentErrorCodes.AGENT_CANCELLED)
        assertThat(decision).isInstanceOf(BackoffDecision.GiveUp::class.java)
    }

    @Test
    fun `timeout is not auto-retried because the effect is unobservable`() {
        val decision = policy().decide(0, ActionOutcome.TIMEOUT, "agent_timeout")
        assertThat(decision).isInstanceOf(BackoffDecision.GiveUp::class.java)
    }

    @Test
    fun `next attempt time is derived from a supplied instant`() {
        val now = java.time.Instant.parse("2026-01-01T10:00:00Z")
        val retry = policy().decide(0, ActionOutcome.UNAVAILABLE, "x") as BackoffDecision.Retry
        assertThat(retry.nextAttemptAt(now))
            .isEqualTo(now.plusMillis(30_000L))
    }

    @Test
    fun `an invalid configuration is rejected at construction`() {
        runCatching {
            BackoffPolicy(
                baseDelay = Duration.ZERO,
                maxDelay = Duration.ofHours(1),
                maxAttempts = 3,
                multiplier = 2.0,
                jitterRatio = 0.2,
                jitter = { 0.5 },
            )
        }.also { assertThat(it.isFailure).isTrue() }

        runCatching {
            BackoffPolicy(
                baseDelay = Duration.ofHours(2),
                maxDelay = Duration.ofHours(1),
                maxAttempts = 3,
                multiplier = 2.0,
                jitterRatio = 0.2,
                jitter = { 0.5 },
            )
        }.also { assertThat(it.isFailure).isTrue() }
    }
}