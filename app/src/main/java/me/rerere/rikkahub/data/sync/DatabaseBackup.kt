package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    fun prepareForUpstream(context: Context, databaseFile: File, paths: BackupFilePaths) {
        open(context, databaseFile).use { database ->
            UpstreamBackupCompatibility.prepareDatabase(database, paths)
            checkpoint(database)
        }
        removeSidecars(databaseFile)
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        open(context, databaseFile).use { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    private fun open(context: Context, databaseFile: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE),
        ),
        null,
    ) { error("Backup database is corrupt") }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}
