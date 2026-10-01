package com.dot.agent.llm

import com.dot.agent.policy.PolicyEngine
import com.dot.agent.policy.ToolCategory
import com.dot.agent.tools.ToolDescriptor
import com.dot.agent.tools.ToolRegistry

/**
 * Milestone 3: the AI fallback path.
 *
 * Order of authority, and it is not negotiable:
 *   provider text  ->  ModelPlanParser (validate)  ->  PolicyEngine (decide risk)
 *                  ->  ToolRegistry (execute)
 *
 * The provider is given the registry's descriptors as DATA. It cannot grant
 * itself a tool, cannot skip validation, and cannot make its own risk decision.
 * If no provider is configured, or anything fails, the answer is an honest
 * sentence — never a guess and never a silent success.
 */
class AiFallback(
    private val provider: ModelProvider?,
    private val toolRegistry: ToolRegistry,
    private val policyEngine: PolicyEngine,
    private val parser: ModelPlanParser = ModelPlanParser(toolRegistry.names()),
    private val timeoutMs: Long = 8_000,
) {

    /** What the caller should do. Mirrors CommandRuntime's vocabulary. */
    sealed interface Outcome {
        /** A registered tool call that passed policy. Caller executes it. */
        data class ExecuteTool(
            val toolName: String,
            val args: Map<String, String?>,
        ) : Outcome

        /** Policy requires an explicit yes before [toolName] runs. */
        data class ConfirmTool(
            val toolName: String,
            val args: Map<String, String?>,
            val prompt: String,
        ) : Outcome

        data class Clarify(val question: String) : Outcome

        /** A short answer needing no tool. */
        data class Answer(val text: String) : Outcome

        /** Nothing happened. [code] is a stable machine code for diagnostics. */
        data class Failed(val code: String) : Outcome
    }

    suspend fun handle(userText: String): Outcome {
        val p = provider ?: return Outcome.Failed(ModelErrorCodes.NOT_CONFIGURED)

        val request = ModelRequest(
            systemPrompt = SYSTEM_PROMPT,
            userText = userText,
            allowedTools = descriptors(),
            timeoutMs = timeoutMs,
        )

        val reply = try {
            p.complete(request)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A provider that throws is a provider that failed. Never a success.
            return Outcome.Failed(ModelErrorCodes.UNAVAILABLE)
        }

        return when (reply) {
            is ModelReply.Failure -> Outcome.Failed(reply.errorCode)
            is ModelReply.Text -> interpret(p, reply.content)
        }
    }

    private fun interpret(provider: ModelProvider, content: String): Outcome =
        when (val parsed = parser.parse(content)) {
            is ParseOutcome.Invalid -> Outcome.Failed(ModelErrorCodes.MALFORMED_OUTPUT)
            is ParseOutcome.Parsed -> when (val plan = parsed.plan) {
                is ModelPlan.Clarify -> Outcome.Clarify(plan.question)
                is ModelPlan.Answer -> Outcome.Answer(plan.text)
                is ModelPlan.ToolCall -> gateAgainstPolicy(plan)
            }
        }

    private fun gateAgainstPolicy(plan: ModelPlan.ToolCall): Outcome {
        val tool = toolRegistry.get<Any, Any>(plan.toolName)
            // Belt and braces: the parser already checked membership.
            ?: return Outcome.Failed("unknown_tool")

        if (policyEngine.isOutOfScope(plan.toolName)) {
            return Outcome.Failed("out_of_scope")
        }

        val decision = policyEngine.evaluate(
            category = tool.category,
            hasPermission = toolRegistry.permissionGranted(),
            isAuthenticated = toolRegistry.authenticated(),
        )

        return if (decision.requiresConfirmation) {
            Outcome.ConfirmTool(
                toolName = plan.toolName,
                args = plan.arguments,
                prompt = "Confirm: ${tool.description}",
            )
        } else {
            Outcome.ExecuteTool(plan.toolName, plan.arguments)
        }
    }

    private fun descriptors(): List<ToolSchema> = SCHEMA_HINTS.mapNotNull { (name, schema) ->
        toolRegistry.contains(name).let { if (it) ToolSchema(name, schema) else null }
    }

    private companion object {
        /**
         * Descriptions shown to the model. These are hints about REGISTERED tools;
         * authority still comes from the registry, never from this text.
         */
        val SCHEMA_HINTS = mapOf(
            "get_today" to "Show today's events, open tasks and reminders.",
            "create_task" to "Create a task. Args: title (required), dueAt (optional ISO-8601).",
            "complete_task" to "Complete a task. Args: taskRef (required, id or title fragment).",
            "create_reminder" to "Create a reminder. Args: title, remindAt (required ISO-8601).",
            "create_note" to "Save a note. Args: body (required), title (optional).",
            "search_local" to "Search local tasks and notes. Args: query (required).",
            "open_app" to "Open a supported app. Args: packageOrAlias (required, allowlisted).",
        )

        const val SYSTEM_PROMPT =
            "You route short user requests in a personal productivity app. " +
                "Reply with ONE JSON object and nothing else. " +
                "Exactly one of: " +
                "{\"tool\":\"<registered name>\",\"args\":{...}}, " +
                "{\"question\":\"...\"} when the request is ambiguous, or " +
                "{\"answer\":\"...\"} for a short factual reply. " +
                "Use only the listed tools. Never invent a tool. " +
                "Never include prose, markdown, or code outside the JSON object."
    }
}

/** Tools whose category forces the AI path to ask before acting. */
internal val ToolCategory.isAiConfirmable: Boolean
    get() = this == ToolCategory.EXTERNAL_WRITE
