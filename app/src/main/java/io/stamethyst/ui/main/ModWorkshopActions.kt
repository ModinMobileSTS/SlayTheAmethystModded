package io.stamethyst.ui.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState

/** The card and its tools panel must offer the same way to finish a market installation. */
@Composable
internal fun ModWorkshopActions(
    mod: ModItemUi,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRetryDownload: () -> Unit,
    onManualHandle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val workshop = mod.workshop ?: return
    val actionModifier = modifier.fillMaxWidth()
    when (workshop.state) {
        WorkshopModState.ImportedUnpatched,
        WorkshopModState.UpdatePendingInstall -> Button(
            onClick = onInstall,
            enabled = enabled,
            modifier = actionModifier,
        ) {
            Text(stringResource(R.string.main_mod_workshop_action_install))
        }
        WorkshopModState.DownloadFailed,
        WorkshopModState.DownloadPaused,
        WorkshopModState.FileMissing -> {
            val label = when (workshop.state) {
                WorkshopModState.DownloadPaused -> R.string.main_mod_workshop_action_continue_download
                WorkshopModState.FileMissing -> R.string.main_mod_workshop_action_redownload
                else -> R.string.workshop_action_retry
            }
            if (workshop.downloadBlocked) {
                OutlinedButton(onClick = {}, enabled = false, modifier = actionModifier) {
                    Text(stringResource(R.string.workshop_download_state_unavailable))
                }
            } else {
                Button(onClick = onRetryDownload, enabled = enabled, modifier = actionModifier) {
                    Text(stringResource(label))
                }
            }
        }
        WorkshopModState.NonStandardDownloaded -> if (onManualHandle != null) {
            OutlinedButton(onClick = onManualHandle, enabled = enabled, modifier = actionModifier) {
                Text(stringResource(R.string.main_mod_workshop_action_manual_handle))
            }
        }
        WorkshopModState.Downloading -> {
            val progress = workshop.downloadProgressPercent?.coerceIn(0, 100)?.let { it / 100f }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = actionModifier)
            } else {
                LinearProgressIndicator(modifier = actionModifier)
            }
        }
        // Waiting/cancelling is conveyed by status text, never an active transfer animation.
        else -> Unit
    }
}
