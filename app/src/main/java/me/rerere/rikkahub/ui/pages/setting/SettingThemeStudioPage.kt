package me.rerere.rikkahub.ui.pages.setting

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.GradientStyle
import me.rerere.rikkahub.data.datastore.MotionStyle
import me.rerere.rikkahub.data.datastore.ThemeProfile
import me.rerere.rikkahub.data.datastore.ThemeStudioConfig
import me.rerere.rikkahub.data.datastore.activeProfileOrDefault
import me.rerere.rikkahub.data.datastore.defaultBalancedThemeProfile
import me.rerere.rikkahub.data.datastore.ensureValid
import me.rerere.rikkahub.data.datastore.normalized
import me.rerere.rikkahub.data.export.ThemeProfileSerializer
import me.rerere.rikkahub.data.export.ThemeStudioConfigSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.ThemeStudioDraftStore
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingThemeStudioPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val normalizedSettingsStudio = remember(settings.themeStudio, settings.themeId) {
        settings.themeStudio.ensureValid(settings.themeId)
    }
    var draftConfig by remember(normalizedSettingsStudio) {
        mutableStateOf(normalizedSettingsStudio)
    }

    val activeProfile = remember(draftConfig, settings.themeId) {
        draftConfig.activeProfileOrDefault(settings.themeId)
    }

    val latestSettings by rememberUpdatedState(settings)

    fun updateDraftConfig(newConfig: ThemeStudioConfig) {
        val normalized = newConfig.ensureValid(settings.themeId)
        draftConfig = normalized
        ThemeStudioDraftStore.setDraftProfile(normalized.activeProfileOrDefault(settings.themeId))
    }

    fun updateActiveProfile(transform: (ThemeProfile) -> ThemeProfile) {
        val current = draftConfig.activeProfileOrDefault(settings.themeId)
        val updated = transform(current).normalized(settings.themeId)
        updateDraftConfig(
            draftConfig.copy(
                profiles = draftConfig.profiles.map { profile ->
                    if (profile.id == current.id) updated else profile
                },
                activeProfileId = updated.id,
            )
        )
    }

    fun persistNow() {
        if (!latestSettings.init) {
            vm.updateSettings(latestSettings.copy(themeStudio = draftConfig))
        }
    }

    LaunchedEffect(draftConfig) {
        if (latestSettings.init) return@LaunchedEffect
        delay(500)
        val persisted = latestSettings.themeStudio.ensureValid(latestSettings.themeId)
        if (draftConfig != persisted) {
            vm.updateSettings(latestSettings.copy(themeStudio = draftConfig))
        }
    }

    LaunchedEffect(activeProfile) {
        ThemeStudioDraftStore.setDraftProfile(activeProfile)
    }

    DisposableEffect(Unit) {
        ThemeStudioDraftStore.setDraftProfile(activeProfile)
        onDispose {
            ThemeStudioDraftStore.clear()
        }
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(activeProfile.id) { mutableStateOf(activeProfile.name) }
    var showExportCurrentDialog by remember { mutableStateOf(false) }
    var showExportLibraryDialog by remember { mutableStateOf(false) }
    var showFullPreview by remember { mutableStateOf(false) }

    val exportCurrent = rememberExporter(activeProfile, ThemeProfileSerializer)
    val exportLibrary = rememberExporter(draftConfig, ThemeStudioConfigSerializer)
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ThemeStudioConfigSerializer) { result ->
        result.onSuccess { imported ->
            val incoming = imported.ensureValid(settings.themeId)
            val mergedProfiles = draftConfig.profiles + incoming.profiles
            updateDraftConfig(
                draftConfig.copy(
                    activeProfileId = incoming.activeProfileId ?: mergedProfiles.first().id,
                    profiles = mergedProfiles,
                )
            )
            persistNow()
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message.orEmpty()))
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_theme_studio_page_title))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            val resetCurrent = defaultBalancedThemeProfile(activeProfile.basePresetId).copy(
                                id = activeProfile.id,
                                name = activeProfile.name,
                            )
                            updateActiveProfile { resetCurrent }
                            persistNow()
                        }
                    ) {
                        Icon(Lucide.RotateCcw, stringResource(R.string.setting_theme_studio_reset_current))
                    }
                    IconButton(
                        onClick = {
                            showFullPreview = true
                        }
                    ) {
                        Icon(Lucide.Monitor, stringResource(R.string.setting_theme_studio_full_preview))
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            stickyHeader {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = 12.dp)
                ) {
                    ThemeStudioPreviewCard(profile = activeProfile)
                }
            }

            item {
                ThemeLibrarySection(
                    config = draftConfig,
                    activeProfile = activeProfile,
                    onSelect = { selectedId ->
                        updateDraftConfig(
                            draftConfig.copy(activeProfileId = selectedId)
                        )
                    },
                    onCreate = {
                        val created = defaultBalancedThemeProfile(activeProfile.basePresetId).copy(
                            name = "Profile ${draftConfig.profiles.size + 1}",
                        )
                        updateDraftConfig(
                            draftConfig.copy(
                                activeProfileId = created.id,
                                profiles = draftConfig.profiles + created,
                            )
                        )
                        persistNow()
                    },
                    onDuplicate = {
                        val duplicated = activeProfile.copy(
                            id = kotlin.uuid.Uuid.random(),
                            name = "${activeProfile.name} Copy",
                        )
                        updateDraftConfig(
                            draftConfig.copy(
                                activeProfileId = duplicated.id,
                                profiles = draftConfig.profiles + duplicated,
                            )
                        )
                        persistNow()
                    },
                    onRename = {
                        renameText = activeProfile.name
                        showRenameDialog = true
                    },
                    onDelete = {
                        if (draftConfig.profiles.size <= 1) return@ThemeLibrarySection
                        val remaining = draftConfig.profiles.filterNot { it.id == activeProfile.id }
                        updateDraftConfig(
                            draftConfig.copy(
                                profiles = remaining,
                                activeProfileId = remaining.first().id,
                            )
                        )
                        persistNow()
                    },
                    onResetAll = {
                        updateDraftConfig(ThemeStudioConfig().ensureValid(settings.themeId))
                        persistNow()
                    },
                    onImport = {
                        importer.importFromFile()
                    },
                    onExportCurrent = {
                        showExportCurrentDialog = true
                    },
                    onExportLibrary = {
                        showExportLibraryDialog = true
                    },
                )
            }

            item {
                BaseAndColorSection(
                    profile = activeProfile,
                    onUpdateProfile = {
                        updateActiveProfile(it)
                    },
                    onCommit = { persistNow() }
                )
            }

            item {
                RoleTuningSection(
                    profile = activeProfile,
                    onUpdateProfile = {
                        updateActiveProfile(it)
                    },
                    onCommit = { persistNow() }
                )
            }

            item {
                GradientSection(
                    profile = activeProfile,
                    onUpdateProfile = {
                        updateActiveProfile(it)
                    },
                    onCommit = { persistNow() },
                )
            }

            item {
                GlassAndAtmosphereSection(
                    profile = activeProfile,
                    onUpdateProfile = {
                        updateActiveProfile(it)
                    },
                    onCommit = { persistNow() },
                )
            }

            item {
                MotionSection(
                    profile = activeProfile,
                    onUpdateProfile = {
                        updateActiveProfile(it)
                    },
                    onCommit = { persistNow() },
                )
            }
        }
    }

    if (showExportCurrentDialog) {
        ExportDialog(
            exporter = exportCurrent,
            title = stringResource(R.string.setting_theme_studio_export_current),
            onDismiss = { showExportCurrentDialog = false }
        )
    }

    if (showExportLibraryDialog) {
        ExportDialog(
            exporter = exportLibrary,
            title = stringResource(R.string.setting_theme_studio_export_library),
            onDismiss = { showExportLibraryDialog = false }
        )
    }

    if (showFullPreview) {
        ThemeStudioFullPreviewDialog(
            profile = activeProfile,
            onDismiss = { showFullPreview = false }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
            },
            title = {
                Text(stringResource(R.string.setting_theme_studio_rename))
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateActiveProfile {
                            it.copy(name = renameText.ifBlank { it.name })
                        }
                        persistNow()
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ThemeStudioPreviewCard(profile: ThemeProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.setting_theme_studio_preview_desc, profile.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "Assistant reply preview",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "User input preview",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_input_placeholder),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Lucide.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeLibrarySection(
    config: ThemeStudioConfig,
    activeProfile: ThemeProfile,
    onSelect: (kotlin.uuid.Uuid) -> Unit,
    onCreate: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onResetAll: () -> Unit,
    onImport: () -> Unit,
    onExportCurrent: () -> Unit,
    onExportLibrary: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_library_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(config.profiles, key = { it.id }) { profile ->
                    FilterChip(
                        selected = profile.id == config.activeProfileId,
                        onClick = { onSelect(profile.id) },
                        label = {
                            Text(
                                text = profile.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconTextButton(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Plus,
                    text = stringResource(R.string.add),
                    onClick = onCreate,
                )
                IconTextButton(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Copy,
                    text = stringResource(R.string.copy),
                    onClick = onDuplicate,
                )
                IconTextButton(
                    modifier = Modifier.weight(1f),
                    icon = Lucide.Trash2,
                    text = stringResource(R.string.delete),
                    enabled = config.profiles.size > 1,
                    onClick = onDelete,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onRename,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setting_theme_studio_rename))
                }
                TextButton(
                    onClick = onResetAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setting_theme_studio_reset_all))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setting_theme_studio_import))
                }
                TextButton(
                    onClick = onExportCurrent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setting_theme_studio_export_current))
                }
                TextButton(
                    onClick = onExportLibrary,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setting_theme_studio_export_library))
                }
            }

            Text(
                text = stringResource(
                    R.string.setting_theme_studio_active_profile,
                    activeProfile.name,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BaseAndColorSection(
    profile: ThemeProfile,
    onUpdateProfile: ((ThemeProfile) -> ThemeProfile) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_base_and_color_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            PresetThemeButtonGroup(
                themeId = profile.basePresetId,
                onChangeTheme = { newPresetId ->
                    onUpdateProfile { current -> current.copy(basePresetId = newPresetId) }
                    onCommit()
                }
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_color_blend),
                value = profile.colorBlend,
                valueRange = 0f..1f,
                onValueChange = { blend ->
                    onUpdateProfile { current -> current.copy(colorBlend = blend) }
                },
                onValueChangeFinished = onCommit,
            )

            SeedColorEditor(
                label = stringResource(R.string.setting_theme_studio_primary_seed),
                seedArgb = profile.primarySeedArgb,
                onSeedChange = { seed ->
                    onUpdateProfile { current -> current.copy(primarySeedArgb = seed) }
                },
                onCommit = onCommit,
            )
            SeedColorEditor(
                label = stringResource(R.string.setting_theme_studio_secondary_seed),
                seedArgb = profile.secondarySeedArgb,
                onSeedChange = { seed ->
                    onUpdateProfile { current -> current.copy(secondarySeedArgb = seed) }
                },
                onCommit = onCommit,
            )
            SeedColorEditor(
                label = stringResource(R.string.setting_theme_studio_tertiary_seed),
                seedArgb = profile.tertiarySeedArgb,
                onSeedChange = { seed ->
                    onUpdateProfile { current -> current.copy(tertiarySeedArgb = seed) }
                },
                onCommit = onCommit,
            )
        }
    }
}

@Composable
private fun RoleTuningSection(
    profile: ThemeProfile,
    onUpdateProfile: ((ThemeProfile) -> ThemeProfile) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_role_tuning_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme_studio_enabled)) },
                trailingContent = {
                    Switch(
                        checked = profile.roleTuning.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateProfile { current ->
                                current.copy(roleTuning = current.roleTuning.copy(enabled = enabled))
                            }
                            onCommit()
                        }
                    )
                }
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_surface_tone_shift),
                value = profile.roleTuning.surfaceToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(surfaceToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_surface_container_tone_shift),
                value = profile.roleTuning.surfaceContainerToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(surfaceContainerToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_background_tone_shift),
                value = profile.roleTuning.backgroundToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(backgroundToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_primary_container_tone_shift),
                value = profile.roleTuning.primaryContainerToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(primaryContainerToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_secondary_container_tone_shift),
                value = profile.roleTuning.secondaryContainerToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(secondaryContainerToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_tertiary_container_tone_shift),
                value = profile.roleTuning.tertiaryContainerToneShift,
                valueRange = -20f..20f,
                onValueChange = { shift ->
                    onUpdateProfile { current ->
                        current.copy(roleTuning = current.roleTuning.copy(tertiaryContainerToneShift = shift))
                    }
                },
                onValueChangeFinished = onCommit,
            )
        }
    }
}

@Composable
private fun GradientSection(
    profile: ThemeProfile,
    onUpdateProfile: ((ThemeProfile) -> ThemeProfile) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_gradient_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme_studio_enabled)) },
                trailingContent = {
                    Switch(
                        checked = profile.gradient.enabled,
                        onCheckedChange = {
                            onUpdateProfile { current ->
                                current.copy(
                                    gradient = current.gradient.copy(enabled = it)
                                )
                            }
                            onCommit()
                        }
                    )
                }
            )

            EnumSelector(
                values = GradientStyle.entries,
                selected = profile.gradient.style,
                onSelect = { style ->
                    onUpdateProfile { current ->
                        current.copy(
                            gradient = current.gradient.copy(style = style)
                        )
                    }
                    onCommit()
                },
                labelMapper = { style ->
                    when (style) {
                        GradientStyle.LINEAR -> "Linear"
                        GradientStyle.RADIAL -> "Radial"
                        GradientStyle.AURORA -> "Aurora"
                    }
                }
            )

            SeedColorEditor(
                label = stringResource(R.string.setting_theme_studio_gradient_start),
                seedArgb = profile.gradient.startArgb,
                onSeedChange = { seed ->
                    seed?.let { value ->
                        onUpdateProfile { current ->
                            current.copy(gradient = current.gradient.copy(startArgb = value))
                        }
                    }
                },
                onCommit = onCommit,
                nullable = false,
            )
            SeedColorEditor(
                label = stringResource(R.string.setting_theme_studio_gradient_end),
                seedArgb = profile.gradient.endArgb,
                onSeedChange = { seed ->
                    seed?.let { value ->
                        onUpdateProfile { current ->
                            current.copy(gradient = current.gradient.copy(endArgb = value))
                        }
                    }
                },
                onCommit = onCommit,
                nullable = false,
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_gradient_intensity),
                value = profile.gradient.intensity,
                valueRange = 0f..0.35f,
                onValueChange = { intensity ->
                    onUpdateProfile { current ->
                        current.copy(gradient = current.gradient.copy(intensity = intensity))
                    }
                },
                onValueChangeFinished = onCommit,
            )
        }
    }
}

@Composable
private fun GlassAndAtmosphereSection(
    profile: ThemeProfile,
    onUpdateProfile: ((ThemeProfile) -> ThemeProfile) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_glass_atmosphere_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme_studio_glass_enabled)) },
                trailingContent = {
                    Switch(
                        checked = profile.glass.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateProfile { current ->
                                current.copy(glass = current.glass.copy(enabled = enabled))
                            }
                            onCommit()
                        }
                    )
                }
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_glass_blur),
                value = profile.glass.blurDp,
                valueRange = 0f..30f,
                onValueChange = { blur ->
                    onUpdateProfile { current ->
                        current.copy(glass = current.glass.copy(blurDp = blur))
                    }
                },
                onValueChangeFinished = onCommit,
            )
            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_glass_tint),
                value = profile.glass.tintOpacity,
                valueRange = 0f..0.35f,
                onValueChange = { opacity ->
                    onUpdateProfile { current ->
                        current.copy(glass = current.glass.copy(tintOpacity = opacity))
                    }
                },
                onValueChangeFinished = onCommit,
            )
            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_glass_border),
                value = profile.glass.borderOpacity,
                valueRange = 0f..0.25f,
                onValueChange = { opacity ->
                    onUpdateProfile { current ->
                        current.copy(glass = current.glass.copy(borderOpacity = opacity))
                    }
                },
                onValueChangeFinished = onCommit,
            )

            Spacer(modifier = Modifier.height(4.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme_studio_atmosphere_enabled)) },
                trailingContent = {
                    Switch(
                        checked = profile.atmosphere.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateProfile { current ->
                                current.copy(atmosphere = current.atmosphere.copy(enabled = enabled))
                            }
                            onCommit()
                        }
                    )
                }
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_vignette),
                value = profile.atmosphere.vignetteIntensity,
                valueRange = 0f..0.25f,
                onValueChange = { intensity ->
                    onUpdateProfile { current ->
                        current.copy(atmosphere = current.atmosphere.copy(vignetteIntensity = intensity))
                    }
                },
                onValueChangeFinished = onCommit,
            )
            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_top_glow),
                value = profile.atmosphere.topGlowIntensity,
                valueRange = 0f..0.25f,
                onValueChange = { intensity ->
                    onUpdateProfile { current ->
                        current.copy(atmosphere = current.atmosphere.copy(topGlowIntensity = intensity))
                    }
                },
                onValueChangeFinished = onCommit,
            )
        }
    }
}

@Composable
private fun MotionSection(
    profile: ThemeProfile,
    onUpdateProfile: ((ThemeProfile) -> ThemeProfile) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_theme_studio_motion_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.setting_theme_studio_enabled)) },
                trailingContent = {
                    Switch(
                        checked = profile.motion.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateProfile { current ->
                                current.copy(motion = current.motion.copy(enabled = enabled))
                            }
                            onCommit()
                        }
                    )
                }
            )

            EnumSelector(
                values = MotionStyle.entries,
                selected = profile.motion.style,
                onSelect = { style ->
                    onUpdateProfile { current ->
                        current.copy(motion = current.motion.copy(style = style))
                    }
                    onCommit()
                },
                labelMapper = {
                    when (it) {
                        MotionStyle.GENTLE -> "Gentle"
                        MotionStyle.STANDARD -> "Standard"
                        MotionStyle.BRISK -> "Brisk"
                    }
                }
            )

            ValueSlider(
                label = stringResource(R.string.setting_theme_studio_motion_scale),
                value = profile.motion.durationScale,
                valueRange = 0.5f..2.0f,
                onValueChange = { scale ->
                    onUpdateProfile { current ->
                        current.copy(motion = current.motion.copy(durationScale = scale))
                    }
                },
                onValueChangeFinished = onCommit,
            )
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumSelector(
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelMapper: (T) -> String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(labelMapper(value)) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
private fun SeedColorEditor(
    label: String,
    seedArgb: Long?,
    onSeedChange: (Long?) -> Unit,
    onCommit: () -> Unit,
    nullable: Boolean = true,
) {
    var text by remember(seedArgb) { mutableStateOf(seedArgb?.toHexColor() ?: "") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    val parsed = value.parseColorOrNull()
                    if (parsed != null) {
                        onSeedChange(parsed)
                    } else if (value.isBlank() && nullable) {
                        onSeedChange(null)
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text("#RRGGBB or #AARRGGBB")
                }
            )
            if (nullable) {
                TextButton(
                    onClick = {
                        text = ""
                        onSeedChange(null)
                        onCommit()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun IconTextButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text)
    }
}

private fun Long.toHexColor(): String {
    return String.format("#%08X", this)
}

private fun String.parseColorOrNull(): Long? {
    val normalized = trim()
    if (normalized.isBlank()) return null
    return runCatching {
        val parsed = AndroidColor.parseColor(normalized)
        parsed.toLong() and 0xFFFFFFFF
    }.getOrNull()
}
