package com.dot.app.voice

/** Typed outcome so the caller never has to guess whether junk was produced. */
sealed interface TranscriptNormalization {
    data class Ok(val text: String) : TranscriptNormalization

    /** Nothing survived trimming — silence, or pure punctuation. */
    data object Empty : TranscriptNormalization

    /** Longer than one command can be. Rejected rather than truncated. */
    data class TooLong(val limit: Int, val length: Int) : TranscriptNormalization
}

/**
 * Cleans a raw recognizer string before it reaches [CommandRuntime.handle].
 *
 * This class deliberately does very little. Recognizers emit leading and
 * trailing whitespace, doubled spaces from pause detection, and a trailing
 * "." / "?" when the speaker ends a sentence; all three break the
 * DeterministicRouter's prefix and suffix matching. Beyond that it does not
 * interpret the text — no date rewriting, no lowercasing, no digit
 * normalisation. Date and time bugs belong to DateTimeParser, which was fixed
 * and tested there; "helpfully" reformatting a transcript here would move that
 * bug to a layer with no tests.
 */
class TranscriptNormalizer(
    /** Longest single command we will hand to the router. */
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {

    init {
        require(maxLength > 0) { "maxLength must be positive" }
    }

    fun normalize(raw: String): TranscriptNormalization {
        // Whitespace first: collapsing before trimming would leave a leading
        // space behind, and a leading space defeats prefix matching.
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return TranscriptNormalization.Empty

        val unpunctuated = stripTrailingNoise(collapsed)
        if (unpunctuated.isEmpty()) return TranscriptNormalization.Empty

        if (unpunctuated.length > maxLength) {
            // Truncating would invent a command the user never spoke, so the
            // session fails loudly and the user is asked to shorten it.
            return TranscriptNormalization.TooLong(maxLength, unpunctuated.length)
        }

        return TranscriptNormalization.Ok(unpunctuated)
    }

    /**
     * Removes punctuation that only appears as sentence-final noise.
     *
     * Only the tail is touched, and only for the characters a recognizer adds
     * when a sentence ends. Inner punctuation — colons in "3:30", hyphens,
     * question marks inside a quoted title — is left exactly as spoken.
     */
    private fun stripTrailingNoise(text: String): String {
        var end = text.length
        while (end > 0 && TRAILING_NOISE.contains(text[end - 1])) end--
        return text.substring(0, end).trimEnd()
    }

    private companion object {
        const val DEFAULT_MAX_LENGTH = 240

        val WHITESPACE = Regex("\\s+")

        /** Sentence-final punctuation the recognizer tacks on. */
        val TRAILING_NOISE = charArrayOf('.', '?', '!', ',', ';', '…')
    }
}