package com.dot.agent.llm.gemini

import com.dot.agent.llm.ModelPlanParser
import com.dot.agent.llm.ParseOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the request body the provider actually sends. The body is built by hand
 * (no JSON library), so it is the most likely place for a malformed-payload bug
 * that the API would reject with a bare "unavailable".
 */
class GeminiRequestBodyTest {

    private val provider = GeminiProvider(apiKey = "test-key-not-real")

    private fun bodyFor(userText: String) = provider.debugBodyForTest(
        com.dot.agent.llm.ModelRequest(
            systemPrompt = "Reply with one JSON object only.",
            userText = userText,
            allowedTools = listOf(
                com.dot.agent.llm.ToolSchema("get_today", "Show today."),
                com.dot.agent.llm.ToolSchema("create_task", "Create a task. Args: title."),
            ),
        ),
    )

    @Test
    fun `body is a single json object`() {
        val b = bodyFor("what's on today?")
        assertThat(b.trim().startsWith("{")).isTrue()
        assertThat(b.trim().endsWith("}")).isTrue()
    }

    @Test
    fun `braces are balanced`() {
        // A trailing extra brace is a real bug this caught: the API rejected the
        // payload with "parsing terminated before end of input". Checking only
        // startsWith/endsWith would not have noticed.
        val b = bodyFor("what's on today?")
        var depth = 0
        var inString = false
        var escaped = false
        for (c in b) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> depth--
            }
        }
        assertThat(depth).isEqualTo(0)
    }

    @Test
    fun `braces balance for every prompt shape`() {
        listOf(
            "simple",
            "with \"quotes\" inside",
            "with\nnewlines",
            "with {braces} and [brackets]",
            "unicode café ✓",
            "",
        ).forEach { text ->
            val b = bodyFor(text)
            var depth = 0
            var inString = false
            var escaped = false
            for (c in b) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            assertThat(depth).isEqualTo(0)
        }
    }

    @Test
    fun `body contains no unescaped raw quote inside the text value`() {
        // A raw " inside the prompt would terminate the JSON string early.
        val b = bodyFor("""what about the "big" report?""")
        val textStart = b.indexOf("\"text\":") + 7
        val textEnd = b.indexOf("}]}]", textStart)
        val textValue = b.substring(textStart, textEnd)
        // Every quote in the payload must be backslash-escaped.
        val unescaped = Regex("""(?<!\\)"""").findAll(textValue).count()
        // Opening and closing quotes of the value itself: exactly 2.
        assertThat(unescaped).isEqualTo(2)
    }

    @Test
    fun `body carries the tool list and the user text`() {
        val b = bodyFor("what do I have today?")
        assertThat(b).contains("get_today")
        assertThat(b).contains("create_task")
        assertThat(b).contains("what do I have today?")
    }

    @Test
    fun `body requests json response mime type`() {
        assertThat(bodyFor("x")).contains("\"responseMimeType\":\"application/json\"")
    }

    @Test
    fun `body never contains the api key`() {
        val b = bodyFor("hello")
        assertThat(b).doesNotContain("test-key-not-real")
    }

    @Test
    fun `newline in the user text is escaped not literal`() {
        val b = bodyFor("line one\nline two")
        assertThat(b).contains("\\n")
        assertThat(b).doesNotContain("line one\nline two")
    }

    @Test
    fun `quote in tool description stays escaped`() {
        val b = provider.debugBodyForTest(
            com.dot.agent.llm.ModelRequest(
                systemPrompt = "p",
                userText = "u",
                allowedTools = listOf(
                    com.dot.agent.llm.ToolSchema("t", """say "hi""""),
                ),
            ),
        )
        assertThat(b).contains("""\"""")
    }

    @Test
    fun `a tool-less request still produces valid structure`() {
        val b = provider.debugBodyForTest(
            com.dot.agent.llm.ModelRequest("p", "u", emptyList()),
        )
        assertThat(b).contains("(none)")
        assertThat(b).contains("generationConfig")
    }
}

/**
 * The end-to-end contract that matters: whatever Gemini returns as text must be
 * something ModelPlanParser can act on. Uses a real captured Gemini reply.
 */
class GeminiReplyContractTest {

    @Test
    fun `a real gemini reply parses into a tool call`() {
        val captured = """{"tool":"get_today","args":{}}"""
        val out = ModelPlanParser(setOf("get_today", "create_task")).parse(captured)
        assertThat(out).isInstanceOf(ParseOutcome.Parsed::class.java)
    }

    @Test
    fun `gemini reply arriving json-escaped inside json is unwrapped first`() {
        // This is the literal shape of a Gemini candidates payload's text field.
        val geminiPayload = """{"candidates":[{"content":{"parts":[{"text":"{\"tool\":\"get_today\",\"args\":{}}"}]}}]}"""
        val text = parseCandidatesText(geminiPayload)
        assertThat(text).isEqualTo("""{"tool":"get_today","args":{}}""")
        val out = ModelPlanParser(setOf("get_today")).parse(text)
        assertThat(out).isInstanceOf(ParseOutcome.Parsed::class.java)
    }

    @Test
    fun `pretty printed gemini payload also unwraps`() {
        val pretty = """{
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"tool\":\"get_today\",\"args\":{}}"
                  }
                ]
              }
            }
          ]
        }"""
        val text = parseCandidatesText(pretty)
        assertThat(text).isEqualTo("""{"tool":"get_today","args":{}}""")
        assertThat(ModelPlanParser(setOf("get_today")).parse(text))
            .isInstanceOf(ParseOutcome.Parsed::class.java)
    }
}
