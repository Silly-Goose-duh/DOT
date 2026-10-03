package com.dot.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dot.app.reminder.ReminderScheduler
import com.dot.app.ui.ChatPanel
import com.dot.app.ui.DotViewModel
import com.dot.app.ui.NotesScreen
import com.dot.app.ui.SettingsScreen
import com.dot.app.ui.TasksScreen
import com.dot.app.ui.TodayScreen
import com.dot.app.ui.VoicePanel
import com.dot.app.voice.VoiceInputController
import com.dot.app.voice.VoiceSessionState
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.lifecycleScope

private enum class Tab(val label: String) {
    TODAY("Today"),
    CHAT("Ask"),
    TASKS("Tasks"),
    NOTES("Notes"),
    SETTINGS("Settings"),
}

class MainActivity : ComponentActivity() {

    private var notificationsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        (application as DotApplication).refreshCapabilityFlags()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        voiceController?.onPermissionResult(granted)
    }

    /**
     * Push-to-talk control. Created on first access, which happens inside onCreate —
     * the recognizer needs a main looper, so it must not be built in a field
     * initializer that could run off the main thread.
     */
    private var voiceController: VoiceInputController? = null

    private val dotViewModel: DotViewModel by viewModels {
        DotViewModel.Factory((application as DotApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationsGranted = (application as DotApplication).hasNotificationPermission()
        ReminderScheduler.ensureChannel(this)

        val container = (application as DotApplication).container
        val viewModel = dotViewModel
        val controller = VoiceInputController(
            // The Activity lifecycle scope outlives a rotation, so a half-finished
            // dictation is not silently dropped by a configuration change.
            scope = lifecycleScope,
            port = container.speechToText,
            availability = container.speechAvailability,
            permission = container.micPermission,
            requestPermission = ::requestMicrophonePermission,
            // A transcript is data. It goes through the identical CommandRuntime
            // path as typed text and gains no extra authority for having been
            // spoken, so voice is covered by the same policy gate as the keyboard.
            onTranscript = viewModel::submit,
        ).also { it.refreshAvailability() }
        voiceController = controller

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DotApp(
                        viewModel = viewModel,
                        notificationsGranted = notificationsGranted,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        aiAvailable = container.geminiProviderOrNull() != null,
                        voiceState = controller.state,
                        voiceController = controller,
                        requestMicrophonePermission = ::requestMicrophonePermission,
                        openAppSettings = ::openAppSettings,
                    )
                }
            }
        }
    }

    private fun requestMicrophonePermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * The retry path once the user has denied the mic permanently: the system
     * dialog no longer appears, so the only honest route is app settings.
     */
    private fun openAppSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        // Releases the platform recognizer; skipping this leaks it.
        voiceController?.release()
        super.onDestroy()
    }
}

@Composable
private fun DotApp(
    viewModel: DotViewModel,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    aiAvailable: Boolean,
    voiceState: StateFlow<VoiceSessionState>,
    voiceController: VoiceInputController?,
    requestMicrophonePermission: () -> Unit,
    openAppSettings: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chat by viewModel.chatLines.collectAsStateWithLifecycle()
    val voice by voiceState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, entry ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(entry.label.take(1)) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (Tab.entries[tab]) {
                Tab.TODAY -> TodayScreen(state, viewModel::toggleTask)
                Tab.CHAT -> ChatPanel(
                    lines = chat,
                    busy = state.busy,
                    pendingConfirm = state.pendingConfirm != null,
                    onSubmit = viewModel::submit,
                    onDismissConfirm = viewModel::dismissConfirmation,
                    voice = voice,
                    onVoiceTap = { voiceController?.onTap() },
                    // Lambdas, not ::references: Compose cannot yet take a callable
                    // reference to a @Composable parameter.
                    onVoiceRequestPermission = { requestMicrophonePermission() },
                    onVoiceOpenSettings = { openAppSettings() },
                )

                Tab.TASKS -> TasksScreen(state, viewModel::addTask, viewModel::toggleTask)
                Tab.NOTES -> NotesScreen(state)
                Tab.SETTINGS -> SettingsScreen(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onClearMemory = viewModel::clearLocalMemory,
                    onPurgeExpired = viewModel::purgeExpiredMemory,
                    onToggleConfirmations = viewModel::setConfirmationsEnabled,
                    onToggleAiFallback = viewModel::setAiFallbackEnabled,
                    aiAvailable = aiAvailable,
                )
            }
        }
    }
}
