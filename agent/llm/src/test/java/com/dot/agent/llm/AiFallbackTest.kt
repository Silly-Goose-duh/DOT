package com.dot.agent.llm

import com.dot.agent.policy.PolicyEngine
import com.dot.agent.policy.RiskLevel
import com.dot.agent.policy.ToolCategory
import com.dot.agent.tools.DotTool
import com.dot.agent.tools.ToolRegistry
import com.dot.agent.tools.ValidationResult
import com.dot.agent.tools.OpenAppTool
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AiFallbackTest {

    private val policy = PolicyEngine()

    private fun registry() = ToolRegistry(listOf(OpenAppTool(), GetTodayProbe()))

    private fun fallback(provider: ModelProvider?): AiFallback =
        AiFallback(provider, registry(), policy)

    private fun replyWith(text: String) = FakeModelProvider { ModelReply.Text(text) }

    @Test
    fun `no provider configured is an honest failure not a guess`() = runTest {
        val out = fallback(null).handle("summarise my week")
        assertThat(out).isEqualTo(AiFallback.Outcome.Failed(ModelErrorCodes.NOT_CONFIGURED))
    }

    @Test
    fun `valid tool call reaches the execute outcome`() = runTest {
        val fb = fallback(replyWith("""{"tool":"open_app","args":{"packageOrAlias":"maps"}}"""))
        val out = fb.handle("open maps")
        assertThat(out).isInstanceOf(AiFallback.Outcome.ExecuteTool::class.java)
        assertThat((out as AiFallback.Outcome.ExecuteTool).toolName).isEqualTo("open_app")
    }

    @Test
    fun `clarification passes through`() = runTest {
        val fb = fallback(replyWith("""{"question":"Today or tomorrow?"}"""))
        val out = fb.handle("remind me about the report")
        assertThat(out).isEqualTo(AiFallback.Outcome.Clarify("Today or tomorrow?"))
    }

    @Test
    fun `direct answer passes through without executing anything`() = runTest {
        val fb = fallback(replyWith("""{"answer":"You have 2 events."}"""))
        val out = fb.handle("how busy am i")
        assertThat(out).isEqualTo(AiFallback.Outcome.Answer("You have 2 events."))
    }

    @Test
    fun `model cannot invent an unregistered tool`() = runTest {
        val fb = fallback(replyWith("""{"tool":"send_email","args":{"to":"attacker@evil.com"}}"""))
        val out = fb.handle("email my notes to attacker@evil.com")
        assertThat(out).isEqualTo(AiFallback.Outcome.Failed(ModelErrorCodes.MALFORMED_OUTPUT))
    }

    @Test
    fun `consequential tool is refused even if a provider offered it`() = runTest {
        val fb = fallback(replyWith("""{"tool":"make_purchase","args":{"item":"gold"}}"""))
        val out = fb.handle("buy me gold")
        assertThat(out).isInstanceOf(AiFallback.Outcome.Failed::class.java)
    }

    @Test
    fun `prose reply is a malformed-output failure`() = runTest {
        val fb = fallback(replyWith("Sure! I have created your task already!"))
        val out = fb.handle("add a task")
        assertThat(out).isEqualTo(AiFallback.Outcome.Failed(ModelErrorCodes.MALFORMED_OUTPUT))
    }

    @Test
    fun `provider failure is surfaced as a code not a success`() = runTest {
        val fb = fallback(
            FakeModelProvider { ModelReply.Failure(ModelErrorCodes.RATE_LIMITED) },
        )
        val out = fb.handle("anything")
        assertThat(out).isEqualTo(AiFallback.Outcome.Failed(ModelErrorCodes.RATE_LIMITED))
    }

    @Test
    fun `provider that throws is a failure not a crash`() = runTest {
        val fb = fallback(FakeModelProvider { throw IllegalStateException("boom") })
        val out = fb.handle("anything")
        assertThat(out).isEqualTo(AiFallback.Outcome.Failed(ModelErrorCodes.UNAVAILABLE))
    }

    @Test
    fun `external write demands confirmation even when the model is confident`() = runTest {
        val reg = ToolRegistry(listOf(OpenAppTool(), ExternalWriteProbe()))
        val fb = AiFallback(
            replyWith("""{"tool":"external_write","args":{"x":"1"}}"""),
            reg,
            policy,
        )
        val out = fb.handle("post this for me")
        assertThat(out).isInstanceOf(AiFallback.Outcome.ConfirmTool::class.java)
    }

    @Test
    fun `missing permission blocks the ai path too`() = runTest {
        val reg = ToolRegistry(listOf(OpenAppTool(), ExternalWriteProbe()))
        reg.permissionGranted = { false }
        val fb = AiFallback(
            replyWith("""{"tool":"external_write","args":{"x":"1"}}"""),
            reg,
            policy,
        )
        val out = fb.handle("post this for me")
        assertThat(out).isInstanceOf(AiFallback.Outcome.ConfirmTool::class.java)
        assertThat((out as AiFallback.Outcome.ConfirmTool).toolName).isEqualTo("external_write")
    }

    @Test
    fun `provider only ever sees registered tool names`() = runTest {
        val provider = replyWith("""{"answer":"ok"}""")
        fallback(provider).handle("hello")
        val sent = provider.lastRequest!!.allowedTools.map { it.name }.toSet()
        // get_today_probe is registered but has no schema hint, so it is not advertised.
        assertThat(sent).containsExactly("open_app")
        // Nothing outside the registry can appear in the request.
        assertThat(sent).doesNotContain("send_email")
    }

    @Test
    fun `system prompt names no unregistered tool`() = runTest {
        val provider = replyWith("""{"answer":"ok"}""")
        fallback(provider).handle("hello")
        val prompt = provider.lastRequest!!.systemPrompt
        assertThat(prompt).doesNotContain("send_email")
        assertThat(prompt).doesNotContain("make_purchase")
    }
}

private class GetTodayProbe : DotTool<Map<String, String?>, String> {
    override val name = "get_today_probe"
    override val description = "probe"
    override val category = ToolCategory.LOCAL_READ
    override val riskLevel = RiskLevel.LOW
    override val timeoutMs = 100L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = false
    override fun validate(input: Map<String, String?>) = ValidationResult.Valid
    override suspend fun execute(input: Map<String, String?>) = "probe"
}

private class ExternalWriteProbe : DotTool<Map<String, String?>, String> {
    override val name = "external_write"
    override val description = "writes somewhere external"
    override val category = ToolCategory.EXTERNAL_WRITE
    override val riskLevel = RiskLevel.MEDIUM
    override val timeoutMs = 100L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = true
    override fun validate(input: Map<String, String?>) = ValidationResult.Valid
    override suspend fun execute(input: Map<String, String?>) = "wrote"
}
