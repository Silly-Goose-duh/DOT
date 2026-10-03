package com.dot.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TTS is optional and off by default. These tests pin both halves: a disabled
 * gate is genuinely silent, and an enabled one speaks only the string it was
 * handed — never a note body, email subject or transcript it could reach.
 */
class TextToSpeechPortTest {

    private class RecordingTts(engineReady: Boolean = true) : TextToSpeechPort {
        private val engineReady = engineReady
        override val ready: Boolean get() = engineReady
        val spoken = mutableListOf<String>()
        var stops = 0
        var shutdowns = 0

        override fun speak(text: String) { spoken += text }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
    }

    @Test
    fun `tts is off by default`() {
        val port = RecordingTts()
        val gated = GatedTextToSpeech(port)
        gated.speak("task added")
        assertThat(port.spoken).isEmpty()
        assertThat(gated.ready).isFalse()
    }

    @Test
    fun `an explicit false flag keeps it silent`() {
        val port = RecordingTts()
        val gated = GatedTextToSpeech(port, enabled = { false })
        gated.speak("task added")
        assertThat(port.spoken).isEmpty()
    }

    @Test
    fun `when enabled it receives exactly the response text`() {
        val port = RecordingTts()
        val gated = GatedTextToSpeech(port, enabled = { true })
        gated.speak("Added buy milk.")
        assertThat(port.spoken).containsExactly("Added buy milk.")
        assertThat(gated.ready).isTrue()
    }

    @Test
    fun `disabling mid-session stops further speech immediately`() {
        val port = RecordingTts()
        var enabled = true
        val gated = GatedTextToSpeech(port, enabled = { enabled })
        gated.speak("first")
        enabled = false
        gated.speak("second")
        assertThat(port.spoken).containsExactly("first")
    }

    @Test
    fun `nothing is queued while disabled`() {
        // A reply the user cannot hear must not be spoken later at them.
        val port = RecordingTts()
        var enabled = false
        val gated = GatedTextToSpeech(port, enabled = { enabled })
        gated.speak("queued?")
        enabled = true
        assertThat(port.spoken).isEmpty()
    }

    @Test
    fun `a null port is safe`() {
        val gated = GatedTextToSpeech(null, enabled = { true })
        gated.speak("anything")
        gated.stop()
        gated.shutdown()
        assertThat(gated.ready).isFalse()
    }

    @Test
    fun `an engine that never became ready drops the request`() {
        val port = RecordingTts(engineReady = false)
        val gated = GatedTextToSpeech(port, enabled = { true })
        gated.speak("ignored")
        assertThat(port.spoken).isEmpty()
        assertThat(gated.ready).isFalse()
    }

    @Test
    fun `blank text is never spoken`() {
        val port = RecordingTts()
        val gated = GatedTextToSpeech(port, enabled = { true })
        gated.speak("")
        gated.speak("   ")
        assertThat(port.spoken).isEmpty()
    }

    @Test
    fun `stop and shutdown pass through regardless of the gate`() {
        val port = RecordingTts()
        val gated = GatedTextToSpeech(port, enabled = { false })
        gated.stop()
        gated.shutdown()
        assertThat(port.stops).isEqualTo(1)
        assertThat(port.shutdowns).isEqualTo(1)
    }

    @Test
    fun `the port exposes no way to speak a note or transcript directly`() {
        // Compile-time guarantee, asserted as a type: the only mutating method
        // takes a String, so there is no accidental content-shaped overload.
        val method = TextToSpeechPort::class.java.methods
            .single { it.name == "speak" }
        assertThat(method.parameterTypes.toList()).containsExactly(String::class.java)
    }
}