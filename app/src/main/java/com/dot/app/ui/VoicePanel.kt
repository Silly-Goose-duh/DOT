package com.dot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.dot.app.voice.SpeechErrorCode
import com.dot.app.voice.SpeechUnavailableReason
import com.dot.app.voice.VoiceSessionState

/**
 * Push-to-talk control.
 *
 * Stateless by design: state and callbacks come in as parameters so the host
 * owns the ViewModel and this composable cannot reach anything on its own. That
 * is also what makes the whole surface testable — a test renders it with a
 * chosen [VoiceSessionState] and asserts on semantics.
 *
 * Accessibility rules applied here rather than left to review:
 *  - every icon-only control carries a contentDescription,
 *  - the mic button is a minimum 48dp touch target,
 *  - the current state is exposed through stateDescription *and* a visible text
 *    label, so a screen-reader user and a sighted user get the same information.
 *
 * Privacy: the partial transcript is rendered only while the user is actively
 * holding the button, and it is never logged or persisted.
 */
@Composable
fun VoicePanel(
    state: VoiceSessionState,
    onTap: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = VoicePanelUiModel.from(state)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicButton(
                model = model,
                onClick = onTap,
            )

            Column(modifier = Modifier.weight(1f)) {
                // Visible state label: not decoration, the accessible text for
                // anyone who cannot infer state from the mic colour. The paired
                // semantics modifier exposes the same string as stateDescription.
                Text(
                    text = model.stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { stateDescription = model.stateLabel },
                )
                if (model.hint != null) {
                    Text(
                        text = model.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (model.showPermissionRetry) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Allow microphone")
                }
                // The permanent-denial path. Android stops showing the dialog
                // after two refusals, so a second "allow" button would be a lie.
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Open settings")
                }
            }
        }
    }
}

@Composable
private fun MicButton(
    model: VoicePanelUiModel,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = model.enabled,
        modifier = Modifier
            // 48dp is the platform minimum touch target; a smaller mic is a
            // mis-tap generator on the one control users reach for fastest.
            .size(56.dp)
            .semantics {
                contentDescription = model.contentDescription
                stateDescription = model.stateLabel
            },
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (model.listening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
    ) {
        // The mic glyph is drawn rather than imported: :app carries no
        // material-icons artifact, and pulling one in for a single shape would
        // add a dependency for no behaviour. The button's contentDescription
        // carries the meaning, so the glyph itself is decorative.
        MicGlyph(
            listening = model.listening,
            tint = contentColorFor(model.listening),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** True while listening, false when idle — the button swaps to a stop glyph. */
@Composable
private fun MicGlyph(
    listening: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val capsuleW = w * 0.34f
        val capsuleH = h * 0.52f
        val left = (w - capsuleW) / 2f
        val top = h * 0.06f

        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(capsuleW, capsuleH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleW / 2f),
        )

        if (listening) {
            // Stop square: unambiguous "finish now".
            drawRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(left, top + capsuleH * 0.25f),
                size = androidx.compose.ui.geometry.Size(capsuleW, capsuleH * 0.5f),
            )
            return@Canvas
        }

        // Mic body plus stand.
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.3f),
            size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.45f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.72f),
            end = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.92f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.94f),
            end = androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.94f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun contentColorFor(listening: Boolean): androidx.compose.ui.graphics.Color =
    if (listening) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

/**
 * Presentation model, split out so the state's meaning can be unit-tested
 * without inflating a Compose tree.
 */
data class VoicePanelUiModel(
    val enabled: Boolean,
    val listening: Boolean,
    val stateLabel: String,
    val contentDescription: String,
    val hint: String?,
    val showPermissionRetry: Boolean,
) {
    companion object {
        fun from(state: VoiceSessionState): VoicePanelUiModel = when (state) {
            VoiceSessionState.Idle -> VoicePanelUiModel(
                enabled = true,
                listening = false,
                stateLabel = "Voice input ready",
                contentDescription = "Start voice input",
                hint = "Tap to speak",
                showPermissionRetry = false,
            )

            VoiceSessionState.RequestingPermission -> VoicePanelUiModel(
                enabled = false,
                listening = false,
                stateLabel = "Waiting for microphone permission",
                contentDescription = "Waiting for microphone permission",
                hint = "Allow the microphone to use voice input",
                showPermissionRetry = false,
            )

            is VoiceSessionState.Listening -> VoicePanelUiModel(
                enabled = true,
                listening = true,
                stateLabel = if (state.partialText.isBlank()) {
                    "Listening..."
                } else {
                    "Listening: ${state.partialText}"
                },
                contentDescription = "Stop voice input",
                hint = "Tap again when you're done",
                showPermissionRetry = false,
            )

            VoiceSessionState.Processing -> VoicePanelUiModel(
                enabled = false,
                listening = false,
                stateLabel = "Processing speech",
                contentDescription = "Processing speech",
                hint = null,
                showPermissionRetry = false,
            )

            is VoiceSessionState.Completed -> VoicePanelUiModel(
                enabled = true,
                listening = false,
                stateLabel = "Heard: ${state.transcript}",
                contentDescription = "Start voice input",
                hint = null,
                showPermissionRetry = false,
            )

            VoiceSessionState.PermissionDenied -> VoicePanelUiModel(
                enabled = true,
                listening = false,
                stateLabel = "Microphone permission denied",
                contentDescription = "Start voice input",
                // Names the consequence, which is what PRD 22 asks for: the app
                // keeps working, voice specifically does not.
                hint = "Typing still works. Allow the microphone to enable voice input.",
                showPermissionRetry = true,
            )

            is VoiceSessionState.Unavailable -> VoicePanelUiModel(
                enabled = false,
                listening = false,
                stateLabel = "Voice input unavailable",
                contentDescription = "Voice input unavailable",
                hint = when (state.reason) {
                    SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE ->
                        "This device has no speech recognition service. Typing still works."
                    SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING ->
                        "Microphone access is off. Typing still works."
                    SpeechUnavailableReason.UNKNOWN ->
                        "Voice input couldn't be checked. Typing still works."
                },
                showPermissionRetry = state.reason ==
                    SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING,
            )

            is VoiceSessionState.Error -> VoicePanelUiModel(
                enabled = true,
                listening = false,
                stateLabel = "Voice input failed",
                contentDescription = "Start voice input",
                hint = state.code.userMessage,
                showPermissionRetry = state.code.needsPermission,
            )
        }
    }
}

/**
 * Announces the push-to-talk state on change. Hosts that replace the panel can
 * apply this to the surrounding container so a screen-reader user hears
 * "Listening..." without having to re-focus the button.
 */
fun Modifier.voiceLiveRegion(): Modifier =
    semantics { liveRegion = LiveRegionMode.Polite }