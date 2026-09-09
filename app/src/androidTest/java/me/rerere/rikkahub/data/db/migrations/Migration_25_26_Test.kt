package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_25_26_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate24To26_preservesDataAndAddsDefaults() {
        val name = "workspace-migration-24"
        helper.createDatabase(name, 24).use { db ->
            seedData(db)
        }

        helper.runMigrationsAndValidate(name, 26, true, Migration_25_26).use { db ->
            assertData(db, "{}", 1, "", 0)
        }
    }

    @Test
    fun migrateFork25To26_preservesToolAndPromptSettings() {
        val name = "workspace-migration-fork-25"
        val enabled = """{"workspace_shell":false,"workspace_python":true}"""
        helper.createDatabase(name, 25).use { db ->
            seedData(db)
            db.execSQL(
                "UPDATE workspaces SET tool_enabled = ?, system_prompt_enabled = 0, system_prompt = ?",
                arrayOf(enabled, "Custom workspace prompt"),
            )
        }

        helper.runMigrationsAndValidate(name, 26, true, Migration_25_26).use { db ->
            assertData(db, enabled, 0, "Custom workspace prompt", 0)
        }
    }

    @Test
    fun migrateUpstream25To26_preservesCompatibilityModeAndAddsForkDefaults() {
        val name = "workspace-migration-upstream-25"
        // Upstream 25 is schema 24 plus shell_compatibility_mode, without fork settings.
        helper.createDatabase(name, 24).use { db ->
            seedData(db)
            db.execSQL("ALTER TABLE workspaces ADD COLUMN shell_compatibility_mode INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE workspaces SET shell_compatibility_mode = 1")
            db.execSQL(
                "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                arrayOf("049f05fd92fc292b92c652743f056693"),
            )
            db.version = 25
        }

        helper.runMigrationsAndValidate(name, 26, true, Migration_25_26).use { db ->
            assertData(db, "{}", 1, "", 1)
        }
    }

    private fun seedData(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at, tool_approvals)
            VALUES ('workspace', 'My workspace', 'workspace-root', 'READY', 1, 2, ?)
            """.trimIndent(),
            arrayOf("""{"workspace_shell":false}"""),
        )
        db.execSQL(
            """
            INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at)
            VALUES ('conversation', 'Saved chat', '[]', 1, 2)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
            VALUES ('node', 'conversation', 0, ?, 0)
            """.trimIndent(),
            arrayOf("""[{"role":"USER","parts":[{"type":"text","text":"Keep this message"}]}]"""),
        )
    }

    private fun assertData(
        db: SupportSQLiteDatabase,
        toolEnabled: String,
        promptEnabled: Int,
        prompt: String,
        compatibilityMode: Int,
    ) {
        db.query("SELECT * FROM workspaces WHERE id = 'workspace'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("My workspace", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals("workspace-root", cursor.getString(cursor.getColumnIndexOrThrow("root")))
            assertEquals("READY", cursor.getString(cursor.getColumnIndexOrThrow("shell_status")))
            assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            assertEquals(
                """{"workspace_shell":false}""",
                cursor.getString(cursor.getColumnIndexOrThrow("tool_approvals")),
            )
            assertEquals(toolEnabled, cursor.getString(cursor.getColumnIndexOrThrow("tool_enabled")))
            assertEquals(promptEnabled, cursor.getInt(cursor.getColumnIndexOrThrow("system_prompt_enabled")))
            assertEquals(prompt, cursor.getString(cursor.getColumnIndexOrThrow("system_prompt")))
            assertEquals(compatibilityMode, cursor.getInt(cursor.getColumnIndexOrThrow("shell_compatibility_mode")))
        }
        db.query("SELECT title FROM ConversationEntity WHERE id = 'conversation'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Saved chat", cursor.getString(0))
        }
        db.query("SELECT messages FROM message_node WHERE id = 'node'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                """[{"role":"USER","parts":[{"type":"text","text":"Keep this message"}]}]""",
                cursor.getString(0),
            )
        }
    }
}
