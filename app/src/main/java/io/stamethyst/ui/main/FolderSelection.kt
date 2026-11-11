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

/** Long press always adds matching children, even when the whole group is already selected. */
internal fun selectFolderBatchSelection(selected: Set<String>, visible: Set<String>): Set<String> =
    selected + visible

/** Search and collapse do not invalidate selections; only the full eligible inventory does. */
internal fun retainEligibleBatchSelection(selected: Set<String>, eligible: Set<String>): Set<String> =
    selected intersect eligible

internal fun batchSelectionState(selected: Set<String>, scope: Set<String>): ToggleableState =
    folderAggregateState(scope.count { it in selected }, scope.size)
