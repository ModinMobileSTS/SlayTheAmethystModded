package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.ui.SimpleMarkdownCard


internal data class UpdateSettingsActions(
    val onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    val onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit,
    val onManualCheckUpdates: () -> Unit,
    val onOpenReleaseHistory: () -> Unit,
    val onDismissReleaseHistoryDialog: () -> Unit,
)


@Composable
internal fun SettingsUpdateSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: UpdateSettingsActions,
) {
    val controlsEnabled =
        !uiState.busy && !uiState.updateCheckInProgress && !uiState.releaseHistoryLoading

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSwitchItem(
            SettingsSwitchSpec(
                checked = uiState.autoCheckUpdatesEnabled,
                enabled = !uiState.busy,
                title = stringResource(R.string.update_auto_check_enabled),
                onCheckedChange = actions.onAutoCheckUpdatesChanged
            )
        )

        SettingsChoiceDialogItem(
            SettingsChoiceSpec(
                title = stringResource(R.string.update_mirror_title),
                valueText = uiState.preferredUpdateMirror.displayName,
                enabled = controlsEnabled,
                selectedValue = uiState.preferredUpdateMirror,
                options = uiState.availableUpdateMirrors,
                optionLabel = { source -> source.displayName },
                onOptionSelected = actions.onPreferredUpdateMirrorChanged,
                description = stringResource(R.string.update_mirror_desc),
                dialogDescription = stringResource(R.string.update_mirror_desc),
            )
        )

        if (uiState.updateCheckInProgress || uiState.releaseHistoryLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SettingsActionListItem(
            title = stringResource(
                if (uiState.updateCheckInProgress) {
                    R.string.update_manual_check_running
                } else {
                    R.string.update_manual_check_title
                }
            ),
            enabled = controlsEnabled,
            onClick = actions.onManualCheckUpdates
        )
        SettingsActionListItem(
            title = stringResource(
                if (uiState.releaseHistoryLoading) {
                    R.string.update_history_loading
                } else {
                    R.string.update_history_title
                }
            ),
            enabled = controlsEnabled,
            onClick = actions.onOpenReleaseHistory
        )

        Text(
            text = stringResource(R.string.update_current_version, uiState.currentVersionText),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.update_status_title),
            style = MaterialTheme.typography.bodyMedium
        )
        SelectionContainer {
            Text(
                text = uiState.updateStatusSummary.ifBlank {
                    stringResource(R.string.update_status_not_checked)
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    uiState.releaseHistoryDialogState?.let { dialogState ->
        AlertDialog(
            onDismissRequest = actions.onDismissReleaseHistoryDialog,
            title = { Text(stringResource(R.string.update_history_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.update_history_dialog_source,
                            dialogState.metadataSourceDisplayName
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (dialogState.entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.update_history_dialog_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        dialogState.entries.forEach { entry ->
                            UpdateHistoryEntryCard(entry = entry)
                        }
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = actions.onDismissReleaseHistoryDialog) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

}


@Composable
internal fun UpdateHistoryEntryCard(
    entry: SettingsScreenViewModel.UpdateHistoryEntryState,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.update_history_dialog_entry_title, entry.version),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(
                    R.string.update_history_dialog_published_at,
                    entry.publishedAtText
                ),
                style = MaterialTheme.typography.bodySmall
            )
            SimpleMarkdownCard(
                title = stringResource(R.string.update_dialog_notes_title),
                markdown = entry.notesText
            )
        }
    }
}

