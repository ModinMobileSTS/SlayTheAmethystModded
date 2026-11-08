package io.stamethyst.ui.settings.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.settings.core.SettingsLoadDomain
import io.stamethyst.ui.settings.core.SettingsLoadState
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

internal val LocalSettingsLoadRetry = staticCompositionLocalOf<((SettingsLoadDomain) -> Unit)?> { null }
internal val LocalSettingsControlsEnabled = staticCompositionLocalOf { true }

/** Nested dependencies may disable controls, but must never re-enable a disabled parent. */
@Composable
internal fun settingsControlEnabled(enabled: Boolean = true): Boolean =
    LocalSettingsControlsEnabled.current && enabled

/** Keep controls in place while dependencies are unknown, without a loading placeholder. */
@Composable
internal fun SettingsDependencyContent(
    uiState: SettingsScreenViewModel.UiState,
    vararg domains: SettingsLoadDomain,
    content: @Composable () -> Unit,
) {
    val loadState = uiState.loadState(*domains)
    CompositionLocalProvider(
        LocalSettingsControlsEnabled provides settingsControlEnabled(loadState == SettingsLoadState.READY),
        content = content,
    )
    if (loadState == SettingsLoadState.ERROR) {
        val retry = LocalSettingsLoadRetry.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_value_load_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (retry != null) {
                TextButton(onClick = {
                    domains.filter { uiState.loadState(it) == SettingsLoadState.ERROR }.forEach(retry)
                }) { Text(stringResource(R.string.settings_value_retry)) }
            }
        }
    }
}
