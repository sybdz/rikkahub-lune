package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillImportPreview
import me.rerere.rikkahub.data.skills.SkillSourceType
import me.rerere.rikkahub.data.skills.SkillsCatalogState
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

private data class PendingSkillImport(
    val uri: Uri,
    val displayName: String?,
    val preview: SkillImportPreview,
)

@Composable
fun SkillsPickerButton(
    assistant: Assistant,
    modelSupportsTools: Boolean,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    val skillsRepository = koinInject<SkillsRepository>()
    val skillsState by skillsRepository.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val enabledCount = if (assistant.skillsEnabled) {
        assistant.selectedSkills.count { it in skillsState.entryNames }
    } else {
        0
    }

    LaunchedEffect(showPicker) {
        if (showPicker && !skillsState.isLoading && skillsState.refreshedAt == 0L) {
            skillsRepository.requestRefresh()
        }
    }

    ToggleSurface(
        modifier = modifier,
        checked = assistant.skillsEnabled,
        onClick = {
            showPicker = true
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (skillsState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    BadgedBox(
                        badge = {
                            if (enabledCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text(text = enabledCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Puzzle,
                            contentDescription = stringResource(R.string.assistant_page_tab_skills),
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_tab_skills),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                SkillsPicker(
                    assistant = assistant,
                    skillsState = skillsState,
                    modelSupportsTools = modelSupportsTools,
                    onRefresh = { skillsRepository.requestRefresh(force = true) },
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
fun SkillsPicker(
    assistant: Assistant,
    skillsState: SkillsCatalogState,
    modelSupportsTools: Boolean,
    onRefresh: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val skillsRepository = koinInject<SkillsRepository>()
    val termuxToolEnabled = assistant.localTools.contains(LocalToolOption.TermuxExec)
    val missingSelections = remember(assistant.selectedSkills, skillsState.entryNames) {
        assistant.selectedSkills
            .filterNot { it in skillsState.entryNames }
            .sorted()
    }
    val resolvedRootPath = if (skillsState.rootPath.isBlank()) {
        stringResource(R.string.assistant_page_skills_root_unresolved)
    } else {
        skillsState.rootPath
    }
    val fallbackRootPath = if (skillsState.rootPath.isBlank()) {
        stringResource(R.string.assistant_page_skills_root_fallback)
    } else {
        skillsState.rootPath
    }
    val builtInSectionTitle = stringResource(R.string.assistant_page_skills_section_built_in)
    val importedSectionTitle = stringResource(R.string.assistant_page_skills_section_imported)
    val localSectionTitle = stringResource(R.string.assistant_page_skills_section_local)

    var showCreateDialog by remember { mutableStateOf(false) }
    var createDraft by remember { mutableStateOf(emptySkillEditorDraft()) }
    var isCreating by remember { mutableStateOf(false) }
    var isLoadingImportPreview by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingSkillImport?>(null) }
    var editDocument by remember { mutableStateOf<SkillEditorDocument?>(null) }
    var isLoadingEditor by remember { mutableStateOf(false) }
    var isSavingEditor by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<SkillCatalogEntry?>(null) }
    var relinkMissingDirectoryName by remember { mutableStateOf<String?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var showInvalidEntries by remember(skillsState.invalidEntries) {
        mutableStateOf(skillsState.invalidEntries.isNotEmpty())
    }
    val actionInProgress = isCreating || isLoadingImportPreview || isImporting || isLoadingEditor || isSavingEditor || isDeleting
    val builtInEntries = remember(skillsState.entries) {
        skillsState.entries.filter { it.isBundled }
    }
    val importedEntries = remember(skillsState.entries) {
        skillsState.entries.filter { !it.isBundled && it.sourceType == SkillSourceType.IMPORTED }
    }
    val localEntries = remember(skillsState.entries) {
        skillsState.entries.filter { !it.isBundled && it.sourceType != SkillSourceType.IMPORTED }
    }
    val selectedExistingEntries = remember(assistant.selectedSkills, skillsState.entries) {
        skillsState.entries.filter { it.directoryName in assistant.selectedSkills }
    }
    val autoSelectedCount = remember(selectedExistingEntries) {
        selectedExistingEntries.count { it.modelInvocable }
    }
    val manualOnlySelectedCount = remember(selectedExistingEntries) {
        selectedExistingEntries.count { !it.modelInvocable && it.userInvocable }
    }

    fun resetCreateDialog() {
        createDraft = emptySkillEditorDraft()
    }

    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                isLoadingImportPreview = true
                try {
                    val displayName = queryDisplayName(context, selectedUri)
                    val preview = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { inputStream ->
                            skillsRepository.previewSkillZip(
                                inputStream = inputStream,
                                archiveName = displayName,
                            )
                        } ?: error(resources.getString(R.string.assistant_page_skills_import_failed))
                    }
                    pendingImport = PendingSkillImport(
                        uri = selectedUri,
                        displayName = displayName,
                        preview = preview,
                    )
                } catch (error: Throwable) {
                    toaster.show(
                        error.message ?: resources.getString(R.string.assistant_page_skills_import_failed),
                        type = ToastType.Error,
                    )
                } finally {
                    isLoadingImportPreview = false
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("controls") {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_page_skills_enable_catalog_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_enable_catalog_desc,
                                    resolvedRootPath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = assistant.skillsEnabled,
                            onCheckedChange = {
                                onUpdateAssistant(assistant.copy(skillsEnabled = it))
                            },
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.assistant_page_skills_action_desc,
                            fallbackRootPath,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !actionInProgress,
                            onClick = {
                                resetCreateDialog()
                                showCreateDialog = true
                            },
                        ) {
                            Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Box(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isCreating) {
                                    stringResource(R.string.assistant_page_skills_create_in_progress)
                                } else {
                                    stringResource(R.string.assistant_page_skills_create)
                                }
                            )
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !actionInProgress,
                            onClick = {
                                zipImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ) {
                            Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Box(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isLoadingImportPreview || isImporting) {
                                    stringResource(R.string.assistant_page_skills_import_in_progress)
                                } else {
                                    stringResource(R.string.assistant_page_skills_import_zip)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_selected_count,
                                    assistant.selectedSkills.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_selected_breakdown,
                                    autoSelectedCount,
                                    manualOnlySelectedCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !actionInProgress,
                            onClick = onRefresh,
                        ) {
                            Text(stringResource(R.string.webview_page_refresh))
                        }
                    }
                }
            }
        }

        if (!termuxToolEnabled) {
            item("termux-warning") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_termux_required_title),
                    text = stringResource(R.string.assistant_page_skills_termux_required_desc),
                    isError = true,
                )
            }
        }

        if (!modelSupportsTools) {
            item("model-warning") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_model_unsupported_title),
                    text = stringResource(R.string.assistant_page_skills_model_unsupported_desc),
                    isError = false,
                )
            }
        }

        if (skillsState.error != null) {
            item("error") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_refresh_failed_title),
                    text = skillsState.error.orEmpty(),
                    isError = true,
                )
            }
        }

        if (skillsState.isLoading) {
            item("loading") {
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(
                                R.string.assistant_page_skills_refreshing,
                                fallbackRootPath,
                            )
                        )
                    }
                }
            }
        }

        if (!skillsState.isLoading && skillsState.entries.isEmpty() && skillsState.invalidEntries.isEmpty()) {
            item("empty") {
                SkillsInfoCard(
                    title = stringResource(R.string.assistant_page_skills_empty_title),
                    text = stringResource(
                        R.string.assistant_page_skills_empty_desc,
                        fallbackRootPath,
                    ),
                    isError = false,
                )
            }
        }

        if (skillsState.entries.isNotEmpty()) {
            item("available-header") {
                Text(
                    text = stringResource(R.string.assistant_page_skills_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            skillSection(
                keyPrefix = "built-in",
                title = builtInSectionTitle,
                entries = builtInEntries,
                assistant = assistant,
                actionInProgress = actionInProgress,
                scope = scope,
                skillsRepository = skillsRepository,
                resources = resources,
                toaster = toaster,
                onUpdateAssistant = onUpdateAssistant,
                onLoadedEditor = { editDocument = it },
                onLoadingEditorChange = { isLoadingEditor = it },
                onDeleteRequested = { deleteEntry = it },
            )
            skillSection(
                keyPrefix = "imported",
                title = importedSectionTitle,
                entries = importedEntries,
                assistant = assistant,
                actionInProgress = actionInProgress,
                scope = scope,
                skillsRepository = skillsRepository,
                resources = resources,
                toaster = toaster,
                onUpdateAssistant = onUpdateAssistant,
                onLoadedEditor = { editDocument = it },
                onLoadingEditorChange = { isLoadingEditor = it },
                onDeleteRequested = { deleteEntry = it },
            )
            skillSection(
                keyPrefix = "local",
                title = localSectionTitle,
                entries = localEntries,
                assistant = assistant,
                actionInProgress = actionInProgress,
                scope = scope,
                skillsRepository = skillsRepository,
                resources = resources,
                toaster = toaster,
                onUpdateAssistant = onUpdateAssistant,
                onLoadedEditor = { editDocument = it },
                onLoadingEditorChange = { isLoadingEditor = it },
                onDeleteRequested = { deleteEntry = it },
            )
        }

        if (missingSelections.isNotEmpty()) {
            item("missing-header") {
                Text(
                    text = stringResource(R.string.assistant_page_skills_missing_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(
                items = missingSelections,
                key = { it },
            ) { directoryName ->
                Card {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Alert01, contentDescription = null)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = directoryName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_skills_missing_desc,
                                    fallbackRootPath,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    enabled = skillsState.entries.isNotEmpty(),
                                    onClick = { relinkMissingDirectoryName = directoryName },
                                ) {
                                    Text(stringResource(R.string.assistant_page_skills_missing_relink))
                                }
                                TextButton(
                                    onClick = {
                                        onUpdateAssistant(
                                            assistant.copy(
                                                selectedSkills = assistant.selectedSkills - directoryName
                                            )
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.assistant_page_remove))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (skillsState.invalidEntries.isNotEmpty() && showInvalidEntries) {
            item("invalid-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_skills_invalid_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { showInvalidEntries = false },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                        )
                    }
                }
            }
            items(
                items = skillsState.invalidEntries,
                key = { "${it.directoryName}:${it.reason}" },
            ) { entry ->
                InvalidSkillEntryCard(entry = entry)
            }
        }
    }

    if (showCreateDialog) {
        SkillEditorSheet(
            draft = createDraft,
            title = stringResource(R.string.assistant_page_skills_create_title),
            confirmText = stringResource(R.string.assistant_page_skills_create_confirm),
            progressText = stringResource(R.string.assistant_page_skills_create_in_progress),
            isSaving = isCreating,
            onDismiss = {
                if (!isCreating) {
                    showCreateDialog = false
                }
            },
            onDraftChange = { createDraft = it },
            onConfirm = {
                scope.launch {
                    isCreating = true
                    try {
                        val created = skillsRepository.createSkill(
                            directoryName = createDraft.directoryName,
                            name = createDraft.name,
                            description = createDraft.description,
                            body = createDraft.body,
                            extras = createDraft.extras,
                        )
                        toaster.show(
                            resources.getString(
                                R.string.assistant_page_skills_create_success,
                                created.directoryName,
                            ),
                            type = ToastType.Success,
                        )
                        showCreateDialog = false
                        resetCreateDialog()
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: resources.getString(R.string.assistant_page_skills_create_failed),
                            type = ToastType.Error,
                        )
                    } finally {
                        isCreating = false
                    }
                }
            },
        )
    }

    deleteEntry?.let { currentEntry ->
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    deleteEntry = null
                }
            },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.assistant_page_skills_delete_body,
                        currentEntry.directoryName,
                        skillsState.rootPath.ifBlank {
                            stringResource(R.string.assistant_page_skills_root_fallback)
                        },
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        scope.launch {
                            val latestEntry = deleteEntry ?: return@launch
                            isDeleting = true
                            try {
                                skillsRepository.deleteSkill(latestEntry.directoryName)
                                toaster.show(
                                    resources.getString(
                                        R.string.assistant_page_skills_delete_success,
                                        latestEntry.directoryName,
                                    ),
                                    type = ToastType.Success,
                                )
                                deleteEntry = null
                            } catch (error: Throwable) {
                                toaster.show(
                                    error.message ?: resources.getString(R.string.assistant_page_skills_delete_failed),
                                    type = ToastType.Error,
                                )
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (isDeleting) {
                            stringResource(R.string.assistant_page_skills_delete_in_progress)
                        } else {
                            stringResource(R.string.delete)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = { deleteEntry = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    relinkMissingDirectoryName?.let { missingDirectoryName ->
        AlertDialog(
            onDismissRequest = { relinkMissingDirectoryName = null },
            title = { Text(stringResource(R.string.assistant_page_skills_missing_relink_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.assistant_page_skills_missing_relink_desc,
                            missingDirectoryName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    skillsState.entries.forEach { entry ->
                        Card(
                            onClick = {
                                val nextSelection = assistant.selectedSkills.toMutableSet().apply {
                                    remove(missingDirectoryName)
                                    add(entry.directoryName)
                                }
                                onUpdateAssistant(assistant.copy(selectedSkills = nextSelection))
                                relinkMissingDirectoryName = null
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    entry.directoryName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { relinkMissingDirectoryName = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingImport?.let { currentImport ->
        AlertDialog(
            onDismissRequest = {
                if (!isImporting) {
                    pendingImport = null
                }
            },
            title = {
                Text(
                    text = currentImport.displayName?.let {
                        resources.getString(R.string.assistant_page_skills_import_preview_title_named, it)
                    } ?: stringResource(R.string.assistant_page_skills_import_preview_title)
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val preview = currentImport.preview
                    Text(
                        text = stringResource(
                            R.string.assistant_page_skills_import_preview_summary,
                            preview.directories.size,
                            preview.totalFiles,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SkillImportPreviewSummary(preview = preview)
                    if (preview.hasScripts) {
                        SkillsInfoCard(
                            title = stringResource(R.string.assistant_page_skills_import_preview_review_title),
                            text = stringResource(R.string.assistant_page_skills_import_preview_review_desc),
                            isError = false,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isImporting,
                    onClick = {
                        scope.launch {
                            val latestImport = pendingImport ?: return@launch
                            isImporting = true
                            try {
                                val imported = withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(latestImport.uri)?.use { inputStream ->
                                        skillsRepository.importSkillZip(
                                            inputStream = inputStream,
                                            archiveName = latestImport.displayName,
                                        )
                                    } ?: error(resources.getString(R.string.assistant_page_skills_import_failed))
                                }
                                val message = if (imported.directories.size == 1) {
                                    resources.getString(
                                        R.string.assistant_page_skills_import_success_single,
                                        imported.directories.single(),
                                    )
                                } else {
                                    resources.getString(
                                        R.string.assistant_page_skills_import_success_multiple,
                                        imported.directories.size,
                                    )
                                }
                                toaster.show(message, type = ToastType.Success)
                                pendingImport = null
                            } catch (error: Throwable) {
                                toaster.show(
                                    error.message ?: resources.getString(R.string.assistant_page_skills_import_failed),
                                    type = ToastType.Error,
                                )
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (isImporting) {
                            stringResource(R.string.assistant_page_skills_import_in_progress)
                        } else {
                            stringResource(R.string.assistant_page_skills_install)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImporting,
                    onClick = { pendingImport = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editDocument?.let { currentDocument ->
        SkillEditorSheet(
            draft = currentDocument.toEditorDraft(),
            title = stringResource(R.string.assistant_page_skills_edit_title),
            confirmText = stringResource(R.string.assistant_page_skills_edit_confirm),
            progressText = stringResource(R.string.assistant_page_skills_edit_in_progress),
            isSaving = isSavingEditor,
            onDismiss = {
                if (!isSavingEditor) {
                    editDocument = null
                }
            },
            onDraftChange = { draft ->
                editDocument = editDocument?.withDraft(draft)
            },
            onConfirm = {
                scope.launch {
                    val latestDocument = editDocument ?: return@launch
                    isSavingEditor = true
                    try {
                        val saved = skillsRepository.updateSkill(
                            originalDirectoryName = latestDocument.originalDirectoryName,
                            directoryName = latestDocument.directoryName,
                            name = latestDocument.name,
                            description = latestDocument.description,
                            body = latestDocument.body,
                            extras = latestDocument.extras,
                        )
                        toaster.show(
                            resources.getString(
                                R.string.assistant_page_skills_edit_success,
                                saved.directoryName,
                            ),
                            type = ToastType.Success,
                        )
                        editDocument = null
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: resources.getString(R.string.assistant_page_skills_edit_failed),
                            type = ToastType.Error,
                        )
                    } finally {
                        isSavingEditor = false
                    }
                }
            },
        )
    }
}
