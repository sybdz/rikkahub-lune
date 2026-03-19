package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToasterState
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillFrontmatterExtras
import me.rerere.rikkahub.data.skills.SkillImportPreview
import me.rerere.rikkahub.data.skills.SkillImportPreviewEntry
import me.rerere.rikkahub.data.skills.SkillInvalidEntry
import me.rerere.rikkahub.data.skills.SkillInvalidReason
import me.rerere.rikkahub.data.skills.SkillSourceType
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.normalizeSkillFrontmatterExtras

internal fun LazyListScope.skillSection(
    keyPrefix: String,
    title: String,
    entries: List<SkillCatalogEntry>,
    assistant: Assistant,
    actionInProgress: Boolean,
    scope: CoroutineScope,
    skillsRepository: SkillsRepository,
    resources: Resources,
    toaster: ToasterState,
    onUpdateAssistant: (Assistant) -> Unit,
    onLoadedEditor: (SkillEditorDocument) -> Unit,
    onLoadingEditorChange: (Boolean) -> Unit,
    onDeleteRequested: (SkillCatalogEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    item("$keyPrefix-header") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(
        items = entries,
        key = { it.directoryName },
    ) { entry ->
        SkillEntryCard(
            entry = entry,
            checked = entry.directoryName in assistant.selectedSkills,
            enabled = !actionInProgress,
            onEdit = {
                scope.launch {
                    onLoadingEditorChange(true)
                    try {
                        onLoadedEditor(skillsRepository.loadSkillDocument(entry))
                    } catch (error: Throwable) {
                        toaster.show(
                            error.message ?: resources.getString(R.string.assistant_page_skills_edit_load_failed),
                            type = ToastType.Error,
                        )
                    } finally {
                        onLoadingEditorChange(false)
                    }
                }
            },
            onDelete = if (entry.isBundled) {
                null
            } else {
                {
                    onDeleteRequested(entry)
                }
            },
            onCheckedChange = { checked ->
                val nextSelection = assistant.selectedSkills.toMutableSet().apply {
                    if (checked) add(entry.directoryName) else remove(entry.directoryName)
                }
                onUpdateAssistant(assistant.copy(selectedSkills = nextSelection))
            },
        )
    }
}

@Composable
internal fun SkillImportPreviewSummary(preview: SkillImportPreview) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(stringResource(R.string.assistant_page_skills_chip_count_skills, preview.directories.size)) },
        )
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(stringResource(R.string.assistant_page_skills_chip_count_files, preview.totalFiles)) },
        )
        if (preview.scriptFiles > 0) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.assistant_page_skills_chip_count_scripts, preview.scriptFiles)) },
            )
        }
        if (preview.referenceFiles > 0) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.assistant_page_skills_chip_count_references, preview.referenceFiles)) },
            )
        }
        if (preview.assetFiles > 0) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.assistant_page_skills_chip_count_assets, preview.assetFiles)) },
            )
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        preview.entries.forEach { entry ->
            SkillImportPreviewCard(entry = entry)
        }
    }
}

@Composable
private fun SkillImportPreviewCard(entry: SkillImportPreviewEntry) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
            )
            if (entry.name != entry.directoryName) {
                Text(
                    text = entry.directoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entry.version?.let {
                    SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_version, it))
                }
                entry.author?.let {
                    SkillMetaChip(label = it)
                }
                if (entry.scriptFiles > 0) {
                    SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_count_scripts, entry.scriptFiles))
                }
                if (entry.referenceFiles > 0) {
                    SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_count_references, entry.referenceFiles))
                }
                if (entry.assetFiles > 0) {
                    SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_count_assets, entry.assetFiles))
                }
            }
            if (entry.scriptPaths.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.assistant_page_skills_import_preview_scripts_list,
                        entry.scriptPaths.joinToString(separator = "\n"),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SkillEntryCard(
    entry: SkillCatalogEntry,
    checked: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        onClick = onEdit,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Puzzle, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.name != entry.directoryName) {
                    Text(
                        text = entry.directoryName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkillMetaChip(
                        label = when {
                            entry.isBundled -> stringResource(R.string.assistant_page_skills_chip_built_in)
                            entry.sourceType == SkillSourceType.IMPORTED -> stringResource(R.string.assistant_page_skills_chip_imported)
                            else -> stringResource(R.string.assistant_page_skills_chip_local)
                        }
                    )
                    entry.version?.let { SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_version, it)) }
                    entry.author?.let { SkillMetaChip(label = it) }
                    if (!entry.userInvocable) {
                        SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_auto_only))
                    } else if (!entry.modelInvocable) {
                        SkillMetaChip(label = stringResource(R.string.assistant_page_skills_chip_manual_only))
                    }
                }
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = enabled,
                    onClick = onEdit,
                ) {
                    Icon(
                        imageVector = HugeIcons.PencilEdit01,
                        contentDescription = stringResource(R.string.assistant_page_skills_edit_title),
                    )
                }
                onDelete?.let { deleteSkill ->
                    IconButton(
                        enabled = enabled,
                        onClick = deleteSkill,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun SkillMetaChip(label: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
    )
}

@Composable
internal fun InvalidSkillEntryCard(entry: SkillInvalidEntry) {
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
                Text(
                    text = entry.directoryName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = localizedSkillInvalidReason(entry.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun localizedSkillInvalidReason(reason: SkillInvalidReason): String {
    return when (reason) {
        SkillInvalidReason.MissingSkillFile -> stringResource(R.string.assistant_page_skills_reason_missing_skill_md)
        SkillInvalidReason.MissingYamlFrontmatter -> stringResource(
            R.string.assistant_page_skills_reason_missing_yaml_frontmatter
        )
        SkillInvalidReason.FrontmatterMustStart -> stringResource(
            R.string.assistant_page_skills_reason_frontmatter_must_start
        )
        SkillInvalidReason.FrontmatterNotClosed -> stringResource(
            R.string.assistant_page_skills_reason_frontmatter_not_closed
        )
        SkillInvalidReason.MissingName -> stringResource(R.string.assistant_page_skills_reason_missing_name)
        SkillInvalidReason.MissingDescription -> stringResource(
            R.string.assistant_page_skills_reason_missing_description
        )
        SkillInvalidReason.NoActivationPath -> stringResource(
            R.string.assistant_page_skills_reason_no_activation_path
        )
        is SkillInvalidReason.FailedToRead -> stringResource(
            R.string.assistant_page_skills_reason_failed_to_read,
            reason.detail,
        )
        is SkillInvalidReason.Other -> reason.message
    }
}

@Composable
internal fun SkillsInfoCard(
    title: String,
    text: String,
    isError: Boolean,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } else {
            null
        }
    }
}

internal fun buildSkillFrontmatterExtras(
    license: String,
    compatibility: String,
    allowedTools: String,
    argumentHint: String,
    author: String,
    version: String,
    userInvocable: Boolean,
    disableModelInvocation: Boolean,
): SkillFrontmatterExtras {
    return normalizeSkillFrontmatterExtras(
        SkillFrontmatterExtras(
            license = license.ifBlank { null },
            compatibility = compatibility.ifBlank { null },
            allowedTools = allowedTools.ifBlank { null },
            argumentHint = argumentHint.ifBlank { null },
            userInvocable = userInvocable,
            disableModelInvocation = disableModelInvocation,
            metadata = buildMap {
                if (author.isNotBlank()) put("author", author)
                if (version.isNotBlank()) put("version", version)
            },
        )
    )
}
