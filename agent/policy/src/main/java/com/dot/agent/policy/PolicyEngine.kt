package com.dot.agent.policy

/** PRD 20 risk categories. */
enum class RiskLevel {
    /** Reads local state, opens apps, creates reversible local items. */
    LOW,

    /** Shares data externally, modifies external state, sends messages. */
    MEDIUM,

    /** Purchases, destructive deletes, security changes. OUT OF SCOPE for v0.1. */
    HIGH,
}

enum class ToolCategory {
    LOCAL_READ, LOCAL_WRITE, EXTERNAL_READ, EXTERNAL_WRITE, SYSTEM_ACTION, USER_DIALOG,
}

data class RiskDecision(
    val requiresConfirmation: Boolean,
    val reason: String? = null,
)

/**
 * Deterministic policy engine. The LLM never decides this — SECURITY.md rule 3.
 * In v0.1, every HIGH-risk tool is refused outright rather than confirmed (PRD 15).
 */
class PolicyEngine {

    fun evaluate(category: ToolCategory, hasPermission: Boolean, isAuthenticated: Boolean): RiskDecision {
        if (category == ToolCategory.USER_DIALOG) {
            return RiskDecision(requiresConfirmation = false)
        }

        if (!hasPermission && category in PERMISSION_GATED) {
            return RiskDecision(requiresConfirmation = true, reason = "permission_required")
        }
        if (!isAuthenticated && category in AUTH_GATED) {
            return RiskDecision(requiresConfirmation = true, reason = "auth_required")
        }

        return when (category) {
            ToolCategory.LOCAL_READ, ToolCategory.LOCAL_WRITE -> RiskDecision(false)
            ToolCategory.SYSTEM_ACTION -> RiskDecision(false)
            ToolCategory.EXTERNAL_READ -> RiskDecision(false)
            ToolCategory.EXTERNAL_WRITE -> RiskDecision(true, reason = "external_state_change")
            ToolCategory.USER_DIALOG -> RiskDecision(false)
        }
    }

    /** Hard block for v0.1 consequential actions (purchases, sends, deletes, security changes). */
    fun isOutOfScope(toolName: String): Boolean = toolName in OUT_OF_SCOPE_V01

    private companion object {
        val PERMISSION_GATED = setOf(
            ToolCategory.EXTERNAL_READ, ToolCategory.EXTERNAL_WRITE,
        )
        val AUTH_GATED = setOf(
            ToolCategory.EXTERNAL_READ, ToolCategory.EXTERNAL_WRITE,
        )
        val OUT_OF_SCOPE_V01 = setOf(
            "send_email", "send_message", "delete_email", "make_purchase",
            "change_account_settings", "change_security_settings",
            "bulk_delete_external", "transfer_money",
        )
    }
}
