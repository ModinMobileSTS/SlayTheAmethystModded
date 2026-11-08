package io.stamethyst.ui.settings.steamcloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudSyncProgress
import io.stamethyst.backend.steamcloud.steamCloudPhaseLabel

@Composable
internal fun SteamCloudPullProgressDialog(progress: SteamCloudSyncProgress) {
    val percent = progress.pullProgressPercent()
    AlertDialog(
        scrollableText = false,
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.settings_busy_steam_cloud_pull)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(progress.pullPhaseLabel()))
                if (percent == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (progress.totalFiles > 0) {
                    Text(stringResource(R.string.steam_cloud_pull_file_progress,
                        progress.completedFiles, progress.totalFiles, percent ?: 0))
                }
                if (progress.currentPath.isNotBlank()) {
                    Text(progress.currentPath, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.steam_cloud_pull_keep_open), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
    )
}

// Percentages describe the current phase, not the entire synchronization operation.
internal fun SteamCloudSyncProgress.pullProgressPercent(): Int? =
    progressPercent?.coerceIn(0, 100)
        ?: if (totalFiles > 0) ((completedFiles.toLong() * 100) / totalFiles).toInt().coerceIn(0, 100) else null

private fun SteamCloudSyncProgress.pullPhaseLabel(): Int = when (phase) {
    SteamCloudSyncPhase.CONNECTING -> R.string.steam_cloud_pull_connecting
    SteamCloudSyncPhase.LOGGING_ON -> R.string.steam_cloud_pull_logging_on
    SteamCloudSyncPhase.REFRESHING_MANIFEST -> R.string.settings_busy_steam_cloud_refresh
    SteamCloudSyncPhase.BACKING_UP_LOCAL -> R.string.steam_cloud_pull_backing_up
    SteamCloudSyncPhase.APPLYING_TO_LOCAL -> R.string.steam_cloud_pull_applying
    else -> steamCloudPhaseLabel(phase)
}
