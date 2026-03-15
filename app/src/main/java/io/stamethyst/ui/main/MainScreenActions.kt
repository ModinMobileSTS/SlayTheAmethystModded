package io.stamethyst.ui.main

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.stamethyst.model.ModItemUi

internal data class MainScreenActions(
    val isHostAvailable: Boolean,
    val onSuggestNextFolderName: () -> String = { "文件夹1" },
    val onAddFolder: (String) -> Unit = {},
    val onRenameFolder: (String, String) -> Unit = { _, _ -> },
    val onDeleteFolder: (String) -> Unit = {},
    val onDeleteMod: (ModItemUi) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModFile: (ModItemUi, String) -> Unit = { _, _ -> },
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onTogglePriorityLoad: (ModItemUi, Boolean) -> Unit = { _, _ -> },
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
    val onRestoreFolderCollapseSnapshot: (MainScreenViewModel.FolderCollapseSnapshot?) -> Unit = {},
    val onRetryStorageCheck: () -> Unit = {},
    val onImportMods: () -> Unit = {},
    val onLaunch: () -> Unit = {}
)

@Composable
internal fun rememberMainScreenActions(
    viewModel: MainScreenViewModel,
    hostActivity: Activity?,
    importModsLauncher: ActivityResultLauncher<Array<String>>
): MainScreenActions {
    return remember(viewModel, hostActivity, importModsLauncher) {
        val activity = hostActivity
        if (activity == null) {
            MainScreenActions(isHostAvailable = false)
        } else {
            MainScreenActions(
                isHostAvailable = true,
                onSuggestNextFolderName = { viewModel.suggestNextFolderName() },
                onAddFolder = { name -> viewModel.addFolder(activity, name) },
                onRenameFolder = { folderId, name -> viewModel.renameFolder(activity, folderId, name) },
                onDeleteFolder = { folderId -> viewModel.deleteFolder(activity, folderId) },
                onDeleteMod = { mod -> viewModel.onDeleteMod(activity, mod) },
                onExportMod = { mod -> viewModel.onExportMod(activity, mod) },
                onShareMod = { mod -> viewModel.onShareMod(activity, mod) },
                onRenameModFile = { mod, newFileName -> viewModel.onRenameModFile(activity, mod, newFileName) },
                onToggleMod = { mod, checked -> viewModel.onToggleMod(activity, mod, checked) },
                onTogglePriorityLoad = { mod, enabled ->
                    viewModel.onTogglePriorityLoad(activity, mod, enabled)
                },
                onSetFolderSelected = { folderId, selected -> viewModel.setFolderSelected(activity, folderId, selected) },
                onSetUnassignedSelected = { selected -> viewModel.setUnassignedSelected(activity, selected) },
                onToggleFolderCollapsed = { folderId -> viewModel.toggleFolderCollapsed(activity, folderId) },
                onToggleUnassignedCollapsed = { viewModel.toggleUnassignedCollapsed(activity) },
                onToggleStatusSummaryCollapsed = { viewModel.toggleStatusSummaryCollapsed(activity) },
                onMoveFolderUp = { folderId -> viewModel.moveFolderUp(activity, folderId) },
                onMoveFolderDown = { folderId -> viewModel.moveFolderDown(activity, folderId) },
                onMoveUnassignedUp = { viewModel.moveUnassignedUp(activity) },
                onMoveUnassignedDown = { viewModel.moveUnassignedDown(activity) },
                onMoveFolderTokenToIndex = { folderId, index -> viewModel.moveFolderTokenToIndex(activity, folderId, index) },
                onAssignModToFolder = { mod, folderId -> viewModel.assignModToFolder(activity, mod, folderId) },
                onMoveModToUnassigned = { mod -> viewModel.moveModToUnassigned(activity, mod) },
                onCollapseAllFoldersForModDrag = { viewModel.collapseAllFoldersForModDrag(activity) },
                onExpandOnlySourceFolderAfterModDrag = { folderTokenId ->
                    viewModel.expandOnlySourceFolderAfterModDrag(activity, folderTokenId)
                },
                onCollapseAllFoldersForDragWithSnapshot = { viewModel.collapseAllFoldersForDragWithSnapshot(activity) },
                onRestoreFolderCollapseSnapshot = { snapshot ->
                    viewModel.restoreFolderCollapseSnapshot(activity, snapshot)
                },
                onRetryStorageCheck = { viewModel.refresh(activity) },
                onImportMods = {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
                onLaunch = { viewModel.onLaunch(activity) }
            )
        }
    }
}
