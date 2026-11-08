package io.stamethyst.ui.settings.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.settings.common.HapticTextButton
import io.stamethyst.ui.settings.common.SettingsRadioOptionRow

@Composable
internal fun SaveSlotDialog(
    state: SettingsScreenViewModel.SaveSlotDialogState,
    onConfirm: (SteamCloudSaveMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val importing = state.importUri != null
    var selectedMode by rememberSaveable(state.importUri, state.exportFileName) {
        mutableStateOf(state.selectedMode)
    }
    AlertDialog(
        scrollableText = false,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (importing) R.string.settings_import_saves else R.string.settings_export_saves
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        if (importing) R.string.settings_save_import_target_dialog_message
                        else R.string.settings_save_export_source_dialog_message
                    )
                )
                SettingsRadioOptionRow(
                    selected = selectedMode == SteamCloudSaveMode.STEAM_CLOUD,
                    enabled = state.cloudAvailable,
                    text = stringResource(
                        if (state.cloudAvailable) R.string.settings_save_export_source_cloud_action
                        else R.string.settings_save_import_target_cloud_action_disabled
                    ),
                    onSelect = { selectedMode = SteamCloudSaveMode.STEAM_CLOUD },
                )
                SettingsRadioOptionRow(
                    selected = selectedMode == SteamCloudSaveMode.INDEPENDENT,
                    enabled = true,
                    text = stringResource(R.string.settings_save_export_source_independent_action),
                    onSelect = { selectedMode = SteamCloudSaveMode.INDEPENDENT },
                )
            }
        },
        confirmButton = {
            HapticTextButton(
                enabled = selectedMode != SteamCloudSaveMode.STEAM_CLOUD || state.cloudAvailable,
                onClick = { onConfirm(selectedMode) },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
