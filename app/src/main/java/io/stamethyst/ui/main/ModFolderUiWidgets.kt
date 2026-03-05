package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
internal fun FolderOrderHandle(
    reorderScope: ReorderableCollectionItemScope,
    folderId: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit
) {
    var menuExpanded by remember(folderId) { mutableStateOf(false) }
    val handleModifier = with(reorderScope) {
        Modifier.draggableHandle(
            enabled = true,
            interactionSource = null,
            onDragStarted = {
                onDragStarted()
            },
            onDragStopped = {
                onDragStopped()
            }
        )
    }
    Box(modifier = handleModifier) {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.main_folder_reorder)
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.main_folder_move_up)) },
                enabled = canMoveUp,
                onClick = {
                    menuExpanded = false
                    onMoveUp()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.main_folder_move_down)) },
                enabled = canMoveDown,
                onClick = {
                    menuExpanded = false
                    onMoveDown()
                }
            )
        }
    }
}

@Composable
internal fun FolderNameDialog(
    visible: Boolean,
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!visible) {
        return
    }
    var value by remember(visible, initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.main_folder_dialog_name_hint)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(value.trim())
                }
            ) {
                Text(text = stringResource(R.string.main_folder_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun FolderEmptyHint(text: String) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.outline,
        fontSize = 12.sp
    )
}
