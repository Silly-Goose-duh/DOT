package com.dot.app.command

import com.dot.agent.policy.PolicyEngine
import com.dot.agent.router.DeterministicRouter
import com.dot.agent.router.DirectResponseFormatter
import com.dot.agent.router.RoutedCommand
import com.dot.agent.router.RoutingOutcome
import com.dot.agent.tools.InputGuards
import com.dot.agent.tools.ToolRegistry.InvocationOutcome
import com.dot.agent.tools.ToolRegistry
import com.dot.app.reminder.ReminderSchedulerPort
import com.dot.app.system.AppLauncher
import com.dot.core.model.ActionOutcome
import com.dot.core.model.ActionResult
import com.dot.core.model.DirectResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Joins the pieces that existed but had never been connected: router -> policy ->
 * tool registry -> typed result -> one short sentence.
 *
 * Deliberate properties, in order of importance:
 *  - The router's confidence threshold is enforced here. Below it we ask, we do
 *    not guess, and we never escalate straight to a model (none ships in v0.1).
 *  - Confirmation is decided by [PolicyEngine], never by the router's opinion.
 *  - Every tool result becomes an [ActionResult]. A failure never renders as success.
 */
class CommandRuntime(
    private val router: DeterministicRouter,
    private val toolRegistry: ToolRegistry,
    private val policyEngine: PolicyEngine,
    private val formatter: DirectResponseFormatter,
    private val appLauncher: AppLauncher,
    private val reminderScheduler: ReminderSchedulerPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    sealed interface CommandTurn {
        /** The tool ran (or was correctly refused) and there is a sentence to show. */
        data class Answer(val response: DirectResponse) : CommandTurn

        /** Policy demands a yes/no before anything happens. */
        data class Confirm(
            val prompt: String,
            val pending: PendingAction,
        ) : CommandTurn
    }

    /** A routed command held between the confirmation prompt and the user's reply. */
    data class PendingAction(
        val toolName: String,
        val args: Map<String, String?>,
        val intentLabel: String,
    )

    suspend fun handle(text: String): CommandTurn = withContext(dispatcher) {
        when (val outcome = router.route(text)) {
            is RoutingOutcome.NeedsClarification ->
                CommandTurn.Answer(DirectResponse.Clarification(outcome.question))

            // No ModelProvider exists in v0.1, so the AI seam degrades to an
            // honest "I could not match that" instead of a fake answer.
            is RoutingOutcome.NeedsAi ->
                CommandTurn.Answer(DirectResponse.Clarification(NO_MODEL_MESSAGE))

            is RoutingOutcome.Routed -> executeRouted(outcome.command)
        }
    }

    /** Runs a previously confirmed action. Only reachable after a [CommandTurn.Confirm]. */
    suspend fun confirm(pending: PendingAction): CommandTurn = withContext(dispatcher) {
        CommandTurn.Answer(runTool(pending.toolName, pending.args))
    }

    private suspend fun executeRouted(command: RoutedCommand): CommandTurn {
        if (command.confidence < LOCAL_CONFIDENCE_THRESHOLD) {
            return CommandTurn.Answer(
                DirectResponse.Clarification("Did you mean something simpler?"),
            )
        }

        val spec = specFor(command)
            ?: return CommandTurn.Answer(DirectResponse.Clarification(UNSUPPORTED_INTENT))

        if (policyEngine.isOutOfScope(spec.toolName)) {
            return CommandTurn.Answer(
                DirectResponse.Information("That action isn't available yet."),
            )
        }

        if (spec.toolName == REJECTED) {
            return CommandTurn.Answer(
                DirectResponse.Information("That app isn't supported."),
            )
        }

        val tool = toolRegistry.get<Any, Any>(spec.toolName)
            ?: return CommandTurn.Answer(
                DirectResponse.Information("That action isn't available yet."),
            )

        val decision = policyEngine.evaluate(
            category = tool.category,
            hasPermission = toolRegistry.permissionGranted(),
            isAuthenticated = toolRegistry.authenticated(),
        )
        if (decision.requiresConfirmation) {
            return CommandTurn.Confirm(
                prompt = "Confirm: ${spec.confirmPhrase}",
                pending = PendingAction(spec.toolName, spec.args, spec.intentLabel),
            )
        }

        return CommandTurn.Answer(runTool(spec.toolName, spec.args))
    }

    private suspend fun runTool(toolName: String, args: Map<String, String?>): DirectResponse {
        // ActionResult is generic in its value; nothing here knows or cares about
        // the payload type, so it is pinned to Any? explicitly.
        val result: ActionResult<Any?> = when (val outcome = toolRegistry.invoke(toolName, args)) {
            is InvocationOutcome.Success -> ActionResult.success<Any?>(outcome.value)

            is InvocationOutcome.Rejected -> ActionResult.failure<Any?>(
                outcome = ActionOutcome.RECOVERABLE_FAILURE,
                errorCode = outcome.errorCode,
                message = outcome.message,
            )

            is InvocationOutcome.PermissionRequired ->
                ActionResult.failure<Any?>(ActionOutcome.PERMISSION_REQUIRED, outcome.errorCode)

            is InvocationOutcome.AuthRequired ->
                ActionResult.failure<Any?>(ActionOutcome.AUTH_REQUIRED, outcome.errorCode)

            is InvocationOutcome.Failed ->
                ActionResult.failure<Any?>(
                    ActionOutcome.RECOVERABLE_FAILURE,
                    outcome.errorCode,
                    outcome.message,
                )
        }

        // Side effects the platform owns. Only reached after a successful tool call,
        // so a refused action never fires an alarm or launches an app.
        if (result.isSuccess) {
            afterSuccess(toolName, result.value)
        }

        return formatter.format(
            outcome = RoutingOutcome.Routed(
                RoutedCommand(
                    intent = intentFor(toolName),
                    confidence = 1.0,
                ),
            ),
            result = result,
        )
    }

    private fun afterSuccess(toolName: String, value: Any?) {
        when (toolName) {
            "create_reminder" -> {
                val r = value as? com.dot.core.model.Reminder ?: return
                reminderScheduler.schedule(r)
            }

            "open_app" -> {
                val pkg = value as? String ?: return
                appLauncher.launch(pkg)
            }
        }
    }

    private fun intentFor(toolName: String) = when (toolName) {
        "get_today" -> com.dot.agent.router.CommandIntent.GET_TODAY
        "create_task" -> com.dot.agent.router.CommandIntent.CREATE_TASK
        "complete_task" -> com.dot.agent.router.CommandIntent.COMPLETE_TASK
        "create_reminder" -> com.dot.agent.router.CommandIntent.CREATE_REMINDER
        "create_note" -> com.dot.agent.router.CommandIntent.CREATE_NOTE
        "search_local" -> com.dot.agent.router.CommandIntent.SEARCH_LOCAL
        "open_app" -> com.dot.agent.router.CommandIntent.OPEN_APP
        else -> com.dot.agent.router.CommandIntent.UNKNOWN_COMPLEX
    }

    private data class ToolSpec(
        val toolName: String,
        val args: Map<String, String?>,
        val confirmPhrase: String,
        val intentLabel: String,
    )

    private fun specFor(command: RoutedCommand): ToolSpec? {
        val a = command.arguments
        return when (command.intent) {
            com.dot.agent.router.CommandIntent.GET_TODAY ->
                ToolSpec("get_today", emptyMap(), "show today", "today")

            com.dot.agent.router.CommandIntent.CREATE_TASK ->
                a.title?.let {
                    ToolSpec("create_task", mapOf("title" to it, "dueAt" to a.dueAt), "add \"$it\"", "add task")
                }

            com.dot.agent.router.CommandIntent.COMPLETE_TASK ->
                a.taskRef?.let {
                    ToolSpec("complete_task", mapOf("taskRef" to it), "complete \"$it\"", "complete task")
                }

            com.dot.agent.router.CommandIntent.CREATE_REMINDER ->
                a.title?.let { t ->
                    a.remindAt?.let {
                        ToolSpec(
                            "create_reminder",
                            mapOf("title" to t, "remindAt" to it),
                            "remind you \"$t\"",
                            "create reminder",
                        )
                    }
                }

            com.dot.agent.router.CommandIntent.CREATE_NOTE ->
                a.title?.let {
                    ToolSpec("create_note", mapOf("title" to it, "body" to it), "save a note", "create note")
                }

            com.dot.agent.router.CommandIntent.SEARCH_LOCAL ->
                a.query?.let {
                    ToolSpec("search_local", mapOf("query" to it), "search for \"$it\"", "search")
                }

            com.dot.agent.router.CommandIntent.OPEN_APP ->
                a.packageOrAlias?.let { ref ->
                    // Reject an unknown alias here rather than passing free text to
                    // the launcher; the allowlist is the security boundary.
                    if (InputGuards.isAllowedApp(ref)) {
                        ToolSpec("open_app", mapOf("packageOrAlias" to ref), "open \"$ref\"", "open app")
                    } else {
                        ToolSpec(REJECTED, emptyMap(), "open \"$ref\"", "open app")
                    }
                }

            com.dot.agent.router.CommandIntent.LIST_EVENTS ->
                ToolSpec("get_today", emptyMap(), "show today", "today")

            else -> null
        }
    }

    private companion object {
        const val LOCAL_CONFIDENCE_THRESHOLD = 0.75
        const val NO_MODEL_MESSAGE = "I can only handle simple commands offline."
        const val UNSUPPORTED_INTENT = "I can't do that yet."
        /** Sentinel spec name meaning "blocked before the registry", not a real tool. */
        const val REJECTED = "__rejected__"
    }
}
