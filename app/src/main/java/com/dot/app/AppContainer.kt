package com.dot.app

import android.content.Context
import androidx.room.Room
import com.dot.agent.memory.MemoryPolicy
import com.dot.agent.memory.MemoryRepository
import com.dot.agent.policy.PolicyEngine
import com.dot.agent.router.DateTimeParser
import com.dot.agent.router.DeterministicRouter
import com.dot.agent.router.DirectResponseFormatter
import com.dot.agent.tools.CompleteTaskTool
import com.dot.agent.tools.CreateNoteTool
import com.dot.agent.tools.CreateReminderTool
import com.dot.agent.tools.CreateTaskTool
import com.dot.agent.tools.GetTodayTool
import com.dot.agent.tools.OpenAppTool
import com.dot.agent.tools.SearchLocalTool
import com.dot.agent.tools.ToolRegistry
import com.dot.app.command.CommandRuntime
import com.dot.app.data.SettingsStore
import com.dot.app.reminder.ReminderScheduler
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomMemoryStore
import com.dot.core.data.RoomNoteRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DotDatabase
import java.time.ZoneId

/**
 * Hand-rolled composition root. v0.1 has no DI framework: one container, built
 * once by DotApplication, passed down explicitly. That keeps the wiring visible
 * and the whole graph constructible in a test.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: DotDatabase = Room
        .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
        .build()

    val zoneId: ZoneId = ZoneId.systemDefault()

    val taskRepo = RoomTaskRepository(database)
    val reminderRepo = RoomReminderRepository(database)
    val eventRepo = RoomEventRepository(database)
    val noteRepo = RoomNoteRepository(database)
    val memoryStore = RoomMemoryStore(database)

    val memoryRepository = MemoryRepository(memoryStore, MemoryPolicy())

    val settings = SettingsStore(appContext)

    val dateTimeParser = DateTimeParser(zoneId)
    val router = DeterministicRouter(dateTimeParser, zoneId)
    val formatter = DirectResponseFormatter(zoneId)
    val policyEngine = PolicyEngine()

    val packageVisibility = PackageVisibility(appContext)
    val appLauncher = AppLauncher(appContext, packageVisibility)
    val reminderScheduler = ReminderScheduler(appContext, zoneId)

    val toolRegistry = ToolRegistry(
        listOf(
            CreateTaskTool(taskRepo, zoneId),
            CompleteTaskTool(taskRepo),
            CreateReminderTool(reminderRepo),
            CreateNoteTool(noteRepo),
            SearchLocalTool(taskRepo, noteRepo),
            GetTodayTool(taskRepo, reminderRepo, eventRepo, zoneId),
            OpenAppTool(),
        ),
    )

    /**
     * PackageManager-backed capability flags. The model never supplies these;
     * PolicyEngine and ToolRegistry read them.
     */
    fun bindCapabilityFlags(hasPermission: Boolean, isAuthenticated: Boolean) {
        toolRegistry.permissionGranted = { hasPermission }
        toolRegistry.authenticated = { isAuthenticated }
    }

    val commandRuntime = CommandRuntime(
        router = router,
        toolRegistry = toolRegistry,
        policyEngine = policyEngine,
        formatter = formatter,
        appLauncher = appLauncher,
        reminderScheduler = reminderScheduler,
    )
}
