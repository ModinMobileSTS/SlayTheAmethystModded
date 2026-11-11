package io.stamethyst.ui.settings.sections

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.ui.refresh.RefreshRateDiagnosticsActivity
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

@Composable
internal fun RefreshRateDiagnosticsSetting(uiState: SettingsScreenViewModel.UiState, settingTitleResId: Int? = null) {
    val context = LocalContext.current
    if (settingTitleResId == null || settingTitleResId == R.string.refresh_diagnostics_title) {
        SettingsActionListItem(
            title = stringResource(R.string.refresh_diagnostics_title),
            supportingText = stringResource(R.string.refresh_diagnostics_description),
            enabled = !uiState.busy,
            onClick = { context.startActivity(Intent(context, RefreshRateDiagnosticsActivity::class.java)) },
        )
    }
    if (settingTitleResId == null || settingTitleResId == R.string.refresh_diagnostics_latest) {
        SettingsActionListItem(
            title = stringResource(R.string.refresh_diagnostics_latest),
            enabled = !uiState.busy,
            onClick = {
                context.startActivity(Intent(context, RefreshRateDiagnosticsActivity::class.java)
                    .putExtra(RefreshRateDiagnosticsActivity.EXTRA_REVIEW, true))
            },
        )
    }
}
