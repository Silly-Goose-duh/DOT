package com.dot.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationsGranted = (application as DotApplication).hasNotificationPermission()
        ReminderScheduler.ensureChannel(this)

        val container = (application as DotApplication).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DotApp(
                        viewModel = viewModel(factory = DotViewModel.Factory(container)),
                        notificationsGranted = notificationsGranted,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun DotApp(
    viewModel: DotViewModel,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val chat by viewModel.chatLines.collectAsStateWithLifecycle()

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
                )
            }
        }
    }
}
