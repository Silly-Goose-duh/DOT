package com.dot.app.command

import com.dot.agent.llm.AiFallback
import com.dot.agent.llm.ModelErrorCodes
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
    /**
     * Optional. Null means no provider is configured, which is the honest offline
     * state rather than a stub — the runtime then answers "I could not match that"
     * instead of pretending. A non-null fallback never gains authority: it can
     * only propose a registered tool, which then goes through the same policy
     * gate and the same registry as a locally routed command.
     */
    private val aiFallback: com.dot.agent.llm.AiFallback? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * The user's AI toggle, independent of whether a provider is configured. Both must
     * be true to reach a model.
     *
     * Defaults to FALSE, matching `DotSettings.aiFallbackEnabled`. An earlier version
     * defaulted this to true while the persisted setting defaulted to false, and because
     * nothing pushed the persisted value into the runtime at startup, a fresh install
     * sent utterances to a remote model while the Settings switch displayed "off". The
     * safe default is the one that keeps data on the device until the user opts in.
     */
    @Volatile
    var aiFallbackEnabled: Boolean = false

    /**
     * Whether policy-mandated confirmations are actually enforced.
     *
     * The Settings switch drove only the switch's own `checked` value; nothing read it,
     * so turning confirmations off did nothing. Defaults to TRUE: confirmations are a
     * safety control and must not be silently disabled by a wiring gap.
     */
    @Volatile
    var confirmationsEnabled: Boolean = true

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

            // The deterministic path is exhausted. Only now may a model be asked,
            // and only to propose a tool call — the proposal is re-validated by the
            // parser, re-gated by PolicyEngine and executed by the same registry a
            // local command uses. With no provider the answer is an honest sentence.
            is RoutingOutcome.NeedsAi -> handleViaAi(outcome.rawText)

            is RoutingOutcome.Routed -> executeRouted(outcome.command)
        }
    }

    /** Runs a previously confirmed action. Only reachable after a [CommandTurn.Confirm]. */
    suspend fun confirm(pending: PendingAction): CommandTurn = withContext(dispatcher) {
        CommandTurn.Answer(runTool(pending.toolName, pending.args))
    }

    /**
     * Bridges a model proposal into the same execution path a local command takes.
     *
     * The model never decides anything here. [AiFallback] has already validated the
     * shape and consulted [PolicyEngine]; this method only translates the outcome and,
     * critically, re-checks registry membership and the app allowlist before anything
     * executes. A proposal naming an unregistered tool is refused here even if every
     * earlier layer were wrong, so one missed check is not a security hole.
     */
    private suspend fun handleViaAi(rawText: String): CommandTurn {
        val fallback = aiFallback
            ?: return CommandTurn.Answer(DirectResponse.Clarification(NO_MODEL_MESSAGE))
        if (!aiFallbackEnabled) {
            return CommandTurn.Answer(DirectResponse.Clarification(AI_DISABLED_MESSAGE))
        }

        return when (val outcome = fallback.handle(rawText)) {
            is AiFallback.Outcome.Clarify ->
                CommandTurn.Answer(DirectResponse.Clarification(outcome.question))

            is AiFallback.Outcome.Answer ->
                CommandTurn.Answer(DirectResponse.Information(outcome.text))

            is AiFallback.Outcome.ExecuteTool -> {
                val spec = aiSpecFor(outcome.toolName, outcome.args)
                    ?: return CommandTurn.Answer(
                        DirectResponse.Information("That action isn't available yet."),
                    )
                if (policyEngine.isOutOfScope(spec.toolName)) {
                    return CommandTurn.Answer(
                        DirectResponse.Information("That action isn't available yet."),
                    )
                }
                CommandTurn.Answer(runTool(spec.toolName, spec.args))
            }

            // Policy gates this tool. We ask the user and hold the action here — the
            // model cannot approve its own request.
            is AiFallback.Outcome.ConfirmTool -> {
                val spec = aiSpecFor(outcome.toolName, outcome.args)
                    ?: return CommandTurn.Answer(
                        DirectResponse.Information("That action isn't available yet."),
                    )
                if (confirmationsEnabled) {
                    CommandTurn.Confirm(
                        prompt = outcome.prompt,
                        pending = PendingAction(spec.toolName, spec.args, "ai: ${spec.toolName}"),
                    )
                } else {
                    CommandTurn.Answer(runTool(spec.toolName, spec.args))
                }
            }

            // Every failure is a typed code surfaced as an honest sentence. An
            // unreachable or unconfigured provider must never look like a success.
            is AiFallback.Outcome.Failed ->
                CommandTurn.Answer(DirectResponse.Information(aiFailureMessage(outcome.code)))
        }
    }

    /**
     * Last gate before an AI-proposed tool call. Returns null unless the tool is
     * registered AND, for open_app, allowlisted — mirroring [specFor]'s local rules.
     */
    private fun aiSpecFor(toolName: String, args: Map<String, String?>): ToolSpec? {
        if (!toolRegistry.contains(toolName)) return null
        if (toolName == "open_app") {
            val ref = args["packageOrAlias"] ?: return null
            if (!InputGuards.isAllowedApp(ref)) return null
        }
        return ToolSpec(
            toolName = toolName,
            args = args,
            confirmPhrase = toolName,
            intentLabel = "ai: $toolName",
        )
    }

    /**
     * Maps a stable provider error code to one short sentence. Codes stay internal
     * (they are a diagnostics contract, not copy) so no provider name, key or internal
     * detail reaches the chat log.
     */
    private fun aiFailureMessage(code: String): String = when (code) {
        ModelErrorCodes.NOT_CONFIGURED -> "I can only handle simple commands offline."
        ModelErrorCodes.TIMEOUT -> "That took too long. Try again."
        ModelErrorCodes.UNAVAILABLE, ModelErrorCodes.RATE_LIMITED ->
            "I couldn't reach the assistant. Try again shortly."
        ModelErrorCodes.AUTH_REQUIRED -> "The assistant isn't authorised. Check settings."
        ModelErrorCodes.MALFORMED_OUTPUT -> "I couldn't understand that. Try rephrasing."
        "unknown_tool", "out_of_scope" -> "That action isn't available yet."
        else -> "I couldn't do that."
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
        if (decision.requiresConfirmation && confirmationsEnabled) {
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
            // A platform side effect can fail even when the tool succeeded, and its
            // typed outcome must replace the tool's success rather than be dropped.
            // Launching a package that is not installed returns UNAVAILABLE, and
            // discarding that made the UI say "Opening." for an app that never opened.
            val sideEffect = afterSuccess(toolName, result.value)
            if (sideEffect != null) {
                return formatter.format(
                    outcome = RoutingOutcome.Routed(
                        RoutedCommand(intent = intentFor(toolName), confidence = 1.0),
                    ),
                    result = sideEffect,
                )
            }
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

    /**
     * Runs the platform-owned side effect of a successful tool and returns its
     * outcome when the side effect failed, or null when it succeeded or is not
     * applicable. Returning null means "carry the tool's own success forward".
     */
    private fun afterSuccess(toolName: String, value: Any?): ActionResult<Any?>? {
        when (toolName) {
            "create_reminder" -> {
                val r = value as? com.dot.core.model.Reminder ?: return null
                // AlarmManager returns nothing, so there is no outcome to verify here.
                // The reminder row is already persisted by the tool.
                reminderScheduler.schedule(r)
            }

            "open_app" -> {
                val pkg = value as? String ?: return null
                val launch = appLauncher.launch(pkg)
                @Suppress("UNCHECKED_CAST")
                return launch as ActionResult<Any?>?
            }
        }
        return null
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
        const val AI_DISABLED_MESSAGE = "AI help is off. Turn it on in Settings."
        const val UNSUPPORTED_INTENT = "I can't do that yet."
        /** Sentinel spec name meaning "blocked before the registry", not a real tool. */
        const val REJECTED = "__rejected__"
    }
}
