package com.dot.agent.llm

/**
 * Provider abstraction so the model backend stays replaceable (PRD 31).
 *
 * A provider does exactly one thing: turn a request into raw text, or fail with a
 * typed code. It does NOT decide what happens next — parsing, validation, policy
 * and tool dispatch all live in [AiFallback], deliberately outside the provider.
 */
interface ModelProvider {
    /** Stable identifier for diagnostics and settings. Never contains a secret. */
    val id: String

    /** Human-readable label for the settings screen. */
    val displayName: String

    /**
     * Sends one request. Implementations MUST apply their own timeout and MUST NOT
     * retry unboundedly. A failure is a typed [ModelReply.Failure], never a throw
     * that the caller could mistake for success.
     */
    suspend fun complete(request: ModelRequest): ModelReply
}

data class ModelRequest(
    /** Instructions. Tool authority never comes from here — see [AiFallback]. */
    val systemPrompt: String,
    val userText: String,
    /** Descriptors of REGISTERED tools only. The model cannot add to this list. */
    val allowedTools: List<ToolSchema>,
    val timeoutMs: Long = 8_000,
)

/** Minimal schema handed to the model. Mirrors [com.dot.agent.tools.ToolDescriptor]. */
data class ToolSchema(
    val name: String,
    val description: String,
    val requiredArgs: List<String> = emptyList(),
    val optionalArgs: List<String> = emptyList(),
)

sealed interface ModelReply {
    data class Text(val content: String) : ModelReply

    /**
     * Typed failure. [errorCode] is a stable machine code, never free text.
     * [retryable] is a hint for the provider's own bounded retry loop; it is not
     * part of the product contract and never surfaces to the user.
     */
    data class Failure(
        val errorCode: String,
        val message: String? = null,
        val retryable: Boolean = false,
    ) : ModelReply
}

/** Stable failure codes. Providers map their own errors onto these. */
object ModelErrorCodes {
    const val TIMEOUT = "model_timeout"
    const val UNAVAILABLE = "model_unavailable"
    const val AUTH_REQUIRED = "model_auth_required"
    const val RATE_LIMITED = "model_rate_limited"
    const val MALFORMED_OUTPUT = "model_malformed_output"
    const val NOT_CONFIGURED = "model_not_configured"
}
