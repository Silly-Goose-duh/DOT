package com.dot.agent.agents

import com.dot.agent.memory.MemoryNamespaces
import com.dot.agent.memory.MemoryRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class InboxSummaryCacheTest {

    private lateinit var store: FakeMemoryStore
    private lateinit var memory: MemoryRepository

    private val writtenAt: Instant = Instant.parse("2026-01-01T10:00:00Z")

    @Before
    fun setUp() {
        store = FakeMemoryStore()
        memory = MemoryRepository(store)
    }

    private fun cacheAt(now: Instant) = InboxSummaryCache(memory, Clock.fixed(now, ZoneOffset.UTC))

    private fun summary(at: Instant = writtenAt, headline: String = "2 new messages.") = InboxSummary(
        headline = headline,
        importantMessageIds = listOf("m1", "m2"),
        totalNew = 2,
        generatedAtIso = at.toString(),
        providerId = "fake",
    )

    @Test
    fun `a written summary reads back`() = runTest {
        val cache = cacheAt(writtenAt)

        cache.put(summary())
        val loaded = cache.latest()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.headline).isEqualTo("2 new messages.")
        assertThat(loaded.totalNew).isEqualTo(2)
        assertThat(loaded.importantMessageIds).containsExactly("m1", "m2").inOrder()
    }

    @Test
    fun `an absent cache reads as null not an error`() = runTest {
        assertThat(cacheAt(writtenAt).latest()).isNull()
    }

    @Test
    fun `the entry is written to the inbox summary namespace`() = runTest {
        cacheAt(writtenAt).put(summary())

        // The namespace is what gives it the 24h TTL and makes it user-clearable.
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).hasSize(1)
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY).single().namespace)
            .isEqualTo(MemoryNamespaces.INBOX_SUMMARY)
    }

    @Test
    fun `the entry carries an explicit expiry one day out`() = runTest {
        cacheAt(writtenAt).put(summary())

        val row = store.snapshot(MemoryNamespaces.INBOX_SUMMARY).single()
        assertThat(row.expiresAt).isNotNull()
        assertThat(row.expiresAt).isEqualTo(writtenAt.plus(Duration.ofHours(24)))
    }

    @Test
    fun `nothing is stored without an expiry`() = runTest {
        cacheAt(writtenAt).put(summary())

        // PRD 12: every cache category needs a TTL. A null expiry here would rot.
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY).single().expiresAt).isNotNull()
    }

    @Test
    fun `a summary is still served just before expiry`() = runTest {
        cacheAt(writtenAt).put(summary())

        val beforeExpiry = cacheAt(writtenAt.plus(Duration.ofHours(23)))

        assertThat(beforeExpiry.latest()).isNotNull()
    }

    @Test
    fun `an expired summary is not served even before a purge runs`() = runTest {
        cacheAt(writtenAt).put(summary())

        val afterExpiry = cacheAt(writtenAt.plus(Duration.ofHours(25)))

        // Read-path TTL check, independent of whether purgeExpired has been called.
        assertThat(afterExpiry.latest()).isNull()
    }

    @Test
    fun `purge removes the expired entry`() = runTest {
        cacheAt(writtenAt).put(summary())
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).hasSize(1)

        val purged = cacheAt(writtenAt.plus(Duration.ofHours(25))).purgeExpired()

        assertThat(purged).isEqualTo(1)
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).isEmpty()
    }

    @Test
    fun `purge leaves an unexpired entry alone`() = runTest {
        cacheAt(writtenAt).put(summary())

        val purged = cacheAt(writtenAt.plus(Duration.ofHours(1))).purgeExpired()

        assertThat(purged).isEqualTo(0)
        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).hasSize(1)
    }

    @Test
    fun `purge at exactly the expiry instant removes the entry`() = runTest {
        cacheAt(writtenAt).put(summary())

        val purged = cacheAt(writtenAt.plus(Duration.ofHours(24))).purgeExpired()

        // Expiring "now" means already expired, matching the Room DAO's inclusive bound.
        assertThat(purged).isEqualTo(1)
    }

    @Test
    fun `invalidate makes the entry unreadable immediately`() = runTest {
        val cache = cacheAt(writtenAt)
        cache.put(summary())

        cache.invalidate()

        assertThat(cache.latest()).isNull()
    }

    @Test
    fun `clear removes the whole namespace`() = runTest {
        cacheAt(writtenAt).put(summary())

        cacheAt(writtenAt).clear()

        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).isEmpty()
    }

    @Test
    fun `a second write replaces the summary rather than accumulating`() = runTest {
        cacheAt(writtenAt).put(summary(headline = "first"))
        cacheAt(writtenAt).put(summary(headline = "second"))

        assertThat(store.snapshot(MemoryNamespaces.INBOX_SUMMARY)).hasSize(1)
        assertThat(cacheAt(writtenAt).latest()!!.headline).isEqualTo("second")
    }

    @Test
    fun `a corrupt stored value reads as absent rather than throwing`() = runTest {
        memory.remember(MemoryNamespaces.INBOX_SUMMARY, "inbox.latest", "{not json", writtenAt)
        cacheAt(writtenAt)

        // A bad cache must never take down a background run.
        assertThat(cacheAt(writtenAt).latest()).isNull()
    }

    @Test
    fun `observe emits the current summary`() = runTest {
        cacheAt(writtenAt).put(summary())

        val emitted = cacheAt(writtenAt).observe().first()

        assertThat(emitted?.headline).isEqualTo("2 new messages.")
    }

    @Test
    fun `observe omits an expired entry`() = runTest {
        cacheAt(writtenAt).put(summary())

        val emitted = cacheAt(writtenAt.plus(Duration.ofHours(25))).observe().first()

        assertThat(emitted).isNull()
    }
}

class NotificationPolicyTest {

    @Test
    fun `important with something behind it notifies`() {
        val decision = NotificationPolicy.decide(ResultImportance.IMPORTANT, importantMessageCount = 1)

        assertThat(decision).isEqualTo(NotifyDecision.NOTIFY)
    }

    @Test
    fun `not important is suppressed`() {
        val decision = NotificationPolicy.decide(ResultImportance.NOT_IMPORTANT, importantMessageCount = 3)

        assertThat(decision).isEqualTo(NotifyDecision.SUPPRESS)
    }

    @Test
    fun `important with a zero count records only`() {
        // Flagged important but nothing backs the flag: keep state, skip the alert.
        val decision = NotificationPolicy.decide(ResultImportance.IMPORTANT, importantMessageCount = 0)

        assertThat(decision).isEqualTo(NotifyDecision.RECORD_ONLY)
    }

    @Test
    fun `not important never notifies even with many important messages`() {
        val decision = NotificationPolicy.decide(ResultImportance.NOT_IMPORTANT, importantMessageCount = 99)

        assertThat(decision).isEqualTo(NotifyDecision.SUPPRESS)
    }
}

class NotificationContentTest {

    private fun result(
        compact: String,
        importance: ResultImportance = ResultImportance.IMPORTANT,
    ) = ImportantResult(
        agentId = TEST_AGENT_ID,
        compactResult = compact,
        generatedAt = TEST_NOW,
        importantMessageCount = if (importance == ResultImportance.IMPORTANT) 1 else 0,
    )

    @Test
    fun `content is built from the compact summary only`() {
        val content = NotificationContentFactory.build(result("2 of 5 new messages look important"))

        assertThat(content.title).isEqualTo(NotificationContentFactory.TITLE)
        assertThat(content.body).isEqualTo("2 of 5 new messages look important")
    }

    @Test
    fun `only the first line of a multi-line summary is used`() {
        val content = NotificationContentFactory.build(result("first line\nsecond line\nthird"))

        // First line *only*, as the test name documents and as PRD 21 requires. This
        // assertion previously expected the first two lines joined, which contradicted its
        // own name, kept prose in notification text, and was unreachable alongside the
        // sibling test above — that one passes a 34-character summary through untouched, so
        // no character cap can yield a 22-character body without breaking it.
        assertThat(content.body).isEqualTo("first line")
    }

    @Test
    fun `the body is capped for glanceability`() {
        val content = NotificationContentFactory.build(result("word ".repeat(200)))

        assertThat(content.body.length).isAtMost(NotificationContentFactory.MAX_BODY_CHARS)
    }

    @Test
    fun `control characters are stripped`() {
        val content = NotificationContentFactory.build(result("line breakbell"))

        assertThat(content.body).doesNotContain(" ")
        assertThat(content.body).doesNotContain("")
    }

    @Test
    fun `a raw body-length fragment never survives sanitisation intact`() {
        val rawBody = "Dear team, here are the confidential figures for the quarter, please review"

        val content = NotificationContentFactory.build(result(rawBody))

        // SECURITY.md forbids full email bodies in notification text.
        assertThat(content.body).isNotEqualTo(rawBody)
        assertThat(content.body.length)
            .isAtMost(NotificationContentFactory.MAX_BODY_CHARS)
    }

    @Test
    fun `a known secret fragment is detected as unsafe`() {
        val candidate = "the key is sk-live-AAAABBBBCCCCDDDD1234"

        assertThat(
            NotificationPolicy.isNotificationTextSafe(candidate, listOf("sk-live-AAAABBBBCCCCDDDD1234")),
        ).isFalse()
    }

    @Test
    fun `an ordinary summary passes the safety check`() {
        assertThat(NotificationPolicy.isNotificationTextSafe("2 new messages.")).isTrue()
    }

    @Test
    fun `an empty summary fails the safety check`() {
        assertThat(NotificationPolicy.isNotificationTextSafe("   ")).isFalse()
    }

    @Test
    fun `sanitisation collapses whitespace runs`() {
        assertThat(NotificationContentFactory.sanitize("a    b\t\tc")).isEqualTo("a b c")
    }

    @Test
    fun `sanitisation strips markup used to disguise content`() {
        val sanitized = NotificationContentFactory.sanitize("<b>urgent</b> pay now")

        assertThat(sanitized).doesNotContain("<")
        assertThat(sanitized).doesNotContain(">")
        assertThat(sanitized.lowercase()).contains("urgent")
    }

    @Test
    fun `the source code is hashed, not the raw agent id`() {
        val content = NotificationContentFactory.build(result("hello"))

        assertThat(content.sourceCode).doesNotContain(TEST_AGENT_ID)
        assertThat(content.sourceCode).startsWith("agent:")
    }

    @Test
    fun `diagnostic formatting carries no free text`() {
        val log = DotAgentLog.event(
            event = "agent_run_finished",
            agentId = TEST_AGENT_ID,
            outcome = "SUCCESS",
            durationMs = 42,
        )

        assertThat(log).contains("event=agent_run_finished")
        assertThat(log).contains("result=SUCCESS")
        assertThat(log).contains("duration_ms=42")
        // The agent id is hashed, so a log cannot be correlated back to it.
        assertThat(log).doesNotContain(TEST_AGENT_ID)
    }

    @Test
    fun `an event with no agent or outcome still formats`() {
        assertThat(DotAgentLog.event(event = "scheduler_sync")).isEqualTo("event=scheduler_sync")
    }
}