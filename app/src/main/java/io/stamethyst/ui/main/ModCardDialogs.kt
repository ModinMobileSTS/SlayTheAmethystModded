package io.stamethyst.ui.main

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.stamethyst.R

@Composable
internal fun ModActionsDialog(
    visible: Boolean,
    controlsEnabled: Boolean,
    priorityLoad: Boolean,
    onDismiss: () -> Unit,
    onTogglePriorityLoad: () -> Unit,
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
                        text = stringResource(
                            if (priorityLoad) {
                                R.string.main_mod_priority_remove
                            } else {
                                R.string.main_mod_priority_add
                            }
                        ),
                        enabled = controlsEnabled
                    ) {
                        onDismiss()
                        onTogglePriorityLoad()
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
