package io.stamethyst.ui.main

import androidx.compose.ui.state.ToggleableState
import io.stamethyst.model.ModItemUi

internal data class ModFolderSectionCallbacks(
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onDeleteMods: (List<ModItemUi>) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    val onSetPriority: (ModItemUi, Int?) -> Unit = { _, _ -> },
    val onMarkModSuggestionRead: (ModItemUi, String) -> Unit = { _, _ -> },
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onDeleteFolder: (String) -> Unit = {},
    val onSetFolderSelected: (String, Boolean) -> Unit = { _, _ -> },
    val onSetUnassignedSelected: (Boolean) -> Unit = {},
    val onToggleFolderCollapsed: (String) -> Unit = {},
    val onToggleUnassignedCollapsed: () -> Unit = {},
    val onToggleDependencyFolderCollapsed: () -> Unit = {},
    val onMoveFolderUp: (String) -> Unit = {},
    val onMoveFolderDown: (String) -> Unit = {},
    val onMoveUnassignedUp: () -> Unit = {},
    val onMoveUnassignedDown: () -> Unit = {},
    val onMoveFolderTokenToIndex: (String, Int) -> Unit = { _, _ -> },
    val onAssignModToFolder: (ModItemUi, String) -> Unit = { _, _ -> },
    val onMoveModToUnassigned: (ModItemUi) -> Unit = {},
    val onAssignModsToFolder: (List<ModItemUi>, String) -> Unit = { _, _ -> },
    val onMoveModsToUnassigned: (List<ModItemUi>) -> Unit = {},
    val onSetModsSelected: (List<ModItemUi>, Boolean) -> Unit = { _, _ -> },
    val onRevealFolderToken: (String) -> Unit = {}
)

internal data class FolderUiModel(
    val key: String,
    val folderTokenId: String,
    val folderName: String,
    val mods: List<ModItemUi>,
    val isCollapsed: Boolean,
    val isUnassigned: Boolean,
    val selectedCount: Int,
    val toggleState: ToggleableState
)
