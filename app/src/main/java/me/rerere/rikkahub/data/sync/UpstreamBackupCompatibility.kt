package me.rerere.rikkahub.data.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema 25 is accepted by upstream 2.5.6 and migrates normally in newer upstream builds. */
internal object UpstreamBackupCompatibility {
    const val DATABASE_VERSION = 25
    const val IDENTITY_HASH = "049f05fd92fc292b92c652743f056693"
    const val NATIVE_DATABASE = "lune/rikka_hub.db"
    const val NATIVE_SETTINGS = "lune/settings.json"
    const val NATIVE_FILES = "lune/files/"

    fun prepareDatabase(database: SupportSQLiteDatabase, paths: BackupFilePaths) {
        // Only the standalone snapshot is modified; the native copy retains all fork-specific data.
        database.beginTransaction()
        try {
            database.execSQL(
                """
                CREATE TABLE `upstream_workspaces` (
                    `id` TEXT NOT NULL, `name` TEXT NOT NULL, `root` TEXT NOT NULL,
                    `shell_status` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, `last_access_at` INTEGER,
                    `tool_approvals` TEXT NOT NULL DEFAULT '{}',
                    `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            val columns = "id, name, root, shell_status, created_at, updated_at, last_access_at, " +
                "tool_approvals, shell_compatibility_mode"
            database.execSQL("INSERT INTO upstream_workspaces ($columns) SELECT $columns FROM workspaces")
            database.execSQL("DROP TABLE workspaces")
            database.execSQL("ALTER TABLE upstream_workspaces RENAME TO workspaces")
            database.execSQL("CREATE UNIQUE INDEX index_workspaces_root ON workspaces (root)")
            database.execSQL("CREATE INDEX index_workspaces_updated_at ON workspaces (updated_at)")

            relocateColumn(database, "message_node", "messages") { paths.relocateJson(it) }
            relocateColumn(database, "favorites", "snapshot_json") { paths.relocateJson(it) }
            relocateColumn(database, "GenMediaEntity", "path", paths::relocate)
            relocateColumn(database, "GenMediaEntity", "source_paths") { paths.relocateJson(it, pathsOnly = true) }
            relocateColumn(database, "managed_files", "relative_path") { BackupFilePaths.archivePath(it) }
            database.execSQL("UPDATE managed_files SET folder = 'upload' WHERE folder IN ('images', 'tool_outputs')")
            // Extra media-creation tables are kept: upstream 2.5.6 ignores them, newer versions use them.
            database.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(IDENTITY_HASH))
            database.version = DATABASE_VERSION
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun relocateColumn(
        database: SupportSQLiteDatabase,
        table: String,
        column: String,
        transform: (String) -> String,
    ) {
        var lastRow = Long.MIN_VALUE
        while (true) {
            val rows = database.query(
                "SELECT rowid, `$column` FROM `$table` WHERE rowid > ? AND `$column` IS NOT NULL ORDER BY rowid LIMIT 128",
                arrayOf(lastRow),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
                }
            }
            if (rows.isEmpty()) break
            rows.forEach { (id, value) ->
                val relocated = transform(value)
                if (relocated != value) {
                    database.execSQL("UPDATE `$table` SET `$column` = ? WHERE rowid = ?", arrayOf<Any>(relocated, id))
                }
            }
            lastRow = rows.last().first
        }
    }
}
