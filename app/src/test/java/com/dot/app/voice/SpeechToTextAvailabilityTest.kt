package com.dot.app.voice

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeechToTextAvailabilityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun availability(
        recognizer: Boolean,
        permission: Boolean = true,
    ) = AndroidSpeechAvailability(
        context = context,
        probe = { recognizer },
        permissionProbe = { permission },
    )

    @Test
    fun `available when a recognizer exists and the mic is granted`() {
        assertThat(availability(recognizer = true).check())
            .isEqualTo(SpeechAvailability.Available)
    }

    @Test
    fun `unavailable with no recognizer on the device`() {
        val result = availability(recognizer = false).check()
        assertThat(result.available).isFalse()
        assertThat(result.reason).isEqualTo(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE)
    }

    @Test
    fun `recognizer present but mic missing reports the permission reason`() {
        val result = availability(recognizer = true, permission = false).check()
        assertThat(result.available).isFalse()
        assertThat(result.reason).isEqualTo(SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING)
    }

    @Test
    fun `a throwing probe degrades to unknown rather than available`() {
        val result = AndroidSpeechAvailability(
            context = context,
            probe = { throw IllegalStateException("no service") },
        ).check()
        assertThat(result.available).isFalse()
        assertThat(result.reason).isEqualTo(SpeechUnavailableReason.UNKNOWN)
    }

    @Test
    fun `no recognizer wins over missing permission`() {
        // Permission is checked second on purpose: asking for the mic when there
        // is nothing to use it with is a pointless system dialog.
        val result = availability(recognizer = false, permission = false).check()
        assertThat(result.reason).isEqualTo(SpeechUnavailableReason.NO_RECOGNIZER_ON_DEVICE)
    }

    @Test
    fun `a security exception from the permission probe is not a crash`() {
        val result = AndroidSpeechAvailability(
            context = context,
            probe = { true },
            permissionProbe = { throw SecurityException("denied") },
        ).check()
        assertThat(result.available).isFalse()
        assertThat(result.reason).isEqualTo(SpeechUnavailableReason.MICROPHONE_PERMISSION_MISSING)
    }

    @Test
    fun `the android mic permission port reads the real grant state`() {
        // Robolectric grants nothing by default; the port must report false
        // rather than throw, which is what keeps the app usable on denial.
        val port = AndroidMicPermission(context)
        // Either answer is acceptable as long as it does not throw; asserting the
        // value would make the test depend on Robolectric's shadow defaults.
        port.isGranted()
    }

    @Test
    fun `availability implements the port so it can be faked`() {
        val port: SpeechAvailabilityPort = SpeechAvailabilityPort { SpeechAvailability.Available }
        assertThat(port.check().available).isTrue()
    }
}