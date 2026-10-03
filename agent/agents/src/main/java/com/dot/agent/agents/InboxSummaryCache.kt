package com.dot.agent.agents

import com.dot.agent.memory.MemoryNamespaces
import com.dot.agent.memory.MemoryRepository
import com.dot.agent.memory.MemoryTtl
import com.dot.core.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant

/**
 * The compact, expiring record of what the inbox agent last saw.
 *
 * Holds a one-line summary and message *ids* — never snippets, subjects or bodies
 * beyond what appears in the summary itself. Keeping the ids lets the next run
 * report only what is genuinely new without retaining content.
 */
@Serializable
data class InboxSummary(
    val headline: String,
    val importantMessageIds: List<String> = emptyList(),
    val totalNew: Int = 0,
    val generatedAtIso: String,
    val providerId: String,
)

/**
 * Compact local cache over [MemoryRepository].
 *
 * Uses the INBOX_SUMMARY namespace so [MemoryTtl.INBOX_SUMMARY] (24 hours) applies
 * automatically — nothing rots by accident, and the namespace is one of the
 * user-clearable categories in Settings (PRD 12).
 *
 * The TTL is re-checked on read as well as by the store's purge: an entry whose
 * expiry has passed must not be served, whether or not a purge has run yet.
 */
class InboxSummaryCache(
    private val memory: MemoryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The stable key for the single current summary. */
    val key: String = LATEST_KEY

    fun observe(now: Instant = clock.instant()): Flow<InboxSummary?> =
        memory.observeAll().map { items -> currentEntry(items, now) }

    suspend fun latest(now: Instant = clock.instant()): InboxSummary? = observe(now).first()

    /**
     * Writes the summary, replacing whatever was there. [MemoryRepository.remember] stamps the
     * namespace TTL, so callers cannot accidentally create a permanent cache entry.
     *
     * The namespace is cleared first because rows are keyed by a generated `id`, not by
     * (namespace, key): writing the same key twice therefore *appends* a second row rather than
     * replacing the first, and the reader would then have to guess which of two rows for one key
     * is the live one. This cache owns the whole [MemoryNamespaces.INBOX_SUMMARY] namespace and
     * stores exactly one entry, so clearing is both correct and the only way to keep the "one
     * current summary" invariant true.
     *
     * The window between the clear and the write reads as "no summary yet", which costs the next
     * run a slightly wider fetch. That is the right trade for a cache and the wrong trade for
     * anything authoritative.
     */
    suspend fun put(summary: InboxSummary) {
        memory.clearNamespace(MemoryNamespaces.INBOX_SUMMARY)
        memory.remember(
            namespace = MemoryNamespaces.INBOX_SUMMARY,
            key = key,
            value = json.encodeToString(InboxSummary.serializer(), summary),
            now = summary.instantOrNow(clock),
            source = SOURCE_INBOX_AGENT,
            userVisible = true,
        )
    }

    suspend fun invalidate() = memory.invalidate(MemoryNamespaces.INBOX_SUMMARY, key, clock.instant())

    suspend fun purgeExpired(): Int = memory.purgeExpired(clock.instant())

    suspend fun clear() = memory.clearNamespace(MemoryNamespaces.INBOX_SUMMARY)

    /**
     * Resolves the one current entry from every row that claims this key.
     *
     * A store row is identified by a generated `id`, so an [invalidate] tombstone and the summary
     * it supersedes can coexist as two rows sharing one (namespace, key). "First match wins" would
     * then serve the superseded summary, which is the bug this replaces: an invalidated entry must
     * be unreadable (PRD 12), so the newest row decides, and a tombstone that is the newest wins.
     */
    private fun currentEntry(items: List<MemoryItem>, now: Instant): InboxSummary? {
        val candidates = items.filter { it.namespace == MemoryNamespaces.INBOX_SUMMARY && it.key == key }
        if (candidates.isEmpty()) return null

        // Tie-break on tombstone-ness so invalidate wins even when the tombstone and the row it
        // supersedes carry the same timestamp: erring toward "absent" is the safe direction for a
        // cache, and it makes invalidate() independent of write ordering.
        val newest = candidates.maxWithOrNull(compareBy({ it.updatedAt }, { isTombstone(it) }))
            ?: return null
        if (isTombstone(newest) || isExpired(newest.expiresAt, now)) return null
        return decode(newest.value)
    }

    /**
     * True for the invalidation marker [MemoryRepository.invalidate] writes: an empty value that
     * expires at the moment of invalidation. Both halves are checked because either one alone
     * makes the entry unreadable.
     */
    private fun isTombstone(item: MemoryItem): Boolean =
        item.value.isBlank() && item.source == SOURCE_INVALIDATE

    /** A malformed value is treated as absent rather than thrown: a corrupt cache must not fail a run. */
    private fun decode(value: String): InboxSummary? =
        runCatching { json.decodeFromString(InboxSummary.serializer(), value) }.getOrNull()

    private fun isExpired(expiresAt: Instant?, now: Instant): Boolean =
        expiresAt != null && !expiresAt.isAfter(now)

    private companion object {
        const val LATEST_KEY = "inbox.latest"
        const val SOURCE_INBOX_AGENT = "inbox_agent"

        /** Matches the source [MemoryRepository.invalidate] stamps on its tombstone row. */
        const val SOURCE_INVALIDATE = "invalidate"

        fun InboxSummary.instantOrNow(clock: Clock): Instant =
            runCatching { Instant.parse(generatedAtIso) }.getOrElse { clock.instant() }
    }
}