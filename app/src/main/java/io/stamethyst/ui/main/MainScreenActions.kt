package io.stamethyst.ui.main

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.stamethyst.model.ModItemUi

internal enum class LaunchRequestAction {
    NONE,
    OPEN_STEAM_CLOUD_SHEET,
}

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
    val onSetPriority: (ModItemUi, Int?) -> Unit = { _, _ -> },
    val onMarkModSuggestionRead: (ModItemUi, String) -> Unit = { _, _ -> },
    val onSetFolderSelected: (String, Boolean) -> Unit = { _, _ -> },
    val onSetUnassignedSelected: (Boolean) -> Unit = {},
    val onToggleFolderCollapsed: (String) -> Unit = {},
    val onToggleUnassignedCollapsed: () -> Unit = {},
    val onToggleDependencyFolderCollapsed: () -> Unit = {},
    val onToggleDragLocked: () -> Unit = {},
    val onMoveFolderUp: (String) -> Unit = {},
    val onMoveFolderDown: (String) -> Unit = {},
    val onMoveUnassignedUp: () -> Unit = {},
    val onMoveUnassignedDown: () -> Unit = {},
    val onMoveFolderTokenToIndex: (String, Int) -> Unit = { _, _ -> },
    val onAssignModToFolder: (ModItemUi, String) -> Unit = { _, _ -> },
    val onMoveModToUnassigned: (ModItemUi) -> Unit = {},
    val onRevealFolderToken: (String) -> Unit = {},
    val onRetryStorageCheck: () -> Unit = {},
    val onDismissCrashRecovery: () -> Unit = {},
    val onRetryLaunchAfterCrash: () -> Unit = {},
    val onConfirmLaunchWithUnreadSuggestions: () -> Unit = {},
    val onCancelLaunchWithUnreadSuggestions: () -> Unit = {},
    val onCopyCrashReport: () -> Unit = {},
    val onShareCrashRecoveryReport: () -> Unit = {},
    val onCloseApp: () -> Unit = {},
    val onImportMods: () -> Unit = {},
    val onLaunch: () -> LaunchRequestAction = { LaunchRequestAction.NONE },
    val onRefreshSteamCloudStatus: () -> Unit = {},
    val onCancelSteamCloudCheck: () -> Unit = {},
    val onUseLocalSteamCloudProgress: () -> Unit = {},
    val onUseCloudSteamCloudProgress: () -> Unit = {},
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
                onSetPriority = { mod, priority ->
                    viewModel.onSetPriority(activity, mod, priority)
                },
                onMarkModSuggestionRead = { mod, suggestionText ->
                    viewModel.markModSuggestionRead(activity, mod, suggestionText)
                },
                onSetFolderSelected = { folderId, selected -> viewModel.setFolderSelected(activity, folderId, selected) },
                onSetUnassignedSelected = { selected -> viewModel.setUnassignedSelected(activity, selected) },
                onToggleFolderCollapsed = { folderId -> viewModel.toggleFolderCollapsed(activity, folderId) },
                onToggleUnassignedCollapsed = { viewModel.toggleUnassignedCollapsed(activity) },
                onToggleDependencyFolderCollapsed = { viewModel.toggleDependencyFolderCollapsed(activity) },
                onToggleDragLocked = { viewModel.toggleDragLocked(activity) },
                onMoveFolderUp = { folderId -> viewModel.moveFolderUp(activity, folderId) },
                onMoveFolderDown = { folderId -> viewModel.moveFolderDown(activity, folderId) },
                onMoveUnassignedUp = { viewModel.moveUnassignedUp(activity) },
                onMoveUnassignedDown = { viewModel.moveUnassignedDown(activity) },
                onMoveFolderTokenToIndex = { folderId, index -> viewModel.moveFolderTokenToIndex(activity, folderId, index) },
                onAssignModToFolder = { mod, folderId -> viewModel.assignModToFolder(activity, mod, folderId) },
                onMoveModToUnassigned = { mod -> viewModel.moveModToUnassigned(activity, mod) },
                onRevealFolderToken = { folderTokenId -> viewModel.revealFolderToken(activity, folderTokenId) },
                onRetryStorageCheck = { viewModel.refresh(activity) },
                onDismissCrashRecovery = { viewModel.dismissCrashRecovery() },
                onRetryLaunchAfterCrash = { viewModel.retryLaunchAfterCrash(activity) },
                onConfirmLaunchWithUnreadSuggestions = {
                    viewModel.confirmLaunchWithUnreadSuggestions(activity)
                },
                onCancelLaunchWithUnreadSuggestions = {
                    viewModel.cancelLaunchWithUnreadSuggestions()
                },
                onCopyCrashReport = { viewModel.copyCrashRecoveryReport(activity) },
                onShareCrashRecoveryReport = { viewModel.shareCrashRecoveryReport(activity) },
                onCloseApp = { activity.finishAffinity() },
                onImportMods = {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
                onLaunch = { viewModel.onLaunchRequested(activity) },
                onRefreshSteamCloudStatus = {
                    viewModel.syncSteamCloudIndicatorIfNeeded(activity, force = true)
                },
                onCancelSteamCloudCheck = {
                    viewModel.cancelSteamCloudCheck(activity)
                },
                onUseLocalSteamCloudProgress = {
                    viewModel.onUseLocalSteamCloudProgress(activity)
                },
                onUseCloudSteamCloudProgress = {
                    viewModel.onUseCloudSteamCloudProgress(activity)
                },
            )
        }
    }
}
