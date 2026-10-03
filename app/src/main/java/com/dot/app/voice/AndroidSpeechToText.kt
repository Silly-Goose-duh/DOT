package com.dot.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * [SpeechRecognizer]-backed implementation of [SpeechToTextPort].
 *
 * Three constraints shape this class:
 *
 *  - SpeechRecognizer must be created and driven on the main thread, so every
 *    platform call is posted through [main] and results are re-emitted into a
 *    cold [callbackFlow]. Callers therefore collect from any dispatcher.
 *  - destroy() is mandatory. A leaked SpeechRecognizer keeps the microphone
 *    service bound for the process lifetime, which is both a battery leak and a
 *    privacy problem for an app that holds personal data.
 *  - No raw audio and no transcript is ever logged. Only the event kind and the
 *    integer result code are logged, which SECURITY.md permits.
 */
class AndroidSpeechToText(
    context: Context,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : SpeechToTextPort {

    private val appContext = context.applicationContext

    /** Guards against a start arriving while a recognizer is still being torn down. */
    @Volatile
    private var destroyed = false

    override fun session(config: ListenConfig): Flow<SpeechEvent> {
        if (destroyed) return flowOf(SpeechEvent.Failed(SpeechErrorCode.Client))

        val granted = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // Belt and braces: the state machine already gates on permission, but a
        // missing grant must surface as a typed error rather than a platform
        // callback that never arrives.
        if (!granted) return flowOf(SpeechEvent.Failed(SpeechErrorCode.PermissionDenied))

        return callbackFlow {
            val channel = this

            fun post(block: () -> Unit) {
                if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
            }

            var recognizer: SpeechRecognizer? = null

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    channel.trySend(SpeechEvent.Ready)
                }

                override fun onBeginningOfSpeech() {
                    channel.trySend(SpeechEvent.SpeechStarted)
                }

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = bestText(partialResults) ?: return
                    channel.trySend(SpeechEvent.Partial(text))
                }

                override fun onResults(results: Bundle?) {
                    // A null or empty bundle is a real outcome (silence), not an
                    // error: it is reported as a blank Final so the normalizer,
                    // not this class, decides that there is nothing to send.
                    channel.trySend(SpeechEvent.Final(bestText(results) ?: ""))
                    channel.close()
                }

                override fun onError(error: Int) {
                    // Never swallow a failure and never fall through to a
                    // success: every platform code maps to a typed error.
                    channel.trySend(SpeechEvent.Failed(mapPlatformError(error)))
                    channel.close()
                }
            }

            post {
                if (destroyed) {
                    channel.trySend(SpeechEvent.Failed(SpeechErrorCode.Client))
                    channel.close()
                    return@post
                }
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                        it.setRecognitionListener(listener)
                        current = it
                        it.startListening(buildIntent(config))
                    }
                } catch (e: SecurityException) {
                    channel.trySend(SpeechEvent.Failed(SpeechErrorCode.PermissionDenied))
                    channel.close()
                } catch (e: RuntimeException) {
                    // Some ROMs throw when no recognizer service is bound.
                    channel.trySend(SpeechEvent.Failed(mapPlatformError(ERROR_CLIENT)))
                    channel.close()
                }
            }

            awaitClose {
                post {
                    recognizer?.let { r ->
                        try {
                            r.cancel()
                            r.destroy()
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "recognizer_teardown_failed")
                        }
                    }
                    recognizer = null
                }
            }
        }.buffer(UNBOUNDED)
    }

    override fun stopListening() {
        main.post {
            // stopListening asks for the best-so-far result; the RecognitionListener
            // still delivers onResults, so nothing is lost.
            current?.stopListening()
        }
    }

    override fun cancelListening() {
        main.post { current?.cancel() }
    }

    override fun destroy() {
        destroyed = true
    }

    /**
     * The active recognizer, published by [session]. Kept separate from the
     * flow so stop/cancel can be issued without holding the collection.
     */
    @Volatile
    private var current: SpeechRecognizer? = null

    private fun buildIntent(config: ListenConfig): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, config.maxResults)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (config.preferOffline) {
                // Kept off by default: offline recognition is far less accurate
                // and a worse experience than a clear "needs a connection".
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    internal companion object {
        const val TAG = "DotSpeech"
        const val UNBOUNDED = Channel.UNLIMITED

        // Mirrors of the framework constants (AOSP core/java/android/speech/
        // SpeechRecognizer.java), kept so the mapping can be exercised in plain
        // JVM tests where android.speech is stubbed out. These values are the
        // real ones: a "close enough" mirror would map a server fault to a
        // client fault and show the user the wrong recovery hint.
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
        const val ERROR_CANNOT_CHECK_SUPPORT = 14
        const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

        /** Every documented platform code, so the test can prove totality. */
        val ALL_PLATFORM_ERRORS = intArrayOf(
            ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_AUDIO, ERROR_SERVER,
            ERROR_CLIENT, ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH,
            ERROR_RECOGNIZER_BUSY, ERROR_INSUFFICIENT_PERMISSIONS,
            ERROR_TOO_MANY_REQUESTS, ERROR_SERVER_DISCONNECTED,
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE,
            ERROR_CANNOT_CHECK_SUPPORT, ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
        )

        fun bestText(bundle: Bundle?): String? =
            bundle?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()

        /**
         * Total mapping of the platform error space onto [SpeechErrorCode].
         *
         * Anything unmapped becomes [SpeechErrorCode.Unknown] carrying the raw
         * int for logs. A partially-mapped table would let a future framework
         * error code be read as success, which is the one outcome this codebase
         * treats as unforgivable.
         */
        fun mapPlatformError(error: Int): SpeechErrorCode = when (error) {
            ERROR_NO_MATCH -> SpeechErrorCode.NoMatch
            ERROR_SPEECH_TIMEOUT -> SpeechErrorCode.SpeechTimeout
            ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorCode.PermissionDenied
            ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_TOO_MANY_REQUESTS ->
                SpeechErrorCode.Network
            ERROR_RECOGNIZER_BUSY -> SpeechErrorCode.RecognizerBusy
            ERROR_CLIENT, ERROR_CANNOT_CHECK_SUPPORT,
            ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> SpeechErrorCode.Client
            ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechErrorCode.Server
            ERROR_AUDIO -> SpeechErrorCode.Audio
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                SpeechErrorCode.Client
            else -> SpeechErrorCode.Unknown(error)
        }
    }
}