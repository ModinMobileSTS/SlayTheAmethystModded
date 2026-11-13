package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.LauncherAlertDialog
import io.stamethyst.ui.settings.common.ForceHighRefreshRateSetting

@Composable
internal fun RefreshRateSettingsDialog(
    onDismissRequest: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    LauncherAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.main_refresh_rate_mismatch_title)) },
        text = {
            // LauncherAlertDialog owns scrolling; a nested vertical scroller gets infinite height.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.main_refresh_rate_settings_message))
                ForceHighRefreshRateSetting(enabled = enabled)
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenDisplaySettings) {
                Text(stringResource(R.string.main_refresh_rate_open_settings))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_action_confirm))
            }
        },
    )
}
