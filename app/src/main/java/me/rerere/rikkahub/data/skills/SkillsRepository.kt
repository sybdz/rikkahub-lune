package me.rerere.rikkahub.data.skills

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
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
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

private const val TAG = "SkillsRepository"
private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
private const val SKILL_COMMAND_TIMEOUT_MS = 30_000L
private const val SKILL_WRITE_TIMEOUT_MS = 60_000L
private const val SKILL_SINGLE_WRITE_BYTES_LIMIT = 192 * 1024
private const val SKILL_CHUNK_WRITE_BYTES = 48 * 1024
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
private const val SKILL_SOURCE_METADATA_SCHEMA_VERSION = 1

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
    val version: String? = null,
    val author: String? = null,
    val scriptFiles: Int = 0,
    val referenceFiles: Int = 0,
    val assetFiles: Int = 0,
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

    suspend fun createSkill(
        directoryName: String,
        name: String,
        description: String,
        body: String,
        extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
    ): SkillCreationResult {
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }

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
        require(originalDirectoryName.isNotBlank()) { "Original skill directory cannot be empty" }
        require(name.isNotBlank()) { "Skill name cannot be empty" }
        require(description.isNotBlank()) { "Skill description cannot be empty" }

        return runCatalogMutation { workdir, rootPath ->
            ensureSkillsRootDirectory(rootPath = rootPath, workdir = workdir)
            val existingDirectoryNames = listSkillDirectories(rootPath, workdir)
                .mapTo(linkedSetOf()) { it.directoryName }

            val finalDirectoryName = sanitizeSkillDirectoryName(
                input = directoryName.ifBlank { name },
                fallback = originalDirectoryName,
            )
            val conflictingDirectories = existingDirectoryNames - originalDirectoryName
            require(finalDirectoryName !in conflictingDirectories) {
                "Skill directory already exists: $finalDirectoryName"
            }

            if (finalDirectoryName != originalDirectoryName) {
                runSkillScript(
                    script = buildMoveSkillDirectoryScript(
                        rootPath = rootPath,
                        fromDirectoryName = originalDirectoryName,
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
            ) ?: SkillSourceMetadata(
                sourceType = SkillSourceType.LOCAL,
                sourceId = finalDirectoryName,
            )
            writeSkillSourceMetadata(
                rootPath = rootPath,
                workdir = workdir,
                directoryName = finalDirectoryName,
                metadata = sourceMetadata.copy(sourceId = sourceMetadata.sourceId ?: finalDirectoryName),
            )

            SkillCreationResult(
                directoryName = finalDirectoryName,
                path = "$rootPath/$finalDirectoryName",
            )
        }
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

            importPlan.directories
                .sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it })
                .forEach { relativeDirectory ->
                    createSkillDirectory(
                        rootPath = rootPath,
                        workdir = workdir,
                        relativeDirectory = relativeDirectory,
                    )
                }

            importPlan.files.forEach { file ->
                writeSkillFile(
                    rootPath = rootPath,
                    workdir = workdir,
                    relativePath = file.path,
                    bytes = file.bytes,
                )
            }
            importPlan.topLevelDirectories.forEach { directoryName ->
                writeSkillSourceMetadata(
                    rootPath = rootPath,
                    workdir = workdir,
                    directoryName = directoryName,
                    metadata = SkillSourceMetadata(
                        sourceType = SkillSourceType.IMPORTED,
                        sourceId = archiveName?.substringBeforeLast('.', archiveName)?.ifBlank { directoryName } ?: directoryName,
                    ),
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
        require(directoryName.isNotBlank()) { "Skill directory cannot be empty" }

        runCatalogMutation { workdir, rootPath ->
            val metadata = readSkillSourceMetadata(
                directoryPath = "$rootPath/$directoryName",
                workdir = workdir,
            )
            require(metadata?.sourceType != SkillSourceType.BUNDLED) {
                "Built-in skills cannot be deleted: $directoryName"
            }
            runSkillScript(
                script = buildDeleteSkillScript(
                    rootPath = rootPath,
                    directoryName = directoryName,
                ),
                workdir = workdir,
                label = "RikkaHub delete local skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
    }

    suspend fun loadSkillActivations(directoryNames: Collection<String>): List<SkillActivationEntry> {
        if (directoryNames.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val settings = settingsStore.settingsFlow.value
            require(!settings.init) { "Settings are not ready" }
            val entriesByDirectory = state.value.entries.associateBy { it.directoryName }
            directoryNames.distinct()
                .mapNotNull { directoryName -> entriesByDirectory[directoryName] }
                .map { entry ->
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
    }

    private suspend fun refreshLocked(workdir: String) {
        val rootPath = buildSkillsRootPath(workdir)
        _state.value = _state.value.toRefreshingCatalogState(
            workdir = workdir,
            rootPath = rootPath,
        )
        _state.value = runCatching {
            discover(workdir = workdir, rootPath = rootPath)
        }.getOrElse { error ->
            Log.w(TAG, "refresh failed for $rootPath", error)
            SkillsCatalogState(
                workdir = workdir,
                rootPath = rootPath,
                entries = emptyList(),
                invalidEntries = emptyList(),
                isLoading = false,
                error = error.message ?: error.javaClass.name,
                refreshedAt = System.currentTimeMillis(),
            )
        }
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
                    _state.value = _state.value.copy(
                        workdir = workdir,
                        rootPath = rootPath,
                        isLoading = false,
                        error = error.message ?: error.javaClass.name,
                        refreshedAt = System.currentTimeMillis(),
                    )
                    throw error
                }
            }
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

            val installedMetadata = runCatching {
                readSkillSourceMetadata(
                    directoryPath = "$rootPath/${bundledSkill.directoryName}",
                    workdir = workdir,
                )
            }.getOrNull()
            val shouldRefreshBundledSkill = installedMetadata?.sourceType == SkillSourceType.BUNDLED &&
                installedMetadata.sourceId == bundledSkill.directoryName &&
                installedMetadata.hash != expectedMetadata.hash
            if (!shouldRefreshBundledSkill) return@forEach
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
        if (replaceExisting) {
            runSkillScript(
                script = buildDeleteSkillScript(
                    rootPath = rootPath,
                    directoryName = bundledSkill.directoryName,
                ),
                workdir = workdir,
                label = "RikkaHub replace bundled skill",
                timeoutMs = SKILL_WRITE_TIMEOUT_MS,
            )
        }
        files.sortedBy { it.path }.forEach { assetFile ->
            writeSkillFile(
                rootPath = rootPath,
                workdir = workdir,
                relativePath = "${bundledSkill.directoryName}/${assetFile.path}",
                bytes = assetFile.bytes,
            )
        }
        writeSkillSourceMetadata(
            rootPath = rootPath,
            workdir = workdir,
            directoryName = bundledSkill.directoryName,
            metadata = metadata,
        )
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
            mkdir -p "${'$'}DIR" "${'$'}DIR/scripts" "${'$'}DIR/assets" "${'$'}DIR/references"
            cat > "${'$'}DIR/$SKILL_PACKAGE_FILE_NAME"
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
            TMP_FILE="${'$'}TMP_DIR/$(basename "${'$'}REL").${'$'}$.tmp"
            base64 -d > "${'$'}TMP_FILE"
            mv -f "${'$'}TMP_FILE" "${'$'}TARGET"
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

    return SkillFrontmatterParseResult.Success(
        SkillFrontmatter(
            name = name,
            description = description,
            extras = SkillFrontmatterExtras(
                license = values.stringLikeValue("license"),
                compatibility = values.stringLikeValue("compatibility"),
                allowedTools = values.stringLikeValue("allowed-tools"),
                argumentHint = values.stringLikeValue("argument-hint"),
                userInvocable = values.booleanValue("user-invocable") ?: true,
                disableModelInvocation = values.booleanValue("disable-model-invocation") ?: false,
                metadata = metadata,
            ),
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

    val sourceMetadata = directory.sourceMetadataPreview
        ?.let(::parseSkillSourceMetadata)
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
                    isBundled = sourceMetadata?.sourceType == SkillSourceType.BUNDLED,
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
                val bytes = zipInputStream.readBytes()
                require(bytes.size <= SKILL_ARCHIVE_SINGLE_FILE_BYTES_LIMIT) {
                    "Zip entry is too large: $normalizedPath"
                }
                totalBytes += bytes.size
                require(totalBytes <= SKILL_ARCHIVE_MAX_TOTAL_BYTES) {
                    "Zip archive is too large"
                }
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
        val mapping = resolveUniqueDirectoryNames(
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
            version = frontmatter.version,
            author = frontmatter.author,
            scriptFiles = filesForSkill.count { it.path.startsWith("$directoryName/scripts/") },
            referenceFiles = filesForSkill.count { it.path.startsWith("$directoryName/references/") },
            assetFiles = filesForSkill.count { it.path.startsWith("$directoryName/assets/") },
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
    }.getOrNull()
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
