package com.dot.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Drives one push-to-talk lifecycle: availability gate, permission gate, the
 * [SpeechToTextPort] stream, the [VoiceSessionStateMachine], and finally the
 * transcript callback.
 *
 * The controller is deliberately the only place the two halves meet. The state
 * machine stays pure and the platform port stays dumb, so the whole policy —
 * what a tap does in each state, when a transcript is allowed out — is testable
 * with a fake port and no Android at all.
 *
 * Security note: [onTranscript] receives a plain string that the host wires
 * straight into `DotViewModel.submit`. A spoken transcript is DATA. It travels
 * the identical CommandRuntime.handle path as typed text and gains no extra
 * authority, no tool access and no confirmation bypass by having been spoken.
 */
class VoiceInputController(
    private val scope: CoroutineScope,
    private val port: SpeechToTextPort,
    private val availability: SpeechAvailabilityPort,
    private val permission: MicPermissionPort,
    /** Asks the host to show the system permission dialog. */
    private val requestPermission: () -> Unit,
    /** Where a validated transcript goes. Wired to DotViewModel.submit by the host. */
    private val onTranscript: (String) -> Unit,
    private val normalizer: TranscriptNormalizer = TranscriptNormalizer(),
    private val config: ListenConfig = ListenConfig(),
) {

    private val machine = VoiceSessionStateMachine(normalizer = normalizer)

    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null

    /** Re-checks the device capability so the UI can enable/disable the control. */
    fun refreshAvailability() {
        val result = availability.check()
        if (!result.available && result.reason != null) {
            apply(VoiceEvent.BecameUnavailable(result.reason))
            // Losing the recognizer mid-session is a loss of the microphone, not
            // just a UI change: the live collection is dropped so the platform
            // teardown in the flow's awaitClose actually runs.
            endSession()
        }
    }

    /** The mic button was tapped. Idempotent while a session is running. */
    fun onTap() {
        when (machine.state) {
            is VoiceSessionState.Listening -> stop()
            VoiceSessionState.RequestingPermission -> cancel()
            else -> start()
        }
    }

    private fun start() {
        val availabilityResult = availability.check()
        if (!availabilityResult.available) {
            // A missing recognizer is a device fact, not a user error: refuse
            // quietly into Unavailable instead of prompting for a permission
            // that cannot possibly help.
            val reason = availabilityResult.reason ?: SpeechUnavailableReason.UNKNOWN
            apply(VoiceEvent.BecameUnavailable(reason))
            return
        }

        if (!permission.isGranted()) {
            apply(VoiceEvent.StartRequested)
            if (machine.state is VoiceSessionState.RequestingPermission) {
                requestPermission()
            }
            return
        }

        val transition = apply(VoiceEvent.PermissionGranted)
        if (machine.state !is VoiceSessionState.Listening) return
        if (!transition.accepted) return
        collectSession()
    }

    /** Push-to-talk released, or the user tapped again to finish. */
    fun stop() {
        if (machine.state !is VoiceSessionState.Listening) return
        apply(VoiceEvent.StopRequested)
        // stopListening still delivers a final result via onResults, so the flow
        // is left running; cancelling it here would discard what was heard.
        port.stopListening()
    }

    /** Abort without submitting anything. */
    fun cancel() {
        apply(VoiceEvent.CancelRequested)
        // cancelListening is issued before the collection is dropped so the
        // platform recognizer is told to stop in the same turn; the flow's
        // awaitClose then destroys it. Idempotent: cancelling an already-cancelled
        // or never-started session is a no-op on both sides.
        port.cancelListening()
        endSession()
    }

    /** System permission dialog resolved. */
    fun onPermissionResult(granted: Boolean) {
        if (machine.state !is VoiceSessionState.RequestingPermission) return
        if (!granted) {
            apply(VoiceEvent.PermissionRefused)
            // The recognizer was never started on this path, but the refusal is
            // still an abort: dropping any stale collection keeps a retry from
            // inheriting a microphone that is already open.
            port.cancelListening()
            endSession()
            return
        }
        if (apply(VoiceEvent.PermissionGranted).accepted) collectSession()
    }

    /** Back to Idle; called once the UI has shown the result. */
    fun reset() {
        apply(VoiceEvent.Reset)
        // Reset can be reached from a resting state where the recognizer has
        // already answered but the flow was never closed by the port. Ending the
        // session here is what stops reset from leaking a live collector — and
        // therefore from hanging whatever scope the controller was given.
        endSession()
    }

    /** Releases the platform recognizer. Call from the host lifecycle. */
    fun release() {
        endSession()
        port.destroy()
    }

    /**
     * Drops the live collection, if any, and forgets it.
     *
     * Single teardown point so cancel/reset/release cannot drift apart, and
     * safe to call repeatedly: a null or already-completed job cancels cleanly.
     */
    private fun endSession() {
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun collectSession() {
        endSession()
        sessionJob = scope.launch {
            // A session is ONE listen -> final-or-failed cycle (see
            // SpeechToTextPort). Ending the collection here rather than trusting
            // the port to close its own flow means a recognizer that answers and
            // then keeps the callback open cannot leave this coroutine — and the
            // microphone behind it — alive indefinitely, and cannot leak a stale
            // Final into a later session. Aborting the collection also runs the
            // flow's awaitClose, which is what destroys the recognizer.
            //
            // onEach before takeWhile, not after: the side effect must run for
            // the terminal event too, and takeWhile drops the element that fails
            // its predicate. Chaining them the other way round would evaluate the
            // predicate first and discard exactly the result that ends the
            // session, leaving the controller stuck in Listening with the
            // microphone still open. Nothing downstream needs the elements, so
            // the dropped terminal is harmless.
            port.session(config)
                .onEach { onSpeechEvent(it) }
                .takeWhile { event -> !event.isTerminal }
                .collect { /* the work already happened in onEach */ }
        }
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            SpeechEvent.Ready -> apply(VoiceEvent.SpeechReady)
            SpeechEvent.SpeechStarted -> apply(VoiceEvent.SpeechReady)
            is SpeechEvent.Partial -> apply(VoiceEvent.PartialTranscript(event.text))
            is SpeechEvent.Final -> {
                val before = machine.state
                apply(VoiceEvent.FinalTranscript(event.text))
                // Only a Completed state carries a usable transcript. Every other
                // outcome (Error(EmptyTranscript), Error(TooLong)) stops here —
                // nothing is submitted and nothing is fabricated.
                val after = machine.state
                if (after is VoiceSessionState.Completed && before != after) {
                    onTranscript(after.transcript)
                }
            }
            is SpeechEvent.Failed -> apply(VoiceEvent.RecognizerFailed(event.code))
        }
    }

    private fun apply(event: VoiceEvent): VoiceSessionStateMachine.Transition {
        val transition = machine.dispatch(event)
        _state.value = machine.state
        return transition
    }
}