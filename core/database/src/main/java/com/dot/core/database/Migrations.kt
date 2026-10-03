package com.dot.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history, in one file, so a reviewer can read the whole upgrade story
 * without hunting through the DAOs.
 *
 * v1 -> v2 adds two things, both additive and neither of which changes a single
 * existing row's meaning:
 *
 *  1. `notes.pinned`. The Notes list is ordered by `updatedAtEpochMs DESC`, which
 *     makes an important note sink below whatever was edited most recently. A pin
 *     flag is the minimum column that fixes that, and it is declared with
 *     `@ColumnInfo(defaultValue = "0")` so every existing note migrates to
 *     "not pinned" without a rewrite. Defaulting in the DDL (rather than in Kotlin
 *     after the fact) is what lets `ALTER TABLE ADD COLUMN` succeed on a table that
 *     already holds rows: SQLite refuses to add a NOT NULL column with no default.
 *
 *  2. `index_tasks_dueAtEpochMs`. `TaskDao.observeAll()` / `all()` order by
 *     `dueAtEpochMs` and the home screen collects that Flow, so the query re-runs
 *     on every write. On v1 SQLite full-scans the table and sorts it each time.
 *     This is the query PRD 24 requires to work offline on the Today screen, so it
 *     is the one worth indexing.
 *
 * What is deliberately NOT here: a `destructiveMigration()` fallback. The builder
 * must never be able to silently drop a user's tasks, notes or memory because a
 * migration was forgotten — a loud crash in QA beats silent data loss in the field.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Additive column. DEFAULT 0 must match @ColumnInfo(defaultValue = "0")
        // exactly, or Room's post-migration schema validation rejects the result.
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")

        // IF NOT EXISTS keeps the migration re-runnable after a partial failure.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tasks_dueAtEpochMs` " +
                "ON `tasks` (`dueAtEpochMs`)",
        )
    }
}

/** Every migration, newest last. Pass to `Room.databaseBuilder(...).addMigrations(...)`. */
val DOT_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2)
