package com.dot.core.model

import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for background agents. Split by aggregate — definitions
 * are user configuration (long-lived), runs are execution history — so a caller
 * that only records runs cannot accidentally rewrite user configuration.
 *
 * These live in core:model for the same reason as the other ports: the Room
 * binding belongs in :core:data, which must not depend on the agent layer.
 */

interface AgentDefinitionRepositoryPort {

    /** For the Agents screen only; execution paths use [byId] so nothing blocks. */
    fun observeAll(): Flow<List<AgentDefinition>>

    suspend fun byId(id: String): AgentDefinition?

    suspend fun upsert(agent: AgentDefinition)
}

interface AgentRunRepositoryPort {

    suspend fun latestFor(agentId: String): AgentRun?

    suspend fun upsert(run: AgentRun)

    /** Execution history is bounded, not permanent memory (PRD 12). */
    suspend fun trimHistory(agentId: String): Int
}

/**
 * Combined port for callers that hold both aggregates (the runner). Kept as one
 * interface so the composition root binds a single Room-backed object.
 */
interface AgentRepositoryPort : AgentDefinitionRepositoryPort, AgentRunRepositoryPort