package com.dot.agent.memory

import com.dot.core.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * PRD 12: persistent local memory is separate from expiring cache.
 * Every cache category carries an explicit TTL — nothing expires implicitly.
 */
object MemoryNamespaces {
    const val USER_FACTS = "user_facts"
    const val PREFERENCES = "preferences"
    const val SESSION_CONTEXT = "session_context"
    const val RECENT_TOOL_RESULTS = "recent_tool_results"
    const val INBOX_SUMMARY = "inbox_summary"
}

/** A TTL expressed as an amount plus a unit. Null TTL means "persistent, never expires". */
data class Ttl(val amount: Long, val unit: ChronoUnit) {
    fun expiryFrom(now: Instant): Instant = now.plus(amount, unit)
}

/** TTLs, stated explicitly per PRD 12. */
object MemoryTtl {
    val SESSION_CONTEXT = Ttl(30, ChronoUnit.MINUTES)
    val RECENT_TOOL_RESULTS = Ttl(15, ChronoUnit.MINUTES)
    val INBOX_SUMMARY = Ttl(24, ChronoUnit.HOURS)
    val USER_FACTS: Ttl? = null // persistent, no expiry
    val PREFERENCES: Ttl? = null
}

data class MemoryPolicy(
    val namespaces: Set<String> = setOf(
        MemoryNamespaces.USER_FACTS,
        MemoryNamespaces.PREFERENCES,
        MemoryNamespaces.SESSION_CONTEXT,
        MemoryNamespaces.RECENT_TOOL_RESULTS,
        MemoryNamespaces.INBOX_SUMMARY,
    ),
    val ttlFor: (String) -> Ttl? = { namespace ->
        when (namespace) {
            MemoryNamespaces.SESSION_CONTEXT -> MemoryTtl.SESSION_CONTEXT
            MemoryNamespaces.RECENT_TOOL_RESULTS -> MemoryTtl.RECENT_TOOL_RESULTS
            MemoryNamespaces.INBOX_SUMMARY -> MemoryTtl.INBOX_SUMMARY
            else -> null
        }
    },
) {
    /** Categories the user can inspect and clear from Settings. */
    fun userVisibleNamespaces(): List<String> = namespaces.sorted()

    fun isPersistent(namespace: String): Boolean = ttlFor(namespace) == null

    fun expiryFor(namespace: String, now: Instant): Instant? =
        ttlFor(namespace)?.expiryFrom(now)
}

interface MemoryStore {
    fun observeAll(): Flow<List<MemoryItem>>
    suspend fun put(item: MemoryItem)
    suspend fun expired(now: Instant): List<MemoryItem>
    suspend fun purgeExpired(now: Instant): Int
    suspend fun clearNamespace(namespace: String)
    suspend fun clearAll()
}

class MemoryRepository(
    private val store: MemoryStore,
    private val policy: MemoryPolicy = MemoryPolicy(),
) {
    fun observeAll(): Flow<List<MemoryItem>> = store.observeAll()

    /** Stamps expiry from policy; a persistent namespace gets null (never expires). */
    suspend fun remember(
        namespace: String,
        key: String,
        value: String,
        now: Instant = Instant.now(),
        source: String = "local",
        userVisible: Boolean = true,
    ): MemoryItem {
        val item = MemoryItem(
            namespace = namespace,
            key = key,
            value = value,
            expiresAt = policy.expiryFor(namespace, now),
            source = source,
            userVisible = userVisible,
        )
        store.put(item)
        return item
    }

    /** Cache entries are explicitly invalidated, never left to rot. */
    suspend fun invalidate(namespace: String, key: String, now: Instant = Instant.now()) {
        store.put(
            MemoryItem(
                namespace = namespace,
                key = key,
                value = "",
                expiresAt = now, // expiring exactly now counts as already invalid
                source = "invalidate",
                userVisible = false,
            ),
        )
    }

    suspend fun purgeExpired(now: Instant = Instant.now()): Int = store.purgeExpired(now)

    suspend fun clearNamespace(namespace: String) = store.clearNamespace(namespace)

    /** PRD 12 / acceptance: "User can clear DOT's local memory." */
    suspend fun clearAll() = store.clearAll()
}
