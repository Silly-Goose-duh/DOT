package com.dot.app.voice

/**
 * Optional spoken output. Off by default (PRD 18 calls TTS optional).
 *
 * Narrow on purpose: the port can only say a string it is given. There is no
 * method that accepts a note, a reminder or a transcript, so the audio path
 * cannot become a side channel for content the text UI never showed.
 */
interface TextToSpeechPort {
    /** True once the engine has loaded; false means "silently do nothing". */
    val ready: Boolean

    /** Speaks [text] verbatim. Callers are responsible for sanitising content. */
    fun speak(text: String)

    fun stop()

    /** Releases the engine. Idempotent. */
    fun shutdown()
}

/**
 * Wraps a port with the caller-supplied enabled flag.
 *
 * Disabled is the default and is checked on every call rather than only at
 * construction, so flipping the setting takes effect immediately without
 * rebuilding the object graph. Nothing is ever queued while disabled: a
 * response the user cannot hear must not be spoken later at them.
 */
class GatedTextToSpeech(
    private val port: TextToSpeechPort?,
    private val enabled: () -> Boolean = { false },
) : TextToSpeechPort {

    override val ready: Boolean get() = enabled() && port?.ready == true

    override fun speak(text: String) {
        if (!enabled()) return
        if (port?.ready != true) return
        if (text.isBlank()) return
        port.speak(text)
    }

    override fun stop() {
        port?.stop()
    }

    override fun shutdown() {
        port?.shutdown()
    }
}