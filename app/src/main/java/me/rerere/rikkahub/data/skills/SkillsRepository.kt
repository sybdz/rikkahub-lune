package me.rerere.rikkahub.data.skills

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.termux.TermuxCommandManager
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.ai.tools.termux.isSuccessful
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private const val TAG = "SkillsRepository"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
private const val SKILL_COMMAND_TIMEOUT_MS = 30_000L
private const val SKILL_WRITE_TIMEOUT_MS = 60_000L
private const val SKILL_SINGLE_WRITE_BYTES_LIMIT = 192 * 1024
private const val SKILL_CHUNK_WRITE_BYTES = 48 * 1024
private const val SKILL_CATALOG_REFRESH_TTL_MS = 60_000L
private const val DEFAULT_IMPORTED_SKILL_DIRECTORY = "skill-import"
private const val DEFAULT_CREATED_SKILL_DIRECTORY = "new-skill"
private const val SKILL_PACKAGE_FILE_NAME = "SKILL.md"
private const val SKILL_SOURCE_METADATA_FILE_NAME = ".rikkahub-skill-source.json"
private const val BUNDLED_SKILLS_ASSET_ROOT = "builtin_skills"
private const val SKILL_LIST_PREVIEW_BYTES_LIMIT = 8 * 1024
private const val SKILL_ARCHIVE_MAX_FILES = 256
private const val SKILL_ARCHIVE_MAX_TOTAL_BYTES = 4 * 1024 * 1024
private const val SKILL_ARCHIVE_SINGLE_FILE_BYTES_LIMIT = 512 * 1024
private const val SKILL_ARCHIVE_MAX_DEPTH = 8
private const val SKILL_RESOURCE_LIST_LIMIT = 128
private const val SKILL_IMPORT_PREVIEW_SCRIPT_LIST_LIMIT = 8
private const val SKILL_SOURCE_METADATA_SCHEMA_VERSION = 1
internal const val SKILL_RESOURCE_DEFAULT_READ_CHAR_LIMIT = 12_000
internal const val SKILL_RESOURCE_MAX_READ_CHAR_LIMIT = 48_000
private const val SKILL_SCRIPT_MAX_ARGS = 24
private const val SKILL_SCRIPT_MAX_ARG_CHARS = 512

@Serializable
enum class SkillSourceType {
    LOCAL,
    IMPORTED,
    BUNDLED,
}

data class SkillCatalogEntry(
    val directoryName: String,
    val path: String,
    val name: String,
    val description: String,
    val sourceType: SkillSourceType = SkillSourceType.LOCAL,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val version: String? = null,
    val author: String? = null,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: String? = null,
    val argumentHint: String? = null,
    val userInvocable: Boolean = true,
    val modelInvocable: Boolean = true,
    val isBundled: Boolean = false,
)

data class SkillCreationResult(
    val directoryName: String,
    val path: String,
)

data class SkillEditorDocument(
    val originalDirectoryName: String,
    val directoryName: String,
    val name: String,
    val description: String,
    val body: String,
    val extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
)

data class SkillImportResult(
    val directories: List<String>,
    val importedFiles: Int,
)

data class SkillImportPreviewEntry(
    val directoryName: String,
    val name: String,
    val description: String,
    val sourceId: String? = null,
    val version: String? = null,
    val author: String? = null,
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: String? = null,
    val packageHash: String? = null,
    val scriptFiles: Int = 0,
    val referenceFiles: Int = 0,
    val assetFiles: Int = 0,
    val scriptPaths: List<String> = emptyList(),
)

data class SkillImportPreview(
    val archiveName: String? = null,
    val directories: List<String>,
    val totalFiles: Int,
    val scriptFiles: Int,
    val referenceFiles: Int,
    val assetFiles: Int,
    val hasScripts: Boolean,
    val entries: List<SkillImportPreviewEntry>,
)

data class SkillActivationEntry(
    val entry: SkillCatalogEntry,
    val markdown: String,
    val resourceFiles: List<String>,
)

data class SkillResourceReadResult(
    val entry: SkillCatalogEntry,
    val relativePath: String,
    val content: String,
    val truncated: Boolean,
    val totalBytes: Int,
)

data class SkillScriptRunResult(
    val entry: SkillCatalogEntry,
    val relativePath: String,
    val interpreter: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val errMsg: String?,
)

data class SkillInvalidEntry(
    val directoryName: String,
    val path: String,
    val reason: SkillInvalidReason,
)

sealed interface SkillInvalidReason {
    data object MissingSkillFile : SkillInvalidReason
    data object MissingYamlFrontmatter : SkillInvalidReason
    data object FrontmatterMustStart : SkillInvalidReason
    data object FrontmatterNotClosed : SkillInvalidReason
    data object MissingName : SkillInvalidReason
    data object MissingDescription : SkillInvalidReason
    data object NoActivationPath : SkillInvalidReason
    data class FailedToRead(val detail: String) : SkillInvalidReason
    data class Other(val message: String) : SkillInvalidReason
}

data class SkillsCatalogState(
    val workdir: String = "",
    val rootPath: String = "",
    val entries: List<SkillCatalogEntry> = emptyList(),
    val invalidEntries: List<SkillInvalidEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val refreshedAt: Long = 0L,
) {
    val entryNames: Set<String> = entries.mapTo(linkedSetOf()) { it.directoryName }
}

internal data class SkillFrontmatter(
    val name: String,
    val description: String,
    val extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
) {
    val license: String? = extras.license
    val compatibility: String? = extras.compatibility
    val allowedTools: String? = extras.allowedTools
    val argumentHint: String? = extras.argumentHint
    val userInvocable: Boolean = extras.userInvocable
    val modelInvocable: Boolean = !extras.disableModelInvocation
    val author: String? = extras.metadata["author"]
    val version: String? = extras.metadata["version"]
}

data class SkillFrontmatterExtras(
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: String? = null,
    val argumentHint: String? = null,
    val userInvocable: Boolean = true,
    val disableModelInvocation: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    val modelInvocable: Boolean = !disableModelInvocation
    val version: String? = metadata["version"]
    val author: String? = metadata["author"]
}

internal fun SkillFrontmatterExtras.hasActivationPath(): Boolean = userInvocable || modelInvocable

internal fun normalizeSkillFrontmatterExtras(extras: SkillFrontmatterExtras): SkillFrontmatterExtras {
    if (extras.hasActivationPath()) return extras
    return extras.copy(disableModelInvocation = false)
}

internal sealed interface SkillFrontmatterParseResult {
    data class Success(val frontmatter: SkillFrontmatter) : SkillFrontmatterParseResult
    data class Error(val reason: SkillInvalidReason) : SkillFrontmatterParseResult
}

internal data class SkillDirectoryDescriptor(
    val directoryName: String,
    val path: String,
    val hasSkillFile: Boolean,
    val skillMarkdownPreview: String? = null,
    val sourceMetadataPreview: String? = null,
)

internal data class SkillCatalogDiscoveryResult(
    val entries: List<SkillCatalogEntry>,
    val invalidEntries: List<SkillInvalidEntry>,
)

internal sealed interface SkillDirectoryInspectionResult {
    data class Valid(val entry: SkillCatalogEntry) : SkillDirectoryInspectionResult
    data class Invalid(val entry: SkillInvalidEntry) : SkillDirectoryInspectionResult
}

internal data class SkillArchiveFile(
    val path: String,
    val bytes: ByteArray,
)

internal data class ParsedSkillArchive(
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

internal data class SkillImportPlan(
    val topLevelDirectories: List<String>,
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

internal data class SkillImportPreviewData(
    val plan: SkillImportPlan,
    val preview: SkillImportPreview,
)

private data class SkillFileReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
    val totalBytes: Int,
)

internal data class BundledSkill(
    val directoryName: String,
    val assetPath: String,
)

internal data class SkillMarkdownDocument(
    val frontmatter: SkillFrontmatter,
    val body: String,
)

@Serializable
internal data class SkillSourceMetadata(
    val schemaVersion: Int = SKILL_SOURCE_METADATA_SCHEMA_VERSION,
    val sourceType: SkillSourceType,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val version: String? = null,
    val hash: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
)

class SkillsRepository(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(SkillsCatalogState())
    val state: StateFlow<SkillsCatalogState> = _state.asStateFlow()

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .filter { !it.init }
                .map { it.termuxWorkdir }
                .distinctUntilChanged()
                .collect { workdir ->
                    refresh(workdir)
                }
        }
    }

    fun requestRefresh() {
        requestRefresh(force = false)
    }

    fun requestRefresh(force: Boolean) {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return
        if (!force && _state.value.isLoading && _state.value.workdir == settings.termuxWorkdir) return
        if (!force && !shouldRefreshCatalogSnapshot(_state.value, settings.termuxWorkdir)) return
        appScope.launch {
            refresh(settings.termuxWorkdir)
        }
    }

    suspend fun refresh(workdir: String = settingsStore.settingsFlow.value.termuxWorkdir) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                refreshLocked(workdir)
            }
        }
    }

    suspend fun getCatalogSnapshot(
        forceRefresh: Boolean = false,
        workdir: String = settingsStore.settingsFlow.value.termuxWorkdir,
    ): SkillsCatalogState {
        if (forceRefresh || shouldRefreshCatalogSnapshot(state.value, workdir)) {
            runCatching {
                refresh(workdir)
            }.onFailure { error ->
                Log.w(TAG, "skills snapshot refresh failed for $workdir", error)
            }
        }
        return state.value
    }

    suspend fun createSkill(
        directoryName: String,
        name: String,
        description: String,
        body: String,
        extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
    ): SkillCreationResult {
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }
        require(extras.hasActivationPath()) { "Skill must remain invocable by the user or the model" }

        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }
            val desiredDirectoryName = sanitizeSkillDirectoryName(
                input = directoryName.ifBlank { name },
                fallback = DEFAULT_CREATED_SKILL_DIRECTORY,
            )
            val finalDirectoryName = resolveUniqueDirectoryNames(
                desired = listOf(desiredDirectoryName),
                existing = existingDirectoryNames,
            ).getValue(desiredDirectoryName)

            val markdown = buildSkillMarkdown(
                name = name.trim(),
                description = description.trim(),
                body = body,
                extras = extras,
            )
            val script = buildCreateSkillScript(
                rootPath = rootPath,
                directoryName = finalDirectoryName,
            )
            runSkillScript(
                script = script,
                workdir = workdir,
                label = "RikkaHub create local skill",
                stdin = markdown,
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            writeSkillSourceMetadata(
                rootPath = rootPath,
                workdir = workdir,
                directoryName = finalDirectoryName,
                metadata = SkillSourceMetadata(
                    sourceType = SkillSourceType.LOCAL,
                    sourceId = finalDirectoryName,
                ),
            )

            SkillCreationResult(
                directoryName = finalDirectoryName,
                path = "$rootPath/$finalDirectoryName",
            )
        }
    }

    suspend fun loadSkillDocument(entry: SkillCatalogEntry): SkillEditorDocument {
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }

            val markdown = readSkillFile(
                directoryPath = entry.path,
                workdir = settings.termuxWorkdir,
            )
            val document = parseSkillMarkdownDocument(markdown)

            SkillEditorDocument(
                originalDirectoryName = entry.directoryName,
                directoryName = entry.directoryName,
                name = document.frontmatter.name,
                description = document.frontmatter.description,
                body = document.body,
                extras = document.frontmatter.extras,
            )
        }
    }

    suspend fun updateSkill(
        originalDirectoryName: String,
        directoryName: String,
        name: String,
        description: String,
        body: String,
        extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
    ): SkillCreationResult {
        val safeOriginalDirectoryName = requireTopLevelSkillDirectoryName(
            originalDirectoryName,
            argumentName = "Original skill directory",
        )
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }
        require(extras.hasActivationPath()) { "Skill must remain invocable by the user or the model" }

        val result = runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }

            val finalDirectoryName = sanitizeSkillDirectoryName(
                input = directoryName.ifBlank { name },
                fallback = safeOriginalDirectoryName,
            )
            val conflictingDirectories = existingDirectoryNames - safeOriginalDirectoryName
            require(finalDirectoryName !in conflictingDirectories) {
                "Skill directory already exists: $finalDirectoryName"
            }

            if (finalDirectoryName != safeOriginalDirectoryName) {
                runSkillScript(
                    script = buildMoveSkillDirectoryScript(
                        rootPath = rootPath,
                        fromDirectoryName = safeOriginalDirectoryName,
                        toDirectoryName = finalDirectoryName,
                    ),
                    workdir = workdir,
                    label = "RikkaHub rename local skill",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }

            val markdown = buildSkillMarkdown(
                name = name.trim(),
                description = description.trim(),
                body = body,
                extras = extras,
            )
            runSkillScript(
                script = buildCreateSkillScript(
                    rootPath = rootPath,
                    directoryName = finalDirectoryName,
                ),
                workdir = workdir,
                label = "RikkaHub update local skill",
                stdin = markdown,
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            val sourceMetadata = readSkillSourceMetadata(
                directoryPath = "$rootPath/$finalDirectoryName",
                workdir = workdir,
            )
            writeSkillSourceMetadata(
                rootPath = rootPath,
                workdir = workdir,
                directoryName = finalDirectoryName,
                metadata = resolveUpdatedSkillSourceMetadata(
                    existingMetadata = sourceMetadata,
                    originalDirectoryName = safeOriginalDirectoryName,
                    finalDirectoryName = finalDirectoryName,
                ),
            )

            SkillCreationResult(
                directoryName = finalDirectoryName,
                path = "$rootPath/$finalDirectoryName",
            )
        }
        renameSelectedSkillDirectoryAcrossAssistants(
            fromDirectoryName = safeOriginalDirectoryName,
            toDirectoryName = result.directoryName,
        )
        return result
    }

    suspend fun importSkillZip(
        inputStream: InputStream,
        archiveName: String? = null,
    ): SkillImportResult {
        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }
            val previewData = buildSkillImportPreview(
                archive = parseSkillArchive(inputStream),
                suggestedDirectoryName = archiveName
                    ?.substringBeforeLast('.', archiveName)
                    ?.let { sanitizeSkillDirectoryName(it, DEFAULT_IMPORTED_SKILL_DIRECTORY) },
                existingDirectoryNames = existingDirectoryNames,
                archiveName = archiveName,
            )
            val importPlan = previewData.plan
            val stageToken = UUID.randomUUID().toString()
            val stageRootPath = "$rootPath/.rikkahub_tmp/import-$stageToken"
            val movedDirectories = arrayListOf<String>()

            runCatching {
                createSkillDirectory(
                    rootPath = rootPath,
                    workdir = workdir,
                    relativeDirectory = ".rikkahub_tmp/import-$stageToken",
                )
                importPlan.directories
                    .sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it })
                    .forEach { relativeDirectory ->
                        createSkillDirectory(
                            rootPath = stageRootPath,
                            workdir = workdir,
                            relativeDirectory = relativeDirectory,
                        )
                    }

                importPlan.files.forEach { file ->
                    writeSkillFile(
                        rootPath = stageRootPath,
                        workdir = workdir,
                        relativePath = file.path,
                        bytes = file.bytes,
                    )
                }
                val previewEntriesByDirectory = previewData.preview.entries.associateBy { it.directoryName }
                importPlan.topLevelDirectories.forEach { directoryName ->
                    writeSkillSourceMetadata(
                        rootPath = stageRootPath,
                        workdir = workdir,
                        directoryName = directoryName,
                        metadata = buildImportedSkillSourceMetadata(
                            directoryName = directoryName,
                            previewEntry = previewEntriesByDirectory[directoryName],
                            archiveName = archiveName,
                        ),
                    )
                }

                importPlan.topLevelDirectories.forEach { directoryName ->
                    runSkillScript(
                        script = buildMoveStagedSkillDirectoryScript(
                            stageRootPath = stageRootPath,
                            rootPath = rootPath,
                            directoryName = directoryName,
                        ),
                        workdir = workdir,
                        label = "RikkaHub install imported skill",
                        timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                    )
                    movedDirectories += directoryName
                }
            }.getOrElse { error ->
                movedDirectories.forEach { directoryName ->
                    runCatching {
                        runSkillScript(
                            script = buildDeleteSkillScript(
                                rootPath = rootPath,
                                directoryName = directoryName,
                            ),
                            workdir = workdir,
                            label = "RikkaHub rollback imported skill",
                            timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                        )
                    }
                }
                runCatching {
                    runSkillScript(
                        script = buildDeleteDirectoryScript(stageRootPath),
                        workdir = workdir,
                        label = "RikkaHub cleanup staged import",
                        timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                    )
                }
                throw error
            }
            runCatching {
                runSkillScript(
                    script = buildDeleteDirectoryScript(stageRootPath),
                    workdir = workdir,
                    label = "RikkaHub cleanup staged import",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }

            SkillImportResult(
                directories = importPlan.topLevelDirectories,
                importedFiles = importPlan.files.size,
            )
        }
    }

    suspend fun previewSkillZip(
        inputStream: InputStream,
        archiveName: String? = null,
    ): SkillImportPreview {
        return withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val settings = settingsStore.settingsFlow.value
                require(!settings.init) { "Settings are not ready" }
                val rootPath = buildSkillsRootPath(settings.termuxWorkdir)
                val existingDirectoryNames = listSkillDirectories(rootPath, settings.termuxWorkdir)
                    .mapTo(linkedSetOf()) { it.directoryName }
                buildSkillImportPreview(
                    archive = parseSkillArchive(inputStream),
                    suggestedDirectoryName = archiveName
                        ?.substringBeforeLast('.', archiveName)
                        ?.let { sanitizeSkillDirectoryName(it, DEFAULT_IMPORTED_SKILL_DIRECTORY) },
                    existingDirectoryNames = existingDirectoryNames,
                    archiveName = archiveName,
                ).preview
            }
        }
    }

    suspend fun deleteSkill(directoryName: String) {
        val safeDirectoryName = requireTopLevelSkillDirectoryName(
            directoryName,
            argumentName = "Skill directory",
        )

        runCatalogMutation { workdir, rootPath ->
            val metadata = readSkillSourceMetadata(
                directoryPath = "$rootPath/$safeDirectoryName",
                workdir = workdir,
            )
            require(!isCanonicalBundledMetadata(metadata, safeDirectoryName)) {
                "Built-in skills cannot be deleted: $safeDirectoryName"
            }
            runSkillScript(
                script = buildDeleteSkillScript(
                    rootPath = rootPath,
                    directoryName = safeDirectoryName,
                ),
                workdir = workdir,
                label = "RikkaHub delete local skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
        removeSelectedSkillDirectoryAcrossAssistants(safeDirectoryName)
    }

    suspend fun loadSkillActivation(entry: SkillCatalogEntry): SkillActivationEntry {
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }
            SkillActivationEntry(
                entry = entry,
                markdown = readSkillFile(
                    directoryPath = entry.path,
                    workdir = settings.termuxWorkdir,
                ),
                resourceFiles = listSkillResourceFiles(
                    directoryPath = entry.path,
                    workdir = settings.termuxWorkdir,
                ),
            )
        }
    }

    suspend fun loadSkillActivations(directoryNames: Collection<String>): List<SkillActivationEntry> {
        if (directoryNames.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val entriesByDirectory = state.value.entries.associateBy { it.directoryName }
            buildList {
                directoryNames.distinct()
                    .mapNotNull { directoryName -> entriesByDirectory[directoryName] }
                    .forEach { entry ->
                        add(loadSkillActivation(entry))
                    }
            }
        }
    }

    suspend fun readSkillResource(
        entry: SkillCatalogEntry,
        relativePath: String,
        maxChars: Int = SKILL_RESOURCE_DEFAULT_READ_CHAR_LIMIT,
    ): SkillResourceReadResult {
        val normalizedPath = normalizeSkillResourcePath(relativePath)
        val safeMaxChars = maxChars.coerceIn(1, SKILL_RESOURCE_MAX_READ_CHAR_LIMIT)
        val maxBytes = safeMaxChars * 4
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }
            val file = readRelativeSkillFile(
                directoryPath = entry.path,
                relativePath = normalizedPath,
                workdir = settings.termuxWorkdir,
                maxBytes = maxBytes,
            )
            val content = decodeSkillTextResource(file.bytes)
            SkillResourceReadResult(
                entry = entry,
                relativePath = normalizedPath,
                content = if (content.length <= safeMaxChars) content else content.take(safeMaxChars),
                truncated = file.truncated || content.length > safeMaxChars,
                totalBytes = file.totalBytes,
            )
        }
    }

    suspend fun runSkillEntryScript(
        entry: SkillCatalogEntry,
        relativePath: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = SKILL_COMMAND_TIMEOUT_MS,
    ): SkillScriptRunResult {
        val normalizedPath = normalizeSkillScriptPath(relativePath)
        val normalizedArgs = normalizeSkillScriptArguments(args)
        val interpreter = resolveSkillScriptInterpreter(normalizedPath)
        val result = withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }
            termuxCommandManager.run(
                TermuxRunCommandRequest(
                    commandPath = TERMUX_BASH_PATH,
                    arguments = listOf(
                        "-lc",
                        buildRunSkillEntryScriptWrapper(),
                        "_",
                        entry.path,
                        normalizedPath,
                        interpreter,
                    ) + normalizedArgs,
                    workdir = entry.path,
                    background = true,
                    timeoutMs = timeoutMs.coerceAtLeast(1_000L),
                    label = "RikkaHub run skill script",
                    description = "Run a selected skill script inside its package root",
                )
            )
        }

        return SkillScriptRunResult(
            entry = entry,
            relativePath = normalizedPath,
            interpreter = interpreter,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            errMsg = result.errMsg,
        )
    }

    private suspend fun refreshLocked(workdir: String) {
        if (settingsStore.settingsFlow.value.termuxWorkdir != workdir) {
            return
        }
        val rootPath = buildSkillsRootPath(workdir)
        _state.value = _state.value.toRefreshingCatalogState(
            workdir = workdir,
            rootPath = rootPath,
        )
        val refreshedState = runCatching {
            discover(workdir = workdir, rootPath = rootPath)
        }.getOrElse { error ->
            Log.w(TAG, "refresh failed for $rootPath", error)
            _state.value.copy(
                workdir = workdir,
                rootPath = rootPath,
                isLoading = false,
                error = error.message ?: error.javaClass.name,
                refreshedAt = System.currentTimeMillis(),
            )
        }
        if (settingsStore.settingsFlow.value.termuxWorkdir != workdir) {
            return
        }
        _state.value = refreshedState
    }

    private suspend fun <T> runCatalogMutation(
        mutation: suspend (workdir: String, rootPath: String) -> T,
    ): T {
        return withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val settings = settingsStore.settingsFlow.value
                require(!settings.init) { "Settings are not ready" }
                val workdir = settings.termuxWorkdir
                val rootPath = buildSkillsRootPath(workdir)
                _state.value = _state.value.toMutatingCatalogState(
                    workdir = workdir,
                    rootPath = rootPath,
                )

                runCatching {
                    ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
                    val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                        .mapTo(linkedSetOf()) { it.directoryName }
                    ensureBundledSkillsInstalled(
                        rootPath = rootPath,
                        workdir = workdir,
                        existingDirectoryNames = existingDirectoryNames,
                    )
                    val result = mutation(workdir, rootPath)
                    _state.value = discover(workdir = workdir, rootPath = rootPath)
                    result
                }.getOrElse { error ->
                    Log.w(TAG, "skills mutation failed for $rootPath", error)
                    val recoveredState = runCatching {
                        discover(workdir = workdir, rootPath = rootPath)
                    }.getOrElse {
                        _state.value.copy(
                            workdir = workdir,
                            rootPath = rootPath,
                            isLoading = false,
                            refreshedAt = System.currentTimeMillis(),
                        )
                    }
                    _state.value = recoveredState.copy(
                        error = error.message ?: error.javaClass.name,
                        refreshedAt = System.currentTimeMillis(),
                    )
                    throw error
                }
            }
        }
    }

    private suspend fun renameSelectedSkillDirectoryAcrossAssistants(
        fromDirectoryName: String,
        toDirectoryName: String,
    ) {
        if (fromDirectoryName == toDirectoryName) return
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.replaceSelectedSkillDirectory(
                    fromDirectoryName = fromDirectoryName,
                    toDirectoryName = toDirectoryName,
                ),
            )
        }
    }

    private suspend fun removeSelectedSkillDirectoryAcrossAssistants(
        directoryName: String,
    ) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.removeSelectedSkillDirectory(directoryName),
            )
        }
    }

    private suspend fun discover(
        workdir: String,
        rootPath: String,
    ): SkillsCatalogState {
        var listed = snapshotSkillDirectories(rootPath, workdir)
        val existingDirectoryNames = listed
            .mapTo(linkedSetOf()) { it.directoryName }
        if (
            ensureBundledSkillsInstalled(
                rootPath = rootPath,
                workdir = workdir,
                existingDirectoryNames = existingDirectoryNames,
            )
        ) {
            listed = snapshotSkillDirectories(rootPath, workdir)
        }
        val discovery = discoverCatalogEntries(
            directories = listed,
            readSkillFile = { directoryPath -> readSkillFile(directoryPath, workdir) },
        )

        return SkillsCatalogState(
            workdir = workdir,
            rootPath = rootPath,
            entries = discovery.entries.sortedBy { it.directoryName },
            invalidEntries = discovery.invalidEntries.sortedBy { it.directoryName },
            isLoading = false,
            error = null,
            refreshedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun snapshotSkillDirectories(
        rootPath: String,
        workdir: String,
    ): List<SkillDirectoryDescriptor> {
        val result = runSkillScript(
            script = buildDiscoverScript(rootPath),
            workdir = workdir,
            label = "RikkaHub scan local skills",
        )
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 5)
                if (parts.size < 3) return@mapNotNull null
                SkillDirectoryDescriptor(
                    directoryName = decodeSkillDiscoveryField(parts[0]),
                    path = decodeSkillDiscoveryField(parts[1]),
                    hasSkillFile = parts[2] == "1",
                    skillMarkdownPreview = parts.getOrNull(3)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::decodeSkillMarkdownPreview),
                    sourceMetadataPreview = parts.getOrNull(4)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::decodeSkillMarkdownPreview),
                )
            }
            .toList()
    }

    private suspend fun listSkillDirectories(
        rootPath: String,
        workdir: String,
    ): List<SkillDirectoryDescriptor> {
        val script = buildListScript(rootPath)
        val result = runSkillScript(
            script = script,
            workdir = workdir,
            label = "RikkaHub list local skills",
        )
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                SkillDirectoryDescriptor(
                    directoryName = decodeSkillDiscoveryField(parts[0]),
                    path = decodeSkillDiscoveryField(parts[1]),
                    hasSkillFile = parts[2] == "1",
                )
            }
            .toList()
    }

    private suspend fun readSkillFile(
        directoryPath: String,
        workdir: String,
    ): String {
        return readDirectoryFile(
            directoryPath = directoryPath,
            fileName = SKILL_PACKAGE_FILE_NAME,
            workdir = workdir,
            optional = false,
            label = "RikkaHub read local skill",
        )!!
    }

    private suspend fun readDirectoryFile(
        directoryPath: String,
        fileName: String,
        workdir: String,
        optional: Boolean,
        label: String,
    ): String? {
        val result = runSkillScript(
            script = buildReadDirectoryFileScript(
                directoryPath = directoryPath,
                fileName = fileName,
                optional = optional,
            ),
            workdir = workdir,
            label = label,
        )
        return result.stdout.takeIf { it.isNotEmpty() }
    }

    private suspend fun readRelativeSkillFile(
        directoryPath: String,
        relativePath: String,
        workdir: String,
        maxBytes: Int,
    ): SkillFileReadResult {
        val result = runSkillScript(
            script = buildReadRelativeSkillFileScript(
                directoryPath = directoryPath,
                relativePath = relativePath,
                maxBytes = maxBytes,
            ),
            workdir = workdir,
            label = "RikkaHub read skill resource",
        )
        val parts = result.stdout.trimEnd().split('\t', limit = 2)
        require(parts.isNotEmpty()) { "Failed to parse skill resource response" }
        val totalBytes = parts.first().trim().toIntOrNull()
            ?: error("Failed to parse skill resource size")
        val encodedBytes = parts.getOrNull(1).orEmpty()
        val bytes = if (encodedBytes.isBlank()) {
            ByteArray(0)
        } else {
            Base64.getDecoder().decode(encodedBytes)
        }
        return SkillFileReadResult(
            bytes = bytes,
            truncated = totalBytes > maxBytes,
            totalBytes = totalBytes,
        )
    }

    private suspend fun readSkillSourceMetadata(
        directoryPath: String,
        workdir: String,
    ): SkillSourceMetadata? {
        val raw = readDirectoryFile(
            directoryPath = directoryPath,
            fileName = SKILL_SOURCE_METADATA_FILE_NAME,
            workdir = workdir,
            optional = true,
            label = "RikkaHub read skill source metadata",
        ) ?: return null
        return parseSkillSourceMetadata(raw)
    }

    private suspend fun computeInstalledSkillPackageHash(
        directoryPath: String,
        workdir: String,
    ): String? {
        val result = runSkillScript(
            script = buildComputeSkillPackageHashScript(directoryPath),
            workdir = workdir,
            label = "RikkaHub hash local skill package",
        )
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private suspend fun listSkillResourceFiles(
        directoryPath: String,
        workdir: String,
    ): List<String> {
        val result = runSkillScript(
            script = buildListSkillResourceFilesScript(directoryPath),
            workdir = workdir,
            label = "RikkaHub list skill resources",
        )
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map(::decodeSkillDiscoveryField)
            .toList()
    }

    private suspend fun writeSkillSourceMetadata(
        rootPath: String,
        workdir: String,
        directoryName: String,
        metadata: SkillSourceMetadata,
    ) {
        writeSkillFile(
            rootPath = rootPath,
            workdir = workdir,
            relativePath = "$directoryName/$SKILL_SOURCE_METADATA_FILE_NAME",
            bytes = skillSourceMetadataJson.encodeToString(metadata).toByteArray(Charsets.UTF_8),
        )
    }

    private fun buildBundledSkillSourceMetadata(bundledSkill: BundledSkill): SkillSourceMetadata {
        val files = readBundledSkillFiles(
            context = context,
            assetPath = bundledSkill.assetPath,
        )
        val skillMarkdown = files.firstOrNull { it.path == SKILL_PACKAGE_FILE_NAME }
            ?.bytes
            ?.toString(Charsets.UTF_8)
            .orEmpty()
        val frontmatter = (parseSkillFrontmatter(skillMarkdown) as? SkillFrontmatterParseResult.Success)
            ?.frontmatter
        return SkillSourceMetadata(
            sourceType = SkillSourceType.BUNDLED,
            sourceId = bundledSkill.directoryName,
            version = frontmatter?.version,
            hash = computeSkillPackageHash(files),
        )
    }

    private suspend fun ensureSkillsRootDirectory(
        rootPath: String,
        workdir: String,
    ) {
        runSkillScript(
            script = buildCreateDirectoryScript(rootPath = rootPath, relativePath = ""),
            workdir = workdir,
            label = "RikkaHub ensure skills root",
        )
    }

    private suspend fun ensureBundledSkillsInstalled(
        rootPath: String,
        workdir: String,
        existingDirectoryNames: MutableSet<String>,
    ): Boolean {
        var installedAny = false
        BUNDLED_SKILLS.forEach { bundledSkill ->
            val expectedMetadata = buildBundledSkillSourceMetadata(bundledSkill)
            val expectedHash = expectedMetadata.hash
            if (bundledSkill.directoryName !in existingDirectoryNames) {
                runCatching {
                    installBundledSkill(
                        rootPath = rootPath,
                        workdir = workdir,
                        bundledSkill = bundledSkill,
                        metadata = expectedMetadata,
                        replaceExisting = false,
                    )
                    existingDirectoryNames += bundledSkill.directoryName
                    installedAny = true
                }.onFailure { error ->
                    Log.w(TAG, "Failed to install bundled skill ${bundledSkill.directoryName}", error)
                }
                return@forEach
            }

            val directoryPath = "$rootPath/${bundledSkill.directoryName}"
            val installedMetadata = runCatching {
                readSkillSourceMetadata(
                    directoryPath = directoryPath,
                    workdir = workdir,
                )
            }.getOrNull()
            val installedHash = runCatching {
                computeInstalledSkillPackageHash(
                    directoryPath = directoryPath,
                    workdir = workdir,
                )
            }.getOrNull()
            if (shouldBackfillBundledSkillMetadata(installedMetadata, installedHash, expectedMetadata)) {
                runCatching {
                    writeSkillSourceMetadata(
                        rootPath = rootPath,
                        workdir = workdir,
                        directoryName = bundledSkill.directoryName,
                        metadata = expectedMetadata,
                    )
                    installedAny = true
                }.onFailure { error ->
                    Log.w(TAG, "Failed to backfill bundled skill metadata ${bundledSkill.directoryName}", error)
                }
                return@forEach
            }
            if (shouldConvertBundledSkillInstallationToLocal(bundledSkill, installedMetadata, installedHash, expectedMetadata)) {
                runCatching {
                    writeSkillSourceMetadata(
                        rootPath = rootPath,
                        workdir = workdir,
                        directoryName = bundledSkill.directoryName,
                        metadata = SkillSourceMetadata(
                            sourceType = SkillSourceType.LOCAL,
                            sourceId = bundledSkill.directoryName,
                        ),
                    )
                    installedAny = true
                }.onFailure { error ->
                    Log.w(TAG, "Failed to relabel bundled skill ${bundledSkill.directoryName} as local", error)
                }
                return@forEach
            }
            if (!shouldRefreshBundledSkillInstallation(bundledSkill, installedMetadata, installedHash, expectedMetadata)) {
                if (
                    isCanonicalBundledMetadata(installedMetadata, bundledSkill.directoryName) &&
                    installedHash == expectedHash &&
                    installedMetadata != expectedMetadata
                ) {
                    runCatching {
                        writeSkillSourceMetadata(
                            rootPath = rootPath,
                            workdir = workdir,
                            directoryName = bundledSkill.directoryName,
                            metadata = expectedMetadata,
                        )
                        installedAny = true
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to refresh bundled skill metadata ${bundledSkill.directoryName}", error)
                    }
                }
                return@forEach
            }
            runCatching {
                installBundledSkill(
                    rootPath = rootPath,
                    workdir = workdir,
                    bundledSkill = bundledSkill,
                    metadata = expectedMetadata,
                    replaceExisting = true,
                )
                existingDirectoryNames += bundledSkill.directoryName
                installedAny = true
            }.onFailure { error ->
                Log.w(TAG, "Failed to install bundled skill ${bundledSkill.directoryName}", error)
            }
        }
        return installedAny
    }

    private suspend fun installBundledSkill(
        rootPath: String,
        workdir: String,
        bundledSkill: BundledSkill,
        metadata: SkillSourceMetadata,
        replaceExisting: Boolean,
    ) {
        val files = readBundledSkillFiles(
            context = context,
            assetPath = bundledSkill.assetPath,
        )
        require(files.any { it.path == SKILL_PACKAGE_FILE_NAME }) {
            "Bundled skill ${bundledSkill.directoryName} is missing $SKILL_PACKAGE_FILE_NAME"
        }
        val stageToken = UUID.randomUUID().toString()
        val stageRootPath = "$rootPath/.rikkahub_tmp/bundled-${bundledSkill.directoryName}-$stageToken"
        val backupDirectoryName = ".rikkahub_backup_${bundledSkill.directoryName}_$stageToken"
        var backupWasCreated = false

        runCatching {
            createSkillDirectory(
                rootPath = rootPath,
                workdir = workdir,
                relativeDirectory = ".rikkahub_tmp/bundled-${bundledSkill.directoryName}-$stageToken",
            )
            createSkillDirectory(
                rootPath = stageRootPath,
                workdir = workdir,
                relativeDirectory = bundledSkill.directoryName,
            )
            files.sortedBy { it.path }.forEach { assetFile ->
                writeSkillFile(
                    rootPath = stageRootPath,
                    workdir = workdir,
                    relativePath = "${bundledSkill.directoryName}/${assetFile.path}",
                    bytes = assetFile.bytes,
                )
            }
            writeSkillSourceMetadata(
                rootPath = stageRootPath,
                workdir = workdir,
                directoryName = bundledSkill.directoryName,
                metadata = metadata,
            )
            if (replaceExisting) {
                runSkillScript(
                    script = buildMoveSkillDirectoryScript(
                        rootPath = rootPath,
                        fromDirectoryName = bundledSkill.directoryName,
                        toDirectoryName = backupDirectoryName,
                    ),
                    workdir = workdir,
                    label = "RikkaHub backup bundled skill",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
                backupWasCreated = true
            }
            runSkillScript(
                script = buildMoveStagedSkillDirectoryScript(
                    stageRootPath = stageRootPath,
                    rootPath = rootPath,
                    directoryName = bundledSkill.directoryName,
                ),
                workdir = workdir,
                label = "RikkaHub install bundled skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }.getOrElse { error ->
            if (backupWasCreated) {
                runCatching {
                    runSkillScript(
                        script = buildRestoreBackedUpSkillDirectoryScript(
                            rootPath = rootPath,
                            backupDirectoryName = backupDirectoryName,
                            directoryName = bundledSkill.directoryName,
                        ),
                        workdir = workdir,
                        label = "RikkaHub restore bundled skill backup",
                        timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                    )
                }
            }
            runCatching {
                runSkillScript(
                    script = buildDeleteDirectoryScript(stageRootPath),
                    workdir = workdir,
                    label = "RikkaHub cleanup staged bundled skill",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }
            throw error
        }
        if (backupWasCreated) {
            runCatching {
                runSkillScript(
                    script = buildDeleteSkillScript(
                        rootPath = rootPath,
                        directoryName = backupDirectoryName,
                    ),
                    workdir = workdir,
                    label = "RikkaHub cleanup bundled skill backup",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }
        }
        runCatching {
            runSkillScript(
                script = buildDeleteDirectoryScript(stageRootPath),
                workdir = workdir,
                label = "RikkaHub cleanup staged bundled skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
    }

    private suspend fun createSkillDirectory(
        rootPath: String,
        workdir: String,
        relativeDirectory: String,
    ) {
        runSkillScript(
            script = buildCreateDirectoryScript(rootPath = rootPath, relativePath = relativeDirectory),
            workdir = workdir,
            label = "RikkaHub create local skill directory",
        )
    }

    private suspend fun writeSkillFile(
        rootPath: String,
        workdir: String,
        relativePath: String,
        bytes: ByteArray,
    ) {
        if (bytes.size <= SKILL_SINGLE_WRITE_BYTES_LIMIT) {
            runSkillScript(
                script = buildSingleFileWriteScript(rootPath = rootPath, relativePath = relativePath),
                workdir = workdir,
                label = "RikkaHub write local skill file",
                stdin = Base64.getEncoder().encodeToString(bytes),
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
            return
        }

        val tempToken = UUID.randomUUID().toString()
        runCatching {
            runSkillScript(
                script = buildBeginChunkedFileWriteScript(
                    rootPath = rootPath,
                    relativePath = relativePath,
                    tempToken = tempToken,
                ),
                workdir = workdir,
                label = "RikkaHub begin local skill file upload",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )

            bytes.asList()
                .chunked(SKILL_CHUNK_WRITE_BYTES)
                .forEach { chunk ->
                    val chunkBytes = ByteArray(chunk.size)
                    chunk.forEachIndexed { index, value ->
                        chunkBytes[index] = value
                    }
                    runSkillScript(
                        script = buildAppendChunkedFileWriteScript(
                            rootPath = rootPath,
                            relativePath = relativePath,
                            tempToken = tempToken,
                        ),
                        workdir = workdir,
                        label = "RikkaHub append local skill file upload",
                        stdin = Base64.getEncoder().encodeToString(chunkBytes),
                        timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                    )
                }

            runSkillScript(
                script = buildCommitChunkedFileWriteScript(
                    rootPath = rootPath,
                    relativePath = relativePath,
                    tempToken = tempToken,
                ),
                workdir = workdir,
                label = "RikkaHub commit local skill file upload",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }.getOrElse { error ->
            runCatching {
                runSkillScript(
                    script = buildCleanupChunkedFileWriteScript(
                        rootPath = rootPath,
                        tempToken = tempToken,
                    ),
                    workdir = workdir,
                    label = "RikkaHub cleanup local skill file upload",
                    timeoutMs = SKILL_WRITE_TIMEOUT_MS,
                )
            }
            throw error
        }
    }

    private suspend fun runSkillScript(
        script: String,
        workdir: String,
        label: String,
        stdin: String? = null,
        timeoutMs: Long = SKILL_COMMAND_TIMEOUT_MS,
    ) = termuxCommandManager.run(
        buildSkillCommandRequest(
            script = script,
            workdir = workdir,
            label = label,
            stdin = stdin,
            timeoutMs = timeoutMs,
        )
    ).also { result ->
        if (!result.isSuccessful()) {
            error(result.errMsg.orEmpty().ifBlank {
                result.stderr.orEmpty().ifBlank { "Failed to run skill command" }
            })
        }
    }

    private fun buildListScript(rootPath: String): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            if [ ! -d "${'$'}ROOT" ]; then
              exit 0
            fi
            find "${'$'}ROOT" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z | while IFS= read -r -d '' dir; do
              [ -n "${'$'}dir" ] || continue
              name="${'$'}(basename "${'$'}dir")"
              encoded_name="${'$'}(printf '%s' "${'$'}name" | base64 | tr -d '\n')"
              encoded_dir="${'$'}(printf '%s' "${'$'}dir" | base64 | tr -d '\n')"
              if [ -f "${'$'}dir/$SKILL_PACKAGE_FILE_NAME" ]; then
                printf '%s\t%s\t1\n' "${'$'}encoded_name" "${'$'}encoded_dir"
              else
                printf '%s\t%s\t0\n' "${'$'}encoded_name" "${'$'}encoded_dir"
              fi
            done
        """.trimIndent()
    }

    private fun buildDiscoverScript(rootPath: String): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            PREVIEW_BYTES=$SKILL_LIST_PREVIEW_BYTES_LIMIT
            mkdir -p "${'$'}ROOT"
            find "${'$'}ROOT" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z | while IFS= read -r -d '' dir; do
              [ -n "${'$'}dir" ] || continue
              name="${'$'}(basename "${'$'}dir")"
              encoded_name="${'$'}(printf '%s' "${'$'}name" | base64 | tr -d '\n')"
              encoded_dir="${'$'}(printf '%s' "${'$'}dir" | base64 | tr -d '\n')"
              skill_file="${'$'}dir/$SKILL_PACKAGE_FILE_NAME"
              source_file="${'$'}dir/$SKILL_SOURCE_METADATA_FILE_NAME"
              if [ -f "${'$'}skill_file" ]; then
                printf '%s\t%s\t1\t' "${'$'}encoded_name" "${'$'}encoded_dir"
                head -c "${'$'}PREVIEW_BYTES" "${'$'}skill_file" | base64 | tr -d '\n'
                printf '\t'
                if [ -f "${'$'}source_file" ]; then
                  head -c "${'$'}PREVIEW_BYTES" "${'$'}source_file" | base64 | tr -d '\n'
                fi
                printf '\n'
              else
                printf '%s\t%s\t0\t\t\n' "${'$'}encoded_name" "${'$'}encoded_dir"
              fi
            done
        """.trimIndent()
    }

    private fun buildReadDirectoryFileScript(
        directoryPath: String,
        fileName: String,
        optional: Boolean,
    ): String {
        val safeDirectory = directoryPath.escapeForSingleQuotedShell()
        val safeFileName = fileName.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectory'
            FILE='$safeFileName'
            TARGET="${'$'}DIR/${'$'}FILE"
            if [ ! -f "${'$'}TARGET" ]; then
              if ${if (optional) "true" else "false"}; then
                exit 0
              fi
              exit 1
            fi
            cat "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildReadRelativeSkillFileScript(
        directoryPath: String,
        relativePath: String,
        maxBytes: Int,
    ): String {
        val safeDirectory = directoryPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectory'
            REL='$safeRelativePath'
            MAX_BYTES=$maxBytes
            [ -d "${'$'}DIR" ] || exit 1
            CANON_DIR="${'$'}(cd "${'$'}DIR" && pwd -P)"
            TARGET="${'$'}DIR/${'$'}REL"
            [ -f "${'$'}TARGET" ] || exit 1
            CANON_TARGET="${'$'}(realpath "${'$'}TARGET")"
            case "${'$'}CANON_TARGET" in
              "${'$'}CANON_DIR"/*) ;;
              *) exit 1 ;;
            esac
            TOTAL_BYTES="${'$'}(wc -c < "${'$'}CANON_TARGET" | tr -d '[:space:]')"
            printf '%s\t' "${'$'}TOTAL_BYTES"
            if [ "${'$'}TOTAL_BYTES" -gt "${'$'}MAX_BYTES" ]; then
              head -c "${'$'}MAX_BYTES" "${'$'}CANON_TARGET" | base64 | tr -d '\n'
            else
              base64 "${'$'}CANON_TARGET" | tr -d '\n'
            fi
            printf '\n'
        """.trimIndent()
    }

    private fun buildRunSkillEntryScriptWrapper(): String {
        return """
            set -eu
            DIR="${'$'}1"
            REL="${'$'}2"
            INTERPRETER="${'$'}3"
            shift 3
            [ -d "${'$'}DIR" ] || exit 1
            CANON_DIR="${'$'}(cd "${'$'}DIR" && pwd -P)"
            TARGET="${'$'}DIR/${'$'}REL"
            [ -f "${'$'}TARGET" ] || exit 1
            CANON_TARGET="${'$'}(realpath "${'$'}TARGET")"
            case "${'$'}CANON_TARGET" in
              "${'$'}CANON_DIR"/*) ;;
              *)
                printf '%s\n' "Script path escapes the skill directory" >&2
                exit 1
                ;;
            esac
            case "${'$'}INTERPRETER" in
              bash)
                exec "$TERMUX_BASH_PATH" "${'$'}CANON_TARGET" "${'$'}@"
                ;;
              python)
                exec /data/data/com.termux/files/usr/bin/python3 "${'$'}CANON_TARGET" "${'$'}@"
                ;;
              *)
                printf '%s\n' "Unsupported skill script interpreter: ${'$'}INTERPRETER" >&2
                exit 1
                ;;
            esac
        """.trimIndent()
    }

    private fun buildListSkillResourceFilesScript(directoryPath: String): String {
        val safeDirectory = directoryPath.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectory'
            if [ ! -d "${'$'}DIR" ]; then
              exit 0
            fi
            count=0
            find "${'$'}DIR" -type f -print0 | sort -z | while IFS= read -r -d '' file; do
              rel="${'$'}{file#"${'$'}DIR"/}"
              [ "${'$'}rel" = "$SKILL_PACKAGE_FILE_NAME" ] && continue
              [ "${'$'}rel" = "$SKILL_SOURCE_METADATA_FILE_NAME" ] && continue
              printf '%s\n' "${'$'}(printf '%s' "${'$'}rel" | base64 | tr -d '\n')"
              count="${'$'}((count + 1))"
              [ "${'$'}count" -lt "$SKILL_RESOURCE_LIST_LIMIT" ] || break
            done
        """.trimIndent()
    }

    private fun buildCreateSkillScript(
        rootPath: String,
        directoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            DIR_NAME='$safeDirectoryName'
            DIR="${'$'}ROOT/${'$'}DIR_NAME"
            [ ! -L "${'$'}DIR" ] || exit 1
            mkdir -p "${'$'}DIR" "${'$'}DIR/scripts" "${'$'}DIR/assets" "${'$'}DIR/references"
            TMP_FILE="${'$'}DIR/.${SKILL_PACKAGE_FILE_NAME}.${'$'}$.tmp"
            trap 'rm -f "${'$'}TMP_FILE"' EXIT
            cat > "${'$'}TMP_FILE"
            mv -f "${'$'}TMP_FILE" "${'$'}DIR/$SKILL_PACKAGE_FILE_NAME"
            trap - EXIT
        """.trimIndent()
    }

    private fun buildCreateDirectoryScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            if [ -n "${'$'}REL" ]; then
              mkdir -p "${'$'}ROOT/${'$'}REL"
            else
              mkdir -p "${'$'}ROOT"
            fi
        """.trimIndent()
    }

    private fun buildMoveSkillDirectoryScript(
        rootPath: String,
        fromDirectoryName: String,
        toDirectoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeFrom = fromDirectoryName.escapeForSingleQuotedShell()
        val safeTo = toDirectoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            FROM='$safeFrom'
            TO='$safeTo'
            [ -d "${'$'}ROOT/${'$'}FROM" ] || exit 1
            [ ! -e "${'$'}ROOT/${'$'}TO" ] || exit 1
            mv "${'$'}ROOT/${'$'}FROM" "${'$'}ROOT/${'$'}TO"
        """.trimIndent()
    }

    private fun buildDeleteSkillScript(
        rootPath: String,
        directoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            DIR_NAME='$safeDirectoryName'
            TARGET="${'$'}ROOT/${'$'}DIR_NAME"
            [ -d "${'$'}TARGET" ] || exit 0
            rm -rf "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildSingleFileWriteScript(
        rootPath: String,
        relativePath: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TMP_DIR="${'$'}ROOT/.rikkahub_tmp"
            TARGET="${'$'}ROOT/${'$'}REL"
            mkdir -p "${'$'}TMP_DIR" "$(dirname "${'$'}TARGET")"
            TMP_FILE="${'$'}TMP_DIR/$(basename -- "${'$'}REL").${'$'}$.tmp"
            trap 'rm -f "${'$'}TMP_FILE"' EXIT
            base64 -d > "${'$'}TMP_FILE"
            mv -f "${'$'}TMP_FILE" "${'$'}TARGET"
            trap - EXIT
        """.trimIndent()
    }

    private fun buildBeginChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TMP_DIR="${'$'}ROOT/.rikkahub_tmp"
            TARGET="${'$'}ROOT/${'$'}REL"
            TMP_FILE="${'$'}TMP_DIR/${'$'}TEMP_TOKEN"
            mkdir -p "${'$'}TMP_DIR" "$(dirname "${'$'}TARGET")"
            : > "${'$'}TMP_FILE"
        """.trimIndent()
    }

    private fun buildAppendChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TMP_FILE="${'$'}ROOT/.rikkahub_tmp/${'$'}TEMP_TOKEN"
            [ -f "${'$'}TMP_FILE" ] || exit 1
            base64 -d >> "${'$'}TMP_FILE"
        """.trimIndent()
    }

    private fun buildCommitChunkedFileWriteScript(
        rootPath: String,
        relativePath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeRelativePath = relativePath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            REL='$safeRelativePath'
            TEMP_TOKEN='$safeTempToken'
            TARGET="${'$'}ROOT/${'$'}REL"
            TMP_FILE="${'$'}ROOT/.rikkahub_tmp/${'$'}TEMP_TOKEN"
            mkdir -p "$(dirname "${'$'}TARGET")"
            mv -f "${'$'}TMP_FILE" "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildCleanupChunkedFileWriteScript(
        rootPath: String,
        tempToken: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeTempToken = tempToken.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            TEMP_TOKEN='$safeTempToken'
            rm -f "${'$'}ROOT/.rikkahub_tmp/${'$'}TEMP_TOKEN"
        """.trimIndent()
    }

    private fun buildMoveStagedSkillDirectoryScript(
        stageRootPath: String,
        rootPath: String,
        directoryName: String,
    ): String {
        val safeStageRoot = stageRootPath.escapeForSingleQuotedShell()
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            STAGE_ROOT='$safeStageRoot'
            ROOT='$safeRoot'
            DIR_NAME='$safeDirectoryName'
            SOURCE="${'$'}STAGE_ROOT/${'$'}DIR_NAME"
            TARGET="${'$'}ROOT/${'$'}DIR_NAME"
            [ -d "${'$'}SOURCE" ] || exit 1
            [ ! -e "${'$'}TARGET" ] || exit 1
            mv "${'$'}SOURCE" "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildRestoreBackedUpSkillDirectoryScript(
        rootPath: String,
        backupDirectoryName: String,
        directoryName: String,
    ): String {
        val safeRoot = rootPath.escapeForSingleQuotedShell()
        val safeBackupDirectoryName = backupDirectoryName.escapeForSingleQuotedShell()
        val safeDirectoryName = directoryName.escapeForSingleQuotedShell()
        return """
            set -eu
            ROOT='$safeRoot'
            BACKUP_DIR='$safeBackupDirectoryName'
            DIR_NAME='$safeDirectoryName'
            SOURCE="${'$'}ROOT/${'$'}BACKUP_DIR"
            TARGET="${'$'}ROOT/${'$'}DIR_NAME"
            [ -d "${'$'}SOURCE" ] || exit 0
            [ ! -e "${'$'}TARGET" ] || exit 1
            mv "${'$'}SOURCE" "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildDeleteDirectoryScript(
        directoryPath: String,
    ): String {
        val safeDirectoryPath = directoryPath.escapeForSingleQuotedShell()
        return """
            set -eu
            TARGET='$safeDirectoryPath'
            [ -d "${'$'}TARGET" ] || exit 0
            rm -rf "${'$'}TARGET"
        """.trimIndent()
    }

    private fun buildComputeSkillPackageHashScript(
        directoryPath: String,
    ): String {
        val safeDirectoryPath = directoryPath.escapeForSingleQuotedShell()
        return """
            set -eu
            DIR='$safeDirectoryPath'
            [ -d "${'$'}DIR" ] || exit 1
            find "${'$'}DIR" -type f ! -name "$SKILL_SOURCE_METADATA_FILE_NAME" -print0 | sort -z | while IFS= read -r -d '' file; do
              rel="${'$'}{file#"${'$'}DIR"/}"
              printf '%s' "${'$'}rel"
              printf '\0'
              cat "${'$'}file"
              printf '\0'
            done | sha256sum | awk '{print $1}'
        """.trimIndent()
    }

    companion object {
        fun buildSkillsRootPath(workdir: String): String {
            val normalized = workdir.trimEnd('/').ifBlank { "/data/data/com.termux/files/home" }
            return "$normalized/skills"
        }
    }
}

private fun decodeSkillMarkdownPreview(encodedPreview: String): String {
    return String(Base64.getDecoder().decode(encodedPreview), Charsets.UTF_8)
}

private fun decodeSkillDiscoveryField(encodedValue: String): String {
    return String(Base64.getDecoder().decode(encodedValue), Charsets.UTF_8)
}

private fun decodeSkillTextResource(bytes: ByteArray): String {
    for (endIndex in bytes.size downTo 0) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chunk = if (endIndex == bytes.size) bytes else bytes.copyOf(endIndex)
        val decoded = runCatching {
            decoder.decode(ByteBuffer.wrap(chunk)).toString()
        }.getOrNull()
        if (decoded != null) {
            return decoded
        }
    }
    error("Skill resource is not valid UTF-8 text")
}

private val skillSourceMetadataJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private val skillFrontmatterLoader = Load(
    LoadSettings.builder()
        .setLabel(SKILL_PACKAGE_FILE_NAME)
        .build()
)

private val BUNDLED_SKILLS = listOf(
    BundledSkill(
        directoryName = "skill-creator",
        assetPath = "$BUNDLED_SKILLS_ASSET_ROOT/skill-creator",
    )
)

internal fun parseSkillFrontmatter(markdown: String): SkillFrontmatterParseResult {
    val section = extractSkillFrontmatterSection(markdown)
    if (section is SkillFrontmatterSectionResult.Error) {
        return SkillFrontmatterParseResult.Error(section.reason)
    }
    section as SkillFrontmatterSectionResult.Success

    val rawValues = runCatching {
        skillFrontmatterLoader.loadFromString(section.yaml)
    }.getOrElse { error ->
        return SkillFrontmatterParseResult.Error(
            SkillInvalidReason.Other(error.message ?: "Failed to parse YAML frontmatter")
        )
    }
    val values = rawValues as? Map<*, *> ?: emptyMap<Any?, Any?>()

    val name = values.stringValue("name")
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingName)
    val description = values.stringValue("description")
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingDescription)
    val metadata = values.stringMap("metadata").toMutableMap().apply {
        values.stringValue("author")?.let { putIfAbsent("author", it) }
        values.stringValue("version")?.let { putIfAbsent("version", it) }
    }

    val extras = SkillFrontmatterExtras(
        license = values.stringLikeValue("license"),
        compatibility = values.stringLikeValue("compatibility"),
        allowedTools = values.stringLikeValue("allowed-tools"),
        argumentHint = values.stringLikeValue("argument-hint"),
        userInvocable = values.booleanValue("user-invocable") ?: true,
        disableModelInvocation = values.booleanValue("disable-model-invocation") ?: false,
        metadata = metadata,
    )
    if (!extras.hasActivationPath()) {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.NoActivationPath)
    }

    return SkillFrontmatterParseResult.Success(
        SkillFrontmatter(
            name = name,
            description = description,
            extras = extras,
        )
    )
}

internal fun sanitizeSkillDirectoryName(
    input: String,
    fallback: String = DEFAULT_CREATED_SKILL_DIRECTORY,
): String {
    val normalized = input
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-', '.', '_')
    return normalized.ifBlank { fallback }
}

internal fun requireTopLevelSkillDirectoryName(
    directoryName: String,
    argumentName: String = "Skill directory",
): String {
    require(directoryName.isNotBlank()) { "$argumentName cannot be empty" }
    require(!directoryName.contains('/')) { "$argumentName must not contain path separators" }
    require(!directoryName.contains('\\')) { "$argumentName must not contain path separators" }
    require(directoryName != "." && directoryName != "..") { "$argumentName must not escape the skills root" }
    require(directoryName.none { it.code < 0x20 || it == '\u007f' }) { "$argumentName contains invalid characters" }
    return directoryName
}

internal fun normalizeSkillResourcePath(
    relativePath: String,
    argumentName: String = "Skill resource path",
): String {
    require(relativePath.isNotBlank()) { "$argumentName cannot be empty" }
    require(!relativePath.startsWith('/')) { "$argumentName must be relative" }
    require(!relativePath.contains('\\')) { "$argumentName must use forward slashes" }
    require(!Regex("^[A-Za-z]:").containsMatchIn(relativePath)) { "$argumentName must be relative" }
    require(relativePath.none { it.code < 0x20 || it == '\u007f' }) { "$argumentName contains invalid characters" }

    val segments = relativePath.split('/')
        .filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "$argumentName cannot be empty" }
    require(segments.none { it == "." || it == ".." }) { "$argumentName must stay inside the skill directory" }

    val normalized = segments.joinToString("/")
    require(normalized != SKILL_PACKAGE_FILE_NAME) { "$argumentName must not reference $SKILL_PACKAGE_FILE_NAME" }
    require(normalized != SKILL_SOURCE_METADATA_FILE_NAME) {
        "$argumentName must not reference $SKILL_SOURCE_METADATA_FILE_NAME"
    }
    return normalized
}

internal fun normalizeSkillScriptPath(
    relativePath: String,
    argumentName: String = "Skill script path",
): String {
    val normalized = normalizeSkillResourcePath(
        relativePath = relativePath,
        argumentName = argumentName,
    )
    require(normalized.startsWith("scripts/")) { "$argumentName must live under scripts/" }
    require(
        normalized.endsWith(".sh") || normalized.endsWith(".py")
    ) { "$argumentName must be a .sh or .py script" }
    return normalized
}

internal fun normalizeSkillScriptArguments(
    args: List<String>,
    argumentName: String = "Skill script arguments",
): List<String> {
    require(args.size <= SKILL_SCRIPT_MAX_ARGS) {
        "$argumentName cannot exceed $SKILL_SCRIPT_MAX_ARGS items"
    }
    return args.mapIndexed { index, arg ->
        require(arg.length <= SKILL_SCRIPT_MAX_ARG_CHARS) {
            "$argumentName[$index] exceeds $SKILL_SCRIPT_MAX_ARG_CHARS characters"
        }
        require(arg.none { it.code < 0x20 || it == '\u007f' }) {
            "$argumentName[$index] contains invalid characters"
        }
        arg
    }
}

internal fun resolveSkillScriptInterpreter(relativePath: String): String {
    return when {
        relativePath.endsWith(".sh") -> "bash"
        relativePath.endsWith(".py") -> "python"
        else -> error("Unsupported skill script interpreter")
    }
}

internal fun isCanonicalBundledMetadata(
    metadata: SkillSourceMetadata?,
    directoryName: String,
): Boolean {
    return metadata?.sourceType == SkillSourceType.BUNDLED && metadata.sourceId == directoryName
}

internal fun normalizeCatalogSkillSourceMetadata(
    directoryName: String,
    metadata: SkillSourceMetadata?,
): SkillSourceMetadata? {
    if (metadata == null) return null
    if (metadata.sourceType != SkillSourceType.BUNDLED) return metadata
    if (metadata.sourceId == directoryName) return metadata
    return SkillSourceMetadata(
        sourceType = SkillSourceType.LOCAL,
        sourceId = directoryName,
        sourceUrl = metadata.sourceUrl,
        version = metadata.version,
        installedAt = metadata.installedAt,
    )
}

internal fun List<Assistant>.replaceSelectedSkillDirectory(
    fromDirectoryName: String,
    toDirectoryName: String,
): List<Assistant> {
    return map { assistant ->
        if (fromDirectoryName !in assistant.selectedSkills) {
            assistant
        } else {
            assistant.copy(
                selectedSkills = assistant.selectedSkills
                    .toMutableSet()
                    .apply {
                        remove(fromDirectoryName)
                        add(toDirectoryName)
                    },
            )
        }
    }
}

internal fun List<Assistant>.removeSelectedSkillDirectory(
    directoryName: String,
): List<Assistant> {
    return map { assistant ->
        if (directoryName !in assistant.selectedSkills) {
            assistant
        } else {
            assistant.copy(selectedSkills = assistant.selectedSkills - directoryName)
        }
    }
}

private fun String.escapeForSingleQuotedShell(): String = replace("'", "'\"'\"'")

private fun String.escapeForDoubleQuotedYaml(): String {
    return buildString(length + 8) {
        for (char in this@escapeForDoubleQuotedYaml) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

internal fun SkillsCatalogState.toRefreshingCatalogState(
    workdir: String,
    rootPath: String,
): SkillsCatalogState {
    val shouldKeepCachedEntries = this.workdir == workdir && this.rootPath == rootPath
    return copy(
        workdir = workdir,
        rootPath = rootPath,
        entries = if (shouldKeepCachedEntries) entries else emptyList(),
        invalidEntries = if (shouldKeepCachedEntries) invalidEntries else emptyList(),
        isLoading = true,
        error = null,
    )
}

internal fun SkillsCatalogState.toMutatingCatalogState(
    workdir: String,
    rootPath: String,
): SkillsCatalogState {
    return copy(
        workdir = workdir,
        rootPath = rootPath,
        isLoading = true,
        error = null,
    )
}

internal fun shouldRefreshCatalogSnapshot(
    state: SkillsCatalogState,
    workdir: String,
    nowMs: Long = System.currentTimeMillis(),
    ttlMs: Long = SKILL_CATALOG_REFRESH_TTL_MS,
): Boolean {
    if (state.refreshedAt == 0L) return true
    if (state.workdir != workdir) return true
    if (state.isLoading) return false
    if (state.error != null) return true
    return nowMs - state.refreshedAt >= ttlMs
}

internal fun buildSkillCommandRequest(
    script: String,
    workdir: String,
    label: String,
    stdin: String? = null,
    timeoutMs: Long = SKILL_COMMAND_TIMEOUT_MS,
): TermuxRunCommandRequest {
    return TermuxRunCommandRequest(
        commandPath = TERMUX_BASH_PATH,
        arguments = listOf("-lc", script),
        workdir = workdir,
        stdin = stdin,
        background = true,
        timeoutMs = timeoutMs,
        label = label,
    )
}

internal suspend fun discoverCatalogEntries(
    directories: List<SkillDirectoryDescriptor>,
    readSkillFile: suspend (String) -> String,
): SkillCatalogDiscoveryResult {
    val validEntries = arrayListOf<SkillCatalogEntry>()
    val invalidEntries = arrayListOf<SkillInvalidEntry>()

    directories.forEach { directory ->
        when (val result = inspectSkillDirectory(directory = directory, readSkillFile = readSkillFile)) {
            is SkillDirectoryInspectionResult.Valid -> validEntries += result.entry
            is SkillDirectoryInspectionResult.Invalid -> invalidEntries += result.entry
        }
    }

    return SkillCatalogDiscoveryResult(
        entries = validEntries,
        invalidEntries = invalidEntries,
    )
}

internal suspend fun inspectSkillDirectory(
    directory: SkillDirectoryDescriptor,
    readSkillFile: suspend (String) -> String,
): SkillDirectoryInspectionResult {
    if (!directory.hasSkillFile) {
        return SkillDirectoryInspectionResult.Invalid(
            SkillInvalidEntry(
                directoryName = directory.directoryName,
                path = directory.path,
                reason = SkillInvalidReason.MissingSkillFile,
            )
        )
    }

    val sourceMetadata = normalizeCatalogSkillSourceMetadata(
        directoryName = directory.directoryName,
        metadata = directory.sourceMetadataPreview
            ?.let(::parseSkillSourceMetadata),
    )
    val previewResult = directory.skillMarkdownPreview?.let(::parseSkillFrontmatter)
    val parsed = when {
        previewResult is SkillFrontmatterParseResult.Success -> previewResult
        previewResult is SkillFrontmatterParseResult.Error &&
            shouldRetrySkillPreviewRead(previewResult.reason) -> {
            runCatching {
                parseSkillFrontmatter(readSkillFile(directory.path))
            }.getOrElse { error ->
                return SkillDirectoryInspectionResult.Invalid(
                    SkillInvalidEntry(
                        directoryName = directory.directoryName,
                        path = directory.path,
                        reason = buildSkillReadFailureReason(error),
                    )
                )
            }
        }

        previewResult != null -> previewResult

        else -> {
            runCatching {
                parseSkillFrontmatter(readSkillFile(directory.path))
            }.getOrElse { error ->
                return SkillDirectoryInspectionResult.Invalid(
                    SkillInvalidEntry(
                        directoryName = directory.directoryName,
                        path = directory.path,
                        reason = buildSkillReadFailureReason(error),
                    )
                )
            }
        }
    }

    return when (parsed) {
        is SkillFrontmatterParseResult.Success -> {
            SkillDirectoryInspectionResult.Valid(
                SkillCatalogEntry(
                    directoryName = directory.directoryName,
                    path = directory.path,
                    name = parsed.frontmatter.name,
                    description = parsed.frontmatter.description,
                    sourceType = sourceMetadata?.sourceType ?: SkillSourceType.LOCAL,
                    sourceId = sourceMetadata?.sourceId,
                    sourceUrl = sourceMetadata?.sourceUrl,
                    version = sourceMetadata?.version ?: parsed.frontmatter.version,
                    author = parsed.frontmatter.author,
                    license = parsed.frontmatter.license,
                    compatibility = parsed.frontmatter.compatibility,
                    allowedTools = parsed.frontmatter.allowedTools,
                    argumentHint = parsed.frontmatter.argumentHint,
                    userInvocable = parsed.frontmatter.userInvocable,
                    modelInvocable = parsed.frontmatter.modelInvocable,
                    isBundled = isCanonicalBundledMetadata(sourceMetadata, directory.directoryName),
                )
            )
        }

        is SkillFrontmatterParseResult.Error -> {
            SkillDirectoryInspectionResult.Invalid(
                SkillInvalidEntry(
                    directoryName = directory.directoryName,
                    path = directory.path,
                    reason = parsed.reason,
                )
            )
        }
    }
}

internal fun buildSkillReadFailureReason(error: Throwable): SkillInvalidReason {
    val detail = error.message ?: error.javaClass.name
    return SkillInvalidReason.FailedToRead(detail)
}

internal fun resolveUpdatedSkillSourceMetadata(
    existingMetadata: SkillSourceMetadata?,
    originalDirectoryName: String,
    finalDirectoryName: String,
): SkillSourceMetadata {
    if (existingMetadata == null) {
        return SkillSourceMetadata(
            sourceType = SkillSourceType.LOCAL,
            sourceId = finalDirectoryName,
        )
    }
    if (existingMetadata.sourceType == SkillSourceType.BUNDLED) {
        return SkillSourceMetadata(
            sourceType = SkillSourceType.LOCAL,
            sourceId = finalDirectoryName,
        )
    }
    return existingMetadata.copy(
        sourceId = existingMetadata.sourceId ?: finalDirectoryName,
        hash = null,
    )
}

internal fun shouldBackfillBundledSkillMetadata(
    installedMetadata: SkillSourceMetadata?,
    installedHash: String?,
    expectedMetadata: SkillSourceMetadata,
): Boolean {
    return installedMetadata == null && installedHash != null && installedHash == expectedMetadata.hash
}

internal fun shouldRefreshBundledSkillInstallation(
    bundledSkill: BundledSkill,
    installedMetadata: SkillSourceMetadata?,
    installedHash: String?,
    expectedMetadata: SkillSourceMetadata,
): Boolean {
    if (installedMetadata == null) return false
    if (!isCanonicalBundledMetadata(installedMetadata, bundledSkill.directoryName)) return false
    if (installedHash == null || installedMetadata.hash == null) return false
    return installedHash == installedMetadata.hash && installedHash != expectedMetadata.hash
}

internal fun shouldConvertBundledSkillInstallationToLocal(
    bundledSkill: BundledSkill,
    installedMetadata: SkillSourceMetadata?,
    installedHash: String?,
    expectedMetadata: SkillSourceMetadata,
): Boolean {
    if (installedMetadata == null) return false
    if (!isCanonicalBundledMetadata(installedMetadata, bundledSkill.directoryName)) return false
    if (installedHash == null || installedMetadata.hash == null) return false
    if (installedHash == expectedMetadata.hash) return false
    return installedHash != installedMetadata.hash
}

internal fun parseSkillMarkdownDocument(markdown: String): SkillMarkdownDocument {
    val normalized = markdown.trimStart()
    val section = extractSkillFrontmatterSection(normalized)
    if (section is SkillFrontmatterSectionResult.Error) {
        error(localizedSkillParseError(section.reason))
    }
    section as SkillFrontmatterSectionResult.Success
    val parsedFrontmatter = parseSkillFrontmatter(normalized)
    val frontmatter = when (parsedFrontmatter) {
        is SkillFrontmatterParseResult.Success -> parsedFrontmatter.frontmatter
        is SkillFrontmatterParseResult.Error -> error(localizedSkillParseError(parsedFrontmatter.reason))
    }
    val body = section.body.trim()

    return SkillMarkdownDocument(
        frontmatter = frontmatter,
        body = body,
    )
}

internal fun buildSkillMarkdown(
    name: String,
    description: String,
    body: String,
    extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
): String {
    require(extras.hasActivationPath()) { "Skill must remain invocable by the user or the model" }
    val resolvedBody = body.trim().ifBlank {
        """
        # Instructions

        Describe when this skill should be used, which files to inspect, and what steps to follow.
        """.trimIndent()
    }
    return buildString {
        appendLine("---")
        appendLine("name: \"${name.escapeForDoubleQuotedYaml()}\"")
        appendLine("description: \"${description.escapeForDoubleQuotedYaml()}\"")
        extras.license?.takeIf { it.isNotBlank() }?.let {
            appendLine("license: \"${it.escapeForDoubleQuotedYaml()}\"")
        }
        extras.compatibility?.takeIf { it.isNotBlank() }?.let {
            appendLine("compatibility: \"${it.escapeForDoubleQuotedYaml()}\"")
        }
        extras.allowedTools?.takeIf { it.isNotBlank() }?.let {
            appendLine("allowed-tools: \"${it.escapeForDoubleQuotedYaml()}\"")
        }
        extras.argumentHint?.takeIf { it.isNotBlank() }?.let {
            appendLine("argument-hint: \"${it.escapeForDoubleQuotedYaml()}\"")
        }
        if (!extras.userInvocable) {
            appendLine("user-invocable: false")
        }
        if (extras.disableModelInvocation) {
            appendLine("disable-model-invocation: true")
        }
        if (extras.metadata.isNotEmpty()) {
            appendLine("metadata:")
            extras.metadata.toSortedMap().forEach { (key, value) ->
                appendLine("  $key: \"${value.escapeForDoubleQuotedYaml()}\"")
            }
        }
        appendLine("---")
        appendLine()
        appendLine(resolvedBody)
        appendLine()
    }
}

private fun localizedSkillParseError(reason: SkillInvalidReason): String {
    return when (reason) {
        SkillInvalidReason.MissingSkillFile -> "Missing $SKILL_PACKAGE_FILE_NAME"
        SkillInvalidReason.MissingYamlFrontmatter -> "$SKILL_PACKAGE_FILE_NAME is missing YAML frontmatter"
        SkillInvalidReason.FrontmatterMustStart -> "$SKILL_PACKAGE_FILE_NAME frontmatter must start with ---"
        SkillInvalidReason.FrontmatterNotClosed -> "$SKILL_PACKAGE_FILE_NAME frontmatter is not closed"
        SkillInvalidReason.MissingName -> "$SKILL_PACKAGE_FILE_NAME frontmatter is missing name"
        SkillInvalidReason.MissingDescription -> "$SKILL_PACKAGE_FILE_NAME frontmatter is missing description"
        SkillInvalidReason.NoActivationPath -> "$SKILL_PACKAGE_FILE_NAME must allow user or model invocation"
        is SkillInvalidReason.FailedToRead -> reason.detail
        is SkillInvalidReason.Other -> reason.message
    }
}

internal fun parseSkillArchive(inputStream: InputStream): ParsedSkillArchive {
    val directories = linkedSetOf<String>()
    val files = arrayListOf<SkillArchiveFile>()
    var totalBytes = 0
    var fileCount = 0

    ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
        while (true) {
            val entry = zipInputStream.nextEntry ?: break
            val normalizedPath = normalizeSkillArchiveEntryPath(entry.name)
            val shouldIgnore = normalizedPath == null || isIgnoredSkillArchiveEntry(normalizedPath)
            if (shouldIgnore) {
                zipInputStream.closeEntry()
                continue
            }

            if (entry.isDirectory) {
                directories += normalizedPath
            } else {
                fileCount += 1
                require(fileCount <= SKILL_ARCHIVE_MAX_FILES) {
                    "Zip archive contains too many files"
                }
                val bytes = readZipEntryBytes(
                    zipInputStream = zipInputStream,
                    normalizedPath = normalizedPath,
                    totalBytesConsumed = totalBytes,
                )
                totalBytes += bytes.size
                files += SkillArchiveFile(
                    path = normalizedPath,
                    bytes = bytes,
                )
                collectParentDirectories(normalizedPath).forEach { directories += it }
            }
            zipInputStream.closeEntry()
        }
    }

    if (files.isEmpty() && directories.isEmpty()) {
        error("Zip archive is empty")
    }

    return collapseSkillArchiveContainerLayers(
        ParsedSkillArchive(
            directories = directories,
            files = files,
        )
    )
}

private fun readZipEntryBytes(
    zipInputStream: ZipInputStream,
    normalizedPath: String,
    totalBytesConsumed: Int,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var entryBytes = 0
    var totalBytes = totalBytesConsumed
    while (true) {
        val read = zipInputStream.read(buffer)
        if (read <= 0) break
        entryBytes += read
        totalBytes += read
        require(entryBytes <= SKILL_ARCHIVE_SINGLE_FILE_BYTES_LIMIT) {
            "Zip entry is too large: $normalizedPath"
        }
        require(totalBytes <= SKILL_ARCHIVE_MAX_TOTAL_BYTES) {
            "Zip archive is too large"
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun buildSkillImportPlan(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
    existingDirectoryNames: Set<String> = emptySet(),
): SkillImportPlan {
    if (archive.files.isEmpty() && archive.directories.isEmpty()) {
        error("Zip archive is empty")
    }

    val hasRootLevelContent = archive.files.any { !it.path.contains('/') }
    val remappedFiles: List<SkillArchiveFile>
    val remappedDirectories: Set<String>
    val topLevelDirectories: List<String>

    if (hasRootLevelContent) {
        val desiredRootDirectory = deriveRootImportDirectoryName(
            archive = archive,
            suggestedDirectoryName = suggestedDirectoryName,
        )
        val resolvedRootDirectory = resolveUniqueDirectoryNames(
            desired = listOf(desiredRootDirectory),
            existing = existingDirectoryNames,
        ).getValue(desiredRootDirectory)
        remappedFiles = archive.files.map { file ->
            file.copy(path = "$resolvedRootDirectory/${file.path}")
        }
        remappedDirectories = buildSet {
            add(resolvedRootDirectory)
            archive.directories.forEach { directory ->
                add("$resolvedRootDirectory/$directory")
            }
        }
        topLevelDirectories = listOf(resolvedRootDirectory)
    } else {
        val desiredTopLevelDirectories = archive.topLevelDirectories()
        val mapping = resolveUniqueNormalizedDirectoryNames(
            desired = desiredTopLevelDirectories,
            existing = existingDirectoryNames,
        )
        remappedFiles = archive.files.map { file ->
            file.copy(path = replaceTopLevelDirectory(file.path, mapping))
        }
        remappedDirectories = archive.directories.mapTo(linkedSetOf()) { directory ->
            replaceTopLevelDirectory(directory, mapping)
        }
        topLevelDirectories = desiredTopLevelDirectories.map { mapping.getValue(it) }
    }

    validateUniqueSkillImportPaths(remappedFiles.map { it.path })

    val allDirectories = linkedSetOf<String>()
    allDirectories += topLevelDirectories
    allDirectories += remappedDirectories
    remappedFiles.forEach { file ->
        collectParentDirectories(file.path).forEach { allDirectories += it }
    }

    val hasSkillFile = remappedFiles.any { file ->
        file.path.endsWith("/$SKILL_PACKAGE_FILE_NAME") && file.path.count { it == '/' } == 1
    }
    if (!hasSkillFile) {
        error("Zip package must contain $SKILL_PACKAGE_FILE_NAME at the root of a skill directory")
    }

    return SkillImportPlan(
        topLevelDirectories = topLevelDirectories.distinct(),
        directories = allDirectories,
        files = remappedFiles.sortedBy { it.path },
    )
}

internal fun buildSkillImportPreview(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
    existingDirectoryNames: Set<String> = emptySet(),
    archiveName: String? = null,
): SkillImportPreviewData {
    val plan = buildSkillImportPlan(
        archive = archive,
        suggestedDirectoryName = suggestedDirectoryName,
        existingDirectoryNames = existingDirectoryNames,
    )
    val entries = plan.topLevelDirectories.map { directoryName ->
        val skillFilePath = "$directoryName/$SKILL_PACKAGE_FILE_NAME"
        val skillFile = plan.files.firstOrNull { it.path == skillFilePath }
            ?: error("Zip package must contain $SKILL_PACKAGE_FILE_NAME at the root of a skill directory")
        val frontmatter = when (val parsed = parseSkillFrontmatter(skillFile.bytes.toString(Charsets.UTF_8))) {
            is SkillFrontmatterParseResult.Success -> parsed.frontmatter
            is SkillFrontmatterParseResult.Error -> error("Invalid $skillFilePath: ${localizedSkillParseError(parsed.reason)}")
        }
        val filesForSkill = plan.files.filter { it.path.startsWith("$directoryName/") }
        SkillImportPreviewEntry(
            directoryName = directoryName,
            name = frontmatter.name,
            description = frontmatter.description,
            sourceId = buildImportedSkillSourceId(
                directoryName = directoryName,
                skillName = frontmatter.name,
                archiveName = archiveName,
            ),
            version = frontmatter.version,
            author = frontmatter.author,
            license = frontmatter.license,
            compatibility = frontmatter.compatibility,
            allowedTools = frontmatter.allowedTools,
            packageHash = computeSkillPackageHash(filesForSkill),
            scriptFiles = filesForSkill.count { it.path.startsWith("$directoryName/scripts/") },
            referenceFiles = filesForSkill.count { it.path.startsWith("$directoryName/references/") },
            assetFiles = filesForSkill.count { it.path.startsWith("$directoryName/assets/") },
            scriptPaths = filesForSkill
                .filter { it.path.startsWith("$directoryName/scripts/") }
                .map { it.path.removePrefix("$directoryName/") }
                .take(SKILL_IMPORT_PREVIEW_SCRIPT_LIST_LIMIT),
        )
    }
    val preview = SkillImportPreview(
        archiveName = archiveName,
        directories = plan.topLevelDirectories,
        totalFiles = plan.files.size,
        scriptFiles = entries.sumOf { it.scriptFiles },
        referenceFiles = entries.sumOf { it.referenceFiles },
        assetFiles = entries.sumOf { it.assetFiles },
        hasScripts = entries.any { it.scriptFiles > 0 },
        entries = entries,
    )
    return SkillImportPreviewData(
        plan = plan,
        preview = preview,
    )
}

internal fun resolveUniqueDirectoryNames(
    desired: List<String>,
    existing: Set<String>,
): Map<String, String> {
    val reserved = existing.toMutableSet()
    val resolved = linkedMapOf<String, String>()

    desired.distinct().forEach { original ->
        val baseName = original.ifBlank { DEFAULT_IMPORTED_SKILL_DIRECTORY }
        var candidate = baseName
        var suffix = 2
        while (candidate in reserved) {
            candidate = "$baseName-$suffix"
            suffix += 1
        }
        reserved += candidate
        resolved[original] = candidate
    }

    return resolved
}

internal fun resolveUniqueNormalizedDirectoryNames(
    desired: List<String>,
    existing: Set<String>,
): Map<String, String> {
    val reserved = existing.toMutableSet()
    val resolved = linkedMapOf<String, String>()

    desired.distinct().forEach { original ->
        val baseName = sanitizeSkillDirectoryName(
            input = original,
            fallback = DEFAULT_IMPORTED_SKILL_DIRECTORY,
        )
        var candidate = baseName
        var suffix = 2
        while (candidate in reserved) {
            candidate = "$baseName-$suffix"
            suffix += 1
        }
        reserved += candidate
        resolved[original] = candidate
    }

    return resolved
}

internal fun validateUniqueSkillImportPaths(
    paths: List<String>,
) {
    val exactCollisions = paths.groupBy { it }.filterValues { it.size > 1 }.keys
    require(exactCollisions.isEmpty()) {
        "Zip archive contains duplicate paths: ${exactCollisions.first()}"
    }

    val caseInsensitiveCollisions = paths
        .groupBy { it.lowercase() }
        .filterValues { it.mapTo(linkedSetOf()) { path -> path }.size > 1 }
        .values
        .firstOrNull()
    val conflictingPaths = caseInsensitiveCollisions?.joinToString(", ")
    require(caseInsensitiveCollisions == null) {
        "Zip archive contains conflicting paths: $conflictingPaths"
    }
}

internal fun collapseSkillArchiveContainerLayers(archive: ParsedSkillArchive): ParsedSkillArchive {
    var currentArchive = archive
    while (true) {
        if (currentArchive.files.isEmpty()) break
        if (currentArchive.files.any { !it.path.contains('/') }) break

        val topLevelDirectories = currentArchive.topLevelDirectories()
        if (topLevelDirectories.size != 1) break

        val container = topLevelDirectories.single()
        val containsTopLevelSkillFile = currentArchive.files.any { it.path == "$container/$SKILL_PACKAGE_FILE_NAME" }
        if (containsTopLevelSkillFile) break

        currentArchive = ParsedSkillArchive(
            directories = currentArchive.directories.mapNotNullTo(linkedSetOf()) { stripLeadingDirectory(it) },
            files = currentArchive.files.map { file ->
                file.copy(path = stripLeadingDirectory(file.path) ?: file.path)
            },
        )
    }
    return currentArchive
}

internal fun normalizeSkillArchiveEntryPath(path: String): String? {
    val slashNormalized = path.replace('\\', '/').trim()
    if (slashNormalized.startsWith('/')) error("Zip entry path must be relative: $path")
    val trimmed = slashNormalized
        .trim('/')
        .removePrefix("./")
    if (trimmed.isBlank()) return null
    if (Regex("^[A-Za-z]:").containsMatchIn(trimmed)) error("Zip entry path must be relative: $path")

    val segments = trimmed.split('/')
    if (segments.any { segment ->
            segment.isBlank() ||
                segment == "." ||
                segment == ".." ||
                segment.any { char -> char.code < 0x20 || char == '\u007f' }
        }
    ) {
        error("Zip entry contains an invalid path: $path")
    }
    require(segments.size <= SKILL_ARCHIVE_MAX_DEPTH) {
        "Zip entry is nested too deeply: $path"
    }
    return segments.joinToString("/")
}

internal fun isIgnoredSkillArchiveEntry(path: String): Boolean {
    val segments = path.split('/')
    val fileName = segments.lastOrNull().orEmpty()
    return segments.any { it == "__MACOSX" } ||
        fileName == ".DS_Store" ||
        fileName == "Thumbs.db" ||
        fileName.startsWith("._")
}

internal fun collectParentDirectories(path: String): List<String> {
    val segments = path.split('/')
    if (segments.size <= 1) return emptyList()
    return buildList {
        for (index in 1 until segments.lastIndex) {
            add(segments.take(index).joinToString("/"))
        }
        add(segments.dropLast(1).joinToString("/"))
    }.distinct()
}

private fun ParsedSkillArchive.topLevelDirectories(): List<String> {
    return buildSet {
        files.forEach { add(it.path.substringBefore('/')) }
        directories.forEach { add(it.substringBefore('/')) }
    }.sorted()
}

private fun replaceTopLevelDirectory(
    path: String,
    mapping: Map<String, String>,
): String {
    val firstSegment = path.substringBefore('/')
    val remainder = path.substringAfter('/', missingDelimiterValue = "")
    val replaced = mapping[firstSegment] ?: firstSegment
    return if (remainder.isBlank()) replaced else "$replaced/$remainder"
}

private fun stripLeadingDirectory(path: String): String? {
    val slashIndex = path.indexOf('/')
    return if (slashIndex < 0) null else path.substring(slashIndex + 1)
}

private fun deriveRootImportDirectoryName(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
): String {
    suggestedDirectoryName?.takeIf { it.isNotBlank() }?.let { return it }

    val rootSkillFile = archive.files.firstOrNull { it.path == SKILL_PACKAGE_FILE_NAME }
    if (rootSkillFile != null) {
        val parsed = parseSkillFrontmatter(rootSkillFile.bytes.toString(Charsets.UTF_8))
        if (parsed is SkillFrontmatterParseResult.Success) {
            return sanitizeSkillDirectoryName(
                input = parsed.frontmatter.name,
                fallback = DEFAULT_IMPORTED_SKILL_DIRECTORY,
            )
        }
    }

    return DEFAULT_IMPORTED_SKILL_DIRECTORY
}

internal fun readBundledSkillFiles(
    context: Context,
    assetPath: String,
): List<SkillArchiveFile> {
    val result = arrayListOf<SkillArchiveFile>()

    fun walk(currentAssetPath: String, relativePrefix: String = "") {
        val children = context.assets.list(currentAssetPath)
        if (children.isNullOrEmpty()) {
            context.assets.open(currentAssetPath).use { inputStream ->
                result += SkillArchiveFile(
                    path = relativePrefix,
                    bytes = inputStream.readBytes(),
                )
            }
            return
        }

        children.sorted().forEach { child ->
            val childAssetPath = "$currentAssetPath/$child"
            val childRelativePath = if (relativePrefix.isBlank()) child else "$relativePrefix/$child"
            walk(
                currentAssetPath = childAssetPath,
                relativePrefix = childRelativePath,
            )
        }
    }

    walk(assetPath)
    return result.sortedBy { it.path }
}

private sealed interface SkillFrontmatterSectionResult {
    data class Success(
        val yaml: String,
        val body: String,
    ) : SkillFrontmatterSectionResult

    data class Error(val reason: SkillInvalidReason) : SkillFrontmatterSectionResult
}

private fun extractSkillFrontmatterSection(markdown: String): SkillFrontmatterSectionResult {
    val normalized = markdown.trimStart()
    if (!normalized.startsWith("---")) {
        return SkillFrontmatterSectionResult.Error(SkillInvalidReason.MissingYamlFrontmatter)
    }

    val lines = normalized.lineSequence().toList()
    if (lines.isEmpty() || lines.first().trim() != "---") {
        return SkillFrontmatterSectionResult.Error(SkillInvalidReason.FrontmatterMustStart)
    }

    val endIndex = lines.drop(1)
        .indexOfFirst { it.trim() == "---" }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: return SkillFrontmatterSectionResult.Error(SkillInvalidReason.FrontmatterNotClosed)

    return SkillFrontmatterSectionResult.Success(
        yaml = lines.subList(1, endIndex).joinToString("\n"),
        body = lines.drop(endIndex + 1).joinToString("\n"),
    )
}

private fun Map<*, *>.stringValue(key: String): String? {
    return (this[key] as? String)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun Map<*, *>.stringLikeValue(key: String): String? {
    val value = this[key] ?: return null
    return value.toPromptString()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun Map<*, *>.booleanValue(key: String): Boolean? {
    val value = this[key] ?: return null
    return when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase().let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

        else -> null
    }
}

private fun Map<*, *>.stringMap(key: String): Map<String, String> {
    val value = this[key] as? Map<*, *> ?: return emptyMap()
    return value.entries.mapNotNull { (entryKey, entryValue) ->
        val normalizedKey = (entryKey as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val normalizedValue = entryValue?.toPromptString()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        normalizedKey to normalizedValue
    }.toMap()
}

private fun Any.toPromptString(): String? {
    return when (this) {
        is String -> this
        is Number, is Boolean -> toString()
        is Collection<*> -> joinToString(", ") { it?.toPromptString().orEmpty() }
            .takeIf { it.isNotBlank() }

        is Map<*, *> -> entries.joinToString(", ") { (key, value) ->
            "${key?.toString().orEmpty()}=${value?.toPromptString().orEmpty()}"
        }.takeIf { it.isNotBlank() }

        else -> toString()
    }
}

private fun parseSkillSourceMetadata(raw: String): SkillSourceMetadata? {
    return runCatching {
        skillSourceMetadataJson.decodeFromString<SkillSourceMetadata>(raw)
    }.getOrNull()?.takeIf { it.schemaVersion <= SKILL_SOURCE_METADATA_SCHEMA_VERSION }
}

private fun computeSkillPackageHash(files: List<SkillArchiveFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.path }.forEach { file ->
        digest.update(file.path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(file.bytes)
        digest.update(0)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}

private fun shouldRetrySkillPreviewRead(reason: SkillInvalidReason): Boolean {
    return reason == SkillInvalidReason.FrontmatterNotClosed ||
        reason == SkillInvalidReason.MissingName ||
        reason == SkillInvalidReason.MissingDescription
}

private fun buildImportedSkillSourceMetadata(
    directoryName: String,
    previewEntry: SkillImportPreviewEntry?,
    archiveName: String?,
): SkillSourceMetadata {
    return SkillSourceMetadata(
        sourceType = SkillSourceType.IMPORTED,
        sourceId = previewEntry?.sourceId ?: buildImportedSkillSourceId(
            directoryName = directoryName,
            skillName = directoryName,
            archiveName = archiveName,
        ),
        version = previewEntry?.version,
        hash = previewEntry?.packageHash,
    )
}

private fun buildImportedSkillSourceId(
    directoryName: String,
    skillName: String,
    archiveName: String?,
): String {
    return sanitizeSkillDirectoryName(
        input = skillName.ifBlank {
            archiveName?.substringBeforeLast('.', archiveName)?.ifBlank { directoryName } ?: directoryName
        },
        fallback = directoryName,
    )
}
