package com.dot.core.common

/**
 * PRD 21 / SECURITY.md logging rules.
 * Allowed: event name, duration, result code, tool name, redacted IDs, build info.
 * Forbidden: tokens, passwords, API keys, full email bodies, note bodies, raw voice, prompts.
 */
object LogRedactor {
    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)\b(bearer)\s+[A-Za-z0-9\-._~+/]+=*"""),
        Regex("""(?i)\b(ya29\.)[A-Za-z0-9\-._~+/]+"""),
        Regex("""(?i)\b(1//)[A-Za-z0-9\-._~+/]+"""),
        Regex("""(?i)(sk|pk|ghp|gho|xox[baprs])[-_][A-Za-z0-9\-_]{10,}"""),
        Regex("""(?i)\b(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\b\s*[:=]\s*\S+"""),
        Regex("""(?i)\beyJ[A-Za-z0-9\-_]{8,}\.[A-Za-z0-9\-_]{8,}\.[A-Za-z0-9\-_]{8,}"""),
    )

    const val REDACTED = "[redacted]"

    fun redact(input: String): String {
        var out = input
        for (p in SECRET_PATTERNS) out = p.replace(out, REDACTED)
        return out
    }

    /** Redacts a value destined for a log field, truncating to a safe length. */
    fun safeValue(value: String?, maxLen: Int = 40): String {
        if (value == null) return ""
        val r = redact(value)
        return if (r.length <= maxLen) r else r.take(maxLen) + "…"
    }

    /** Identifiers are hashed, not logged raw. */
    fun redactId(id: String?): String {
        if (id.isNullOrEmpty()) return ""
        return REDACTED + ":" + (id.hashCode().toUInt().toString(16))
    }
}

/** Structured event. Only the allowed fields exist by construction. */
data class DotLogEvent(
    val event: String,
    val toolName: String? = null,
    val durationMs: Long? = null,
    val resultCode: String? = null,
    val id: String? = null,
    val buildVersion: String? = null,
) {
    fun format(): String = buildString {
        append("event=").append(event)
        toolName?.let { append(" tool=").append(it) }
        durationMs?.let { append(" duration_ms=").append(it) }
        resultCode?.let { append(" result=").append(it) }
        id?.let { append(" id=").append(LogRedactor.redactId(it)) }
        buildVersion?.let { append(" build=").append(it) }
    }
}
