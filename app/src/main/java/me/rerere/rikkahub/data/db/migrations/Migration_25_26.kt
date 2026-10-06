package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureWorkspaceColumns(db)
        ensureMediaCreationTables(db)
    }
}

internal fun ensureWorkspaceColumns(db: SupportSQLiteDatabase) {
    // Upstream and this fork shipped different workspace columns at version 25.
    val columns = db.query("PRAGMA table_info(`workspaces`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) {
                add(cursor.getString(nameIndex))
            }
        }
    }
    if ("tool_enabled" !in columns) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `tool_enabled` TEXT NOT NULL DEFAULT '{}'")
    }
    if ("system_prompt_enabled" !in columns) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `system_prompt_enabled` INTEGER NOT NULL DEFAULT 1")
    }
    if ("system_prompt" !in columns) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `system_prompt` TEXT NOT NULL DEFAULT ''")
    }
    if ("shell_compatibility_mode" !in columns) {
        db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `shell_compatibility_mode` INTEGER NOT NULL DEFAULT 0")
    }
}

internal fun ensureMediaCreationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_session` (
            `id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `draft` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_node` (
            `id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `selected_record_id` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_creation_record` (
            `id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `node_id` TEXT NOT NULL,
            `provider_id` TEXT NOT NULL,
            `provider_name` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `prompt` TEXT NOT NULL,
            `params` TEXT NOT NULL,
            `inputs` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `task_id` TEXT,
            `error` TEXT,
            `outputs` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)")
}
