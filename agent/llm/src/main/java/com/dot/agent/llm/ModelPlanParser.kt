package com.dot.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The ONLY shape a model reply is allowed to take (PRD 33: strict structured schema).
 *
 * The model may pick exactly one of: a registered tool call, a clarification
 * question, or a direct short answer. It may not invent a tool, may not add
 * authority, and may not ask for raw code. Anything else is a parse failure.
 */
sealed interface ModelPlan {
    data class ToolCall(
        val toolName: String,
        val arguments: Map<String, String?>,
    ) : ModelPlan

    data class Clarify(val question: String) : ModelPlan

    /** A short factual answer that needs no tool. Rendered as-is, no execution. */
    data class Answer(val text: String) : ModelPlan
}

sealed interface ParseOutcome {
    data class Parsed(val plan: ModelPlan) : ParseOutcome
    data class Invalid(val reason: String) : ParseOutcome
}

/**
 * Parses and validates model output BEFORE anything is executed.
 *
 * Every check here is a security boundary, not a formatting nicety:
 *  - the reply must be a single JSON object (no prose, no code fences)
 *  - `tool` must already exist in the registry — checked against [allowedToolNames]
 *  - arguments must be flat string/null values, so nothing can smuggle a nested
 *    object, list, or executable-looking payload into a tool
 *  - unknown top-level keys are rejected rather than ignored
 */
class ModelPlanParser(
    private val allowedToolNames: Set<String>,
    private val maxTextLength: Int = 200,
    private val maxArgs: Int = 12,
) {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    private val allowedKeys = setOf("tool", "args", "question", "answer")

    fun parse(raw: String): ParseOutcome {
        val text = raw.trim()
        if (text.isEmpty()) return ParseOutcome.Invalid("empty response")

        // Strip a single markdown fence if the model added one, then require JSON.
        val candidate = text.removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            return ParseOutcome.Invalid("response is not a single JSON object")
        }

        val obj = try {
            json.parseToJsonElement(candidate) as? JsonObject
                ?: return ParseOutcome.Invalid("response is not a JSON object")
        } catch (e: Exception) {
            return ParseOutcome.Invalid("unparseable JSON: ${e::class.simpleName}")
        }

        val unknown = obj.keys - allowedKeys
        if (unknown.isNotEmpty()) return ParseOutcome.Invalid("unknown keys: ${unknown.sorted()}")

        // Exactly one action key: tool | question | answer. "args" is a payload
        // field belonging to "tool", not an action in its own right. Two actions
        // is ambiguous, and resolving that ambiguity in the model's favour would
        // let prose smuggle a tool call alongside an innocuous "answer".
        val actionKeys = obj.keys.filter { it == "tool" || it == "question" || it == "answer" }
        if (actionKeys.size != 1) {
            return ParseOutcome.Invalid("expected exactly one action key, got ${actionKeys.sorted()}")
        }

        return when (actionKeys.single()) {
            "tool" -> parseToolCall(obj)
            "question" -> {
                val q = obj["question"]?.asString()
                if (q.isNullOrBlank() || q.length > maxTextLength) {
                    ParseOutcome.Invalid("question missing or too long")
                } else {
                    ParseOutcome.Parsed(ModelPlan.Clarify(q))
                }
            }
            "answer" -> {
                val a = obj["answer"]?.asString()
                if (a.isNullOrBlank() || a.length > maxTextLength) {
                    ParseOutcome.Invalid("answer missing or too long")
                } else {
                    ParseOutcome.Parsed(ModelPlan.Answer(a))
                }
            }
            else -> ParseOutcome.Invalid("no recognised action key")
        }
    }

    private fun parseToolCall(obj: JsonObject): ParseOutcome {
        val tool = obj["tool"]?.asString()
            ?: return ParseOutcome.Invalid("tool is not a string")

        // The registry is the authority on what exists. A model naming an
        // unregistered tool gets a refusal, never an attempt to execute it.
        if (tool !in allowedToolNames) {
            return ParseOutcome.Invalid("tool '$tool' is not registered")
        }

        val rawArgs = obj["args"]?.let { el ->
            try {
                el.jsonObject
            } catch (e: Exception) {
                return ParseOutcome.Invalid("args is not a JSON object")
            }
        } ?: JsonObject(emptyMap())

        if (rawArgs.size > maxArgs) return ParseOutcome.Invalid("too many arguments")

        val args = mutableMapOf<String, String?>()
        for ((k, v) in rawArgs) {
            // Flat scalars only. A nested object or array here is a smuggling attempt.
            val primitive = v as? JsonPrimitive
                ?: return ParseOutcome.Invalid("argument '$k' is not a scalar")
            args[k] = if (primitive is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                primitive.content
            }
        }

        return ParseOutcome.Parsed(ModelPlan.ToolCall(tool, args))
    }

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
}

// Small alias so the null check above reads clearly.
private typealias JsonElement = kotlinx.serialization.json.JsonElement
private typealias JsonNull = kotlinx.serialization.json.JsonNull
