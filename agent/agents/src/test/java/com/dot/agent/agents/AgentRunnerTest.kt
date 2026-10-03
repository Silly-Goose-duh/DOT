package com.dot.agent.agents

import com.dot.agent.policy.PolicyEngine
import com.dot.agent.policy.RiskLevel
import com.dot.agent.policy.ToolCategory
import com.dot.agent.tools.DotTool
import com.dot.agent.tools.InputGuards
import com.dot.agent.tools.ToolRegistry
import com.dot.agent.tools.ValidationResult
import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import com.dot.core.model.AgentDefinition
import com.dot.core.model.AgentRepositoryPort
import com.dot.core.model.AgentRun
import com.dot.core.model.AgentRunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A tool that records whether it ran, so "was it executed?" is observable. */
private class RecordingWriteTool : DotTool<Map<String, String?>, String> {
    var invocations = 0
        private set

    override val name = "record_write"
    override val description = "Test-only external write."
    override val category = ToolCategory.EXTERNAL_WRITE
    override val riskLevel = RiskLevel.MEDIUM
    override val timeoutMs = 1_000L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true

    override fun validate(input: Map<String, String?>): ValidationResult = InputGuards.title(input["title"])

    override suspend fun execute(input: Map<String, String?>): String {
        invocations++
        return "ok"
    }
}

private class NoopLocalTool : DotTool<Map<String, String?>, String> {
    override val name = "noop_local"
    override val description = "Test-only local read."
    override val category = ToolCategory.LOCAL_READ
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 1_000L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = false

    override fun validate(input: Map<String, String?>): ValidationResult = ValidationResult.Valid

    override suspend fun execute(input: Map<String, String?>): String = "ok"
}

private class ScriptedTask(
    override val agentType: String = "inbox",
    private val behaviour: suspend (AgentRunContext) -> ActionResult<AgentTaskResult>,
) : AgentTask {
    var calls = 0
        private set

    override suspend fun execute(context: AgentRunContext): ActionResult<AgentTaskResult> {
        calls++
        return behaviour(context)
    }
}

private const val AGENT_ID = TEST_AGENT_ID
private val NOW = TEST_NOW

private fun fixedClock(instant: Instant = NOW) = Clock.fixed(instant, ZoneOffset.UTC)

private fun definition(
    enabled: Boolean = true,
    type: String = "inbox",
    frequencyMinutes: Int? = 60,
) = AgentDefinition(
    id = AGENT_ID,
    type = type,
    enabled = enabled,
    frequencyMinutes = frequencyMinutes,
)

private fun successTask(result: String = "3 new messages.") = ScriptedTask {
    ActionResult.success(AgentTaskResult(result))
}

/**
 * Records every run row *in write order*, without collapsing.
 *
 * [FakeAgentRepository] deliberately mirrors Room's REPLACE-on-id semantics, so
 * `runHistory` only ever holds the newest row for a given run id. A RUNNING row
 * overwritten by its terminal SUCCESS therefore collapses to a single entry and
 * the transition becomes invisible — which is exactly the thing PRD 11 cares
 * about. This decorator delegates all state to the fake and additionally keeps
 * the full write sequence, so a test can assert the transitions rather than the
 * final state.
 */
private class TransitionRecordingRepository(
    initial: List<AgentDefinition> = emptyList(),
) : AgentRepositoryPort {

    private val delegate = FakeAgentRepository(initial)

    /** Every run row ever written, in write order, including overwrites. */
    val writes = mutableListOf<AgentRun>()

    /** Statuses written for [agentId], in the order they were persisted. */
    fun transitions(agentId: String): List<AgentRunStatus> =
        writes.filter { it.agentId == agentId }.map { it.status }

    fun latestRow(agentId: String): AgentRun? = delegate.latestRow(agentId)

    fun hasDanglingRun(agentId: String): Boolean = delegate.hasDanglingRun(agentId)

    val trimCount: Int get() = delegate.trimCount

    val definitions: Map<String, AgentDefinition> get() = delegate.definitions

    override fun observeAll() = delegate.observeAll()

    override suspend fun byId(id: String) = delegate.byId(id)

    override suspend fun upsert(agent: AgentDefinition) = delegate.upsert(agent)

    override suspend fun latestFor(agentId: String) = delegate.latestFor(agentId)

    override suspend fun upsert(run: AgentRun) {
        writes += run
        delegate.upsert(run)
    }

    override suspend fun trimHistory(agentId: String) = delegate.trimHistory(agentId)
}

class AgentRunnerTest {

    private fun runner(
        repository: AgentRepositoryPort,
        task: AgentTask,
        retryStore: AgentRetryStateStore = InMemoryAgentRetryStateStore(),
        notifier: ImportantResultNotifier? = null,
        runTimeout: Duration = Duration.ofMinutes(5),
        tools: ToolRegistry? = null,
        guard: InFlightGuard = InFlightGuard(),
    ) = AgentRunner(
        repository = repository,
        tasks = listOf(task),
        policyEngine = PolicyEngine(),
        backoffPolicy = BackoffPolicy(jitter = { 0.5 }),
        retryStateStore = retryStore,
        clock = fixedClock(),
        runTimeout = runTimeout,
        toolRegistryProvider = { tools },
        notifier = notifier,
        inFlightGuard = guard,
    )

    @Test
    fun `success path records RUNNING then SUCCESS with a compact result`() = runTest {
        val repo = TransitionRecordingRepository(listOf(definition()))
        val outcome = runner(repo, successTask()).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Success::class.java)
        // Read the *write sequence*, not the final row: Room replaces by id, so the
        // terminal row overwrites the RUNNING one and the transition is only
        // observable as it happened. PRD 11 requires both writes — a process killed
        // mid-run leaves the RUNNING row for ProcessRecovery to reconcile.
        assertThat(repo.transitions(AGENT_ID))
            .containsExactly(AgentRunStatus.RUNNING, AgentRunStatus.SUCCESS).inOrder()
        val final = repo.latestRow(AGENT_ID)!!
        assertThat(final.compactResult).isEqualTo("3 new messages.")
        assertThat(final.finishedAt).isNotNull()
        assertThat(final.errorCode).isNull()
        assertThat(repo.hasDanglingRun(AGENT_ID)).isFalse()
        assertThat(repo.trimCount).isGreaterThan(0)
    }

    @Test
    fun `RUNNING is persisted before the task executes so a mid-run kill is recoverable`() = runTest {
        val repo = TransitionRecordingRepository(listOf(definition()))
        // Captured *during* execution: if RUNNING were written after the task, this
        // would be empty and a process death here would leave nothing to reconcile.
        var statusesVisibleDuringExecution: List<AgentRunStatus> = emptyList()
        val task = ScriptedTask {
            statusesVisibleDuringExecution = repo.transitions(AGENT_ID)
            ActionResult.success(AgentTaskResult("done"))
        }

        runner(repo, task).run(AGENT_ID)

        assertThat(statusesVisibleDuringExecution).containsExactly(AgentRunStatus.RUNNING)
    }

    @Test
    fun `success stamps the definition and clears the retry counter`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val retryStore = InMemoryAgentRetryStateStore()
        retryStore.recordFailure(AGENT_ID)
        retryStore.recordFailure(AGENT_ID)

        runner(repo, successTask(), retryStore).run(AGENT_ID)

        assertThat(repo.definitions[AGENT_ID]!!.lastSuccessAt).isEqualTo(NOW)
        assertThat(repo.definitions[AGENT_ID]!!.lastRunAt).isEqualTo(NOW)
        assertThat(repo.definitions[AGENT_ID]!!.lastErrorCode).isNull()
        assertThat(retryStore.attemptsMade(AGENT_ID)).isEqualTo(0)
    }

    @Test
    fun `a thrown exception becomes FAILURE with a typed code and no dangling RUNNING`() = runTest {
        val repo = TransitionRecordingRepository(listOf(definition()))
        val task = ScriptedTask { throw IllegalStateException("provider leaked a token: hunter2") }

        val outcome = runner(repo, task).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Failed::class.java)
        val final = repo.latestRow(AGENT_ID)!!
        assertThat(final.status).isEqualTo(AgentRunStatus.FAILURE)
        // IllegalStateException is an unrecognised internal fault, which is exactly
        // what agent_unexpected_error means. The dedicated codes (invalid_argument,
        // io_error, permission_denied) each name a *specific* fault class; claiming
        // one of them for a generic throwable would mislabel the failure in
        // diagnostics and telemetry.
        assertThat(final.errorCode).isEqualTo(AgentErrorCodes.AGENT_UNEXPECTED_ERROR)
        // SECURITY.md: only the throwable's identity is stored, never its message.
        assertThat(final.errorCode).doesNotContain("hunter2")
        assertThat(final.finishedAt).isNotNull()
        assertThat(repo.hasDanglingRun(AGENT_ID)).isFalse()
        // The RUNNING row is still written first and is then closed out, so a
        // crash between the two is reconcilable rather than invisible.
        assertThat(repo.transitions(AGENT_ID))
            .containsExactly(AgentRunStatus.RUNNING, AgentRunStatus.FAILURE).inOrder()
    }

    @Test
    fun `an IllegalArgumentException is classified as invalid_argument not a generic error`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val task = ScriptedTask { throw IllegalArgumentException("scope was empty") }

        val outcome = runner(repo, task).run(AGENT_ID)

        // The mapping table is per throwable type, so each branch needs its own case:
        // this is the branch that agent_invalid_argument exists for.
        assertThat((outcome as AgentRunOutcome.Failed).errorCode).isEqualTo("agent_invalid_argument")
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.FAILURE)
    }

    @Test
    fun `an io failure gets its own code and the throwable message never reaches storage`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val task = ScriptedTask { throw java.io.IOException("connect failed for user@example.com") }

        val outcome = runner(repo, task).run(AGENT_ID)

        assertThat((outcome as AgentRunOutcome.Failed).errorCode).isEqualTo("agent_io_error")
        // SECURITY.md: the message may contain remote content, so only the type is used.
        assertThat(repo.latestRow(AGENT_ID)!!.errorCode).doesNotContain("example.com")
        assertThat(repo.latestRow(AGENT_ID)!!.compactResult).isNull()
    }

    @Test
    fun `a retryable failure is requeued and leaves a terminal FAILURE row`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val retryStore = InMemoryAgentRetryStateStore()
        val task = ScriptedTask {
            ActionResult.failure(ActionOutcome.UNAVAILABLE, AgentErrorCodes.INBOX_UNAVAILABLE)
        }

        val outcome = runner(repo, task, retryStore).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Requeue::class.java)
        assertThat((outcome as AgentRunOutcome.Requeue).delay).isEqualTo(Duration.ofSeconds(30))
        assertThat(outcome.attemptsMade).isEqualTo(1)
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.FAILURE)
        assertThat(repo.hasDanglingRun(AGENT_ID)).isFalse()
        assertThat(retryStore.attemptsMade(AGENT_ID)).isEqualTo(1)
        assertThat(repo.definitions[AGENT_ID]!!.lastErrorCode)
            .isEqualTo(AgentErrorCodes.INBOX_UNAVAILABLE)
    }

    @Test
    fun `the retry budget is bounded across runs`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val retryStore = InMemoryAgentRetryStateStore()
        val task = ScriptedTask {
            ActionResult.failure(ActionOutcome.UNAVAILABLE, AgentErrorCodes.INBOX_UNAVAILABLE)
        }
        val r = runner(repo, task, retryStore)

        repeat(6) { r.run(AGENT_ID) }

        val outcomes = repo.runHistory.count { it.errorCode == AgentErrorCodes.INBOX_UNAVAILABLE }
        assertThat(outcomes).isAtLeast(1)
        assertThat(retryStore.attemptsMade(AGENT_ID)).isAtMost(5)
        assertThat(repo.hasDanglingRun(AGENT_ID)).isFalse()
    }

    @Test
    fun `revoked auth gives up immediately as AUTH_REQUIRED and is not retried`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val retryStore = InMemoryAgentRetryStateStore()
        val task = ScriptedTask {
            ActionResult.failure(ActionOutcome.AUTH_REQUIRED, AgentErrorCodes.INBOX_AUTH_REQUIRED)
        }

        val outcome = runner(repo, task, retryStore).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Failed::class.java)
        val failed = outcome as AgentRunOutcome.Failed
        assertThat(failed.outcome).isEqualTo(ActionOutcome.AUTH_REQUIRED)
        assertThat(failed.errorCode).isEqualTo(AgentErrorCodes.INBOX_AUTH_REQUIRED)
        assertThat(retryStore.attemptsMade(AGENT_ID)).isEqualTo(0)
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.FAILURE)
    }

    @Test
    fun `a timeout is AMBIGUOUS, fails the run and is never auto-retried`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val retryStore = InMemoryAgentRetryStateStore()
        val task = ScriptedTask {
            delay(10_000)
            ActionResult.success(AgentTaskResult("too late"))
        }

        val outcome = runner(repo, task, retryStore, runTimeout = Duration.ofMillis(50)).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Failed::class.java)
        assertThat((outcome as AgentRunOutcome.Failed).outcome).isEqualTo(ActionOutcome.AMBIGUOUS)
        assertThat(outcome.errorCode).isEqualTo(AgentErrorCodes.AGENT_TIMEOUT)
        assertThat(retryStore.attemptsMade(AGENT_ID)).isEqualTo(0)
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.FAILURE)
        assertThat(repo.hasDanglingRun(AGENT_ID)).isFalse()
    }

    @Test
    fun `a success with no payload is AMBIGUOUS, never a silent success`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val task = ScriptedTask {
            ActionResult.success<AgentTaskResult>(AgentTaskResult("x")).copy(value = null)
        }

        val outcome = runner(repo, task).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Failed::class.java)
        assertThat((outcome as AgentRunOutcome.Failed).outcome).isEqualTo(ActionOutcome.AMBIGUOUS)
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.FAILURE)
    }

    @Test
    fun `a disabled agent is a no-op and records CANCELLED rather than executing`() = runTest {
        val repo = FakeAgentRepository(listOf(definition(enabled = false)))
        val task = successTask()

        val outcome = runner(repo, task).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Skipped::class.java)
        assertThat((outcome as AgentRunOutcome.Skipped).reasonCode).isEqualTo(ScheduleReasons.DISABLED)
        assertThat(task.calls).isEqualTo(0)
        assertThat(repo.latestRow(AGENT_ID)!!.status).isEqualTo(AgentRunStatus.CANCELLED)
        assertThat(repo.latestRow(AGENT_ID)!!.finishedAt).isNotNull()
    }

    @Test
    fun `an unknown agent id fails without writing a run row`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))

        val outcome = runner(repo, successTask()).run("nope")

        assertThat((outcome as AgentRunOutcome.Failed).errorCode)
            .isEqualTo(AgentErrorCodes.AGENT_NOT_FOUND)
        assertThat(repo.runHistory).isEmpty()
    }

    @Test
    fun `an unregistered agent type fails without executing`() = runTest {
        val repo = FakeAgentRepository(listOf(definition(type = "calendar")))
        val task = successTask()

        val outcome = runner(repo, task).run(AGENT_ID)

        assertThat((outcome as AgentRunOutcome.Failed).errorCode)
            .isEqualTo(AgentErrorCodes.AGENT_TYPE_UNSUPPORTED)
        assertThat(task.calls).isEqualTo(0)
    }

    @Test
    fun `an in-flight guard turns a concurrent second run into a recorded no-op`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val guard = InFlightGuard()
        val task = ScriptedTask { ActionResult.success(AgentTaskResult("done")) }

        assertThat(guard.tryAcquire(AGENT_ID)).isTrue()
        val outcome = runner(repo, task, guard = guard).run(AGENT_ID)

        assertThat(outcome).isInstanceOf(AgentRunOutcome.Skipped::class.java)
        assertThat((outcome as AgentRunOutcome.Skipped).reasonCode).isEqualTo("agent_already_in_flight")
        assertThat(task.calls).isEqualTo(0)
    }

    @Test
    fun `a completed run releases the guard so the next run proceeds`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val guard = InFlightGuard()
        val task = successTask("first")

        runner(repo, task, guard = guard).run(AGENT_ID)
        val second = runner(repo, task, guard = guard).run(AGENT_ID)

        assertThat(second).isInstanceOf(AgentRunOutcome.Success::class.java)
        assertThat(task.calls).isEqualTo(2)
    }

    @Test
    fun `a thrown exception still releases the in-flight guard`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val guard = InFlightGuard()
        val task = ScriptedTask { throw IllegalStateException("boom") }

        runner(repo, task, guard = guard).run(AGENT_ID)

        assertThat(guard.tryAcquire(AGENT_ID)).isTrue()
    }

    @Test
    fun `the guard never unlocks a lock it does not hold`() = runTest {
        val guard = InFlightGuard()
        guard.tryAcquire(AGENT_ID)
        guard.release("some-other-agent") // must not throw or unlock ours
        assertThat(guard.tryAcquire(AGENT_ID)).isFalse()
        guard.release(AGENT_ID)
        assertThat(guard.tryAcquire(AGENT_ID)).isTrue()
    }

    @Test
    fun `an important result notifies the notifier`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val notifier = RecordingNotifier()
        val task = ScriptedTask {
            ActionResult.success(AgentTaskResult("1 needs attention", ResultImportance.IMPORTANT))
        }

        runner(repo, task, notifier = notifier).run(AGENT_ID)

        assertThat(notifier.posted).hasSize(1)
        assertThat(notifier.posted.first().compactResult).isEqualTo("1 needs attention")
    }

    @Test
    fun `an unimportant result never notifies`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val notifier = RecordingNotifier()
        val task = ScriptedTask { ActionResult.success(AgentTaskResult("nothing new", ResultImportance.NOT_IMPORTANT)) }

        runner(repo, task, notifier = notifier).run(AGENT_ID)

        assertThat(notifier.posted).isEmpty()
    }

    @Test
    fun `a compact result is capped so a verbose provider cannot flood storage`() = runTest {
        val repo = FakeAgentRepository(listOf(definition()))
        val task = ScriptedTask { ActionResult.success(AgentTaskResult("x".repeat(5_000))) }

        runner(repo, task).run(AGENT_ID)

        assertThat(repo.latestRow(AGENT_ID)!!.compactResult).hasLength(400)
    }

    @Test
    fun `an external write tool is refused because policy requires confirmation`() = runTest {
        val writeTool = RecordingWriteTool()
        val registry = ToolRegistry(listOf(writeTool, NoopLocalTool()))
        val gate = ToolGate(
            policyEngine = PolicyEngine(),
            registryProvider = { registry },
            hasPermission = { true },
            isAuthenticated = { true },
        )

        val result = gate.execute("record_write", mapOf("title" to "hello"))

        assertThat(result.outcome).isEqualTo(ActionOutcome.PERMISSION_REQUIRED)
        assertThat(result.errorCode).isEqualTo(AgentErrorCodes.POLICY_CONFIRMATION_REQUIRED)
        assertThat(writeTool.invocations).isEqualTo(0)
    }

    @Test
    fun `a v0_1 out-of-scope tool is refused by name before the registry is consulted`() = runTest {
        val registry = ToolRegistry(listOf(NoopLocalTool()))
        val gate = ToolGate(
            policyEngine = PolicyEngine(),
            registryProvider = { registry },
            hasPermission = { true },
            isAuthenticated = { true },
        )

        val result = gate.execute("send_email", mapOf("to" to "attacker@evil.com"))

        assertThat(result.errorCode).isEqualTo(AgentErrorCodes.POLICY_OUT_OF_SCOPE)
    }

    @Test
    fun `an unregistered tool name from provider text is rejected`() = runTest {
        val registry = ToolRegistry(listOf(NoopLocalTool()))
        val gate = ToolGate(
            policyEngine = PolicyEngine(),
            registryProvider = { registry },
            hasPermission = { true },
            isAuthenticated = { true },
        )

        val result = gate.execute("wire_transfer", emptyMap())

        assertThat(result.errorCode).isEqualTo(AgentErrorCodes.UNKNOWN_TOOL)
    }

    @Test
    fun `a permitted local tool executes through the gate`() = runTest {
        val registry = ToolRegistry(listOf(NoopLocalTool()))
        val gate = ToolGate(
            policyEngine = PolicyEngine(),
            registryProvider = { registry },
            hasPermission = { true },
            isAuthenticated = { true },
        )

        val result = gate.execute("noop_local", emptyMap())

        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo("ok")
    }

    @Test
    fun `a missing registry is UNAVAILABLE rather than a silent skip`() = runTest {
        val gate = ToolGate(
            policyEngine = PolicyEngine(),
            registryProvider = { null },
            hasPermission = { true },
            isAuthenticated = { true },
        )

        val result = gate.execute("noop_local", emptyMap())

        assertThat(result.outcome).isEqualTo(ActionOutcome.UNAVAILABLE)
        assertThat(result.errorCode).isEqualTo(AgentErrorCodes.TOOL_UNRESOLVED)
    }
}

/** Records what it was asked to post. */
internal class RecordingNotifier : ImportantResultNotifier {
    val posted = mutableListOf<ImportantResult>()

    override suspend fun notifyImportant(result: ImportantResult): NotifyResult {
        posted.add(result)
        return NotifyResult.Posted(NotificationContentFactory.build(result))
    }
}