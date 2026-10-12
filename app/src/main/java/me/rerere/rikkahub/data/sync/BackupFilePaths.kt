package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI
import java.util.UUID

/** Upstream does not relocate absolute file references when restoring another application's backup. */
internal class BackupFilePaths(
    sourceFilesDir: File,
    private val targetFilesDir: File,
    private val json: Json,
) {
    private val sourcePaths = listOfNotNull(
        sourceFilesDir.absolutePath,
        runCatching { sourceFilesDir.canonicalPath }.getOrNull(),
        sourceFilesDir.absolutePath.takeIf { it.startsWith("/data/user/0/") }
            ?.replaceFirst("/data/user/0/", "/data/data/"),
    ).map { it.trimEnd('/') + "/" }.distinct()

    fun relocate(value: String): String {
        val uri = if (value.startsWith("file:")) runCatching { URI(value) }.getOrNull() else null
        if (value.startsWith("file:") && (uri?.scheme != "file" || !uri.authority.isNullOrEmpty())) return value
        val path = if (uri != null) uri.path ?: return value else value
        val sourcePath = sourcePaths.firstOrNull { path.startsWith(it) } ?: return value
        val relative = path.removePrefix(sourcePath)
        if (relative.isBlank() || relative.startsWith('/') || relative.split('/').any { it == ".." || it == "." }) return value
        val target = File(targetFilesDir, archivePath(relative))
        return if (uri != null) URI("file", "", target.absolutePath, null).toASCIIString() else target.absolutePath
    }

    fun relocateJson(value: String, pathsOnly: Boolean = false): String {
        val original = json.parseToJsonElement(value)
        val relocated = relocateElement(original, pathsOnly)
        return if (relocated == original) value else json.encodeToString(relocated)
    }

    private fun relocateElement(element: JsonElement, isPath: Boolean = false): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            // Provider payloads and tool arguments are opaque, not application file references.
            if (key in OPAQUE_FIELDS) value else relocateElement(value, key in PATH_FIELDS)
        })
        is JsonArray -> JsonArray(element.map { relocateElement(it, isPath) })
        is JsonPrimitive -> if (isPath && element.isString) JsonPrimitive(relocate(element.content)) else element
    }

    companion object {
        const val UPSTREAM_PACKAGE = "me.rerere.rikkahub"
        val LEGACY_FOLDERS = setOf("images", "tool_outputs")
        private val PATH_FIELDS = setOf("url", "path", "background", "chatCustomFontPath", "posterPath", "lastFramePath")
        private val OPAQUE_FIELDS = setOf("metadata", "input", "arguments", "customBodies")

        fun archivePath(relative: String): String {
            if (relative.substringBefore('/') !in LEGACY_FOLDERS) return relative
            val id = UUID.nameUUIDFromBytes(relative.toByteArray(Charsets.UTF_8))
            val extension = File(relative).extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            return "upload/lune_$id$extension"
        }
    }
}
