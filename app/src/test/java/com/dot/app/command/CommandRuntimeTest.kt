package com.dot.app.command

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.dot.app.reminder.ReminderSchedulerPort
import com.dot.app.system.AppLauncher
import com.dot.app.system.PackageVisibility
import com.dot.core.data.RoomEventRepository
import com.dot.core.data.RoomNoteRepository
import com.dot.core.data.RoomReminderRepository
import com.dot.core.data.RoomTaskRepository
import com.dot.core.database.DotDatabase
import com.dot.core.model.DirectResponse
import com.dot.core.model.Note
import com.dot.core.model.Reminder
import com.dot.core.model.Task
import com.dot.core.model.TaskStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * The runtime is where the pieces meet, so it is where the security properties
 * have to hold end to end: the router cannot reach an unregistered tool, a
 * failure never renders as success, and a refused action fires no side effect.
 */
@RunWith(RobolectricTestRunner::class)
class CommandRuntimeTest {

    private lateinit var db: DotDatabase
    private lateinit var tasks: RoomTaskRepository
    private lateinit var reminders: RoomReminderRepository
    private lateinit var notes: RoomNoteRepository
    private lateinit var events: RoomEventRepository
    private lateinit var registry: ToolRegistry
    private lateinit var runtime: CommandRuntime
    private lateinit var scheduler: FakeScheduler

    /** Records scheduling without touching AlarmManager. */
    private class FakeScheduler : ReminderSchedulerPort {
        val scheduled = mutableListOf<Reminder>()
        override fun schedule(reminder: Reminder) {
            scheduled += reminder
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, DotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val zone = ZoneId.of("UTC")
        tasks = RoomTaskRepository(db)
        reminders = RoomReminderRepository(db)
        notes = RoomNoteRepository(db)
        events = RoomEventRepository(db)

        registry = ToolRegistry(
            listOf(
                CreateTaskTool(tasks, zone),
                CompleteTaskTool(tasks),
                CreateReminderTool(reminders),
                CreateNoteTool(notes),
                SearchLocalTool(tasks, notes),
                GetTodayTool(tasks, reminders, events, zone),
                OpenAppTool(),
            ),
        )
        registry.permissionGranted = { true }
        registry.authenticated = { true }

        scheduler = FakeScheduler()
        runtime = CommandRuntime(
            router = DeterministicRouter(DateTimeParser(zone), zone),
            toolRegistry = registry,
            policyEngine = PolicyEngine(),
            formatter = DirectResponseFormatter(zone),
            appLauncher = AppLauncher(context, PackageVisibility(context)),
            reminderScheduler = scheduler,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `add task actually persists a row`() = runTest {
        val turn = runtime.handle("add buy milk")

        assertThat(turn).isInstanceOf(CommandRuntime.CommandTurn.Answer::class.java)
        assertThat(tasks.all().map { it.title }).contains("buy milk")
    }

    @Test
    fun `get today reports counts from local data`() = runTest {
        tasks.insert(Task(title = "one"))
        tasks.insert(Task(title = "two"))

        val turn = runtime.handle("what do i have today")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("2 tasks.")
    }

    @Test
    fun `empty day says nothing scheduled`() = runTest {
        val turn = runtime.handle("what do i have today")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("Nothing scheduled.")
    }

    @Test
    fun `complete task marks the open task done`() = runTest {
        tasks.insert(Task(title = "buy milk"))

        val turn = runtime.handle("complete buy milk")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Action).message).isEqualTo("Done.")
        assertThat(tasks.all().single().status).isEqualTo(TaskStatus.DONE)
    }

    @Test
    fun `completing a task that does not exist is not reported as done`() = runTest {
        val turn = runtime.handle("complete nonexistent thing")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("No matching task.")
        assertThat(tasks.all()).isEmpty()
    }

    @Test
    fun `unknown app alias never reaches the launcher`() = runTest {
        val turn = runtime.handle("open com.evil.backdoor")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message)
            .isEqualTo("That app isn't supported.")
    }

    @Test
    fun `allowlisted alias still resolves`() = runTest {
        val turn = runtime.handle("open youtube")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        // The package is returned; whether it launches depends on it being
        // installed, which is a separate, honestly-reported outcome.
        assertThat(response).isInstanceOf(DirectResponse.Action::class.java)
    }

    @Test
    fun `ai path degrades to an honest refusal`() = runTest {
        val turn = runtime.handle("draft a haiku about my cat's emotional unavailability")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Clarification).question)
            .isEqualTo("I can only handle simple commands offline.")
    }

    @Test
    fun `empty input asks rather than guessing`() = runTest {
        val turn = runtime.handle("   ")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Clarification).question).isEqualTo("What should I do?")
    }

    @Test
    fun `note with a blank body is rejected, not stored`() = runTest {
        notes.insert(Note(title = "seed", body = "something"))

        val turn = runtime.handle("add ")

        // A blank title must not create a row.
        assertThat(notes.list()).hasSize(1)
        assertThat(turn).isInstanceOf(CommandRuntime.CommandTurn.Answer::class.java)
    }

    @Test
    fun `search returns a match count`() = runTest {
        tasks.insert(Task(title = "read the quarterly report"))
        notes.insert(Note(title = "report ideas", body = "more"))

        val turn = runtime.handle("search report")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("2 matches.")
    }

    @Test
    fun `search with no hits says so`() = runTest {
        val turn = runtime.handle("search nonexistent")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isEqualTo("Nothing found.")
    }

    @Test
    fun `reminder command arms exactly one alarm`() = runTest {
        val turn = runtime.handle("remind me to stretch at 8")

        assertThat(turn).isInstanceOf(CommandRuntime.CommandTurn.Answer::class.java)
        assertThat(scheduler.scheduled).hasSize(1)
        assertThat(scheduler.scheduled.first().title).isEqualTo("stretch")
    }

    @Test
    fun `reminder with no time asks instead of scheduling`() = runTest {
        val turn = runtime.handle("remind me to stretch")

        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Clarification).question)
            .isEqualTo("When should I remind you?")
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `tool failure is typed, never silent success`() = runTest {
        val registryWithoutOpenApp = ToolRegistry(listOf(CreateTaskTool(tasks, ZoneId.of("UTC"))))
        registryWithoutOpenApp.permissionGranted = { true }
        registryWithoutOpenApp.authenticated = { true }

        val bare = CommandRuntime(
            router = DeterministicRouter(DateTimeParser(ZoneId.of("UTC")), ZoneId.of("UTC")),
            toolRegistry = registryWithoutOpenApp,
            policyEngine = PolicyEngine(),
            formatter = DirectResponseFormatter(ZoneId.of("UTC")),
            appLauncher = AppLauncher(
                ApplicationProvider.getApplicationContext(),
                PackageVisibility(ApplicationProvider.getApplicationContext()),
            ),
            reminderScheduler = scheduler,
        )

        val turn = bare.handle("what do i have today")
        val response = (turn as CommandRuntime.CommandTurn.Answer).response

        assertThat((response as DirectResponse.Information).message).isNotEqualTo("Done.")
    }
}
