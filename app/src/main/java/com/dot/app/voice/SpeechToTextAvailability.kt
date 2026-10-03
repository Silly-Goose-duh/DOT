package com.dot.app.voice

import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer

/** Why push-to-talk would fail even if the user tapped it. */
enum class SpeechUnavailableReason {
    /** No recognition service on this device (common on de-Googled ROMs and emulators). */
    NO_RECOGNIZER_ON_DEVICE,

    /** RECORD_AUDIO is not granted. The rest of the app is unaffected. */
    MICROPHONE_PERMISSION_MISSING,

    /** The probe itself failed; treat as unavailable rather than failing at tap time. */
    UNKNOWN,
}

data class SpeechAvailability(
    val available: Boolean,
    val reason: SpeechUnavailableReason? = null,
) {
    companion object {
        val Available = SpeechAvailability(available = true)
    }
}

/**
 * Capability probe. Split behind an interface so the state machine and the UI
 * model can be tested against "no recognizer on this device" without an emulator.
 */
fun interface SpeechAvailabilityPort {
    fun check(): SpeechAvailability
}

/**
 * [SpeechRecognizer.isRecognitionAvailable] wrapped so the platform call is
 * injectable — the real static is reached only through [probe].
 *
 * Both probes answer a question the user would otherwise only learn by tapping
 * a dead button: is there anything to talk to, and are we allowed to.
 */
class AndroidSpeechAvailability(
    context: Context,
    private val probe: (Context) -> Boolean = { SpeechRecognizer.isRecognitionAvailable(it) },
    private val permissionProbe: (Context) -> Boolean = { hasRecordAudio(it) },
) : SpeechAvailabilityPort {

    private val appContext = context.applicationContext

    override fun check(): SpeechAvailability {
        val recognizerPresent = try {
            probe(appContext)
        } catch (e: RuntimeException) {
            // A throwing capability probe is "unknown", not "available": we would
            // rather disable the control than fail inside a touch handler.
            return SpeechAvailability(false, SpeechUnavailableReason.UNKNOWN)
        }
        if (!recognizerPresent) {
            return SpeechAvailability(false, SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE)
        }

        val granted = try {
            permissionProbe(appContext)
        } catch (e: SecurityException) {
            false
        }
        return if (granted) {
            SpeechAvailability.Available
        } else {
            SpeechAvailability(false, SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING)
        }
    }
}

/** Narrow permission seam so tests never touch Context.checkSelfPermission. */
fun interface MicPermissionPort {
    fun isGranted(): Boolean
}

class AndroidMicPermission(context: Context) : MicPermissionPort {
    private val appContext = context.applicationContext
    override fun isGranted(): Boolean = hasRecordAudio(appContext)
}

private fun hasRecordAudio(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED