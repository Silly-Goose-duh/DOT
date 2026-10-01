package com.dot.agent.llm.gemini

import com.dot.agent.llm.ModelErrorCodes
import com.dot.agent.llm.ModelReply
import com.dot.agent.llm.ModelRequest
import com.dot.agent.llm.ToolSchema
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GeminiProviderTest {

    private fun request(text: String) = ModelRequest(
        systemPrompt = "Reply with one JSON object.",
        userText = text,
        allowedTools = listOf(
            ToolSchema("get_today", "Show today."),
            ToolSchema("create_task", "Create a task."),
        ),
    )

    // ---- offline unit tests: no network, no key ----

    @Test
    fun `blank key is not configured, not a network call`() = runTest {
        val p = GeminiProvider(apiKey = "")
        val out = p.complete(request("what's on today"))
        assertThat(out).isEqualTo(ModelReply.Failure(ModelErrorCodes.NOT_CONFIGURED))
    }

    @Test
    fun `blank key never reaches the network`() = runTest {
        // A blank key must short-circuit before any socket is opened.
        val started = System.currentTimeMillis()
        GeminiProvider(apiKey = "   ").complete(request("x"))
        assertThat(System.currentTimeMillis() - started).isLessThan(500)
    }

    @Test
    fun `extracts text from a single candidates part`() {
        val raw = """{"candidates":[{"content":{"parts":[{"text":"{\"tool\":\"get_today\"}"}]},
            "finishReason":"STOP"}]}"""
        assertThat(parseCandidatesText(raw)).isEqualTo("""{"tool":"get_today"}""")
    }

    @Test
    fun `joins text split across multiple parts`() {
        val raw = """{"candidates":[{"content":{"parts":[
            {"text":"{\"tool\":"},{"text":"\"get_today\"}"}]}}]}"""
        assertThat(parseCandidatesText(raw)).isEqualTo("""{"tool":"get_today"}""")
    }

    @Test
    fun `decodes escapes in returned text`() {
        val raw = """{"parts":[{"text":"line1\nline2\ttabbed"}]}"""
        assertThat(parseCandidatesText(raw)).isEqualTo("line1\nline2\ttabbed")
    }

    @Test
    fun `unicode escape is decoded`() {
        // A REGULAR string: the backslash-u must survive into the raw text as a
        // single backslash so the parser sees a genuine JSON \uXXXX escape.
        val raw = "{\"parts\":[{\"text\":\"caf\\u00e9\"}]}"
        assertThat(raw).contains("\\u00e9")
        assertThat(parseCandidatesText(raw)).isEqualTo("café")
    }

    @Test
    fun `a double backslash is not treated as a unicode escape`() {
        // Not a JSON escape sequence, so it must not be silently decoded.
        val raw = "{\"parts\":[{\"text\":\"caf\\\\u00e9\"}]}"
        assertThat(parseCandidatesText(raw)).isEqualTo("caf\\u00e9")
    }

    @Test
    fun `escaped quote survives extraction`() {
        val raw = """{"parts":[{"text":"{\"a\":\"b\"}"}]}"""
        assertThat(parseCandidatesText(raw)).isEqualTo("""{"a":"b"}""")
    }

    @Test
    fun `malformed body yields empty text not a crash`() {
        assertThat(parseCandidatesText("not json at all")).isEmpty()
        assertThat(parseCandidatesText("")).isEmpty()
    }

    @Test
    fun `json string writer escapes quotes and newlines`() {
        val sb = StringBuilder()
        sb.appendJsonString("a\"b\nc\\d")
        assertThat(sb.toString()).isEqualTo("\"a\\\"b\\nc\\\\d\"")
    }

    @Test
    fun `model id with a path escape is rejected before any request`() = runTest {
        // Guards against a crafted model id redirecting the request elsewhere.
        val p = GeminiProvider(apiKey = "k", modelId = "../../evil")
        val out = p.complete(request("x"))
        assertThat(out).isEqualTo(ModelReply.Failure(ModelErrorCodes.UNAVAILABLE))
    }

    // ---- live test: only runs when a key is present, never in CI ----

    @Test
    fun `live call returns a parseable plan`() = runTest {
        val key = liveKey()
        assumeTrue("no local key configured", key != null)
        val p = GeminiProvider(apiKey = key!!)
        val out = p.complete(
            request("what do I have today? Reply with the tool call only."),
        )
        assertThat(out).isInstanceOf(ModelReply.Text::class.java)
        val text = (out as ModelReply.Text).content
        println("LIVE GEMINI REPLY: $text")
        assertThat(text.trim()).startsWith("{")
        assertThat(text).contains("get_today")
    }

    @Test
    fun `live call does not leak the key in its request body`() = runTest {
        val key = liveKey()
        assumeTrue("no local key configured", key != null)
        val p = GeminiProvider(apiKey = key!!)
        // A provider that works but echoes the key would be a serious leak;
        // assert the body we build never contains it.
        val body = p.debugBodyForTest(request("hello"))
        assertThat(body).doesNotContain(key)
    }

    private fun liveKey(): String? {
        // Gradle runs tests with the module dir as cwd, so look upward for the
        // repo-root secret file. Absent in CI, where these tests skip.
        val candidates = listOf(
            File("local-secrets.properties"),
            File("../../local-secrets.properties"),
            File("../../../local-secrets.properties"),
        )
        return candidates
            .filter { it.exists() }
            .asSequence()
            .map { f ->
                f.readLines()
                    .firstOrNull { it.startsWith("gemini.api.key=") }
                    ?.substringAfter('=')
                    ?.trim()
            }
            .firstOrNull { !it.isNullOrEmpty() }
    }
}
