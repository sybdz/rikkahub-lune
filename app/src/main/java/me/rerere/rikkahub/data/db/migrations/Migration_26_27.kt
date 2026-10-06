package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Reconciles databases created by either fork while both were publishing version 26.
 * The operation is intentionally idempotent so it also repairs partial upgrades.
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureWorkspaceColumns(db)
        ensureMediaCreationTables(db)
    }
}
