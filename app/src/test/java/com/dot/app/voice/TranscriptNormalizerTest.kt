package com.dot.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptNormalizerTest {

    private val normalizer = TranscriptNormalizer()

    private fun ok(raw: String): String {
        val result = normalizer.normalize(raw)
        assertThat(result).isInstanceOf(TranscriptNormalization.Ok::class.java)
        return (result as TranscriptNormalization.Ok).text
    }

    @Test
    fun `empty string is empty`() {
        assertThat(normalizer.normalize("")).isEqualTo(TranscriptNormalization.Empty)
    }

    @Test
    fun `whitespace only is empty`() {
        assertThat(normalizer.normalize("   \t\n  ")).isEqualTo(TranscriptNormalization.Empty)
    }

    @Test
    fun `punctuation only is empty`() {
        assertThat(normalizer.normalize("...")).isEqualTo(TranscriptNormalization.Empty)
        assertThat(normalizer.normalize("?!?")).isEqualTo(TranscriptNormalization.Empty)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertThat(ok("  add a task  ")).isEqualTo("add a task")
    }

    @Test
    fun `collapses internal whitespace runs`() {
        assertThat(ok("add    a\t\t task\nn")).isEqualTo("add a task n")
    }

    @Test
    fun `strips trailing sentence punctuation`() {
        assertThat(ok("add a task.")).isEqualTo("add a task")
        assertThat(ok("add a task?")).isEqualTo("add a task")
        assertThat(ok("add a task!")).isEqualTo("add a task")
        assertThat(ok("add a task, ")).isEqualTo("add a task")
        assertThat(ok("add a task...")).isEqualTo("add a task")
    }

    @Test
    fun `preserves inner punctuation`() {
        assertThat(ok("call Dr. Smith at 3.")).isEqualTo("call Dr. Smith at 3")
        assertThat(ok("what is 2+2?")).isEqualTo("what is 2+2")
    }

    @Test
    fun `preserves digits dates and colons`() {
        assertThat(ok("remind me at 15:30 tomorrow")).isEqualTo("remind me at 15:30 tomorrow")
        assertThat(ok("on 2026-03-04 at 09:05.")).isEqualTo("on 2026-03-04 at 09:05")
        assertThat(ok("task 42 costs 12.50")).isEqualTo("task 42 costs 12.50")
    }

    @Test
    fun `does not rewrite dates`() {
        // DateTimeParser owns date interpretation; touching it here would move a
        // fixed bug into a layer with no tests.
        assertThat(ok("march fourth")).isEqualTo("march fourth")
        assertThat(ok("03/04/2026")).isEqualTo("03/04/2026")
        assertThat(ok("Next Tuesday")).isEqualTo("Next Tuesday")
    }

    @Test
    fun `does not lowercase`() {
        assertThat(ok("Add A Task")).isEqualTo("Add A Task")
    }

    @Test
    fun `does not change inner apostrophes or hyphens`() {
        assertThat(ok("don't forget milk.")).isEqualTo("don't forget milk")
        assertThat(ok("follow-up with sam.")).isEqualTo("follow-up with sam")
    }

    @Test
    fun `at limit length is accepted`() {
        val n = TranscriptNormalizer(maxLength = 10)
        assertThat(n.normalize("1234567890")).isEqualTo(TranscriptNormalization.Ok("1234567890"))
    }

    @Test
    fun `over limit length is rejected not truncated`() {
        val n = TranscriptNormalizer(maxLength = 10)
        val result = n.normalize("12345678901")
        assertThat(result).isEqualTo(TranscriptNormalization.TooLong(10, 11))
    }

    @Test
    fun `length is measured after normalization`() {
        // "a." + 9 spaces + "b" is 12 raw chars but "a. b" is 4 after collapsing,
        // so a limit of 5 must accept it.
        val n = TranscriptNormalizer(maxLength = 5)
        assertThat(n.normalize("a.          b")).isEqualTo(TranscriptNormalization.Ok("a. b"))
    }

    @Test
    fun `trailing ellipsis is stripped`() {
        assertThat(ok("open settings\u2026")).isEqualTo("open settings")
    }

    @Test
    fun `non positive max length is rejected at construction`() {
        var threw = false
        try {
            TranscriptNormalizer(maxLength = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `default limit accepts a realistic command`() {
        val long = "remind me to buy groceries tomorrow at 18:00 and then call the dentist"
        assertThat(normalizer.normalize(long)).isInstanceOf(TranscriptNormalization.Ok::class.java)
    }
}