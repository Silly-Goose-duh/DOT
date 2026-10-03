package com.dot.app.voice

import kotlinx.coroutines.flow.Flow

/**
 * Tuning for a single listening session. Nothing here changes the trust model:
 * push-to-talk is the only way in and every session is user-initiated, so the
 * recognizer is never left running in the background (PRD 18).
 */
data class ListenConfig(
    val localeTag: String = "en-US",
    val partialResults: Boolean = true,
    val preferOffline: Boolean = false,
    val maxResults: Int = 1,
)

/**
 * Everything the recognizer can say, as a closed set of typed values.
 *
 * There is deliberately no "raw int" and no "some text" branch: a failure can
 * never be mistaken for a result, and an empty result is still reported as a
 * result so that [TranscriptNormalizer] stays the single authority on whether
 * anything is worth sending to the router.
 */
sealed interface SpeechEvent {
    /** onReadyForSpeech — the session is armed. */
    data object Ready : SpeechEvent

    /** onBeginningOfSpeech — audio detected. Drives the "listening" visual only. */
    data object SpeechStarted : SpeechEvent

    /** Best-so-far hypothesis. Never submitted; display-only. */
    data class Partial(val text: String) : SpeechEvent

    /** Terminal result. `text` may be blank; the normalizer rejects blank input. */
    data class Final(val text: String) : SpeechEvent

    /** Terminal failure. Always closes the session. */
    data class Failed(val code: SpeechErrorCode) : SpeechEvent
}

/**
 * True for the single event that ends a session.
 *
 * A session is exactly one listen -> final-or-failed cycle, so the controller
 * uses this both to stop collecting and to stop a port that forgot to close its
 * own flow. [SpeechEvent.Partial], [SpeechEvent.Ready] and
 * [SpeechEvent.SpeechStarted] are non-terminal by construction: a partial
 * hypothesis is a display detail, never a result, and a "still arming" callback
 * must never be mistaken for one.
 */
internal val SpeechEvent.isTerminal: Boolean
    get() = this is SpeechEvent.Final || this is SpeechEvent.Failed

/**
 * Typed vocabulary for recognizer and normalization failures.
 *
 * `userMessage` is safe to show on screen: it names the problem and never
 * contains the transcript, an audio reference, or a raw platform code.
 * `needsPermission` is what lets the UI route to a settings retry instead of
 * asking the user to talk again.
 */
sealed interface SpeechErrorCode {
    val userMessage: String
    val retryable: Boolean
    val needsPermission: Boolean get() = false

    data object NoMatch : SpeechErrorCode {
        override val userMessage = "I didn't catch that. Tap the mic and try again."
        override val retryable = true
    }

    data object SpeechTimeout : SpeechErrorCode {
        override val userMessage = "I didn't hear anything. Tap the mic and try again."
        override val retryable = true
    }

    data object PermissionDenied : SpeechErrorCode {
        override val userMessage =
            "Microphone access is off, so voice input is unavailable."
        override val retryable = false
        override val needsPermission = true
    }

    data object Network : SpeechErrorCode {
        override val userMessage =
            "Speech recognition needs a connection right now. Typing still works."
        override val retryable = true
    }

    data object RecognizerBusy : SpeechErrorCode {
        override val userMessage =
            "The speech service is busy. Try again in a moment."
        override val retryable = true
    }

    data object Client : SpeechErrorCode {
        override val userMessage =
            "The speech service rejected that request. Try again."
        override val retryable = true
    }

    data object Server : SpeechErrorCode {
        override val userMessage =
            "The speech service had a problem. Try again."
        override val retryable = true
    }

    data object Audio : SpeechErrorCode {
        override val userMessage =
            "I couldn't read the microphone. Check that nothing else is using it."
        override val retryable = true
    }

    /** Recognizer finished but produced nothing usable. */
    data object EmptyTranscript : SpeechErrorCode {
        override val userMessage = "Nothing came through. Tap the mic and try again."
        override val retryable = true
    }

    /** Longer than one command can be. Rejected rather than truncated. */
    data object TranscriptTooLong : SpeechErrorCode {
        override val userMessage =
            "That was too long for one command. Say something shorter."
        override val retryable = false
    }

    /**
     * An error the platform reported but we have no typed case for. The raw code
     * is kept for the log (an integer result code is an allowed log field under
     * SECURITY.md) and is never shown to the user.
     */
    data class Unknown(val platformCode: Int) : SpeechErrorCode {
        override val userMessage = "Voice input failed. Try again."
        override val retryable = true
    }
}

/**
 * The speech-to-text seam.
 *
 * The interface models the real [android.speech.SpeechRecognizer] lifecycle —
 * a session is started, streams partials, then ends in exactly one terminal
 * event — without exposing a single android.speech type. That keeps the
 * controller, the state machine and every test in this package free of Android.
 */
interface SpeechToTextPort {

    /** Cheap, synchronous capability probe used to enable/disable push-to-talk. */
    fun session(config: ListenConfig = ListenConfig()): Flow<SpeechEvent>

    /** Graceful finish: ask for the best-so-far result. */
    fun stopListening()

    /** Abandon the session. The flow completes without a terminal result. */
    fun cancelListening()

    /** Release platform resources. Idempotent. */
    fun destroy()
}