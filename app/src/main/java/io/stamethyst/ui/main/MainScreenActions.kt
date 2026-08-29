package io.stamethyst.ui.main

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.stamethyst.backend.easytier.EasyTierPermissionCoordinator
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
    val onDeleteMods: (List<ModItemUi>) -> Unit = {},
    val onExportMod: (ModItemUi) -> Unit = {},
    val onShareMod: (ModItemUi) -> Unit = {},
    val onRenameModAlias: (ModItemUi, String) -> Unit = { _, _ -> },
    val onRestoreModOriginalName: (ModItemUi) -> Unit = {},
    val onPatchWorkshopMod: (ModItemUi) -> Unit = {},
    val onRetryWorkshopDownload: (ModItemUi) -> Unit = {},
    val onUpdateWorkshopMod: (ModItemUi) -> Unit = {},
    val onUpgradeWorkshopImportPatches: (ModItemUi) -> Unit = {},
    val onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    val onSetImportPatchEnabled: (ModItemUi, String, Boolean) -> Unit = { _, _, _ -> },
    val onToggleMod: (ModItemUi, Boolean) -> Unit = { _, _ -> },
    val onAssociateMods: (ModItemUi, ModItemUi) -> Unit = { _, _ -> },
    val onRemoveModAssociation: (ModItemUi, ModItemUi) -> Unit = { _, _ -> },
    val onClearModAssociationGroup: (ModItemUi) -> Unit = {},
    val onSetPriority: (ModItemUi, Int?) -> Unit = { _, _ -> },
    val onSetModFavorite: (ModItemUi, Boolean) -> Unit = { _, _ -> },
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
    val onAssignModsToFolder: (List<ModItemUi>, String) -> Unit = { _, _ -> },
    val onMoveModsToUnassigned: (List<ModItemUi>) -> Unit = {},
    val onSetModsSelected: (List<ModItemUi>, Boolean) -> Unit = { _, _ -> },
    val onAddModLaunchProfile: (String) -> Unit = {},
    val onSelectModLaunchProfile: (String) -> Unit = {},
    val onRenameModLaunchProfile: (String, String) -> Unit = { _, _ -> },
    val onDeleteModLaunchProfile: (String) -> Unit = {},
    val onRevealFolderToken: (String) -> Unit = {},
    val onRetryStorageCheck: () -> Unit = {},
    val onDismissCrashRecovery: () -> Unit = {},
    val onAskAiAfterCrash: () -> Unit = {},
    val onConfirmLaunchWithUnreadSuggestions: () -> Unit = {},
    val onCancelLaunchWithUnreadSuggestions: () -> Unit = {},
    val onCopyCrashReport: () -> Unit = {},
    val onShareCrashRecoveryReport: () -> Unit = {},
    val onReturnToMainMenu: () -> Unit = {},
    val onImportMods: () -> Unit = {},
    val onOpenWorkshop: () -> Unit = {},
    val onLaunch: () -> LaunchRequestAction = { LaunchRequestAction.NONE },
    val onLaunchAfterSteamCloudError: () -> Unit = {},
    val onRefreshSteamCloudStatus: () -> Unit = {},
    val onCancelSteamCloudCheck: () -> Unit = {},
    val onCancelSteamCloudSync: () -> Unit = {},
    val onUseLocalSteamCloudProgress: () -> Unit = {},
    val onBackgroundUseLocalSteamCloudProgressAndLaunch: () -> Unit = {},
    val onBackgroundSteamCloudSyncAndLaunch: () -> Unit = {},
    val onUseCloudSteamCloudProgress: () -> Unit = {},
    val shouldPromptSteamCloudDirectMode: () -> Boolean = { false },
    val onSwitchSteamCloudDirectMode: () -> Unit = {},
    val onRefreshSteamAchievements: () -> Unit = {},
    val onSetSteamAchievementUnlocked: (String, Boolean) -> Unit = { _, _ -> },
    val onSyncSteamAchievements: () -> Unit = {},
    val onConnectEasyTier: (String?) -> Unit = {},
    val onDisconnectEasyTier: () -> Unit = {},
    val onRefreshEasyTierRooms: () -> Unit = {},
    val onRefreshEasyTierRoomsSilently: () -> Unit = {},
    val onSelectEasyTierRoom: (String) -> Unit = {},
    val onCreateEasyTierRoom: (String, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    val onLockEasyTierRoom: () -> Unit = {},
    val onUnlockEasyTierRoom: () -> Unit = {},
    val onCloseEasyTierRoom: () -> Unit = {},
    val onKickEasyTierRoomMember: (String, String) -> Unit = { _, _ -> },
)

@Composable
internal fun rememberMainScreenActions(
    viewModel: MainScreenViewModel,
    hostActivity: Activity?,
    importModsLauncher: ActivityResultLauncher<Array<String>>,
    easyTierVpnPermissionLauncher: ActivityResultLauncher<Intent>,
    onOpenWorkshop: () -> Unit = {},
    onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
): MainScreenActions {
    return remember(
        viewModel,
        hostActivity,
        importModsLauncher,
        easyTierVpnPermissionLauncher,
        onOpenWorkshop,
        onOpenWorkshopDetails,
    ) {
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
                onDeleteMods = { mods -> viewModel.onDeleteMods(activity, mods) },
                onExportMod = { mod -> viewModel.onExportMod(activity, mod) },
                onShareMod = { mod -> viewModel.onShareMod(activity, mod) },
                onRenameModAlias = { mod, alias -> viewModel.onRenameModAlias(activity, mod, alias) },
                onRestoreModOriginalName = { mod -> viewModel.onRestoreModOriginalName(activity, mod) },
                onPatchWorkshopMod = { mod -> viewModel.onPatchWorkshopMod(activity, mod) },
                onRetryWorkshopDownload = { mod -> viewModel.onRetryWorkshopDownload(activity, mod) },
                onUpdateWorkshopMod = { mod -> viewModel.onUpdateWorkshopMod(activity, mod) },
                onUpgradeWorkshopImportPatches = { mod ->
                    viewModel.onUpgradeWorkshopImportPatches(activity, mod)
                },
                onOpenWorkshopDetails = onOpenWorkshopDetails,
                onSetImportPatchEnabled = { mod, moduleId, enabled ->
                    viewModel.onSetImportPatchEnabled(activity, mod, moduleId, enabled)
                },
                onToggleMod = { mod, checked -> viewModel.onToggleMod(activity, mod, checked) },
                onAssociateMods = { source, target -> viewModel.associateMods(activity, source, target) },
                onRemoveModAssociation = { source, target ->
                    viewModel.removeModAssociation(activity, source, target)
                },
                onClearModAssociationGroup = { mod -> viewModel.clearModAssociationGroup(activity, mod) },
                onSetPriority = { mod, priority ->
                    viewModel.onSetPriority(activity, mod, priority)
                },
                onSetModFavorite = { mod, favorite ->
                    viewModel.onSetModFavorite(activity, mod, favorite)
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
                onAssignModsToFolder = { mods, folderId -> viewModel.assignModsToFolder(activity, mods, folderId) },
                onMoveModsToUnassigned = { mods -> viewModel.moveModsToUnassigned(activity, mods) },
                onSetModsSelected = { mods, selected -> viewModel.setModsSelected(activity, mods, selected) },
                onAddModLaunchProfile = { name -> viewModel.addModLaunchProfile(activity, name) },
                onSelectModLaunchProfile = { profileId -> viewModel.selectModLaunchProfile(activity, profileId) },
                onRenameModLaunchProfile = { profileId, name ->
                    viewModel.renameModLaunchProfile(activity, profileId, name)
                },
                onDeleteModLaunchProfile = { profileId -> viewModel.deleteModLaunchProfile(activity, profileId) },
                onRevealFolderToken = { folderTokenId -> viewModel.revealFolderToken(activity, folderTokenId) },
                onRetryStorageCheck = { viewModel.refresh(activity) },
                onDismissCrashRecovery = { viewModel.dismissCrashRecovery() },
                onAskAiAfterCrash = { viewModel.copyCrashRecoveryAiPrompt(activity) },
                onConfirmLaunchWithUnreadSuggestions = {
                    viewModel.confirmLaunchWithUnreadSuggestions(activity)
                },
                onCancelLaunchWithUnreadSuggestions = {
                    viewModel.cancelLaunchWithUnreadSuggestions()
                },
                onCopyCrashReport = { viewModel.copyCrashRecoveryReport(activity) },
                onShareCrashRecoveryReport = { viewModel.shareCrashRecoveryReport(activity) },
                onReturnToMainMenu = { viewModel.dismissCrashRecovery() },
                onImportMods = {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
                onOpenWorkshop = onOpenWorkshop,
                onLaunch = { viewModel.onLaunchRequested(activity) },
                onLaunchAfterSteamCloudError = { viewModel.onLaunchAfterSteamCloudError(activity) },
                onRefreshSteamCloudStatus = {
                    viewModel.syncSteamCloudIndicatorIfNeeded(activity, force = true)
                },
                onCancelSteamCloudCheck = {
                    viewModel.cancelSteamCloudCheck(activity)
                },
                onCancelSteamCloudSync = {
                    viewModel.cancelSteamCloudSync(activity)
                },
                onUseLocalSteamCloudProgress = {
                    viewModel.onUseLocalSteamCloudProgress(activity)
                },
                onBackgroundUseLocalSteamCloudProgressAndLaunch = {
                    viewModel.onBackgroundUseLocalSteamCloudProgressAndLaunch(activity)
                },
                onBackgroundSteamCloudSyncAndLaunch = {
                    viewModel.onBackgroundSteamCloudSyncAndLaunch(activity)
                },
                onUseCloudSteamCloudProgress = {
                    viewModel.onUseCloudSteamCloudProgress(activity)
                },
                shouldPromptSteamCloudDirectMode = {
                    viewModel.shouldPromptSteamCloudDirectMode(activity)
                },
                onSwitchSteamCloudDirectMode = {
                    viewModel.switchSteamCloudDirectMode(activity)
                },
                onRefreshSteamAchievements = {
                    viewModel.refreshSteamAchievements(activity)
                },
                onSetSteamAchievementUnlocked = { apiName, unlocked ->
                    viewModel.setSteamAchievementUnlocked(activity, apiName, unlocked)
                },
                onSyncSteamAchievements = { viewModel.syncSteamAchievements(activity) },
                onConnectEasyTier = { password ->
                    val permissionIntent = EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        easyTierVpnPermissionLauncher.launch(permissionIntent)
                    } else {
                        // null means "reuse whatever password was remembered for this room".
                        viewModel.onConnectEasyTier(activity, password = password)
                    }
                },
                onDisconnectEasyTier = {
                    viewModel.onDisconnectEasyTier(activity)
                },
                onRefreshEasyTierRooms = {
                    viewModel.refreshEasyTierRooms(activity, forceRoomInfoReload = true)
                },
                onRefreshEasyTierRoomsSilently = {
                    viewModel.refreshEasyTierRooms(
                        activity,
                        forceRoomInfoReload = true,
                        showLoading = false,
                    )
                },
                onSelectEasyTierRoom = { roomId ->
                    viewModel.selectEasyTierRoom(activity, roomId)
                },
                onCreateEasyTierRoom = { roomId, description, password, allowNewJoins ->
                    val permissionIntent = EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.queueEasyTierRoomCreation(roomId, description, password, allowNewJoins)
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        easyTierVpnPermissionLauncher.launch(permissionIntent)
                    } else {
                        viewModel.createEasyTierRoom(activity, roomId, description, password, allowNewJoins)
                    }
                },
                onLockEasyTierRoom = {
                    viewModel.lockEasyTierRoom(activity)
                },
                onUnlockEasyTierRoom = {
                    viewModel.unlockEasyTierRoom(activity)
                },
                onCloseEasyTierRoom = {
                    viewModel.closeEasyTierRoom(activity)
                },
                onKickEasyTierRoomMember = { playerId, message ->
                    viewModel.kickEasyTierRoomMember(activity, playerId, message)
                },
            )
        }
    }
}
