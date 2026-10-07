package io.stamethyst.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.config.SlingBreakEngineMode
import io.stamethyst.backend.resources.ResourcePackSlowDownloadMirrorSwitch
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.ui.resources.ResourcePreparationContent
import io.stamethyst.ui.settings.common.HapticTextButton
import io.stamethyst.ui.settings.common.SettingsChoiceDialogItem
import io.stamethyst.ui.settings.common.SettingsChoiceSpec

@Composable
internal fun SettingsSlingBreakModeItem(
    mode: SlingBreakEngineMode,
    enabled: Boolean,
    onModeChanged: (SlingBreakEngineMode) -> Unit,
) {
    var showDownloadConfirmation by rememberSaveable { mutableStateOf(false) }
    SettingsChoiceDialogItem(
        SettingsChoiceSpec(
            title = stringResource(R.string.settings_sling_break_mode_title),
            valueText = slingBreakEngineModeLabel(mode),
            enabled = enabled,
            selectedValue = mode,
            options = SlingBreakEngineMode.entries,
            optionLabel = { slingBreakEngineModeLabel(it) },
            onOptionSelected = { selected ->
                if (selected == SlingBreakEngineMode.COMPATIBILITY) showDownloadConfirmation = true
                else if (selected != mode) onModeChanged(selected)
            },
        )
    )
    if (showDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirmation = false },
            title = { Text(stringResource(R.string.settings_sling_break_mode_compatibility)) },
            text = { Text(stringResource(R.string.settings_sling_break_download_confirmation)) },
            confirmButton = {
                HapticTextButton(enabled = enabled, onClick = {
                    showDownloadConfirmation = false
                    onModeChanged(SlingBreakEngineMode.COMPATIBILITY)
                }) { Text(stringResource(R.string.settings_sling_break_download_yes)) }
            },
            dismissButton = {
                HapticTextButton(onClick = { showDownloadConfirmation = false }) {
                    Text(stringResource(R.string.settings_sling_break_download_no))
                }
            },
        )
    }
}

@Composable
private fun slingBreakEngineModeLabel(mode: SlingBreakEngineMode): String = stringResource(
    when (mode) {
        SlingBreakEngineMode.WEBVIEW -> R.string.settings_sling_break_mode_webview
        SlingBreakEngineMode.COMPATIBILITY -> R.string.settings_sling_break_mode_compatibility
    }
)

@Composable
internal fun SlingBreakDependencyDialogs(
    progress: Int?,
    message: String,
    failure: String?,
    slowDownloadSwitch: ResourcePackSlowDownloadMirrorSwitch?,
    onRetry: () -> Unit,
    onSwitchMirror: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    val context = LocalContext.current
    if (progress != null || failure != null) {
        var selectedMirror by remember { mutableStateOf(UpdateMirrorManager.current(context)) }
        AlertDialog(
            onDismissRequest = { if (progress == null) onDismissFailure() },
            properties = DialogProperties(
                dismissOnBackPress = progress == null,
                dismissOnClickOutside = progress == null,
            ),
            text = {
                ResourcePreparationContent(
                    title = stringResource(
                        if (failure == null) R.string.settings_sling_break_web_preparing_title
                        else R.string.settings_sling_break_compatibility_failed,
                    ),
                    progress = progress,
                    message = message,
                    failure = failure,
                    selectedMirror = selectedMirror,
                    availableMirrors = remember(context) { UpdateMirrorManager.selectableSources(context) },
                    slowDownloadSwitch = slowDownloadSwitch,
                    onMirrorSelected = { source ->
                        selectedMirror = source
                        UpdateMirrorManager.saveCurrent(context, source)
                        onRetry()
                    },
                    onSlowDownloadMirrorSwitch = {
                        slowDownloadSwitch?.nextPreferredMirrorSource?.let { source ->
                            selectedMirror = source
                            UpdateMirrorManager.saveCurrent(context, source)
                        }
                        onSwitchMirror()
                    },
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                if (progress == null) {
                    HapticTextButton(onClick = onDismissFailure) {
                        Text(stringResource(R.string.common_action_close))
                    }
                }
            },
        )
    }
}
