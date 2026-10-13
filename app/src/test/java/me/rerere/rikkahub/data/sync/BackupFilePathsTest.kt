package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupFilePathsTest {
    private val source = "/data/user/0/me.rerere.rikkahub.ywxk/files"
    private val target = "/data/user/0/me.rerere.rikkahub/files"
    private val json = Json
    private val paths = BackupFilePaths(File(source), File(target), json)

    @Test fun absolutePathsAndEncodedFileUrisAreRelocated() {
        assertEquals("$target/fonts/font.ttf", paths.relocate("$source/fonts/font.ttf"))
        assertEquals("file://$target/upload/my%20image.png", paths.relocate("file://$source/upload/my%20image.png"))
        assertEquals("file://$target/upload/photo.png", paths.relocate("file:$source/upload/photo.png"))
        assertEquals("file://$target/upload/photo.png", paths.relocate("file:///data/data/me.rerere.rikkahub.ywxk/files/upload/photo.png"))
    }

    @Test fun unrelatedLocationsAndTraversalAreNotRewritten() {
        listOf(
            "https://example.com/image.png",
            "data:image/png;base64,abc",
            "content://media/external/1",
            "upload/relative.png",
            "${source}_other/upload/image.png",
            "$source/../private.txt",
            "$source/upload/./image.png",
            "file://server$source/upload/image.png",
        ).forEach { assertEquals(it, paths.relocate(it)) }
    }

    @Test fun legacyFilesMapToDistinctStableTopLevelUploadEntries() {
        val image = BackupFilePaths.archivePath("images/photo.png")
        val tool = BackupFilePaths.archivePath("tool_outputs/nested/photo.png")
        assertTrue(image.startsWith("upload/lune_"))
        assertTrue(image.endsWith(".png"))
        assertFalse(image == tool)
        assertEquals(image, BackupFilePaths.archivePath("images/photo.png"))
        assertEquals("$target/$tool", paths.relocate("$source/tool_outputs/nested/photo.png"))
        assertEquals("media_creation/session/result.png", BackupFilePaths.archivePath("media_creation/session/result.png"))
    }

    @Test fun structuredReferencesChangeButPromptsAndProviderPayloadsDoNot() {
        val original = """
            {
              "background": "file://$source/upload/background.jpg",
              "chatCustomFontPath": "$source/fonts/font.ttf",
              "systemPrompt": "$source/upload/prompt.txt",
              "parts": [{"url": "file://$source/upload/image.png", "text": "$source/upload/image.png"}],
              "metadata": {"url": "file://$source/upload/provider.png"},
              "input": {"path": "$source/upload/tool.txt"},
              "customBodies": [{"value": {"path": "$source/upload/body.txt"}}],
              "enabled": true,
              "optional": null
            }
        """.trimIndent()
        val result = json.parseToJsonElement(paths.relocateJson(original)).jsonObject
        assertEquals("file://$target/upload/background.jpg", result.getValue("background").jsonPrimitive.content)
        assertEquals("$target/fonts/font.ttf", result.getValue("chatCustomFontPath").jsonPrimitive.content)
        assertEquals("$source/upload/prompt.txt", result.getValue("systemPrompt").jsonPrimitive.content)
        val part = result.getValue("parts").jsonArray.single().jsonObject
        assertEquals("file://$target/upload/image.png", part.getValue("url").jsonPrimitive.content)
        assertEquals("$source/upload/image.png", part.getValue("text").jsonPrimitive.content)
        val sourceJson = json.parseToJsonElement(original).jsonObject
        listOf("metadata", "input", "customBodies", "enabled", "optional").forEach {
            assertEquals(sourceJson[it], result[it])
        }
    }

    @Test fun imageEditSourcePathsKeepTheirNewlineSeparatedFormat() {
        val value = "$source/upload/a.png\nfile://$source/upload/b.png\nhttps://example.com/c.png"
        assertEquals(
            "$target/upload/a.png\nfile://$target/upload/b.png\nhttps://example.com/c.png",
            paths.relocatePathList(value),
        )
    }

    @Test fun singleSourcePathAndEmptyEntriesDoNotRequireJson() {
        assertEquals("$target/upload/a.png", paths.relocatePathList("$source/upload/a.png"))
        assertEquals("", paths.relocatePathList(""))
        assertEquals("\n$target/upload/a.png\n", paths.relocatePathList("\n$source/upload/a.png\n"))
    }
}
