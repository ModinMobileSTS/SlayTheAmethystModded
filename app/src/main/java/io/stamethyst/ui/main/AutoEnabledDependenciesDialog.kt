package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.LauncherAlertDialog

@Composable
internal fun AutoEnabledDependenciesDialog(
    dialog: MainScreenViewModel.Effect.ShowAutoEnabledDependenciesDialog,
    onFinish: (undo: Boolean) -> Unit,
) {
    var confirming by remember(dialog) { mutableStateOf(false) }
    val rootName = dialog.rootModName
    LauncherAlertDialog(
        onDismissRequest = {
            if (confirming) confirming = false else onFinish(false)
        },
        title = {
            Text(stringResource(if (confirming) R.string.main_mod_undo_dependencies_title
                else R.string.main_mod_auto_enabled_dependencies_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    confirming -> stringResource(R.string.main_mod_undo_dependencies_intro)
                    rootName != null -> stringResource(R.string.main_mod_auto_enabled_dependencies_single_intro, rootName)
                    else -> stringResource(R.string.main_mod_auto_enabled_dependencies_batch_intro)
                })
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (name in dialog.dependencyNames) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•")
                            Text(name)
                        }
                    }
                }
                if (confirming) {
                    Text(if (rootName != null) {
                        stringResource(R.string.main_mod_undo_dependencies_single_warning, rootName)
                    } else {
                        stringResource(R.string.main_mod_undo_dependencies_batch_warning)
                    })
                }
            }
        },
        dismissButton = {
            if (confirming) {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.main_mod_undo_dependencies_cancel))
                }
            } else {
                TextButton(onClick = { confirming = true }) {
                    Text(stringResource(R.string.common_action_undo))
                }
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(onClick = { onFinish(true) }) {
                    Text(
                        text = stringResource(R.string.main_mod_undo_dependencies_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                TextButton(onClick = { onFinish(false) }) {
                    Text(stringResource(R.string.common_action_acknowledge))
                }
            }
        },
    )
}
