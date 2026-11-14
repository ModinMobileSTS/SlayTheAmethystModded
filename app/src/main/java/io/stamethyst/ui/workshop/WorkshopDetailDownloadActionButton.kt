package io.stamethyst.ui.workshop

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.stamethyst.R

/** Detail loading never hides the action; only an explicit click starts download preparation. */
@Composable
internal fun WorkshopDetailDownloadActionButton(
    state: WorkshopModDownloadState,
    preparing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (preparing) {
        OutlinedButton(onClick = onClick, enabled = false, modifier = modifier) {
            Text(stringResource(R.string.workshop_status_checking_dependencies))
        }
    } else {
        WorkshopDownloadActionButton(state = state, onClick = onClick, modifier = modifier)
    }
}
