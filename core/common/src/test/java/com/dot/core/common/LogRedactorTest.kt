package com.dot.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `bearer tokens are redacted`() {
        val out = LogRedactor.redact("Authorization: Bearer abc123def456ghi789")
        assertThat(out).doesNotContain("abc123def456ghi789")
        assertThat(out).contains(LogRedactor.REDACTED)
    }

    @Test
    fun `google api keys are redacted`() {
        assertThat(LogRedactor.redact("key ya29.AbCdEf123456")).doesNotContain("AbCdEf123456")
    }

    @Test
    fun `oauth tokens are redacted`() {
        assertThat(LogRedactor.redact("refresh_token: 1//0abcdefgh")).doesNotContain("0abcdefgh")
    }

    @Test
    fun `jwt is redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        assertThat(LogRedactor.redact("token=$jwt")).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
    }

    @Test
    fun `api key assignments are redacted`() {
        assertThat(LogRedactor.redact("api_key=sk_live_abcdefghijklmno"))
            .doesNotContain("sk_live_abcdefghijklmno")
        assertThat(LogRedactor.redact("password: hunter2secret")).doesNotContain("hunter2secret")
    }

    @Test
    fun `ordinary text is untouched`() {
        val msg = "event=command_routed intent=GET_TODAY route=LOCAL"
        assertThat(LogRedactor.redact(msg)).isEqualTo(msg)
    }

    @Test
    fun `ids are hashed not logged raw`() {
        val out = LogRedactor.redactId("task-12345-secret")
        assertThat(out).doesNotContain("12345")
        assertThat(out).startsWith(LogRedactor.REDACTED)
    }

    @Test
    fun `safe value truncates long content`() {
        val out = LogRedactor.safeValue("x".repeat(200), maxLen = 10)
        assertThat(out).hasLength(11) // 10 chars + ellipsis
    }

    @Test
    fun `log event contains only allowed fields`() {
        val e = DotLogEvent(
            event = "tool_invoke",
            toolName = "create_task",
            durationMs = 42,
            resultCode = "SUCCESS",
            id = "sensitive-uuid",
        )
        val line = e.format()
        assertThat(line).contains("event=tool_invoke")
        assertThat(line).contains("tool=create_task")
        assertThat(line).contains("duration_ms=42")
        assertThat(line).contains("result=SUCCESS")
        assertThat(line).doesNotContain("sensitive-uuid")
    }
}
