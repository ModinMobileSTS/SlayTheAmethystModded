package io.stamethyst.ui.main

import androidx.compose.ui.state.ToggleableState

internal fun folderAggregateState(active: Int, total: Int): ToggleableState = when {
    active == 0 || total == 0 -> ToggleableState.Off
    active == total -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}

/** Only visible eligible children participate; selections in other groups are preserved. */
internal fun toggleFolderBatchSelection(selected: Set<String>, visible: Set<String>): Set<String> =
    if (visible.all { it in selected }) selected - visible else selected + visible
