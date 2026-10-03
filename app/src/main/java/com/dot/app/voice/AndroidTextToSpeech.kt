package com.dot.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Thin [TextToSpeech] adapter.
 *
 * The engine is created lazily and asynchronously; until its init callback
 * fires, [ready] is false and [GatedTextToSpeech] drops the request rather than
 * blocking the caller. Nothing is queued for later playback.
 */
class AndroidTextToSpeech(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
) : TextToSpeechPort {

    private val appContext = context.applicationContext

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initialized = false

    @Volatile
    override var ready: Boolean = false
        private set

    init {
        // Constructed on whatever thread first needs it; TextToSpeech is
        // thread-safe once created and delivers its callback on the main thread.
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val e = engine
                initialized = e != null && when (e.setLanguage(locale)) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> false
                    else -> true
                }
                ready = initialized
            } else {
                initialized = false
                ready = false
            }
        }
    }

    override fun speak(text: String) {
        val e = engine ?: return
        if (!ready) return
        // QUEUE_FLUSH rather than QUEUE_ADD: an older answer is now wrong, and
        // reading a stale reply after the user has moved on is worse than silence.
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        ready = false
        initialized = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    internal val isInitialized: Boolean get() = initialized

    private companion object {
        const val UTTERANCE_ID = "dot_reply"
    }
}