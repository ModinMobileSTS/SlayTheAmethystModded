package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.stamethyst.R

@Composable
internal fun BatchSelectAllAction(state: BatchEditBarState) {
    Row(
        modifier = Modifier.triStateToggleable(
            state = state.selectAllState,
            enabled = state.selectAllEnabled,
            role = Role.Checkbox,
            onClick = state.onToggleSelectAll,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state.selectAllState, onClick = null, enabled = state.selectAllEnabled)
        Text(
            stringResource(if (state.selectionFiltered) R.string.mod_select_all_results else R.string.mod_select_all),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
