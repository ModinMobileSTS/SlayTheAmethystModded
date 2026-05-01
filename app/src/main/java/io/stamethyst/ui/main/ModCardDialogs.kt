package io.stamethyst.ui.main

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.stamethyst.R
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.ui.haptics.LauncherHaptics
import kotlin.math.roundToInt

@Composable
internal fun ModActionsDialog(
    visible: Boolean,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    onEditPriority: () -> Unit,
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
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModActionDialogListItem(
                        text = stringResource(R.string.main_mod_priority_adjust),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onEditPriority()
                    }
                    ModActionDialogListItem(
                        text = stringResource(R.string.main_mod_export),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onExport()
                    }
                    ModActionDialogListItem(
                        text = stringResource(R.string.main_mod_share),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onShare()
                    }
                    ModActionDialogListItem(
                        text = stringResource(R.string.main_mod_rename),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onRename()
                    }
                    ModActionDialogListItem(
                        text = stringResource(R.string.main_mod_delete),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onDelete()
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
internal fun RenameModFileDialog(
    visible: Boolean,
    value: String,
    controlsEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!visible) {
        return
    }
    var input by remember(visible, value) { mutableStateOf(value) }
    val normalizedInput = input.trim()
    val errorText = when {
        normalizedInput.isEmpty() -> stringResource(R.string.main_mod_rename_error_empty)
        normalizedInput.contains('/') || normalizedInput.contains('\\') ->
            stringResource(R.string.main_mod_rename_error_separator)

        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_mod_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text(stringResource(R.string.main_mod_rename_hint)) },
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
            PillCancelButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun RenameModFileDisplayModeWarningDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_mod_rename_display_mode_warning_title)) },
        text = {
            Text(text = stringResource(R.string.main_mod_rename_display_mode_warning_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.main_mod_rename_display_mode_warning_confirm))
            }
        },
        dismissButton = {
            PillCancelButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_mod_rename_display_mode_warning_dismiss))
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
