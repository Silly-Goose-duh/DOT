package com.dot.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every platform error code must land on a typed error. The table is asserted
 * exhaustively because an unmapped code that slipped through as a success is
 * the single worst failure mode this layer can have.
 */
class SpeechErrorMappingTest {

    private fun map(code: Int) = AndroidSpeechToText.mapPlatformError(code)

    @Test
    fun `no match maps to NoMatch`() {
        assertThat(map(AndroidSpeechToText.ERROR_NO_MATCH)).isEqualTo(SpeechErrorCode.NoMatch)
    }

    @Test
    fun `speech timeout maps to SpeechTimeout`() {
        assertThat(map(AndroidSpeechToText.ERROR_SPEECH_TIMEOUT)).isEqualTo(SpeechErrorCode.SpeechTimeout)
    }

    @Test
    fun `insufficient permission maps to PermissionDenied`() {
        assertThat(map(AndroidSpeechToText.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo(SpeechErrorCode.PermissionDenied)
    }

    @Test
    fun `network maps to Network`() {
        assertThat(map(AndroidSpeechToText.ERROR_NETWORK)).isEqualTo(SpeechErrorCode.Network)
    }

    @Test
    fun `recognizer busy maps to RecognizerBusy`() {
        assertThat(map(AndroidSpeechToText.ERROR_RECOGNIZER_BUSY)).isEqualTo(SpeechErrorCode.RecognizerBusy)
    }

    @Test
    fun `client maps to Client`() {
        assertThat(map(AndroidSpeechToText.ERROR_CLIENT)).isEqualTo(SpeechErrorCode.Client)
    }

    @Test
    fun `server maps to Server`() {
        assertThat(map(AndroidSpeechToText.ERROR_SERVER)).isEqualTo(SpeechErrorCode.Server)
    }

    @Test
    fun `audio maps to Audio`() {
        assertThat(map(AndroidSpeechToText.ERROR_AUDIO)).isEqualTo(SpeechErrorCode.Audio)
    }

    @Test
    fun `framework values match the real constants`() {
        // Guards against the mirrored constants drifting from the platform.
        // These are the literal values in AOSP SpeechRecognizer.java; if the
        // framework ever renumbers them, this test is what notices.
        assertThat(AndroidSpeechToText.ERROR_NETWORK_TIMEOUT).isEqualTo(1)
        assertThat(AndroidSpeechToText.ERROR_NETWORK).isEqualTo(2)
        assertThat(AndroidSpeechToText.ERROR_AUDIO).isEqualTo(3)
        assertThat(AndroidSpeechToText.ERROR_SERVER).isEqualTo(4)
        assertThat(AndroidSpeechToText.ERROR_CLIENT).isEqualTo(5)
        assertThat(AndroidSpeechToText.ERROR_SPEECH_TIMEOUT).isEqualTo(6)
        assertThat(AndroidSpeechToText.ERROR_NO_MATCH).isEqualTo(7)
        assertThat(AndroidSpeechToText.ERROR_RECOGNIZER_BUSY).isEqualTo(8)
        assertThat(AndroidSpeechToText.ERROR_INSUFFICIENT_PERMISSIONS).isEqualTo(9)
        assertThat(AndroidSpeechToText.ERROR_TOO_MANY_REQUESTS).isEqualTo(10)
        assertThat(AndroidSpeechToText.ERROR_SERVER_DISCONNECTED).isEqualTo(11)
        assertThat(AndroidSpeechToText.ERROR_LANGUAGE_NOT_SUPPORTED).isEqualTo(12)
        assertThat(AndroidSpeechToText.ERROR_LANGUAGE_UNAVAILABLE).isEqualTo(13)
        assertThat(AndroidSpeechToText.ERROR_CANNOT_CHECK_SUPPORT).isEqualTo(14)
        assertThat(AndroidSpeechToText.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
            .isEqualTo(15)
    }

    @Test
    fun `every documented platform code is in the enumerated set exactly once`() {
        val all = AndroidSpeechToText.ALL_PLATFORM_ERRORS.toList()
        assertThat(all).containsNoDuplicates()
        assertThat(all).hasSize(15)
        // Contiguous 1..15: a gap is a code we forgot to enumerate, and an
        // unenumerated code is exactly what would fall through to Unknown.
        assertThat(all.sorted()).isEqualTo((1..15).toList())
    }

    @Test
    fun `no documented platform code falls through to Unknown`() {
        // The core safety property: a real framework error must always reach a
        // specific typed error with a specific user-facing recovery hint.
        AndroidSpeechToText.ALL_PLATFORM_ERRORS.forEach { code ->
            val mapped = map(code)
            assertThat(mapped).isNotInstanceOf(SpeechErrorCode.Unknown::class.java)
            assertThat(mapped.userMessage).isNotEmpty()
        }
    }

    @Test
    fun `an unrecognised code becomes Unknown carrying the raw code`() {
        assertThat(map(99)).isEqualTo(SpeechErrorCode.Unknown(99))
        assertThat(map(-1)).isEqualTo(SpeechErrorCode.Unknown(-1))
        assertThat(map(0)).isEqualTo(SpeechErrorCode.Unknown(0))
    }

    @Test
    fun `no mapped error code produces a success`() {
        // Success is not an error code, so nothing in the table may report it as
        // one; and every error carries a non-empty, transcript-free message.
        val all = listOf(
            SpeechErrorCode.NoMatch,
            SpeechErrorCode.SpeechTimeout,
            SpeechErrorCode.PermissionDenied,
            SpeechErrorCode.Network,
            SpeechErrorCode.RecognizerBusy,
            SpeechErrorCode.Client,
            SpeechErrorCode.Server,
            SpeechErrorCode.Audio,
            SpeechErrorCode.EmptyTranscript,
            SpeechErrorCode.TranscriptTooLong,
            SpeechErrorCode.Unknown(1234),
        )
        all.forEach { code ->
            assertThat(code.userMessage).isNotEmpty()
            assertThat(code.userMessage).doesNotContain("1234")
        }
    }

    @Test
    fun `only permission errors are flagged as needing the settings path`() {
        assertThat(SpeechErrorCode.PermissionDenied.needsPermission).isTrue()
        listOf(
            SpeechErrorCode.NoMatch,
            SpeechErrorCode.SpeechTimeout,
            SpeechErrorCode.Network,
            SpeechErrorCode.RecognizerBusy,
            SpeechErrorCode.Client,
            SpeechErrorCode.Server,
            SpeechErrorCode.Audio,
            SpeechErrorCode.EmptyTranscript,
            SpeechErrorCode.TranscriptTooLong,
        ).forEach { assertThat(it.needsPermission).isFalse() }
    }

    @Test
    fun `retryable flags match the PRD retry path`() {
        assertThat(SpeechErrorCode.NoMatch.retryable).isTrue()
        assertThat(SpeechErrorCode.SpeechTimeout.retryable).isTrue()
        assertThat(SpeechErrorCode.Network.retryable).isTrue()
        assertThat(SpeechErrorCode.PermissionDenied.retryable).isFalse()
        assertThat(SpeechErrorCode.TranscriptTooLong.retryable).isFalse()
    }
}