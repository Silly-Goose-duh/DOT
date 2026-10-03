package com.dot.agent.agents

/**
 * Stable machine codes for background-agent failures.
 *
 * These are the only failure strings persisted to `agent_runs.errorCode` or shown
 * in the Agents screen. Free text from a provider, a throwable message or a
 * remote body is never used as a code — a code is for branching and telemetry,
 * and it must not leak content (SECURITY.md logging rules).
 */
object AgentErrorCodes {

    /** No AgentDefinition row for the requested id. */
    const val AGENT_NOT_FOUND = "agent_not_found"

    /** No registered implementation for the definition's `type`. */
    const val AGENT_TYPE_UNSUPPORTED = "agent_type_unsupported"

    /** The task exceeded its own budget; outcome is AMBIGUOUS by definition. */
    const val AGENT_TIMEOUT = "agent_timeout"

    /** A throwable escaped the task. Never surfaced as success. */
    const val AGENT_UNEXPECTED_ERROR = "agent_unexpected_error"

    /** Coroutine cancelled (worker stopped, user disabled, process shutting down). */
    const val AGENT_CANCELLED = "agent_cancelled"

    /** PolicyEngine.isOutOfScope() refused the tool outright (PRD 15). */
    const val POLICY_OUT_OF_SCOPE = "policy_out_of_scope"

    /** Policy required confirmation, which a background run cannot obtain. */
    const val POLICY_CONFIRMATION_REQUIRED = "policy_confirmation_required"

    /** The proposed tool name is not registered. Model/agent text cannot add one. */
    const val UNKNOWN_TOOL = "unknown_tool"

    /** Registered tool rejected the typed arguments. */
    const val INVALID_TOOL_INPUT = "invalid_tool_input"

    /** Tool executed and still did not resolve to one outcome. */
    const val TOOL_UNRESOLVED = "tool_unresolved"

    /** Retry budget exhausted; the run stops until the next user-initiated attempt. */
    const val RETRY_EXHAUSTED = "retry_exhausted"

    /** Inbox provider reports the grant is gone; user must reconnect. */
    const val INBOX_AUTH_REQUIRED = "inbox_auth_required"

    /** Inbox provider is unreachable/offline. */
    const val INBOX_UNAVAILABLE = "inbox_unavailable"

    /** Provider replied but not in a shape we accept. */
    const val INBOX_MALFORMED_RESPONSE = "inbox_malformed_response"

    /** Optional AI summarisation failed; the local summary was used instead. */
    const val SUMMARISER_UNAVAILABLE = "summariser_unavailable"
}

/**
 * Diagnostic formatter restricted to SECURITY.md's allowed fields.
 *
 * Deliberately formats nothing that came from remote or model content: there is
 * no `message` parameter to pass one through. `:core:common`'s LogRedactor is not
 * on this module's classpath yet, so once the parent wires `:core:common` in,
 * `DotAgentLog` should delegate [id] to `LogRedactor.redactId` and keep the same
 * field allow-list.
 */
object DotAgentLog {

    fun event(
        event: String,
        agentId: String? = null,
        outcome: String? = null,
        durationMs: Long? = null,
    ): String = buildString {
        append("event=").append(event)
        agentId?.let { append(" agent=").append(hashId(it)) }
        outcome?.let { append(" result=").append(it) }
        durationMs?.let { append(" duration_ms=").append(it) }
    }

    /**
     * Agent ids are stable strings chosen by us (not user data), but they are
     * still hashed so a build log cannot be correlated back to a user's agent
     * naming choices.
     */
    private fun hashId(id: String): String = "[redacted]:" + id.hashCode().toUInt().toString(16)
}