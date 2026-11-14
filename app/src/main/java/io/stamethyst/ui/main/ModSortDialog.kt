package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModSortDialogHost(
    mods: List<ModItemUi>,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var applyingOrder by remember { mutableStateOf<ModSortOrder?>(null) }
    LaunchedEffect(applyingOrder) {
        val order = applyingOrder ?: return@LaunchedEffect
        val paths = withContext(Dispatchers.IO) { sortedModDisplayPaths(mods, order) }
        ModDisplayOrder(context).save(paths)
        onDismiss()
    }
    ModSortDialog(
        enabled = enabled && applyingOrder == null,
        onDismiss = { if (applyingOrder == null) onDismiss() },
        onConfirm = { applyingOrder = it },
    )
}

@Composable
internal fun ModSortDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ModSortOrder) -> Unit,
) {
    var selectedOrder by rememberSaveable { mutableStateOf(ModSortOrder.NAME_ASCENDING) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    if (confirming) {
        LauncherAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.main_mod_sort)) },
            text = { Text(stringResource(R.string.main_mod_sort_overwrite_confirmation)) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { onConfirm(selectedOrder) }) {
                    Text(stringResource(R.string.common_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            },
        )
        return
    }
    LauncherAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_mod_sort)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.main_mod_sort_method))
                Box {
                    OutlinedButton(
                        enabled = enabled,
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth().testTag("mod_sort_order_dropdown"),
                    ) {
                        Text(stringResource(selectedOrder.labelResource()), modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ModSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(stringResource(order.labelResource())) },
                                onClick = { selectedOrder = order; expanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled,
                onClick = { confirming = true },
            ) {
                Text(stringResource(R.string.common_action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        },
    )
}

private fun ModSortOrder.labelResource(): Int = when (this) {
    ModSortOrder.NAME_ASCENDING -> R.string.main_mod_sort_name_ascending
    ModSortOrder.NAME_DESCENDING -> R.string.main_mod_sort_name_descending
    ModSortOrder.MODIFIED_NEWEST_FIRST -> R.string.main_mod_sort_modified_newest
    ModSortOrder.MODIFIED_OLDEST_FIRST -> R.string.main_mod_sort_modified_oldest
}
