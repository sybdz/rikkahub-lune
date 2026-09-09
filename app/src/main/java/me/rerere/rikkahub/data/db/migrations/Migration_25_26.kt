package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
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
}
