package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.datastore.ThemeProfile
import me.rerere.rikkahub.data.datastore.ThemeStudioConfig
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.MessageInjectionTemplate
import me.rerere.rikkahub.data.model.withNewNodeIds
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                entries = stLorebook.entries.values.map { entry ->
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position),
                        injectDepth = entry.depth,
                        content = entry.content,
                        keywords = entry.key,
                        useRegex = false, // SillyTavern 格式不支持 useRegex
                        caseSensitive = entry.caseSensitive ?: false,
                        scanDepth = entry.scanDepth ?: 4,
                        constantActive = entry.constant,
                    )
                }
            )
        }.getOrNull()
    }

    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2 -> InjectionPosition.TOP_OF_CHAT
            3 -> InjectionPosition.TOP_OF_CHAT // After Examples -> 聊天历史开头
            4 -> InjectionPosition.AT_DEPTH    // @Depth 模式
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
}

object MessageTemplateSerializer : ExportSerializer<MessageInjectionTemplate> {
    override val type = "message_template"

    override fun getExportFileName(data: MessageInjectionTemplate): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: MessageInjectionTemplate): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<MessageInjectionTemplate> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportNative(json)
                ?: tryImportTemplateJson(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): MessageInjectionTemplate? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<MessageInjectionTemplate>(exportData.data)
                .withNewNodeIds()
        }.getOrNull()
    }

    internal fun tryImportTemplateJson(json: String): MessageInjectionTemplate? {
        return runCatching {
            val element = ExportSerializer.DefaultJson.parseToJsonElement(json)
            val obj = element as? JsonObject ?: return null
            // Avoid accepting arbitrary JSON objects as a default template.
            if (!obj.containsKey("template")) return null

            ExportSerializer.DefaultJson
                .decodeFromJsonElement<MessageInjectionTemplate>(obj)
                .withNewNodeIds()
        }.getOrNull()
    }
}

object ThemeStudioConfigSerializer : ExportSerializer<ThemeStudioConfig> {
    override val type = "theme_studio"

    override fun getExportFileName(data: ThemeStudioConfig): String {
        return "theme_studio.json"
    }

    override fun export(data: ThemeStudioConfig): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<ThemeStudioConfig> {
        return runCatching {
            val json = readUri(context, uri)
            tryImportThemeStudio(json)
                ?: tryImportThemeProfile(json)?.let { profile ->
                    ThemeStudioConfig(
                        activeProfileId = profile.id,
                        profiles = listOf(profile),
                    )
                }
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    internal fun tryImportThemeStudio(json: String): ThemeStudioConfig? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<ThemeStudioConfig>(exportData.data)
                .withNewProfileIds()
        }.getOrElse {
            runCatching {
                ExportSerializer.DefaultJson
                    .decodeFromString(ThemeStudioConfig.serializer(), json)
                    .withNewProfileIds()
            }.getOrNull()
        }
    }

    internal fun tryImportThemeProfile(json: String): ThemeProfile? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != ThemeProfileSerializer.type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<ThemeProfile>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrElse {
            runCatching {
                ExportSerializer.DefaultJson
                    .decodeFromString(ThemeProfile.serializer(), json)
                    .copy(id = Uuid.random())
            }.getOrNull()
        }
    }

    private fun ThemeStudioConfig.withNewProfileIds(): ThemeStudioConfig {
        val idMapping = profiles.associate { it.id to Uuid.random() }
        val remappedProfiles = profiles.map { profile ->
            profile.copy(id = idMapping.getValue(profile.id))
        }
        val remappedActive = activeProfileId?.let { idMapping[it] } ?: remappedProfiles.firstOrNull()?.id
        return copy(
            activeProfileId = remappedActive,
            profiles = remappedProfiles,
        )
    }
}

object ThemeProfileSerializer : ExportSerializer<ThemeProfile> {
    override val type = "theme_profile"

    override fun getExportFileName(data: ThemeProfile): String {
        return "${data.name.ifBlank { type }}.json"
    }

    override fun export(data: ThemeProfile): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<ThemeProfile> {
        return runCatching {
            val json = readUri(context, uri)
            ThemeStudioConfigSerializer.tryImportThemeProfile(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }
}

@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
)
