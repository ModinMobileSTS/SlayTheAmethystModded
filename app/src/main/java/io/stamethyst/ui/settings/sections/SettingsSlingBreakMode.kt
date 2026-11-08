package io.stamethyst.ui.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.backend.resources.ResourcePackSlowDownloadMirrorSwitch
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.ui.resources.ResourcePreparationContent
import io.stamethyst.ui.settings.common.HapticTextButton

@Composable
internal fun SlingBreakDownloadConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.godot_settings_title)) },
        text = { Text(stringResource(R.string.godot_download_confirmation)) },
        confirmButton = { HapticTextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_sling_break_download_yes)) } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_sling_break_download_no)) } },
    )
}

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
            scrollableText = false,
            onDismissRequest = { if (progress == null) onDismissFailure() },
            properties = DialogProperties(
                dismissOnBackPress = progress == null,
                dismissOnClickOutside = progress == null,
            ),
            text = {
                ResourcePreparationContent(
                    title = stringResource(
                        if (failure == null) R.string.godot_preparing
                        else R.string.godot_prepare_failed,
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
