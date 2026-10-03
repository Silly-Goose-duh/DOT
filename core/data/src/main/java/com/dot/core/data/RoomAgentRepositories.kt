package com.dot.core.data

import com.dot.core.database.AgentDefinitionEntity
import com.dot.core.database.AgentRunEntity
import com.dot.core.database.DotDatabase
import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentDefinitionRepositoryPort
import com.dot.core.model.AgentRepositoryPort
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunRepositoryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room binding for the agent ports declared in core:model.
 *
 * Tolerant by construction: the entity mappers use parseUuidOrNull/parseEnumOr,
 * so a row written by a future version (or corrupted on disk) degrades to a
 * default instead of throwing out of a query and taking down a background run.
 * No extra validation lives here — that would duplicate the mapper's job.
 */

class RoomAgentDefinitionRepository(private val db: DotDatabase) : AgentDefinitionRepositoryPort {

    override fun observeAll(): Flow<List<AgentDefinition>> =
        db.agentDefinitionDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun byId(id: String): AgentDefinition? =
        db.agentDefinitionDao().byId(id)?.toDomain()

    override suspend fun upsert(agent: AgentDefinition) {
        db.agentDefinitionDao().upsert(AgentDefinitionEntity.from(agent))
    }
}

class RoomAgentRunRepository(private val db: DotDatabase) : AgentRunRepositoryPort {

    override suspend fun latestFor(agentId: String): AgentRun? =
        db.agentRunDao().latestFor(agentId)?.toDomain()

    override suspend fun upsert(run: AgentRun) {
        db.agentRunDao().upsert(AgentRunEntity.from(run))
    }

    override suspend fun trimHistory(agentId: String): Int =
        db.agentRunDao().trimHistory(agentId)
}

/** Single object for the composition root; both aggregates share one database. */
class RoomAgentRepository(private val db: DotDatabase) : AgentRepositoryPort,
    AgentDefinitionRepositoryPort by RoomAgentDefinitionRepository(db),
    AgentRunRepositoryPort by RoomAgentRunRepository(db)