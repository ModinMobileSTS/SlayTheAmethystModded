package io.stamethyst.ui.settings.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.backend.render.ForceHighRefreshRateController
import io.stamethyst.config.LauncherConfig

/** Both the mismatch dialog and performance settings edit the same immediately applied option. */
@Composable
internal fun ForceHighRefreshRateSetting(enabled: Boolean = true) {
    val context = LocalContext.current
    var checked by remember(context) {
        mutableStateOf(LauncherConfig.isForceHighRefreshRateEnabled(context))
    }
    DisposableEffect(context) {
        val listener: (Boolean) -> Unit = { checked = it }
        ForceHighRefreshRateController.addListener(listener)
        onDispose { ForceHighRefreshRateController.removeListener(listener) }
    }
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = checked,
            enabled = settingsControlEnabled(enabled),
            title = stringResource(R.string.settings_force_high_refresh_rate_title),
            description = stringResource(R.string.settings_force_high_refresh_rate_desc),
            chipText = stringResource(R.string.settings_ram_saver_experimental_chip),
            onCheckedChange = { ForceHighRefreshRateController.setEnabled(context, it) },
        )
    )
}
