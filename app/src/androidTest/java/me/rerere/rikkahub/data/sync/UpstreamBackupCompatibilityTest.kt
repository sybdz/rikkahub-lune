package me.rerere.rikkahub.data.sync

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class UpstreamBackupCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun snapshotMatchesUpstreamSchemaAndPreservesConversationBranchesAndMemories() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "upstream-backup-test-").toFile()
        val snapshot = File(directory, "upstream")
        val paths = BackupFilePaths(context.filesDir, File(directory, "upstream-files"), JsonInstant)
        try {
            val room = AppDatabaseFactory.create(context, File(directory, "live").absolutePath)
            try {
                val db = room.openHelper.writableDatabase
                db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('conversation', 'title', '[]', 1, 2)")
                val messages = """[{"parts":[{"type":"image","url":"file://${context.filesDir}/upload/photo.png"}]},{"parts":[{"type":"text","text":"alternative"}]}]"""
                db.execSQL("INSERT INTO message_node VALUES ('node', 'conversation', 0, ?, 1)", arrayOf(messages))
                db.execSQL("INSERT INTO MemoryEntity (assistant_id, content) VALUES ('assistant', 'remember me')")
                db.execSQL(
                    "INSERT INTO managed_files (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                        "VALUES ('images', 'images/legacy.png', 'legacy.png', 'image/png', 10, 1, 2)"
                )
                db.execSQL(
                    "INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, tool_enabled, system_prompt) " +
                        "VALUES ('workspace', 'workspace', '/workspace', 'ready', 1, 2, '{\"shell\":false}', 'custom prompt')"
                )
                DatabaseBackup.createSnapshot(db, snapshot)
                DatabaseBackup.prepareForUpstream(context, snapshot, paths)
                assertEquals(27, db.version)
                assertEquals("custom prompt", scalar(db, "SELECT system_prompt FROM workspaces"))
            } finally {
                room.close()
            }
            assertFalse(File(snapshot.path + "-wal").exists())
            assertFalse(File(snapshot.path + "-shm").exists())
            open(snapshot).use { exported ->
                val schema = JsonInstant.parseToJsonElement(
                    instrumentation.context.assets.open("upstream-backup/schema-25.json").bufferedReader().use { it.readText() }
                ).jsonObject.getValue("database").jsonObject
                assertEquals(schema.getValue("version").jsonPrimitive.content.toInt(), exported.version)
                assertEquals(schema.getValue("identityHash").jsonPrimitive.content, scalar(exported, "SELECT identity_hash FROM room_master_table WHERE id = 42"))
                open(File(directory, "reference")).use { reference ->
                    schema.getValue("entities").jsonArray.forEach { element ->
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        reference.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        entity.getValue("indices").jsonArray.forEach { index ->
                            reference.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                        }
                        assertEquals(table, pragma(reference, "table_info(`$table`)"), pragma(exported, "table_info(`$table`)"))
                        assertEquals(table, pragma(reference, "foreign_key_list(`$table`)"), pragma(exported, "foreign_key_list(`$table`)"))
                        val expectedIndices = indexNames(reference, table)
                        assertEquals(table, expectedIndices, indexNames(exported, table))
                        expectedIndices.forEach { index ->
                            assertEquals(index, pragma(reference, "index_xinfo(`$index`)"), pragma(exported, "index_xinfo(`$index`)"))
                        }
                    }
                }
                assertEquals("remember me", scalar(exported, "SELECT content FROM MemoryEntity"))
                assertEquals("upload", scalar(exported, "SELECT folder FROM managed_files"))
                assertEquals(BackupFilePaths.archivePath("images/legacy.png"), scalar(exported, "SELECT relative_path FROM managed_files"))
                assertEquals("1", scalar(exported, "SELECT select_index FROM message_node"))
                val messages = JsonInstant.parseToJsonElement(scalar(exported, "SELECT messages FROM message_node")).jsonArray
                assertEquals(2, messages.size)
                assertEquals(
                    paths.relocate("file://${context.filesDir}/upload/photo.png"),
                    messages.first().jsonObject.getValue("parts").jsonArray.first().jsonObject.getValue("url").jsonPrimitive.content,
                )
                assertEquals("ok", scalar(exported, "PRAGMA integrity_check"))
                assertTrue(pragma(exported, "foreign_key_check").isEmpty())
            }
            // The standard upstream entry is also importable by this app without the native extension.
            val restored = AppDatabaseFactory.create(context, snapshot.absolutePath)
            try {
                assertEquals("remember me", scalar(restored.openHelper.writableDatabase, "SELECT content FROM MemoryEntity"))
                assertEquals(27, restored.openHelper.writableDatabase.version)
            } finally {
                restored.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun invalidSerializedDataRollsBackTheExportedSchema() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "upstream-backup-rollback-").toFile()
        val snapshot = File(directory, "snapshot")
        val paths = BackupFilePaths(context.filesDir, File(directory, "upstream-files"), JsonInstant)
        try {
            val room = AppDatabaseFactory.create(context, File(directory, "live").absolutePath)
            try {
                room.openHelper.writableDatabase.execSQL(
                    "INSERT INTO favorites (id, type, ref_key, ref_json, snapshot_json, created_at, updated_at) " +
                        "VALUES ('favorite', 'node', 'reference', '{}', 'invalid JSON', 1, 2)"
                )
                DatabaseBackup.createSnapshot(room.openHelper.writableDatabase, snapshot)
            } finally {
                room.close()
            }
            var failed = false
            try {
                DatabaseBackup.prepareForUpstream(context, snapshot, paths)
            } catch (_: kotlinx.serialization.SerializationException) {
                failed = true
            }
            assertTrue(failed)
            open(snapshot).use { db ->
                assertEquals(27, db.version)
                assertTrue(pragma(db, "table_info(workspaces)").any { it[1] == "system_prompt" })
                assertEquals("ok", scalar(db, "PRAGMA integrity_check"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        SQLiteConfiguration.configure(context, SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY)),
        null,
        null,
    )

    private fun scalar(db: SupportSQLiteDatabase, query: String): String = db.query(query).use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private fun pragma(db: SupportSQLiteDatabase, name: String): List<List<String?>> = db.query("PRAGMA $name").use { it.rows() }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> = db.query("PRAGMA index_list(`$table`)").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(1))
        }.sorted()
    }

    private fun Cursor.rows(): List<List<String?>> = buildList {
        while (moveToNext()) add((0 until columnCount).map { if (isNull(it)) null else getString(it) })
    }
}
