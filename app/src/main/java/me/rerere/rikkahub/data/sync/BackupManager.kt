package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlowRaw.first()
            val paths = BackupFilePaths(
                context.filesDir,
                File(File(context.applicationInfo.dataDir).parentFile, "${BackupFilePaths.UPSTREAM_PACKAGE}/files"),
                json,
            )
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                val settingsJson = json.encodeToString(settings)
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(paths.relocateJson(settingsJson).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(UpstreamBackupCompatibility.NATIVE_SETTINGS))
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, UpstreamBackupCompatibility.NATIVE_DATABASE)
                    DatabaseBackup.prepareForUpstream(context, snapshot, paths)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS + BackupFilePaths.LEGACY_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS || folder in BackupFilePaths.LEGACY_FOLDERS) {
                            directory.walkTopDown().asSequence()
                        } else {
                            directory.listFiles().orEmpty().asSequence()
                        }
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            val name = "$folder/$relative"
                            addFile(zip, file, BackupFilePaths.archivePath(name))
                            if (folder in BackupFilePaths.LEGACY_FOLDERS) {
                                addFile(zip, file, UpstreamBackupCompatibility.NATIVE_FILES + name)
                            }
                        }
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        val nativeDatabase = hasFile(zip, UpstreamBackupCompatibility.NATIVE_DATABASE)
                        val nativeSettings = hasFile(zip, UpstreamBackupCompatibility.NATIVE_SETTINGS)
                        // Restore the originals, not a second copy of the same legacy attachments in upload/.
                        val portableCopies = if (includeFiles) {
                            zip.entries().asSequence()
                                .filter { !it.isDirectory && isNativeAttachment(it.name) }
                                .map { BackupFilePaths.archivePath(it.name.removePrefix(UpstreamBackupCompatibility.NATIVE_FILES)) }
                                .toSet()
                        } else {
                            emptySet()
                        }
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> if (!nativeSettings) File(staging, "settings.json") else null
                                UpstreamBackupCompatibility.NATIVE_SETTINGS -> File(staging, "settings.json")
                                UpstreamBackupCompatibility.NATIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase && !nativeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase && !nativeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isNativeAttachment(entry.name)) {
                                    PendingRestore.resolveInside(
                                        File(payload, "files"),
                                        entry.name.removePrefix(UpstreamBackupCompatibility.NATIVE_FILES),
                                    )
                                } else if (includeFiles && entry.name !in portableCopies && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun isNativeAttachment(name: String): Boolean {
        if (!name.startsWith(UpstreamBackupCompatibility.NATIVE_FILES)) return false
        val relative = name.removePrefix(UpstreamBackupCompatibility.NATIVE_FILES)
        return '/' in relative && relative.substringBefore('/') in BackupFilePaths.LEGACY_FOLDERS
    }

    private fun hasFile(zip: ZipFile, name: String): Boolean = zip.getEntry(name)?.let {
        require(!it.isDirectory) { "Invalid backup entry: $name" }
        true
    } ?: false

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
