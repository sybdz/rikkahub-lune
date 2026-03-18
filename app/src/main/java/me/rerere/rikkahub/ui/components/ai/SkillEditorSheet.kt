package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillFrontmatterExtras
import me.rerere.rikkahub.data.skills.sanitizeSkillDirectoryName

internal data class SkillEditorDraft(
    val name: String = "",
    val directoryName: String = "",
    val description: String = "",
    val body: String = "",
    val extras: SkillFrontmatterExtras = SkillFrontmatterExtras(),
    val directoryEdited: Boolean = false,
)

internal fun emptySkillEditorDraft(): SkillEditorDraft = SkillEditorDraft()

internal fun SkillEditorDraft.updateName(value: String): SkillEditorDraft = copy(
    name = value,
    directoryName = if (directoryEdited) directoryName else sanitizeSkillDirectoryName(value),
)

internal fun SkillEditorDraft.updateDirectory(value: String): SkillEditorDraft = copy(
    directoryName = sanitizeSkillDirectoryName(value),
    directoryEdited = true,
)

internal fun SkillEditorDocument.toEditorDraft(): SkillEditorDraft = SkillEditorDraft(
    name = name,
    directoryName = directoryName,
    description = description,
    body = body,
    extras = extras,
    directoryEdited = true,
)

internal fun SkillEditorDocument.withDraft(draft: SkillEditorDraft): SkillEditorDocument = copy(
    name = draft.name,
    directoryName = draft.directoryName,
    description = draft.description,
    body = draft.body,
    extras = draft.extras,
)

@Composable
internal fun SkillEditorSheet(
    draft: SkillEditorDraft,
    title: String,
    confirmText: String,
    progressText: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onDraftChange: (SkillEditorDraft) -> Unit,
    onConfirm: () -> Unit,
) {
    val canConfirm = draft.name.isNotBlank() && draft.description.isNotBlank() && !isSaving

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.updateName(it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_name)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.directoryName,
                    onValueChange = { onDraftChange(draft.updateDirectory(it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_directory)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_description)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.body,
                    onValueChange = { onDraftChange(draft.copy(body = it)) },
                    minLines = 6,
                    label = { Text(stringResource(R.string.assistant_page_skills_create_body)) },
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_skills_create_body_placeholder))
                    },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.license.orEmpty(),
                    onValueChange = {
                        onDraftChange(draft.copy(extras = draft.extras.copy(license = it.ifBlank { null })))
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_license)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.compatibility.orEmpty(),
                    onValueChange = {
                        onDraftChange(draft.copy(extras = draft.extras.copy(compatibility = it.ifBlank { null })))
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_compatibility)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.allowedTools.orEmpty(),
                    onValueChange = {
                        onDraftChange(draft.copy(extras = draft.extras.copy(allowedTools = it.ifBlank { null })))
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_allowed_tools)) },
                    placeholder = { Text(stringResource(R.string.assistant_page_skills_field_allowed_tools_placeholder)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.argumentHint.orEmpty(),
                    onValueChange = {
                        onDraftChange(draft.copy(extras = draft.extras.copy(argumentHint = it.ifBlank { null })))
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_argument_hint)) },
                    placeholder = { Text(stringResource(R.string.assistant_page_skills_field_argument_hint_placeholder)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.author.orEmpty(),
                    onValueChange = {
                        onDraftChange(
                            draft.copy(
                                extras = draft.extras.copy(
                                    metadata = draft.extras.metadata.toMutableMap().apply {
                                        if (it.isBlank()) remove("author") else put("author", it)
                                    }
                                )
                            )
                        )
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_author)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.extras.version.orEmpty(),
                    onValueChange = {
                        onDraftChange(
                            draft.copy(
                                extras = draft.extras.copy(
                                    metadata = draft.extras.metadata.toMutableMap().apply {
                                        if (it.isBlank()) remove("version") else put("version", it)
                                    }
                                )
                            )
                        )
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.assistant_page_skills_field_version)) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.assistant_page_skills_option_user_invocable_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.assistant_page_skills_option_user_invocable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.extras.userInvocable,
                        onCheckedChange = {
                            onDraftChange(draft.copy(extras = draft.extras.copy(userInvocable = it)))
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.assistant_page_skills_option_disable_model_invocation_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.assistant_page_skills_option_disable_model_invocation_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.extras.disableModelInvocation,
                        onCheckedChange = {
                            onDraftChange(draft.copy(extras = draft.extras.copy(disableModelInvocation = it)))
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !isSaving,
                    onClick = onDismiss,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    enabled = canConfirm,
                    onClick = onConfirm,
                ) {
                    Text(
                        text = if (isSaving) {
                            progressText
                        } else {
                            confirmText
                        }
                    )
                }
            }
        }
    }
}
