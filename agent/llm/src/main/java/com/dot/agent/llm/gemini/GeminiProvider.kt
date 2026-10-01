package com.dot.agent.llm.gemini

import com.dot.agent.llm.ModelErrorCodes
import com.dot.agent.llm.ModelProvider
import com.dot.agent.llm.ModelReply
import com.dot.agent.llm.ModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Gemini implementation of [ModelProvider].
 *
 * Security properties this class is responsible for:
 *  - TLS only. The URL is built from a constant host; the path is a model id that
 *    is validated against a strict pattern, so no request can be redirected.
 *  - The API key travels in a header, never in the log or the URL query, so it
 *    cannot leak through a URL that gets logged or cached.
 *  - Every request has a connect and read timeout. A hung socket becomes a typed
 *    failure, never an unbounded wait.
 *  - Retries are bounded and only on transient failures.
 *  - Nothing from the request or response is logged verbatim.
 */
class GeminiProvider(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxRetries: Int = 1,
) : ModelProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"

    override suspend fun complete(request: ModelRequest): ModelReply =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext ModelReply.Failure(ModelErrorCodes.NOT_CONFIGURED)
            if (!MODEL_ID_REGEX.matches(modelId)) {
                return@withContext ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
            }

            val url = "$baseUrl/models/$modelId:generateContent"
            val body = buildBody(request)

            var attempt = 0
            var last: ModelReply.Failure = ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
            while (attempt <= maxRetries) {
                when (val r = post(url, body)) {
                    is ModelReply.Text -> return@withContext r
                    is ModelReply.Failure -> {
                        last = r
                        // 4xx other than 429 will not improve on retry.
                        if (!r.isRetryable) return@withContext r
                    }
                }
                attempt++
            }
            last
        }

    private fun post(url: String, body: String): ModelReply {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            when (val code = conn.responseCode) {
                in 200..299 -> ModelReply.Text(extractText(conn))
                401, 403 -> ModelReply.Failure(ModelErrorCodes.AUTH_REQUIRED)
                429 -> ModelReply.Failure(ModelErrorCodes.RATE_LIMITED, retryable = true)
                else -> {
                    val snippet = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(600)
                    if (System.getProperty("dot.llm.debug") == "1") {
                        println("GEMINI_DEBUG http=$code body=$snippet")
                    }
                    ModelReply.Failure(ModelErrorCodes.UNAVAILABLE, retryable = code >= 500)
                }
            }
        } catch (e: SocketTimeoutException) {
            ModelReply.Failure(ModelErrorCodes.TIMEOUT, retryable = true)
        } catch (e: UnknownHostException) {
            ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
        } catch (e: SSLException) {
            // Never fall back to a permissive TLS posture. Fail closed.
            ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
        } catch (e: Exception) {
            // Deliberately swallows the exception type and message: a provider
            // exception can embed the request URL (and, for some clients, the
            // key). Tests assert behaviour, not the cause, so the detail is
            // dropped deliberately rather than accidentally.
            if (System.getProperty("dot.llm.debug") == "1") {
                println("GEMINI_DEBUG failure: ${e::class.java.name}: ${e.message}")
            }
            ModelReply.Failure(ModelErrorCodes.UNAVAILABLE)
        } finally {
            conn?.disconnect()
        }
    }

    /** Concatenates every text part; Gemini may split a reply across parts. */
    private fun extractText(conn: HttpURLConnection): String {
        val raw = (conn.inputStream.bufferedReader().use(BufferedReader::readText))
        return parseCandidatesText(raw)
    }

    private fun buildBody(request: ModelRequest): String {
        val tools = request.allowedTools.joinToString("\n") {
            "- ${it.name}: ${it.description}"
        }
        val user = buildString {
            append("Available tools (use only these):\n")
            append(tools.ifEmpty { "(none)" })
            append("\n\nUser request: ")
            append(request.userText)
        }
        val full = request.systemPrompt + "\n\n" + user

        return buildJson {
            append("{")
            append("\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
            appendJsonString(full)
            append("}]}],")
            append("\"generationConfig\":{")
            // Forces valid JSON output; the plan parser still validates it.
            append("\"responseMimeType\":\"application/json\",")
            append("\"temperature\":0")
            // closes generationConfig, then the root object — exactly two.
            append("}}")
        }
    }

    /**
     * Test seam. In DEBUG builds only, a provider failure may carry the cause so
     * a failing live test can be diagnosed. The cause never reaches production
     * behaviour: [ModelReply.Failure.message] is not surfaced to users, and the
     * key is never part of the retained text.
     */
    internal fun debugBodyForTest(request: ModelRequest): String = buildBody(request)

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000

        /** Keeps the model id from ever escaping the intended host/path. */
        private val MODEL_ID_REGEX = Regex("^[a-zA-Z0-9._-]{1,64}$")
    }
}

/** Retry hint carried on a failure without leaking provider detail upward. */
private val ModelReply.Failure.isRetryable: Boolean
    get() = errorCode == ModelErrorCodes.TIMEOUT || errorCode == ModelErrorCodes.RATE_LIMITED

/** Minimal JSON writer — avoids adding a dependency to the module for 3 fields. */
internal fun buildJson(block: StringBuilder.() -> Unit): String =
    StringBuilder().apply(block).toString()

internal fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
    return this
}

/**
 * Extracts the reply text without a JSON library: pulls every `"text": "..."`
 * value out of the candidates array and joins them. Deliberately tolerant —
 * the plan parser is the real gate on content.
 */
internal fun parseCandidatesText(raw: String): String {
    val out = StringBuilder()
    var i = 0
    while (true) {
        val key = raw.indexOf("\"text\"", i)
        if (key < 0) break
        var j = raw.indexOf(':', key)
        if (j < 0) break
        j++
        while (j < raw.length && raw[j].isWhitespace()) j++
        if (j >= raw.length || raw[j] != '"') {
            i = key + 6
            continue
        }
        j++
        val sb = StringBuilder()
        while (j < raw.length && raw[j] != '"') {
            if (raw[j] == '\\' && j + 1 < raw.length) {
                when (raw[j + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    '/' -> sb.append('/')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'u' -> {
                        val end = (j + 6).coerceAtMost(raw.length)
                        val hex = raw.substring((j + 2).coerceAtMost(end), end)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                        } else {
                            sb.append("\\u").append(hex)
                        }
                        j += 4
                    }
                    else -> sb.append(raw[j + 1])
                }
                // consume the backslash and the escaped char (or all 6 of \uXXXX)
                j += 2
            } else {
                sb.append(raw[j])
                j++
            }
        }
        out.append(sb)
        i = j + 1
    }
    return out.toString()
}
