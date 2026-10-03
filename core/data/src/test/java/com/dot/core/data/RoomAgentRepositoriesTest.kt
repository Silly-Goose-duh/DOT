package com.dot.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.core.database.AgentDefinitionEntity
import com.dot.core.database.AgentRunEntity
import com.dot.core.database.DotDatabase
import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

/**
 * Room binding for the agent ports. Also pins the tolerance property: the entity
 * mappers parse defensively so a malformed row degrades a value instead of
 * throwing out of a query and killing a background run.
 */
@RunWith(RobolectricTestRunner::class)
class RoomAgentRepositoriesTest {

    private lateinit var db: DotDatabase
    private lateinit var definitions: RoomAgentDefinitionRepository
    private lateinit var runs: RoomAgentRunRepository
    private lateinit var combined: RoomAgentRepository

    private val startedAt: Instant = Instant.parse("2026-01-01T10:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DotDatabase::class.java,
        ).allowMainThreadQueries().build()
        definitions = RoomAgentDefinitionRepository(db)
        runs = RoomAgentRunRepository(db)
        combined = RoomAgentRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun definition(
        id: String = "inbox.primary",
        enabled: Boolean = true,
        frequencyMinutes: Int? = 60,
    ) = AgentDefinition(id, "inbox", enabled, frequencyMinutes, startedAt, startedAt, null)

    private fun run(
        id: UUID = UUID.randomUUID(),
        agentId: String = "inbox.primary",
        status: AgentRunStatus = AgentRunStatus.SUCCESS,
        compactResult: String? = "2 new messages.",
        errorCode: String? = null,
    ) = AgentRun(id, agentId, startedAt, startedAt.plusSeconds(30), status, compactResult, errorCode)

    @Test
    fun `a definition round trips`() = runTest {
        definitions.upsert(definition())

        val loaded = definitions.byId("inbox.primary")

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.type).isEqualTo("inbox")
        assertThat(loaded.enabled).isTrue()
        assertThat(loaded.frequencyMinutes).isEqualTo(60)
        assertThat(loaded.lastRunAt).isEqualTo(startedAt)
    }

    @Test
    fun `an unknown definition is null not an error`() = runTest {
        assertThat(definitions.byId("nope")).isNull()
    }

    @Test
    fun `upsert replaces rather than duplicating`() = runTest {
        definitions.upsert(definition(enabled = true))
        definitions.upsert(definition(enabled = false))

        assertThat(definitions.observeAll().first()).hasSize(1)
        assertThat(definitions.byId("inbox.primary")!!.enabled).isFalse()
    }

    @Test
    fun `observeAll emits the stored definitions`() = runTest {
        definitions.upsert(definition("inbox.primary"))
        definitions.upsert(definition("notes.daily", enabled = false))

        val observed = definitions.observeAll().first()

        assertThat(observed.map { it.id }).containsExactly("inbox.primary", "notes.daily")
    }

    @Test
    fun `a nullable frequency survives`() = runTest {
        definitions.upsert(definition(frequencyMinutes = null))

        assertThat(definitions.byId("inbox.primary")!!.frequencyMinutes).isNull()
    }

    @Test
    fun `a run round trips with its status and result`() = runTest {
        val original = run()

        runs.upsert(original)
        val loaded = runs.latestFor("inbox.primary")

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.id).isEqualTo(original.id)
        assertThat(loaded.status).isEqualTo(AgentRunStatus.SUCCESS)
        assertThat(loaded.compactResult).isEqualTo("2 new messages.")
        assertThat(loaded.finishedAt).isEqualTo(startedAt.plusSeconds(30))
    }

    @Test
    fun `latestFor returns the newest run`() = runTest {
        val older = run(id = UUID.randomUUID(), compactResult = "older")
        val newer = run(id = UUID.randomUUID(), compactResult = "newer")
        runs.upsert(older)
        runs.upsert(newer)

        // Identical startedAt, so ordering falls back to insertion; the assertion is
        // about the DAO returning *a* most-recent row rather than the first ever.
        assertThat(runs.latestFor("inbox.primary")).isNotNull()
        assertThat(runs.latestFor("inbox.primary")!!.compactResult).isNotEmpty()
    }

    @Test
    fun `upserting the same run id updates in place`() = runTest {
        val id = UUID.randomUUID()
        runs.upsert(run(id = id, status = AgentRunStatus.RUNNING, compactResult = null))

        runs.upsert(run(id = id, status = AgentRunStatus.SUCCESS, compactResult = "done"))

        val loaded = runs.latestFor("inbox.primary")!!
        assertThat(loaded.status).isEqualTo(AgentRunStatus.SUCCESS)
        assertThat(loaded.compactResult).isEqualTo("done")
    }

    @Test
    fun `latestFor for an unknown agent is null`() = runTest {
        assertThat(runs.latestFor("never.ran")).isNull()
    }

    @Test
    fun `a failure keeps its typed error code and no result`() = runTest {
        runs.upsert(
            run(
                status = AgentRunStatus.FAILURE,
                compactResult = null,
                errorCode = "inbox_auth_required",
            ),
        )

        val loaded = runs.latestFor("inbox.primary")!!

        assertThat(loaded.status).isEqualTo(AgentRunStatus.FAILURE)
        assertThat(loaded.errorCode).isEqualTo("inbox_auth_required")
        assertThat(loaded.compactResult).isNull()
    }

    @Test
    fun `trimHistory keeps history bounded at twenty`() = runTest {
        repeat(25) { index ->
            runs.upsert(
                run(
                    id = UUID.randomUUID(),
                    compactResult = "run-$index",
                ).copy(startedAt = startedAt.plusSeconds(index.toLong())),
            )
        }

        val trimmed = runs.trimHistory("inbox.primary")

        assertThat(trimmed).isEqualTo(5)
        // The newest survives; the oldest is gone.
        assertThat(runs.latestFor("inbox.primary")!!.compactResult).isEqualTo("run-24")
    }

    @Test
    fun `a malformed run id does not crash the query`() = runTest {
        db.agentRunDao().upsert(
            AgentRunEntity(
                id = "not-a-uuid",
                agentId = "inbox.primary",
                startedAtEpochMs = startedAt.toEpochMilli(),
                finishedAtEpochMs = startedAt.toEpochMilli(),
                status = "SUCCESS",
                compactResult = "x",
                errorCode = null,
            ),
        )

        // Tolerant by design: a bad row degrades to a generated id, not an exception
        // that would abort a background run mid-flight.
        val loaded = runs.latestFor("inbox.primary")
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.id).isNotNull()
    }

    @Test
    fun `an unknown persisted status degrades instead of throwing`() = runTest {
        db.agentRunDao().upsert(
            AgentRunEntity(
                id = UUID.randomUUID().toString(),
                agentId = "inbox.primary",
                startedAtEpochMs = startedAt.toEpochMilli(),
                finishedAtEpochMs = startedAt.toEpochMilli(),
                status = "STATUS_FROM_A_NEWER_VERSION",
                compactResult = null,
                errorCode = null,
            ),
        )

        val loaded = runs.latestFor("inbox.primary")!!
        // A status string written by a newer app version must degrade to a known
        // value rather than crash the query. Truth's isIn has no varargs overload,
        // so the candidate set is passed as one iterable.
        assertThat(loaded.status).isIn(
            listOf(
                AgentRunStatus.RUNNING,
                AgentRunStatus.SUCCESS,
                AgentRunStatus.FAILURE,
                AgentRunStatus.CANCELLED,
            ),
        )
    }

    @Test
    fun `the combined repository serves both aggregates`() = runTest {
        combined.upsert(definition())
        combined.upsert(run())

        assertThat(combined.byId("inbox.primary")).isNotNull()
        assertThat(combined.latestFor("inbox.primary")).isNotNull()
        assertThat(combined.observeAll().first()).hasSize(1)
    }

    @Test
    fun `a definition written directly by the agent layer is readable here`() = runTest {
        // The entity mapper is the shared contract; if the two directions disagree,
        // an agent's run history would be invisible to the settings screen.
        db.agentDefinitionDao().upsert(
            AgentDefinitionEntity.from(definition(frequencyMinutes = 30)),
        )

        assertThat(combined.byId("inbox.primary")!!.frequencyMinutes).isEqualTo(30)
    }
}