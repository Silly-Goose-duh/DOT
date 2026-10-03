package com.dot.app.voice

/**
 * Every state push-to-talk can be in.
 *
 * [Completed] carries the normalized transcript so the caller can render it,
 * but the transcript is handed onward through the same [Callback] as typed
 * text — it gains no authority by having been spoken.
 */
sealed interface VoiceSessionState {

    /** Not listening, nothing pending. */
    data object Idle : VoiceSessionState

    /** Permission dialog is up. Distinguishable from Listening so a slow system
     *  dialog is never mistaken for an active microphone. */
    data object RequestingPermission : VoiceSessionState

    /**
     * Capturing audio. [partialText] is display-only and is never submitted —
     * partial hypotheses are wrong often enough that submitting them would
     * create tasks the user never asked for.
     */
    data class Listening(val partialText: String = "") : VoiceSessionState

    /** A final result arrived and is being validated before submission. */
    data object Processing : VoiceSessionState

    /** Transcript was normalized and is being handed to the router. */
    data class Completed(val transcript: String) : VoiceSessionState

    /** RECORD_AUDIO was refused. The rest of the app is unaffected. */
    data object PermissionDenied : VoiceSessionState

    /** No recognizer on this device. */
    data class Unavailable(val reason: SpeechUnavailableReason) : VoiceSessionState

    /** Recognizer or normalizer failed. */
    data class Error(val code: SpeechErrorCode) : VoiceSessionState
}

/** Everything that can move the machine. An unlisted event is illegal. */
sealed interface VoiceEvent {
    /** User tapped the mic. */
    data object StartRequested : VoiceEvent

    /** User released / tapped again to finish early. */
    data object StopRequested : VoiceEvent

    /** User tapped while already listening: an abort, not a submit. */
    data object CancelRequested : VoiceEvent

    data object PermissionGranted : VoiceEvent

    data object PermissionRefused : VoiceEvent

    data object SpeechReady : VoiceEvent

    data class PartialTranscript(val text: String) : VoiceEvent

    /** Final raw result; the machine normalizes and validates it. */
    data class FinalTranscript(val raw: String) : VoiceEvent

    data class RecognizerFailed(val code: SpeechErrorCode) : VoiceEvent

    /** Return to Idle from any resting state, e.g. after the UI shows the result. */
    data object Reset : VoiceEvent

    /** Availability re-checked by the caller and found unusable. */
    data class BecameUnavailable(val reason: SpeechUnavailableReason) : VoiceEvent
}

/** Why a transition was refused. Surfaced so illegal use is visible, not silent. */
enum class TransitionRejection {
    ILLEGAL,
}

/**
 * Pure state machine for the push-to-talk lifecycle.
 *
 * Illegal transitions are rejected, never ignored and never silently coerced:
 * a dropped [VoiceEvent.StartRequested] in [VoiceSessionState.Listening] would
 * start a second concurrent microphone session, which is exactly the kind of
 * platform misuse that gets an app flagged. Holding the rules here rather than
 * in the UI means both the Compose panel and any future surface get the same
 * behaviour, and every rule is unit-testable without a device.
 */
class VoiceSessionStateMachine(
    initialState: VoiceSessionState = VoiceSessionState.Idle,
    private val normalizer: TranscriptNormalizer = TranscriptNormalizer(),
) {

    var state: VoiceSessionState = initialState
        private set

    /**
     * Applies [event], mutating [state] only when the transition is legal.
     * An illegal event leaves the machine exactly where it was.
     */
    fun dispatch(event: VoiceEvent): Transition {
        val previous = state
        val next = nextStateFor(event)
        if (next != null) state = next
        return Transition(previous = previous, current = state, accepted = next != null)
    }

    /**
     * Pure transition lookup, exposed for the transition-table tests.
     *
     * The current state is copied into a local before the `when` on purpose:
     * [state] is a mutable property, so Kotlin cannot smart-cast it to
     * `VoiceSessionState.Listening` inside a branch and `partialText` would be
     * unresolvable. A local `val` is stable, so the cast — and the carried-over
     * partial hypothesis — are both well-typed.
     */
    fun nextStateFor(event: VoiceEvent): VoiceSessionState? {
        val current = state
        return when (current) {
            VoiceSessionState.Idle -> fromIdle(event)
            VoiceSessionState.RequestingPermission -> fromRequestingPermission(event)
            is VoiceSessionState.Listening -> fromListening(current, event)
            VoiceSessionState.Processing -> fromProcessing(event)
            is VoiceSessionState.Completed -> fromCompleted(event)
            VoiceSessionState.PermissionDenied -> fromPermissionDenied(event)
            is VoiceSessionState.Unavailable -> fromUnavailable(event)
            is VoiceSessionState.Error -> fromError(event)
        }
    }

    private fun fromIdle(event: VoiceEvent): VoiceSessionState? = when (event) {
        // A permission that was granted earlier goes straight to Listening; the
        // request round-trip only happens when we actually do not know.
        VoiceEvent.PermissionGranted -> VoiceSessionState.Listening()
        VoiceEvent.StartRequested -> VoiceSessionState.RequestingPermission
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        VoiceEvent.Reset -> VoiceSessionState.Idle
        else -> null
    }

    private fun fromRequestingPermission(event: VoiceEvent): VoiceSessionState? = when (event) {
        VoiceEvent.PermissionGranted -> VoiceSessionState.Listening()
        VoiceEvent.PermissionRefused -> VoiceSessionState.PermissionDenied
        VoiceEvent.CancelRequested -> VoiceSessionState.Idle
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        else -> null
    }

    private fun fromListening(
        current: VoiceSessionState.Listening,
        event: VoiceEvent,
    ): VoiceSessionState? = when (event) {
        // Second tap finishes the utterance rather than aborting it: releasing
        // the button is the natural "I'm done" gesture for push-to-talk, and
        // discarding what was clearly heard would be hostile.
        VoiceEvent.StopRequested -> VoiceSessionState.Processing
        VoiceEvent.CancelRequested -> VoiceSessionState.Idle
        VoiceEvent.SpeechReady -> VoiceSessionState.Listening(current.partialText)
        is VoiceEvent.PartialTranscript -> VoiceSessionState.Listening(event.text)
        is VoiceEvent.FinalTranscript -> normalized(event.raw)
        is VoiceEvent.RecognizerFailed -> VoiceSessionState.Error(event.code)
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        VoiceEvent.Reset -> VoiceSessionState.Idle
        // StartRequested from Listening is illegal on purpose: a second start
        // would be a second concurrent recognizer.
        else -> null
    }

    private fun fromProcessing(event: VoiceEvent): VoiceSessionState? = when (event) {
        // Processing is reached by StopRequested — the user tapped again to
        // finish — which happens BEFORE the recognizer answers. The final result
        // is therefore the event this state is actually waiting for, and
        // rejecting it would silently throw away what the user just spoke.
        // It goes through the same normalized() gate as the direct path, so a
        // blank or over-length utterance still cannot reach the router.
        is VoiceEvent.FinalTranscript -> normalized(event.raw)
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.RecognizerFailed -> VoiceSessionState.Error(event.code)
        // A late partial or arming callback is dropped: the session is over and
        // must not look like it is still capturing.
        else -> null
    }

    private fun fromCompleted(event: VoiceEvent): VoiceSessionState? = when (event) {
        VoiceEvent.StartRequested -> VoiceSessionState.RequestingPermission
        VoiceEvent.PermissionGranted -> VoiceSessionState.Listening()
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        else -> null
    }

    private fun fromPermissionDenied(event: VoiceEvent): VoiceSessionState? = when (event) {
        // Retrying re-opens the system dialog, which is the documented retry path
        // for a "don't ask again" denial; the UI offers Settings as well.
        VoiceEvent.StartRequested -> VoiceSessionState.RequestingPermission
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        else -> null
    }

    private fun fromUnavailable(event: VoiceEvent): VoiceSessionState? = when (event) {
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        // A later successful availability check legitimately unblocks the
        // button without a restart (user installed a recognizer, granted mic).
        VoiceEvent.PermissionGranted -> VoiceSessionState.Listening()
        else -> null
    }

    private fun fromError(event: VoiceEvent): VoiceSessionState? = when (event) {
        VoiceEvent.StartRequested -> VoiceSessionState.RequestingPermission
        VoiceEvent.PermissionGranted -> VoiceSessionState.Listening()
        VoiceEvent.Reset -> VoiceSessionState.Idle
        is VoiceEvent.BecameUnavailable -> VoiceSessionState.Unavailable(event.reason)
        else -> null
    }

    /**
     * Normalization happens on the way out of Listening so no caller can skip
     * it, and an unusable result is an [VoiceSessionState.Error] rather than a
     * Completed with empty text — an empty transcript must never reach the router.
     */
    private fun normalized(raw: String): VoiceSessionState =
        when (val result = normalizer.normalize(raw)) {
            is TranscriptNormalization.Ok -> VoiceSessionState.Completed(result.text)
            TranscriptNormalization.Empty -> VoiceSessionState.Error(SpeechErrorCode.EmptyTranscript)
            is TranscriptNormalization.TooLong -> VoiceSessionState.Error(SpeechErrorCode.TranscriptTooLong)
        }

    /** Dispatch outcome. [rejection] is non-null exactly when nothing changed. */
    data class Transition(
        val previous: VoiceSessionState,
        val current: VoiceSessionState,
        val accepted: Boolean,
    ) {
        val rejection: TransitionRejection?
            get() = if (accepted) null else TransitionRejection.ILLEGAL
        val changed: Boolean get() = accepted && current != previous
    }
}