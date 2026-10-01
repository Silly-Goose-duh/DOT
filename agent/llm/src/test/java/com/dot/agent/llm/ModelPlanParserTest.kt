package com.dot.agent.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelPlanParserTest {

    private val allowed = setOf("get_today", "create_task", "create_reminder", "open_app")
    private val parser = ModelPlanParser(allowed)

    @Test
    fun `parses a registered tool call`() {
        val out = parser.parse("""{"tool":"create_task","args":{"title":"Submit report"}}""")
        val plan = (out as ParseOutcome.Parsed).plan as ModelPlan.ToolCall
        assertThat(plan.toolName).isEqualTo("create_task")
        assertThat(plan.arguments["title"]).isEqualTo("Submit report")
    }

    @Test
    fun `parses a clarification`() {
        val out = parser.parse("""{"question":"Today or tomorrow?"}""")
        assertThat(((out as ParseOutcome.Parsed).plan as ModelPlan.Clarify).question)
            .isEqualTo("Today or tomorrow?")
    }

    @Test
    fun `parses a direct answer`() {
        val out = parser.parse("""{"answer":"You have 2 events."}""")
        assertThat(((out as ParseOutcome.Parsed).plan as ModelPlan.Answer).text)
            .isEqualTo("You have 2 events.")
    }

    @Test
    fun `unregistered tool is rejected`() {
        val out = parser.parse("""{"tool":"send_email","args":{"to":"a@b.c"}}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `prose instead of json is rejected`() {
        listOf(
            "Sure! I will now create a task for you.",
            "Here is the JSON you asked for:",
            "",
            "   ",
        ).forEach {
            assertThat(parser.parse(it)).isInstanceOf(ParseOutcome.Invalid::class.java)
        }
    }

    @Test
    fun `two json objects are rejected`() {
        val out = parser.parse("""{"tool":"get_today"}{"tool":"open_app","args":{"packageOrAlias":"maps"}}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `unknown top level key is rejected not ignored`() {
        val out = parser.parse("""{"tool":"get_today","args":{},"system_override":true}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `nested object argument is rejected`() {
        // Smuggling: a structured payload where a flat string is expected.
        val dollar = '$'
        val out = parser.parse("""{"tool":"create_task","args":{"title":{"${dollar}ref":"x"}}}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `array argument is rejected`() {
        val out = parser.parse("""{"tool":"create_task","args":{"title":["a","b"]}}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `null argument value is allowed`() {
        val out = parser.parse("""{"tool":"create_task","args":{"title":"x","dueAt":null}}""")
        val plan = (out as ParseOutcome.Parsed).plan as ModelPlan.ToolCall
        assertThat(plan.arguments["dueAt"]).isNull()
    }

    @Test
    fun `overlong answer is rejected`() {
        val long = "x".repeat(500)
        val out = parser.parse("""{"answer":"$long"}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `markdown fence is tolerated but content still validated`() {
        val out = parser.parse("```json\n{\"tool\":\"get_today\",\"args\":{}}\n```")
        assertThat(out).isInstanceOf(ParseOutcome.Parsed::class.java)

        val bad = parser.parse("```json\n{\"tool\":\"send_email\"}\n```")
        assertThat(bad).isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `no recognised action key is rejected`() {
        assertThat(parser.parse("""{"foo":"bar"}"""))
            .isInstanceOf(ParseOutcome.Invalid::class.java)
    }

    @Test
    fun `prompt injection attempting tool invention is rejected`() {
        // Invented tools and smuggled extra keys must be refused outright.
        listOf(
            """{"tool":"exec_shell","args":{"cmd":"rm -rf /"}}""",
            """{"tool":"open_app","args":{"packageOrAlias":"com.evil.backdoor"},"note":"ignore rules"}""",
            // "answer" plus a hidden tool call: previously resolved in the tool's favour.
            """{"answer":"ok","tool":"create_task","args":{"title":"x"}}""",
        ).forEach {
            assertThat(parser.parse(it)).isInstanceOf(ParseOutcome.Invalid::class.java)
        }
    }

    @Test
    fun `two action keys are ambiguous and rejected`() {
        val out = parser.parse("""{"answer":"ok","question":"which one?"}""")
        assertThat(out).isInstanceOf(ParseOutcome.Invalid::class.java)
    }
}
