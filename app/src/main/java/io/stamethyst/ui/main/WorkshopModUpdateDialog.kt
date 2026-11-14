package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.LauncherAlertDialog
import io.stamethyst.ui.SimpleMarkdownCard

internal sealed interface WorkshopUpdateChangeNotesState {
    data object Idle : WorkshopUpdateChangeNotesState
    data object Loading : WorkshopUpdateChangeNotesState
    data class Loaded(val latestMarkdown: String) : WorkshopUpdateChangeNotesState
    data class Failed(val errorDetail: String) : WorkshopUpdateChangeNotesState
}

@Composable
internal fun WorkshopModUpdateDialog(
    modName: String,
    changeNotesState: WorkshopUpdateChangeNotesState,
    onRetryChangeNotes: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    LauncherAlertDialog(
        scrollableText = false,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_mod_workshop_update_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.main_mod_workshop_update_dialog_message, modName)
                )
                when (changeNotesState) {
                    WorkshopUpdateChangeNotesState.Idle,
                    WorkshopUpdateChangeNotesState.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.workshop_change_notes_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is WorkshopUpdateChangeNotesState.Loaded -> {
                        if (changeNotesState.latestMarkdown.isNotBlank()) {
                            SimpleMarkdownCard(
                                title = stringResource(R.string.workshop_change_notes_latest_title),
                                markdown = changeNotesState.latestMarkdown,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.workshop_change_notes_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is WorkshopUpdateChangeNotesState.Failed -> {
                        Text(
                            text = stringResource(
                                R.string.workshop_change_notes_load_failed,
                                changeNotesState.errorDetail,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = onRetryChangeNotes,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.workshop_action_retry))
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Change notes are informational: a pending, failed or retried request must not gate updates.
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.workshop_action_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_folder_dialog_cancel))
            }
        }
    )
}
