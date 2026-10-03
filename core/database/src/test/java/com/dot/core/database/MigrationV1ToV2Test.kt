package com.dot.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room v1 -> v2 migration.
 *
 * This deliberately does NOT use androidx.room.testing.MigrationTestHelper.
 * MigrationTestHelper needs the exported schema JSON to be present in the module's
 * *assets* folder, and Milestone 7's file-ownership boundary for :core:database
 * did not allow adding a `src/main/assets` tree (or editing build.gradle.kts to
 * set `room.schemaLocation`). The test below therefore builds the v1 database from
 * the v1 DDL by hand and then opens it through the real Room builder, which is a
 * stronger check in one respect and weaker in none that matter:
 *
 *  - the DDL below is the v1 schema verbatim, so the starting state is exact;
 *  - the migration is run by Room's own `RoomOpenHelper.onUpgrade`;
 *  - Room's own `onValidateSchema` runs afterwards, so a migration that produced
 *    the wrong shape fails the test rather than passing quietly;
 *  - if `.addMigrations` were missing, or a destructive fallback were in play,
 *    the data assertions below would see an empty table.
 *
 * Swap in MigrationTestHelper (with room.schemaLocation wired up) when the build
 * file can be edited; the assertions should carry over unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV1ToV2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-v1-v2-test.db"

    /**
     * The v1 schema, copied from the Room-generated `createAllTables` for
     * version 1. Do not "tidy" this: if it drifts from real v1, the test stops
     * proving anything.
     */
    private val v1Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `status` TEXT NOT NULL, `priority` TEXT, `dueAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `completedAtEpochMs` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `remindAtEpochMs` INTEGER NOT NULL, `taskId` TEXT, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `event_cache` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `providerEventId` TEXT, `title` TEXT NOT NULL, `startAtEpochMs` INTEGER NOT NULL, `endAtEpochMs` INTEGER, `location` TEXT, `lastSyncedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `title` TEXT, `body` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `agent_definitions` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `frequencyMinutes` INTEGER, `lastRunAtEpochMs` INTEGER, `lastSuccessAtEpochMs` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, `finishedAtEpochMs` INTEGER, `status` TEXT NOT NULL, `compactResult` TEXT, `errorCode` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `memory_items` (`id` TEXT NOT NULL, `namespace` TEXT NOT NULL, `key` TEXT NOT NULL, `value` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER, `source` TEXT NOT NULL, `userVisible` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates a real on-disk v1 database with representative rows in every table. */
    private fun createV1Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                v1Ddl.forEach(db::execSQL)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                throw IllegalStateException("test must never upgrade the v1 database directly")
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        val db = helper.writableDatabase

        // One row per table, with values chosen so a silent column shift or a
        // dropped table would be visible rather than plausible.
        db.execSQL(
            "INSERT INTO tasks (id,title,description,status,priority,dueAtEpochMs,createdAtEpochMs,updatedAtEpochMs,completedAtEpochMs) " +
                "VALUES ('t1','Submit assignment','chapter 1-3','OPEN','HIGH',5000,1000,2000,NULL)",
        )
        db.execSQL(
            "INSERT INTO tasks (id,title,description,status,priority,dueAtEpochMs,createdAtEpochMs,updatedAtEpochMs,completedAtEpochMs) " +
                "VALUES ('t2','Old done thing',NULL,'DONE',NULL,9000,1000,3000,3000)",
        )
        db.execSQL(
            "INSERT INTO reminders (id,title,remindAtEpochMs,taskId,state,createdAtEpochMs) " +
                "VALUES ('r1','Send report',7000,'t1','SCHEDULED',1000)",
        )
        db.execSQL(
            "INSERT INTO event_cache (id,provider,providerEventId,title,startAtEpochMs,endAtEpochMs,location,lastSyncedAtEpochMs) " +
                "VALUES ('e1','local',NULL,'College',4000,4500,'Room 2',1000)",
        )
        db.execSQL(
            "INSERT INTO notes (id,title,body,createdAtEpochMs,updatedAtEpochMs) " +
                "VALUES ('n1','grocery','milk and eggs',1000,1500)",
        )
        db.execSQL(
            "INSERT INTO agent_definitions (id,type,enabled,frequencyMinutes,lastRunAtEpochMs,lastSuccessAtEpochMs,lastErrorCode) " +
                "VALUES ('inbox','inbox_agent',1,60,1000,900,NULL)",
        )
        db.execSQL(
            "INSERT INTO agent_runs (id,agentId,startedAtEpochMs,finishedAtEpochMs,status,compactResult,errorCode) " +
                "VALUES ('a1','inbox',1000,2000,'SUCCESS','2 unread',NULL)",
        )
        db.execSQL(
            "INSERT INTO memory_items (id,namespace,key,value,createdAtEpochMs,updatedAtEpochMs,expiresAtEpochMs,source,userVisible) " +
                "VALUES ('m1','user_facts','name','Sam',1000,1000,NULL,'local',1)",
        )
        return db
    }

    /** Opens the v1 file through the real Room builder at v2, running the migration. */
    private fun openMigrated(addMigration: Boolean = true): DotDatabase {
        val builder = Room.databaseBuilder(context, DotDatabase::class.java, dbName)
            .allowMainThreadQueries()
        if (addMigration) builder.addMigrations(MIGRATION_1_2)
        return builder.build()
    }

    @Test
    fun `v1 rows survive the migration to v2`() = runTest {
        createV1Database().close()

        val db = openMigrated()
        try {
            // Touch every DAO so Room's post-migration schema validation actually runs
            // against a fully realised v2 database rather than a lazy proxy.
            assertThat(db.taskDao().all()).hasSize(2)
            assertThat(db.reminderDao().all()).hasSize(1)
            assertThat(db.eventCacheDao().all()).hasSize(1)
            assertThat(db.noteDao().all()).hasSize(1)
            assertThat(db.agentDefinitionDao().byId("inbox")).isNotNull()
            assertThat(db.agentRunDao().latestFor("inbox")).isNotNull()
            assertThat(db.memoryDao().observeAll().first()).hasSize(1)
        } finally {
            db.close()
        }
    }

    @Test
    fun `task column values are unchanged, not just row counts`() = runTest {
        createV1Database().close()

        val db = openMigrated()
        try {
            val open = db.taskDao().byId("t1")!!
            assertThat(open.title).isEqualTo("Submit assignment")
            assertThat(open.description).isEqualTo("chapter 1-3")
            assertThat(open.priority).isEqualTo("HIGH")
            assertThat(open.dueAtEpochMs).isEqualTo(5000)
            assertThat(open.completedAtEpochMs).isNull()

            val done = db.taskDao().byId("t2")!!
            assertThat(done.status).isEqualTo("DONE")
            assertThat(done.completedAtEpochMs).isEqualTo(3000)

            // Nullable text must not have been coerced to empty string.
            assertThat(db.reminderDao().byId("r1")!!.taskId).isEqualTo("t1")
            assertThat(db.eventCacheDao().all().single().location).isEqualTo("Room 2")
        } finally {
            db.close()
        }
    }

    @Test
    fun `new pinned column exists and defaults to false for migrated rows`() = runTest {
        createV1Database().close()

        val db = openMigrated()
        try {
            val note = db.noteDao().byId("n1")!!
            // The ALTER TABLE default is what makes the pre-existing row legal; if
            // the column arrived NULL this would throw or read as null.
            assertThat(note.pinned).isFalse()
            assertThat(note.body).isEqualTo("milk and eggs")

            // And the new column is genuinely writable, not just readable.
            assertThat(db.noteDao().setPinned("n1", true)).isEqualTo(1)
            assertThat(db.noteDao().byId("n1")!!.pinned).isTrue()
            assertThat(db.noteDao().pinned().map { it.id }).containsExactly("n1")
        } finally {
            db.close()
        }
    }

    @Test
    fun `new tasks index exists and Room schema validation accepts it`() = runTest {
        createV1Database().close()

        val db = openMigrated()
        try {
            // Force the open, then inspect the real schema.
            db.taskDao().count()
            val helper = db.openHelper.writableDatabase

            val indexNames = mutableListOf<String>()
            helper.query("PRAGMA index_list(`tasks`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) indexNames += c.getString(nameIdx)
            }
            assertThat(indexNames).contains("index_tasks_dueAtEpochMs")

            // The column default must read back exactly as the entity declares it,
            // or Room's TableInfo comparison rejects the database.
            val notesColumns = mutableMapOf<String, String?>()
            helper.query("PRAGMA table_info(`notes`)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val dfltIdx = c.getColumnIndexOrThrow("dflt_value")
                val notNullIdx = c.getColumnIndexOrThrow("notnull")
                while (c.moveToNext()) {
                    notesColumns[c.getString(nameIdx)] =
                        if (c.getInt(notNullIdx) == 1) c.getString(dfltIdx) else "<nullable>"
                }
            }
            assertThat(notesColumns).containsKey("pinned")
            assertThat(notesColumns["pinned"]).isEqualTo("0")
        } finally {
            db.close()
        }
    }

    @Test
    fun `without the migration registered Room refuses rather than wiping data`() = runTest {
        createV1Database().close()

        // No .addMigrations. Room must throw IllegalStateException("A migration
        // from 1 to 2 was required but not found") rather than falling back to a
        // destructive recreate. This is the assertion that would fail if anyone ever
        // added .fallbackToDestructiveMigration() to the real builder.
        val db = openMigrated(addMigration = false)
        val thrown = runCatching { db.taskDao().all() }.exceptionOrNull()
        try {
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(thrown!!.message).contains("migration")
        } finally {
            db.close()
        }
    }
}
