package com.dot.app.voice

import com.dot.app.ui.VoicePanelUiModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The panel's presentation rules are separated from the composable so they can
 * be asserted directly: a control that is disabled must say why, and every
 * state must produce a non-empty label and description for screen readers.
 */
class VoicePanelUiModelTest {

    private fun model(state: VoiceSessionState) = VoicePanelUiModel.from(state)

    @Test
    fun `idle enables the control`() {
        val m = model(VoiceSessionState.Idle)
        assertThat(m.enabled).isTrue()
        assertThat(m.listening).isFalse()
        assertThat(m.contentDescription).isNotEmpty()
    }

    @Test
    fun `requesting permission disables the control`() {
        assertThat(model(VoiceSessionState.RequestingPermission).enabled).isFalse()
    }

    @Test
    fun `listening shows the partial text and offers a stop description`() {
        val m = model(VoiceSessionState.Listening("add a"))
        assertThat(m.enabled).isTrue()
        assertThat(m.listening).isTrue()
        assertThat(m.stateLabel).contains("add a")
        assertThat(m.contentDescription).isEqualTo("Stop voice input")
    }

    @Test
    fun `processing disables the control`() {
        assertThat(model(VoiceSessionState.Processing).enabled).isFalse()
    }

    @Test
    fun `permission denied keeps the control tappable and shows the retry path`() {
        val m = model(VoiceSessionState.PermissionDenied)
        assertThat(m.enabled).isTrue()
        assertThat(m.showPermissionRetry).isTrue()
        // PRD 22: name the consequence, not just the failure.
        assertThat(m.hint).contains("Typing still works")
    }

    @Test
    fun `no recognizer disables the control and does not offer a permission retry`() {
        val m = model(VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE))
        assertThat(m.enabled).isFalse()
        assertThat(m.showPermissionRetry).isFalse()
        assertThat(m.hint).contains("speech recognition service")
    }

    @Test
    fun `a missing mic grant does offer the permission retry`() {
        val m = model(VoiceSessionState.Unavailable(SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING))
        assertThat(m.showPermissionRetry).isTrue()
    }

    @Test
    fun `an unknown unavailability reason still explains itself`() {
        val m = model(VoiceSessionState.Unavailable(SpeechUnavailableReason.UNKNOWN))
        assertThat(m.enabled).isFalse()
        assertThat(m.hint).isNotEmpty()
    }

    @Test
    fun `a permission error offers the settings path`() {
        assertThat(model(VoiceSessionState.Error(SpeechErrorCode.PermissionDenied)).showPermissionRetry).isTrue()
    }

    @Test
    fun `a retryable error does not offer the settings path`() {
        assertThat(model(VoiceSessionState.Error(SpeechErrorCode.NoMatch)).showPermissionRetry).isFalse()
        assertThat(model(VoiceSessionState.Error(SpeechErrorCode.Network)).hint)
            .contains("connection")
    }

    @Test
    fun `completed shows what was heard`() {
        val m = model(VoiceSessionState.Completed("add a task"))
        assertThat(m.stateLabel).contains("add a task")
        assertThat(m.enabled).isTrue()
    }

    @Test
    fun `every state yields a non-empty accessible label`() {
        val states = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.RequestingPermission,
            VoiceSessionState.Listening(),
            VoiceSessionState.Listening("partial"),
            VoiceSessionState.Processing,
            VoiceSessionState.Completed("done"),
            VoiceSessionState.PermissionDenied,
            VoiceSessionState.Unavailable(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE),
            VoiceSessionState.Unavailable(SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING),
            VoiceSessionState.Unavailable(SpeechUnavailableReason.UNKNOWN),
            VoiceSessionState.Error(SpeechErrorCode.NoMatch),
            VoiceSessionState.Error(SpeechErrorCode.PermissionDenied),
        )
        states.forEach { s ->
            val m = model(s)
            assertThat(m.stateLabel).isNotEmpty()
            assertThat(m.contentDescription).isNotEmpty()
        }
    }

    @Test
    fun `exactly one state is listening at a time`() {
        val listening = listOf(VoiceSessionState.Listening(), VoiceSessionState.Listening("x"))
        assertThat(listening.all { model(it).listening }).isTrue()
        val notListening = listOf(
            VoiceSessionState.Idle,
            VoiceSessionState.Processing,
            VoiceSessionState.PermissionDenied,
        )
        assertThat(notListening.none { model(it).listening }).isTrue()
    }
}