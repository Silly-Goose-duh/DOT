package com.dot.app

import android.content.Context
import androidx.room.Room
import com.dot.agent.agents.AgentRunner
import com.dot.agent.agents.AgentSchedulerPort
import com.dot.agent.agents.AgentTask
import com.dot.agent.agents.AndroidKeystoreCipher
import com.dot.agent.agents.BackoffPolicy
import com.dot.agent.agents.EncryptedPrefsOAuthTokenStore
import com.dot.agent.agents.InMemoryAgentRetryStateStore
import com.dot.agent.agents.InboxAgent
import com.dot.agent.agents.InboxProviderPort
import com.dot.agent.agents.InboxSummariser
import com.dot.agent.agents.InboxSummaryCache
import com.dot.agent.agents.LocalInboxSummariser
import com.dot.agent.agents.ModelInboxSummariser
import com.dot.agent.agents.OAuthTokenStore
import com.dot.agent.agents.WorkManagerAgentScheduler
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
import com.dot.app.diagnostics.DiagnosticsRecorder
import com.dot.app.reminder.ReminderScheduler
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.app.voice.AndroidMicPermission
import com.dot.app.voice.AndroidSpeechAvailability
import com.dot.app.voice.AndroidSpeechToText
import com.dot.app.voice.AndroidTextToSpeech
import com.dot.app.voice.GatedTextToSpeech
import com.dot.app.voice.MicPermissionPort
import com.dot.app.voice.SpeechAvailabilityPort
import com.dot.app.voice.SpeechToTextPort
import com.dot.app.voice.TextToSpeechPort
import com.dot.core.data.RoomAgentRepository
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomMemoryStore
import com.dot.core.data.RoomNoteRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DOT_MIGRATIONS
import com.dot.core.database.DotDatabase
import com.dot.core.model.AgentRepositoryPort
import java.time.ZoneId

/**
 * Hand-rolled composition root. v0.1 has no DI framework: one container, built
 * once by DotApplication, passed down explicitly. That keeps the wiring visible
 * and the whole graph constructible in a test.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    // Every migration is registered explicitly and there is NO destructiveMigration()
    // fallback: dropping the user's tasks, reminders and notes on a schema change is
    // a worse failure than refusing to open, so a missing migration must be loud.
    val database: DotDatabase = Room
        .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
        .addMigrations(*DOT_MIGRATIONS)
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
        // Null when no key is configured, which keeps the app honestly offline-only
        // rather than failing at command time. The key arrives via BuildConfig from
        // the gitignored local-secrets.properties, so it is never in source control.
        aiFallback = geminiProviderOrNull()?.let { provider ->
            com.dot.agent.llm.AiFallback(
                provider = provider,
                toolRegistry = toolRegistry,
                policyEngine = policyEngine,
            )
        },
    )

    /**
     * Constructs the provider only when a key is compiled into this build. Whether
     * it is *used* is a separate, user-owned decision made by
     * [refreshAiFallback]; having a key is not consent to send anything anywhere.
     */
    fun geminiProviderOrNull(): com.dot.agent.llm.ModelProvider? {
        if (com.dot.app.BuildConfig.GEMINI_API_KEY.isBlank()) return null
        return com.dot.agent.llm.gemini.GeminiProvider(
            apiKey = com.dot.app.BuildConfig.GEMINI_API_KEY,
            modelId = com.dot.app.BuildConfig.GEMINI_MODEL_ID,
        )
    }

    /**
     * Applies the user's persisted AI toggle to the runtime.
     *
     * This MUST be called once during startup, not only when the user toggles the
     * setting. Without it the runtime keeps its own default, so on a fresh install
     * the Settings switch could display "off" while the model was still being
     * reached — a silent egress of user text with no visible consent.
     */
    fun refreshAiFallback(enabled: Boolean) {
        commandRuntime.aiFallbackEnabled = enabled
    }

    // ---------------------------------------------------------------------
    // Milestone 4 — voice. The STT layer is behind ports, so the container only
    // decides which implementations to hand the UI; no speech types leak out.
    // ---------------------------------------------------------------------

    val speechToText: SpeechToTextPort = AndroidSpeechToText(appContext)
    val speechAvailability: SpeechAvailabilityPort = AndroidSpeechAvailability(appContext)
    val micPermission: MicPermissionPort = AndroidMicPermission(appContext)

    /**
     * TTS is optional and OFF unless the user turns it on, so the default gate
     * returns false. It can only speak a string it is handed — there is no API
     * that accepts a note body or a transcript.
     */
    val textToSpeech: TextToSpeechPort = GatedTextToSpeech(
        port = AndroidTextToSpeech(appContext),
        enabled = { voiceSpokenRepliesEnabled },
    )

    @Volatile
    var voiceSpokenRepliesEnabled: Boolean = false

    // ---------------------------------------------------------------------
    // Milestone 5 — background agents.
    // ---------------------------------------------------------------------

    val agentRepository: AgentRepositoryPort = RoomAgentRepository(database)

    private val agentTasks: List<AgentTask> by lazy {
        val provider = inboxProviderOrNull()
        if (provider == null) {
            // No inbox provider is configured in v0.1. An empty task list means a
            // scheduled run resolves to "nothing to do" rather than reporting a
            // fabricated empty inbox, and run-now says so honestly.
            emptyList()
        } else {
            val summariser: InboxSummariser = geminiProviderOrNull()
                ?.let { ModelInboxSummariser(provider = it) }
                // With no model key the summary is computed locally from counts and
                // sender domains only. That is a real result, not a degraded one.
                ?: LocalInboxSummariser()
            listOf(
                InboxAgent(
                    provider = provider,
                    summariser = summariser,
                    cache = InboxSummaryCache(memoryRepository),
                    tokenStore = oauthTokenStoreOrNull(),
                ),
            )
        }
    }

    /**
     * Credentials live in EncryptedSharedPreferences behind a Keystore cipher, and
     * only when the provider has actually been configured. Null means "not connected",
     * which surfaces as AUTH_REQUIRED rather than as an empty but successful fetch.
     */
    fun oauthTokenStoreOrNull(): OAuthTokenStore? = runCatching {
        val prefs = appContext.getSharedPreferences("dot_oauth", Context.MODE_PRIVATE)
        EncryptedPrefsOAuthTokenStore(
            prefs = prefs,
            cipher = AndroidKeystoreCipher(),
        )
    }.getOrNull()

    /**
     * Scheduler bound to WorkManager. A disabled agent has no scheduled work, and a
     * disabled agent that somehow runs anyway is refused by [AgentRunner], not skipped.
     */
    fun agentScheduler(workManager: androidx.work.WorkManager): AgentSchedulerPort =
        WorkManagerAgentScheduler(workManager)

    /** Exposed so the worker factory and any run-now flow share one graph. */
    fun agentRunner(): AgentRunner = AgentRunner(
        repository = agentRepository,
        tasks = agentTasks,
        policyEngine = policyEngine,
        // Jitter is injected so tests can pin it; in production it only spreads
        // retries so a fleet does not hammer a provider on the same second.
        backoffPolicy = BackoffPolicy(jitter = { Math.random() }),
        retryStateStore = InMemoryAgentRetryStateStore(),
        toolRegistryProvider = { toolRegistry },
    )

    fun inboxProviderOrNull(): InboxProviderPort? = null

    // ---------------------------------------------------------------------
    // Milestone 7 — diagnostics. Redacting recorder, bounded buffer.
    // ---------------------------------------------------------------------

    val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder()
}
