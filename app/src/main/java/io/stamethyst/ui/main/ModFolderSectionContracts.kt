package io.stamethyst.ui.main

import androidx.compose.ui.state.ToggleableState
import io.stamethyst.model.ModItemUi

internal data class ModFolderSectionCallbacks(
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onDeleteFolder: (String) -> Unit = {},
    val onSetFolderSelected: (String, Boolean) -> Unit = { _, _ -> },
    val onSetUnassignedSelected: (Boolean) -> Unit = {},
    val onToggleFolderCollapsed: (String) -> Unit = {},
    val onToggleUnassignedCollapsed: () -> Unit = {},
    val onToggleStatusSummaryCollapsed: () -> Unit = {},
    val onMoveFolderUp: (String) -> Unit = {},
    val onMoveFolderDown: (String) -> Unit = {},
    val onMoveUnassignedUp: () -> Unit = {},
    val onMoveUnassignedDown: () -> Unit = {},
    val onMoveFolderTokenToIndex: (String, Int) -> Unit = { _, _ -> },
    val onAssignModToFolder: (ModItemUi, String) -> Unit = { _, _ -> },
    val onMoveModToUnassigned: (ModItemUi) -> Unit = {},
    val onCollapseAllFoldersForModDrag: () -> Unit = {},
    val onExpandOnlySourceFolderAfterModDrag: (String) -> Unit = {},
    val onCollapseAllFoldersForDragWithSnapshot: () -> MainScreenViewModel.FolderCollapseSnapshot? = { null },
    val onRestoreFolderCollapseSnapshot: (MainScreenViewModel.FolderCollapseSnapshot?) -> Unit = {}
)

internal enum class StatusTone {
    Ok,
    Warn,
    Error,
    Info
}

internal data class StatusSummaryEntry(
    val label: String,
    val value: String,
    val tone: StatusTone
)

internal data class StatusSummaryUiModel(
    val entries: List<StatusSummaryEntry>
) {
    val hasEntries: Boolean = entries.isNotEmpty()
    val okCount: Int = entries.count { it.tone == StatusTone.Ok }
    val totalCount: Int = entries.size
}

internal data class FolderUiModel(
    val key: String,
    val folderTokenId: String,
    val folderName: String,
    val mods: List<ModItemUi>,
    val isCollapsed: Boolean,
    val isUnassigned: Boolean,
    val emptyTextResId: Int,
    val selectedCount: Int,
    val toggleState: ToggleableState
)
