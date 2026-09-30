package com.dot.agent.tools

import com.dot.agent.policy.RiskLevel
import com.dot.agent.policy.ToolCategory

/**
 * A typed, registered tool. Model output cannot bypass the registry (SECURITY.md rule 2).
 * Every tool declares its input schema, risk, timeout, retry policy and audit behaviour.
 */
interface DotTool<I, O> {
    val name: String
    val description: String
    val category: ToolCategory
    val riskLevel: RiskLevel
    val timeoutMs: Long
    val maxRetries: Int
    val requiresPermission: Boolean
    val requiresAuth: Boolean
    val audited: Boolean

    /** Strict validation. Reject malformed or injection-shaped input BEFORE execution. */
    fun validate(input: I): ValidationResult

    suspend fun execute(input: I): O
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val field: String, val reason: String) : ValidationResult
}

/** Shared guards used by tool input validators. */
object InputGuards {
    const val MAX_TITLE_LENGTH = 200
    const val MAX_BODY_LENGTH = 4000
    const val MAX_QUERY_LENGTH = 120

    fun title(value: String?): ValidationResult = when {
        value.isNullOrBlank() -> ValidationResult.Invalid("title", "must not be blank")
        value.length > MAX_TITLE_LENGTH ->
            ValidationResult.Invalid("title", "exceeds $MAX_TITLE_LENGTH characters")
        else -> ValidationResult.Valid
    }

    fun body(value: String?): ValidationResult = when {
        value == null -> ValidationResult.Valid
        value.length > MAX_BODY_LENGTH ->
            ValidationResult.Invalid("body", "exceeds $MAX_BODY_LENGTH characters")
        else -> ValidationResult.Valid
    }

    fun query(value: String?): ValidationResult = when {
        value.isNullOrBlank() -> ValidationResult.Invalid("query", "must not be blank")
        value.length > MAX_QUERY_LENGTH ->
            ValidationResult.Invalid("query", "exceeds $MAX_QUERY_LENGTH characters")
        else -> ValidationResult.Valid
    }

    /**
     * Package/alias allowlist. Free-form strings are NOT passed to Intent resolution;
     * only a registered alias or a well-formed package name is accepted.
     */
    fun packageRef(value: String?): ValidationResult = when {
        value.isNullOrBlank() -> ValidationResult.Invalid("packageOrAlias", "must not be blank")
        value.length > 128 -> ValidationResult.Invalid("packageOrAlias", "too long")
        // Allowlist membership only. Matching PACKAGE_REGEX was enough to admit
        // any syntactically valid package name (e.g. com.evil.backdoor), which
        // then reached Intent resolution — the opposite of what this guard claims.
        isAllowedApp(value) -> ValidationResult.Valid
        else -> ValidationResult.Invalid("packageOrAlias", "not a supported app")
    }

    /** True when [value] is a known alias or one of the allowlisted packages. */
    fun isAllowedApp(value: String): Boolean {
        val v = value.trim().lowercase()
        return v in APP_ALIASES || APP_ALIASES.values.any { it == v }
    }

    /** v0.1 supported apps. Everything else requires clarification. */
    val APP_ALIASES: Map<String, String> = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "settings" to "com.android.settings",
        "clock" to "com.android.deskclock",
        "camera" to "com.android.camera",
        "calculator" to "com.android.calculator2",
        "phone" to "com.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "calendar" to "com.google.android.calendar",
    )
}
