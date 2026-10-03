package com.dot.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transition table is the contract the UI and the controller both rely on,
 * so it is checked exhaustively: every legal move is asserted, and every
 * illegal one is asserted to be rejected *and* to leave state untouched.
 */
class VoiceSessionStateMachineTest {

    private fun machine(
        state: VoiceSessionState = VoiceSessionState.Idle,
        normalizer: TranscriptNormalizer = TranscriptNormalizer(),
    ) = VoiceSessionStateMachine(initialState = state, normalizer = normalizer)

    // ---------------------------------------------------------------- legal

    @Test
    fun `idle plus start requests permission`() {
        val m = machine()
        val t = m.dispatch(VoiceEvent.StartRequested)
        assertThat(t.accepted).isTrue()
        assertThat(m.state).isEqualTo(VoiceSessionState.RequestingPermission)
    }

    @Test
    fun `idle plus permission granted starts listening`() {
        val m = machine()
        m.dispatch(VoiceEvent.PermissionGranted)
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening())
    }

    @Test
    fun `requesting permission plus grant starts listening`() {
        val m = machine(VoiceSessionState.RequestingPermission)
        m.dispatch(VoiceEvent.PermissionGranted)
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening())
    }

    @Test
    fun `requesting permission plus refusal is denied`() {
        val m = machine(VoiceSessionState.RequestingPermission)
        m.dispatch(VoiceEvent.PermissionRefused)
        assertThat(m.state).isEqualTo(VoiceSessionState.PermissionDenied)
    }

    @Test
    fun `partial transcript updates the listening text`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.PartialTranscript("add a task"))
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening("add a task"))
    }

    @Test
    fun `speech ready keeps the existing partial text`() {
        val m = machine(VoiceSessionState.Listening("hello"))
        m.dispatch(VoiceEvent.SpeechReady)
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening("hello"))
    }

    @Test
    fun `final transcript completes with normalized text`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.FinalTranscript("  Add a task.  "))
        assertThat(m.state).isEqualTo(VoiceSessionState.Completed("Add a task"))
    }

    @Test
    fun `stop requested moves to processing`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.StopRequested)
        assertThat(m.state).isEqualTo(VoiceSessionState.Processing)
    }

    @Test
    fun `cancel from listening discards everything`() {
        val m = machine(VoiceSessionState.Listening("half a thi"))
        m.dispatch(VoiceEvent.CancelRequested)
        assertThat(m.state).isEqualTo(VoiceSessionState.Idle)
    }

    @Test
    fun `recognizer failure becomes a typed error`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.RecognizerFailed(SpeechErrorCode.Network))
        assertThat(m.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.Network))
    }

    @Test
    fun `empty final transcript is an error not an empty completion`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.FinalTranscript("   "))
        assertThat(m.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.EmptyTranscript))
    }

    @Test
    fun `punctuation-only final transcript is an error`() {
        val m = machine(VoiceSessionState.Listening())
        m.dispatch(VoiceEvent.FinalTranscript("..."))
        assertThat(m.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.EmptyTranscript))
    }

    @Test
    fun `over-length final transcript is rejected not truncated`() {
        val m = machine(
            VoiceSessionState.Listening(),
            TranscriptNormalizer(maxLength = 10),
        )
        m.dispatch(VoiceEvent.FinalTranscript("this is far too long to send"))
        assertThat(m.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.TranscriptTooLong))
    }

    @Test
    fun `reset returns every resting state to idle`() {
        val resting = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.RequestingPermission,
            VoiceSessionState.Listening(),
            VoiceSessionState.Processing,
            VoiceSessionState.Completed("hi"),
            VoiceSessionState.PermissionDenied,
            VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE),
            VoiceSessionState.Error(SpeechErrorCode.Server),
        )
        resting.forEach { start ->
            val m = machine(start)
            assertThat(m.dispatch(VoiceEvent.Reset).accepted).isTrue()
            assertThat(m.state).isEqualTo(VoiceSessionState.Idle)
        }
    }

    @Test
    fun `completed can start a new utterance`() {
        val m = machine(VoiceSessionState.Completed("add a task"))
        m.dispatch(VoiceEvent.StartRequested)
        assertThat(m.state).isEqualTo(VoiceSessionState.RequestingPermission)
    }

    @Test
    fun `permission denied retries via start`() {
        val m = machine(VoiceSessionState.PermissionDenied)
        m.dispatch(VoiceEvent.StartRequested)
        assertThat(m.state).isEqualTo(VoiceSessionState.RequestingPermission)
    }

    @Test
    fun `unavailable becomes listening once a recognizer appears`() {
        val m = machine(VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE))
        m.dispatch(VoiceEvent.PermissionGranted)
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening())
    }

    @Test
    fun `error can be retried`() {
        val m = machine(VoiceSessionState.Error(SpeechErrorCode.NoMatch))
        m.dispatch(VoiceEvent.StartRequested)
        assertThat(m.state).isEqualTo(VoiceSessionState.RequestingPermission)
    }

    @Test
    fun `became unavailable is legal from every resting state`() {
        val resting = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.RequestingPermission,
            VoiceSessionState.Completed("hi"),
            VoiceSessionState.PermissionDenied,
            VoiceSessionState.Error(SpeechErrorCode.Server),
            VoiceSessionState.Unavailable(SpeechUnavailableReason.UNKNOWN),
        )
        resting.forEach { start ->
            val m = machine(start)
            assertThat(m.dispatch(VoiceEvent.BecameUnavailable(SpeechUnavailableReason.UNKNOWN)).accepted).isTrue()
            assertThat(m.state).isEqualTo(VoiceSessionState.Unavailable(SpeechUnavailableReason.UNKNOWN))
        }
    }

    // -------------------------------------------------------------- illegal

    @Test
    fun `listening rejects a second start`() {
        val m = machine(VoiceSessionState.Listening("partial"))
        val t = m.dispatch(VoiceEvent.StartRequested)
        assertThat(t.accepted).isFalse()
        assertThat(t.rejection).isEqualTo(TransitionRejection.ILLEGAL)
        assertThat(m.state).isEqualTo(VoiceSessionState.Listening("partial"))
    }

    @Test
    fun `idle rejects a partial transcript`() {
        val m = machine()
        assertThat(m.dispatch(VoiceEvent.PartialTranscript("x")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.Idle)
    }

    @Test
    fun `idle rejects a final transcript`() {
        val m = machine()
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("x")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.Idle)
    }

    @Test
    fun `idle rejects stop`() {
        val m = machine()
        assertThat(m.dispatch(VoiceEvent.StopRequested).accepted).isFalse()
    }

    @Test
    fun `idle rejects a recognizer failure`() {
        val m = machine()
        assertThat(m.dispatch(VoiceEvent.RecognizerFailed(SpeechErrorCode.Server)).accepted).isFalse()
    }

    @Test
    fun `requesting permission rejects speech events`() {
        val m = machine(VoiceSessionState.RequestingPermission)
        assertThat(m.dispatch(VoiceEvent.SpeechReady).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.PartialTranscript("x")).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("x")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.RequestingPermission)
    }

    @Test
    fun `requesting permission rejects a start`() {
        val m = machine(VoiceSessionState.RequestingPermission)
        assertThat(m.dispatch(VoiceEvent.StartRequested).accepted).isFalse()
    }

    @Test
    fun `processing rejects a late partial or arming callback`() {
        val m = machine(VoiceSessionState.Processing)
        assertThat(m.dispatch(VoiceEvent.PartialTranscript("x")).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.SpeechReady).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.Processing)
    }

    @Test
    fun `processing accepts the final result the stop was waiting for`() {
        // Processing is entered by StopRequested, before the recognizer answers,
        // so the final result is the event this state exists to receive. Treating
        // it as illegal silently discarded what the user had just spoken.
        val m = machine(VoiceSessionState.Processing)
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("  add a task.  ")).accepted).isTrue()
        assertThat(m.state).isEqualTo(VoiceSessionState.Completed("add a task"))
    }

    @Test
    fun `processing applies the same transcript validation as the direct path`() {
        // Tap-to-stop must not become a way around the normalizer: an empty or
        // over-length utterance is still an error, never an empty submission.
        val blank = machine(VoiceSessionState.Processing)
        blank.dispatch(VoiceEvent.FinalTranscript("   "))
        assertThat(blank.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.EmptyTranscript))

        val long = machine(VoiceSessionState.Processing, TranscriptNormalizer(maxLength = 10))
        long.dispatch(VoiceEvent.FinalTranscript("this is far too long to send"))
        assertThat(long.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.TranscriptTooLong))
    }

    @Test
    fun `processing rejects start and stop`() {
        val m = machine(VoiceSessionState.Processing)
        assertThat(m.dispatch(VoiceEvent.StartRequested).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.StopRequested).accepted).isFalse()
    }

    @Test
    fun `completed rejects partial and final transcripts`() {
        val m = machine(VoiceSessionState.Completed("done"))
        assertThat(m.dispatch(VoiceEvent.PartialTranscript("x")).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("y")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.Completed("done"))
    }

    @Test
    fun `permission denied rejects stop and transcripts`() {
        val m = machine(VoiceSessionState.PermissionDenied)
        assertThat(m.dispatch(VoiceEvent.StopRequested).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("x")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.PermissionDenied)
    }

    @Test
    fun `unavailable rejects start and transcripts`() {
        val m = machine(VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE))
        assertThat(m.dispatch(VoiceEvent.StartRequested).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("x")).accepted).isFalse()
        assertThat(m.dispatch(VoiceEvent.StopRequested).accepted).isFalse()
        assertThat(m.state).isEqualTo(
            VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE),
        )
    }

    @Test
    fun `error rejects transcripts`() {
        val m = machine(VoiceSessionState.Error(SpeechErrorCode.NoMatch))
        assertThat(m.dispatch(VoiceEvent.FinalTranscript("x")).accepted).isFalse()
        assertThat(m.state).isEqualTo(VoiceSessionState.Error(SpeechErrorCode.NoMatch))
    }

    @Test
    fun `rejection reports previous and current state without mutation`() {
        val m = machine(VoiceSessionState.Idle)
        val t = m.dispatch(VoiceEvent.StopRequested)
        assertThat(t.accepted).isFalse()
        assertThat(t.changed).isFalse()
        assertThat(t.previous).isEqualTo(VoiceSessionState.Idle)
        assertThat(t.current).isEqualTo(VoiceSessionState.Idle)
    }

    @Test
    fun `start from idle then grant reaches listening end to end`() {
        val m = machine()
        assertThat(m.dispatch(VoiceEvent.StartRequested).accepted).isTrue()
        assertThat(m.dispatch(VoiceEvent.PermissionGranted).accepted).isTrue()
        m.dispatch(VoiceEvent.PartialTranscript("add"))
        m.dispatch(VoiceEvent.PartialTranscript("add a task"))
        m.dispatch(VoiceEvent.FinalTranscript("add a task."))
        assertThat(m.state).isEqualTo(VoiceSessionState.Completed("add a task"))
    }
}