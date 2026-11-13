package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.stamethyst.R

@Composable
internal fun BatchSelectAllAction(state: BatchEditBarState) {
    val actionDescription = stringResource(
        if (state.selectionFiltered) R.string.mod_select_all_results else R.string.mod_select_all
    )
    Row(
        modifier = Modifier.triStateToggleable(
            state = state.selectAllState,
            enabled = state.selectAllEnabled,
            role = Role.Checkbox,
            onClick = state.onToggleSelectAll,
        ).semantics { contentDescription = actionDescription }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state.selectAllState, onClick = null, enabled = state.selectAllEnabled)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.main_batch_edit_selection_count_format, state.selectedCount, state.totalCount),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
