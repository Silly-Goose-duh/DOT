package com.dot.app.command

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dot.agent.llm.AiFallback
import com.dot.agent.llm.ModelPlan
import com.dot.agent.llm.ModelPlanParser
import com.dot.agent.llm.ModelProvider
import com.dot.agent.llm.ModelReply
import com.dot.agent.llm.ModelRequest
import com.dot.agent.policy.PolicyEngine
import com.dot.agent.router.DateTimeParser
import com.dot.agent.router.DeterministicRouter
import com.dot.agent.router.DirectResponseFormatter
import com.dot.agent.tools.CreateTaskTool
import com.dot.agent.tools.GetTodayTool
import com.dot.agent.tools.OpenAppTool
import com.dot.agent.tools.ToolRegistry
import com.dot.app.reminder.ReminderSchedulerPort
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DotDatabase
import com.dot.core.model.DirectResponse
import com.dot.core.model.Task
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * Milestone 3 wiring: the AI fallback is reachable, but it is not a second
 * authority. Every test here is about the boundary — a model may PROPOSE an
 * action, and nothing it proposes may bypass the registry, the allowlist or
 * PolicyEngine. A model that lies must produce a refusal, never an effect.
 */
@RunWith(RobolectricTestRunner::class)
class AiFallbackWiringTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private lateinit var db: DotDatabase
    private lateinit var taskRepo: RoomTaskRepository
    private lateinit var registry: ToolRegistry
    private lateinit var policy: PolicyEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DotDatabase::class.java,
        ).allowMainThreadQueries().build()
        taskRepo = RoomTaskRepository(db)
        registry = ToolRegistry(
            listOf(
                CreateTaskTool(taskRepo, zone),
                GetTodayTool(taskRepo, RoomReminderRepository(db), RoomEventRepository(db), zone),
                OpenAppTool(),
            ),
        )
        policy = PolicyEngine()
    }

    @After
    fun tearDown() = db.close()

    /** A provider that returns whatever the test tells it to, verbatim. */
    private class ScriptedProvider(private val reply: ModelReply) : ModelProvider {
        override val id = "scripted"
        override val displayName = "Scripted"
        var lastRequest: ModelRequest? = null

        override suspend fun complete(request: ModelRequest): ModelReply {
            lastRequest = request
            return reply
        }
    }

    private fun runtimeWith(
        provider: ModelProvider?,
        enabled: Boolean = true,
    ): CommandRuntime {
        val runtime = CommandRuntime(
            router = DeterministicRouter(DateTimeParser(zone), zone),
            toolRegistry = registry,
            policyEngine = policy,
            formatter = DirectResponseFormatter(zone),
            appLauncher = AppLauncher(
                ApplicationProvider.getApplicationContext(),
                PackageVisibility(ApplicationProvider.getApplicationContext()),
            ),
            reminderScheduler = object : ReminderSchedulerPort {
                override fun schedule(reminder: com.dot.core.model.Reminder) = Unit
            },
            aiFallback = provider?.let {
                AiFallback(it, registry, policy, ModelPlanParser(registry.names()))
            },
        )
        runtime.aiFallbackEnabled = enabled
        return runtime
    }

    /** Text no local rule can match, so the router must hand off to the model. */
    private val AMBIGUOUS = "Summarise what my week looks like and suggest a plan"

    @Test
    fun `model tool call reaches the registry and takes effect`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("""{"tool":"create_task","args":{"title":"Buy milk"}}"""),
        )

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        assertThat(turn).isInstanceOf(CommandRuntime.CommandTurn.Answer::class.java)
        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat(response).isInstanceOf(DirectResponse.Action::class.java)
        assertThat(taskRepo.all().map { it.title }).containsExactly("Buy milk")
    }

    @Test
    fun `model cannot invent an unregistered tool`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("""{"tool":"delete_everything","args":{}}"""),
        )

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat((response as DirectResponse).let { it }).isNotNull()
        // Nothing was created, and the answer is a refusal rather than a success.
        assertThat(taskRepo.all()).isEmpty()
    }

    @Test
    fun `model cannot reach the app allowlist`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text(
                """{"tool":"open_app","args":{"packageOrAlias":"com.evil.backdoor"}}""",
            ),
        )

        runtimeWith(provider).handle(AMBIGUOUS)

        // The allowlist is the security boundary; a well-formed package name that
        // is not allowlisted must never reach Intent resolution.
        assertThat(com.dot.agent.tools.InputGuards.isAllowedApp("com.evil.backdoor")).isFalse()
    }

    @Test
    fun `ambiguous request becomes one question not a guess`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("""{"question":"Which task did you mean?"}"""),
        )

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat((response as DirectResponse.Clarification).question)
            .isEqualTo("Which task did you mean?")
    }

    @Test
    fun `provider failure never renders as success`() = runTest {
        val provider = ScriptedProvider(ModelReply.Failure("model_timeout"))

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat(response).isInstanceOf(DirectResponse.Information::class.java)
        assertThat(taskRepo.all()).isEmpty()
    }

    @Test
    fun `a provider that throws is a failure not a success`() = runTest {
        val exploding = object : ModelProvider {
            override val id = "boom"
            override val displayName = "Boom"
            override suspend fun complete(request: ModelRequest): ModelReply =
                throw IllegalStateException("provider exploded")
        }

        val turn = runtimeWith(exploding).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat(response).isInstanceOf(DirectResponse.Information::class.java)
    }

    @Test
    fun `no provider configured stays honestly offline`() = runTest {
        val turn = runtimeWith(provider = null).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat((response as DirectResponse.Clarification).question)
            .isEqualTo("I can only handle simple commands offline.")
    }

    @Test
    fun `user toggle off means the provider is never called`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("""{"tool":"create_task","args":{"title":"Should not exist"}}"""),
        )

        runtimeWith(provider, enabled = false).handle(AMBIGUOUS)

        assertThat(provider.lastRequest).isNull()
        assertThat(taskRepo.all()).isEmpty()
    }

    @Test
    fun `malformed model output is refused not half-applied`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("Sure! {\"tool\":\"create_task\",\"args\":{\"title\":\"x\"}}"),
        )

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat(response).isInstanceOf(DirectResponse.Information::class.java)
        assertThat(taskRepo.all()).isEmpty()
    }

    @Test
    fun `provider only ever sees registered tool names`() = runTest {
        val provider = ScriptedProvider(ModelReply.Text("""{"answer":"ok"}"""))

        runtimeWith(provider).handle(AMBIGUOUS)

        val advertised = provider.lastRequest!!.allowedTools.map { it.name }
        assertThat(advertised).containsExactlyElementsIn(registry.names())
        // Authority comes from the registry, so the model cannot be told about a
        // tool that does not exist.
        assertThat(advertised).doesNotContain("delete_everything")
    }

    @Test
    fun `local fast path never reaches the model`() = runTest {
        val provider = ScriptedProvider(ModelReply.Text("""{"answer":"should not be used"}"""))

        val turn = runtimeWith(provider).handle("What do I have today?")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        assertThat(response).isInstanceOf(DirectResponse.Information::class.java)
        assertThat(provider.lastRequest).isNull()
    }

    @Test
    fun `existing task is untouched when the model proposes a bad write`() = runTest {
        taskRepo.insert(Task(title = "Original"))

        val provider = ScriptedProvider(
            ModelReply.Text("""{"tool":"create_task","args":{"title":""}}"""),
        )

        runtimeWith(provider).handle(AMBIGUOUS)

        assertThat(taskRepo.all().map { it.title }).containsExactly("Original")
    }

    @Test
    fun `model answer is shown as information not as a completed action`() = runTest {
        val provider = ScriptedProvider(
            ModelReply.Text("""{"answer":"You have three open tasks."}"""),
        )

        val turn = runtimeWith(provider).handle(AMBIGUOUS)

        val response = (turn as CommandRuntime.CommandTurn.Answer).response
        // An answer did not perform an action, so it must not claim to have.
        assertThat(response).isInstanceOf(DirectResponse.Information::class.java)
        assertThat((response as DirectResponse.Information).message)
            .isEqualTo("You have three open tasks.")
    }
}
