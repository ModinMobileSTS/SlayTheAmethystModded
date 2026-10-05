package io.stamethyst.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.config.SlingBreakEngineMode
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
internal fun SlingBreakX5Dialogs(progress: Int?, failure: String?, onDismissFailure: () -> Unit) {
    if (progress != null) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.settings_sling_break_web_preparing_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(when {
                            progress < 0 -> R.string.settings_sling_break_web_preparing
                            progress >= 100 -> R.string.settings_sling_break_web_unpacking
                            else -> R.string.settings_sling_break_web_downloading
                        }, progress),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (progress < 0 || progress >= 100) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
    if (failure != null) {
        AlertDialog(
            onDismissRequest = onDismissFailure,
            title = { Text(stringResource(R.string.settings_sling_break_compatibility_failed)) },
            text = { Text(failure) },
            confirmButton = {
                HapticTextButton(onClick = onDismissFailure) {
                    Text(stringResource(R.string.common_action_close))
                }
            },
        )
    }
}
