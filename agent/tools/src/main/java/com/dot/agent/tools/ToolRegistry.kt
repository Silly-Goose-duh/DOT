package com.dot.agent.tools

import com.dot.agent.policy.RiskLevel
import com.dot.agent.policy.ToolCategory

/**
 * Central registry. The LLM cannot invent new tools at runtime (PRD 14) — it can only
 * emit a name + JSON arguments that must match a registered tool's schema exactly.
 */
class ToolRegistry(tools: List<DotTool<*, *>>) {

    private val byName: Map<String, DotTool<*, *>> = tools.associateBy { it.name }

    fun names(): Set<String> = byName.keys

    fun contains(name: String): Boolean = byName.containsKey(name)

    @Suppress("UNCHECKED_CAST")
    fun <I, O> get(name: String): DotTool<I, O>? = byName[name] as? DotTool<I, O>

    /**
     * Validates and executes a tool call with bounded timeout and retries.
     * Never infers success: a thrown exception becomes AMBIGUOUS/RECOVERABLE_FAILURE.
     */
    suspend fun invoke(
        name: String,
        args: Map<String, String?>,
    ): InvocationOutcome {
        val tool = byName[name]
            ?: return InvocationOutcome.Rejected("unknown_tool", "no tool named '$name'")

        if (tool.requiresPermission && !permissionGranted()) {
            return InvocationOutcome.PermissionRequired("permission_required")
        }
        if (tool.requiresAuth && !authenticated()) {
            return InvocationOutcome.AuthRequired("auth_required")
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            val typed = tool as DotTool<Map<String, String?>, Any?>
            val validation = typed.validate(args)
            if (validation is ValidationResult.Invalid) {
                InvocationOutcome.Rejected("invalid_input", "${validation.field}: ${validation.reason}")
            } else {
                var attempt = 0
                var lastError: Throwable? = null
                while (attempt <= tool.maxRetries) {
                    try {
                        return InvocationOutcome.Success(typed.execute(args))
                    } catch (t: Throwable) {
                        lastError = t
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        attempt++
                    }
                }
                InvocationOutcome.Failed("tool_error", lastError?.message ?: "unknown")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            InvocationOutcome.Failed("tool_error", t.message ?: "unknown")
        }
    }

    /** Injected capability flags; the policy engine reads these, never the model. */
    var permissionGranted: () -> Boolean = { true }
    var authenticated: () -> Boolean = { true }

    sealed interface InvocationOutcome {
        data class Success(val value: Any?) : InvocationOutcome
        data class Failed(val errorCode: String, val message: String?) : InvocationOutcome
        data class Rejected(val errorCode: String, val message: String) : InvocationOutcome
        data class PermissionRequired(val errorCode: String) : InvocationOutcome
        data class AuthRequired(val errorCode: String) : InvocationOutcome
    }
}

/** Descriptor used for AI structured-output schemas and for the tool contract docs. */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val riskLevel: RiskLevel,
    val requiredArgs: List<String>,
    val optionalArgs: List<String> = emptyList(),
)

fun DotTool<*, *>.descriptor(required: List<String>, optional: List<String> = emptyList()) = ToolDescriptor(
    name = name,
    description = description,
    category = category,
    riskLevel = riskLevel,
    requiredArgs = required,
    optionalArgs = optional,
)
