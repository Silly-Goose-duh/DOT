package com.dot.agent.agents

import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.dot.core.model.AgentDefinitionRepositoryPort
import com.dot.core.model.AgentRepositoryPort
import com.dot.core.model.AgentRunRepositoryPort
import com.dot.core.model.MemoryItem
import com.dot.core.model.MemoryStorePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Shared constants so every test file agrees on the agent id and clock. */
internal const val TEST_AGENT_ID = "inbox.primary"
internal val TEST_NOW: Instant = Instant.parse("2026-01-01T10:00:00Z")

/**
 * In-memory [AgentRepositoryPort].
 *
 * Records the upsert sequence so tests can assert the *transitions*, not just the
 * final row — the "no dangling RUNNING" property is about the sequence.
 */
class FakeAgentRepository(
    initial: List<AgentDefinition> = emptyList(),
) : AgentRepositoryPort {

    val definitions = linkedMapOf<String, AgentDefinition>().apply {
        initial.forEach { put(it.id, it) }
    }

    /** Every row ever written, in order. */
    val runHistory = mutableListOf<AgentRun>()

    var trimCount = 0
        private set

    override fun observeAll(): Flow<List<AgentDefinition>> =
        MutableStateFlow(definitions.values.toList())

    override suspend fun byId(id: String): AgentDefinition? = definitions[id]

    override suspend fun upsert(agent: AgentDefinition) {
        definitions[agent.id] = agent
    }

    override suspend fun latestFor(agentId: String): AgentRun? =
        runHistory.lastOrNull { it.agentId == agentId }

    override suspend fun upsert(run: AgentRun) {
        // Mirror Room's REPLACE-on-id semantics: the same run id updates in place.
        val index = runHistory.indexOfFirst { it.id == run.id }
        if (index >= 0) runHistory[index] = run else runHistory.add(run)
    }

    override suspend fun trimHistory(agentId: String): Int {
        trimCount++
        return 0
    }

    fun rowsFor(agentId: String): List<AgentRun> = runHistory.filter { it.agentId == agentId }

    fun latestRow(agentId: String): AgentRun? = rowsFor(agentId).lastOrNull()

    /** True when any row for this agent is still RUNNING — a dangling row. */
    fun hasDanglingRun(agentId: String): Boolean =
        rowsFor(agentId).any { it.status == AgentRunStatus.RUNNING }
}

/** Read-only view of the same repository, for tests that only supply definitions. */
class FakeAgentDefinitionRepository(
    private val delegate: AgentDefinitionRepositoryPort,
) : AgentDefinitionRepositoryPort by delegate

/** Read-only view for run history. */
class FakeAgentRunRepository(
    private val delegate: AgentRunRepositoryPort,
) : AgentRunRepositoryPort by delegate

/**
 * In-memory [MemoryStorePort] with faithful TTL semantics.
 *
 * Mirrors the Room DAO's inclusive purge (expiresAt <= now counts as expired),
 * because the cache's contract depends on that boundary.
 */
class FakeMemoryStore : MemoryStorePort {

    private val items = linkedMapOf<String, MemoryItem>()

    override fun observeAll(): Flow<List<MemoryItem>> =
        MutableStateFlow(items.values.sortedByDescending { it.updatedAt })

    override suspend fun put(item: MemoryItem) {
        items[item.id.toString()] = item
    }

    override suspend fun expired(now: Instant): List<MemoryItem> =
        items.values.filter { it.expiresAt != null && !it.expiresAt!!.isAfter(now) }

    override suspend fun purgeExpired(now: Instant): Int {
        val doomed = expired(now)
        doomed.forEach { items.remove(it.id.toString()) }
        return doomed.size
    }

    override suspend fun clearNamespace(namespace: String) {
        items.values.filter { it.namespace == namespace }.forEach { items.remove(it.id.toString()) }
    }

    override suspend fun clearAll() = items.clear()

    /** Test-only view of what actually landed in storage. */
    fun snapshot(namespace: String): List<MemoryItem> = items.values.filter { it.namespace == namespace }

    fun observeNamespace(namespace: String): Flow<List<MemoryItem>> =
        MutableStateFlow(snapshot(namespace))
}