package io.stamethyst.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.stamethyst.R
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.haptics.LauncherHaptics
import kotlin.math.roundToInt

internal data class ModFolderMoveTarget(
    val folderTokenId: String,
    val folderName: String,
    val isCurrent: Boolean
)

internal data class ModBatchMoveTarget(
    val folderTokenId: String,
    val folderName: String
)

internal data class ModAssociationPickerFolder(
    val folderName: String,
    val mods: List<ModItemUi>
)

@Composable
internal fun ModActionsDialog(
    visible: Boolean,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    favorite: Boolean,
    deleteEnabled: Boolean = controlsEnabled,
    onFavoriteChange: (Boolean) -> Unit,
    onEditPriority: () -> Unit,
    showOpenWorkshopDetails: Boolean = false,
    onOpenWorkshopDetails: () -> Unit = {},
    showAiEditorNewBadge: Boolean = false,
    onOpenAiEditor: () -> Unit = {},
    onAssociate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    if (!visible) {
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.main_mod_actions_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModActionDialogSection(title = stringResource(R.string.main_mod_actions_group_management)) {
                        ModActionOptionItem(
                            text = stringResource(
                                if (favorite) R.string.main_mod_favorite_remove else R.string.main_mod_favorite_add
                            ),
                            description = stringResource(
                                if (favorite) R.string.main_mod_action_desc_favorite_remove
                                else R.string.main_mod_action_desc_favorite_add
                            ),
                            icon = R.drawable.ic_favorite_heart,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onFavoriteChange(!favorite)
                            }
                        )
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_priority_adjust),
                            description = stringResource(R.string.main_mod_action_desc_priority),
                            icon = R.drawable.ic_speed,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onEditPriority()
                            }
                        )
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_associate),
                            description = stringResource(R.string.main_mod_action_desc_associate),
                            icon = R.drawable.ic_link,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onAssociate()
                            }
                        )
                        if (showOpenWorkshopDetails) {
                            ModActionOptionItem(
                                text = stringResource(R.string.main_mod_open_market_page),
                                description = stringResource(R.string.main_mod_action_desc_market),
                                icon = R.drawable.ic_settings_market,
                                enabled = true,
                                onClick = {
                                    onDismiss()
                                    onOpenWorkshopDetails()
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                    ModActionDialogSection(title = stringResource(R.string.main_mod_actions_group_tools)) {
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_ai_edit),
                            description = stringResource(R.string.main_mod_action_desc_ai_edit),
                            icon = R.drawable.ic_code,
                            badge = if (showAiEditorNewBadge) stringResource(R.string.main_mod_ai_edit_new_badge) else null,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onOpenAiEditor()
                            }
                        )
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_export),
                            description = stringResource(R.string.main_mod_action_desc_export),
                            icon = R.drawable.ic_workshop_download,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onExport()
                            }
                        )
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_share),
                            description = stringResource(R.string.main_mod_action_desc_share),
                            icon = R.drawable.ic_lan_room_share,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onShare()
                            }
                        )
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_rename),
                            description = stringResource(R.string.main_mod_action_desc_rename),
                            icon = R.drawable.ic_edit,
                            enabled = controlsEnabled,
                            onClick = {
                                onDismiss()
                                onRename()
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                    ModActionDialogSection(title = stringResource(R.string.main_mod_actions_group_remove)) {
                        ModActionOptionItem(
                            text = stringResource(R.string.main_mod_delete),
                            description = stringResource(R.string.main_mod_action_desc_delete),
                            icon = R.drawable.ic_delete,
                            enabled = deleteEnabled,
                            danger = true,
                            onClick = {
                                onDismiss()
                                onDelete()
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    PillCancelButton(onClick = onDismiss) {
                        Text(stringResource(R.string.main_folder_dialog_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModActionDialogSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
        )
        content()
    }
}

@Composable
private fun ModActionOptionItem(
    text: String,
    description: String,
    icon: Int,
    badge: String? = null,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.38f),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
    }
}

@Composable
internal fun AssociateModPickerDialog(
    visible: Boolean,
    sourceMod: ModItemUi,
    folders: List<ModAssociationPickerFolder>,
    associatedModKeys: Set<String>,
    controlsEnabled: Boolean,
    showModFileName: Boolean,
    onDismiss: () -> Unit,
    onSelectTarget: (ModItemUi) -> Unit
) {
    if (!visible) {
        return
    }
    val sourceKey = resolveModAssociationKey(sourceMod)
    val pickerFolders = folders.mapNotNull { folder ->
        val selectableMods = folder.mods.filter { mod ->
            mod.installed &&
                !mod.required &&
                resolveModAssociationKey(mod)?.let { key -> key != sourceKey } == true
        }
        if (selectableMods.isEmpty()) {
            null
        } else {
            folder.copy(mods = selectableMods)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_mod_association_picker_title_format,
                    resolveModDisplayName(sourceMod, showModFileName = showModFileName)
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pickerFolders.isEmpty()) {
                    Text(
                        text = stringResource(R.string.main_mod_association_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    pickerFolders.forEach { folder ->
                        Text(
                            text = folder.folderName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            folder.mods.forEach { targetMod ->
                                val targetKey = resolveModAssociationKey(targetMod)
                                val alreadyAssociated = targetKey != null && associatedModKeys.contains(targetKey)
                                ModAssociationPickerListItem(
                                    mod = targetMod,
                                    alreadyAssociated = alreadyAssociated,
                                    controlsEnabled = controlsEnabled,
                                    showModFileName = showModFileName,
                                    onClick = { onSelectTarget(targetMod) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            PillCancelButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun ModAssociationManageDialog(
    visible: Boolean,
    mod: ModItemUi,
    associatedMods: List<ModItemUi>,
    controlsEnabled: Boolean,
    showModFileName: Boolean,
    onDismiss: () -> Unit,
    onAddAssociation: () -> Unit,
    onRemoveAssociation: (ModItemUi) -> Unit,
    onClearGroup: () -> Unit
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_mod_association_manage_title_format,
                    resolveModDisplayName(mod, showModFileName = showModFileName)
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (associatedMods.isEmpty()) {
                    Text(
                        text = stringResource(R.string.main_mod_association_manage_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    associatedMods.forEach { associatedMod ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = resolveModDisplayName(
                                        associatedMod,
                                        showModFileName = showModFileName
                                    ),
                                    maxLines = 1
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = associatedMod.manifestModId.ifBlank { associatedMod.modId },
                                    maxLines = 1
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onRemoveAssociation(associatedMod) },
                                    enabled = controlsEnabled
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(
                                            R.string.main_mod_association_remove
                                        )
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAddAssociation,
                enabled = controlsEnabled
            ) {
                Text(stringResource(R.string.main_mod_association_add))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onClearGroup,
                    enabled = controlsEnabled && associatedMods.isNotEmpty()
                ) {
                    Text(stringResource(R.string.main_mod_association_clear_group))
                }
                PillCancelButton(onClick = onDismiss) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        }
    )
}

@Composable
private fun ModAssociationPickerListItem(
    mod: ModItemUi,
    alreadyAssociated: Boolean,
    controlsEnabled: Boolean,
    showModFileName: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = resolveModDisplayName(mod, showModFileName = showModFileName),
                maxLines = 1
            )
        },
        supportingContent = {
            Text(
                text = mod.manifestModId.ifBlank { mod.modId },
                maxLines = 1
            )
        },
        trailingContent = {
            Text(
                text = if (alreadyAssociated) {
                    stringResource(R.string.main_mod_association_already_added)
                } else {
                    ">"
                },
                style = MaterialTheme.typography.labelMedium
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = controlsEnabled && !alreadyAssociated,
                onClick = onClick
            )
    )
}

@Composable
internal fun ModPriorityDialog(
    visible: Boolean,
    controlsEnabled: Boolean,
    modName: String,
    explicitPriority: Int?,
    effectivePriority: Int?,
    onDismiss: () -> Unit,
    onClearPriority: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    if (!visible) {
        return
    }
    val view = LocalView.current
    val initialPriority = explicitPriority ?: effectivePriority ?: ModManager.OPTIONAL_MOD_PRIORITY_MIN
    var sliderValue by remember(visible, explicitPriority, effectivePriority) {
        mutableFloatStateOf(initialPriority.toFloat())
    }
    var lastPriorityStep by remember(visible, explicitPriority, effectivePriority) {
        mutableIntStateOf(initialPriority)
    }
    val selectedPriority = sliderValue.roundToInt()
        .coerceIn(ModManager.OPTIONAL_MOD_PRIORITY_MIN, ModManager.OPTIONAL_MOD_PRIORITY_MAX)
    val currentStatusText = when {
        explicitPriority != null && effectivePriority != null && explicitPriority != effectivePriority ->
            stringResource(
                R.string.main_mod_priority_dialog_status_explicit_and_effective,
                explicitPriority,
                effectivePriority
            )

        explicitPriority != null ->
            stringResource(R.string.main_mod_priority_dialog_status_explicit, explicitPriority)

        effectivePriority != null ->
            stringResource(R.string.main_mod_priority_dialog_status_inherited, effectivePriority)

        else -> stringResource(R.string.main_mod_priority_dialog_status_default)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_mod_priority_dialog_title_format,
                    modName
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.main_mod_priority_dialog_summary),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = currentStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = ModManager.OPTIONAL_MOD_PRIORITY_MIN.toString(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = ModManager.OPTIONAL_MOD_PRIORITY_MAX.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        sliderValue = value
                        val step = value.roundToInt()
                            .coerceIn(
                                ModManager.OPTIONAL_MOD_PRIORITY_MIN,
                                ModManager.OPTIONAL_MOD_PRIORITY_MAX
                            )
                        if (step != lastPriorityStep) {
                            lastPriorityStep = step
                            LauncherHaptics.perform(view, HapticFeedbackConstants.CLOCK_TICK)
                        }
                    },
                    valueRange = ModManager.OPTIONAL_MOD_PRIORITY_MIN.toFloat()..
                        ModManager.OPTIONAL_MOD_PRIORITY_MAX.toFloat(),
                    steps = (ModManager.OPTIONAL_MOD_PRIORITY_MAX - ModManager.OPTIONAL_MOD_PRIORITY_MIN - 1)
                        .coerceAtLeast(0),
                    enabled = controlsEnabled
                )
                Text(
                    text = stringResource(
                        R.string.main_mod_priority_dialog_selected_format,
                        selectedPriority
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedPriority) },
                enabled = controlsEnabled
            ) {
                Text(stringResource(R.string.common_action_confirm))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onClearPriority,
                    enabled = controlsEnabled && explicitPriority != null
                ) {
                    Text(text = stringResource(R.string.main_mod_priority_dialog_clear))
                }
                PillCancelButton(onClick = onDismiss) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        }
    )
}

@Composable
internal fun RenameModAliasDialog(
    visible: Boolean,
    value: String,
    controlsEnabled: Boolean,
    showRestoreOriginal: Boolean,
    onDismiss: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!visible) {
        return
    }
    var input by remember(visible, value) { mutableStateOf(value) }
    val normalizedInput = input.trim()
    val errorText = when {
        normalizedInput.isEmpty() -> stringResource(R.string.main_mod_alias_error_empty)
        normalizedInput.contains('/') || normalizedInput.contains('\\') ->
            stringResource(R.string.main_mod_alias_error_separator)

        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_mod_alias_dialog_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text(stringResource(R.string.main_mod_alias_hint)) },
                enabled = controlsEnabled,
                isError = errorText != null,
                supportingText = {
                    if (errorText != null) {
                        Text(text = errorText)
                    }
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedInput) },
                enabled = controlsEnabled && errorText == null
            ) {
                Text(stringResource(R.string.main_folder_dialog_confirm))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showRestoreOriginal) {
                    TextButton(
                        onClick = onRestoreOriginal,
                        enabled = controlsEnabled
                    ) {
                        Text(stringResource(R.string.main_mod_alias_restore_original))
                    }
                }
                PillCancelButton(onClick = onDismiss) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        }
    )
}

@Composable
internal fun MoveModToFolderDialog(
    visible: Boolean,
    modName: String,
    targets: List<ModFolderMoveTarget>,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelectTarget: (String) -> Unit
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_mod_move_folder_dialog_title_format,
                    modName
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                targets.forEach { target ->
                    ModActionDialogListItem(
                        text = if (target.isCurrent) {
                            stringResource(
                                R.string.main_mod_move_folder_current_format,
                                target.folderName
                            )
                        } else {
                            target.folderName
                        },
                        enabled = controlsEnabled && !target.isCurrent
                    ) {
                        onSelectTarget(target.folderTokenId)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            PillCancelButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun MoveSelectedModsToFolderDialog(
    visible: Boolean,
    selectedCount: Int,
    targets: List<ModBatchMoveTarget>,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelectTarget: (String) -> Unit
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    R.string.main_batch_move_dialog_title_format,
                    selectedCount
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                targets.forEach { target ->
                    ModActionDialogListItem(
                        text = target.folderName,
                        enabled = controlsEnabled
                    ) {
                        onSelectTarget(target.folderTokenId)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            PillCancelButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
private fun ModActionDialogListItem(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text = text) },
        trailingContent = {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun PillCancelButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        content = { content() }
    )
}
