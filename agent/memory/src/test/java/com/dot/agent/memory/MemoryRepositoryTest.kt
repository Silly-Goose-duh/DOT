package com.dot.agent.memory

import com.dot.core.model.MemoryItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class InMemoryStore : MemoryStore {
    private val state = MutableStateFlow<List<MemoryItem>>(emptyList())
    override fun observeAll(): Flow<List<MemoryItem>> = state

    override suspend fun put(item: MemoryItem) {
        state.value = state.value.filterNot { it.namespace == item.namespace && it.key == item.key } + item
    }

    override suspend fun expired(now: Instant) =
        state.value.filter { it.expiresAt != null && !it.expiresAt!!.isAfter(now) }

    override suspend fun purgeExpired(now: Instant): Int {
        val before = state.value.size
        state.value = state.value.filter { it.expiresAt == null || it.expiresAt!!.isAfter(now) }
        return before - state.value.size
    }

    override suspend fun clearNamespace(namespace: String) {
        state.value = state.value.filterNot { it.namespace == namespace }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}

class MemoryPolicyTest {

    private val policy = MemoryPolicy()
    private val now = Instant.parse("2026-09-27T00:00:00Z")

    @Test
    fun `session context expires`() {
        assertThat(policy.isPersistent(MemoryNamespaces.SESSION_CONTEXT)).isFalse()
        val exp = policy.expiryFor(MemoryNamespaces.SESSION_CONTEXT, now)!!
        assertThat(exp).isEqualTo(now.plus(30, ChronoUnit.MINUTES))
    }

    @Test
    fun `recent tool results expire`() {
        val exp = policy.expiryFor(MemoryNamespaces.RECENT_TOOL_RESULTS, now)!!
        assertThat(exp).isEqualTo(now.plus(15, ChronoUnit.MINUTES))
    }

    @Test
    fun `inbox summary expires after a day`() {
        val exp = policy.expiryFor(MemoryNamespaces.INBOX_SUMMARY, now)!!
        assertThat(exp).isEqualTo(now.plus(24, ChronoUnit.HOURS))
    }

    @Test
    fun `user facts are persistent and never expire`() {
        assertThat(policy.isPersistent(MemoryNamespaces.USER_FACTS)).isTrue()
        assertThat(policy.expiryFor(MemoryNamespaces.USER_FACTS, now)).isNull()
        assertThat(policy.expiryFor(MemoryNamespaces.PREFERENCES, now)).isNull()
    }

    @Test
    fun `every category has an explicit ttl rule`() {
        // no namespace silently falls through to an undefined policy
        policy.namespaces.forEach { ns ->
            assertThat(policy.ttlFor(ns)).isAnyOf(
                null,
                MemoryTtl.SESSION_CONTEXT,
                MemoryTtl.RECENT_TOOL_RESULTS,
                MemoryTtl.INBOX_SUMMARY,
            )
        }
    }
}

class MemoryRepositoryTest {

    private val store = InMemoryStore()
    private val repo = MemoryRepository(store)
    private val now = Instant.parse("2026-09-27T00:00:00Z")

    @Test
    fun `persistent fact gets no expiry`() = runTest {
        val item = repo.remember(MemoryNamespaces.USER_FACTS, "name", "Tom", now)
        assertThat(item.expiresAt).isNull()
        assertThat(repo.purgeExpired(now.plus(3650, ChronoUnit.DAYS))).isEqualTo(0)
    }

    @Test
    fun `cache entry expires and is purged`() = runTest {
        repo.remember(MemoryNamespaces.SESSION_CONTEXT, "last_command", "what's today", now)
        val later = now.plus(31, ChronoUnit.MINUTES)
        assertThat(store.expired(later)).hasSize(1)
        assertThat(repo.purgeExpired(later)).isEqualTo(1)
    }

    @Test
    fun `invalidate stamps immediate expiry`() = runTest {
        repo.remember(MemoryNamespaces.SESSION_CONTEXT, "k", "v", now)
        repo.invalidate(MemoryNamespaces.SESSION_CONTEXT, "k", now)
        assertThat(repo.purgeExpired(now)).isEqualTo(1)
    }

    @Test
    fun `clear all wipes everything`() = runTest {
        repo.remember(MemoryNamespaces.USER_FACTS, "a", "1", now)
        repo.remember(MemoryNamespaces.PREFERENCES, "b", "2", now)
        repo.clearAll()
        val all = store.observeAll().first()
        assertThat(all).isEmpty()
    }

    @Test
    fun `clear namespace leaves other namespaces intact`() = runTest {
        repo.remember(MemoryNamespaces.PREFERENCES, "theme", "dark", now)
        repo.remember(MemoryNamespaces.USER_FACTS, "name", "Tom", now)
        repo.clearNamespace(MemoryNamespaces.PREFERENCES)
        val remaining = store.observeAll().first().map { it.namespace }
        assertThat(remaining).containsExactly(MemoryNamespaces.USER_FACTS)
    }
}
