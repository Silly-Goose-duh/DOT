package com.dot.agent.router

import kotlinx.serialization.Serializable

/** PRD 13 intent taxonomy. Router returns structured commands, never executable code. */
@Serializable
enum class CommandIntent {
    GET_TODAY,
    CREATE_TASK,
    COMPLETE_TASK,
    CREATE_REMINDER,
    LIST_EVENTS,
    CREATE_NOTE,
    SEARCH_LOCAL,
    RUN_AGENT,
    OPEN_APP,
    UNKNOWN_COMPLEX,
}

@Serializable
data class CommandArguments(
    val title: String? = null,
    val query: String? = null,
    val remindAt: String? = null,
    val dueAt: String? = null,
    val packageOrAlias: String? = null,
    val agentId: String? = null,
    val taskRef: String? = null,
)

@Serializable
data class RoutedCommand(
    val intent: CommandIntent,
    val arguments: CommandArguments = CommandArguments(),
    val confidence: Double,
    val requiresConfirmation: Boolean = false,
    /** LOCAL means deterministic fast path; AI means remote fallback was needed. */
    val route: Route = Route.LOCAL,
)

enum class Route { LOCAL, AI }

/** Confidence below this forces the AI path or a clarification (never a silent guess). */
const val LOCAL_CONFIDENCE_THRESHOLD = 0.75

/** Result of routing, including the need to ask the user. */
sealed interface RoutingOutcome {
    data class Routed(val command: RoutedCommand) : RoutingOutcome
    data class NeedsClarification(val question: String) : RoutingOutcome
    data class NeedsAi(val rawText: String) : RoutingOutcome
}
