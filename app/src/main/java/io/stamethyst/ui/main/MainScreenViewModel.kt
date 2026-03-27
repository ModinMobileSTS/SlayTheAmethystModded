package io.stamethyst.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.crash.LatestLogCleanShutdownDetector
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.crash.ProcessExitSummary
import io.stamethyst.backend.crash.SignalCrashDumpReader
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.launch.CrashReturnPayload
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.launch.LauncherReturnAction
import io.stamethyst.backend.launch.LauncherReturnActionResolver
import io.stamethyst.backend.launch.LauncherReturnSnapshot
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModSuggestionService
import io.stamethyst.backend.mods.StsDesktopJarPatcher
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.StsExternalStorageAccess
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherTransientNoticeDuration
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.JvmLogShareService
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Stable
class MainScreenViewModel : ViewModel() {
    data class ModFolder(
        val id: String,
        val name: String
    )

    data class FolderCollapseSnapshot(
        val folderCollapsed: Map<String, Boolean>,
        val unassignedCollapsed: Boolean
    )

    data class StorageIssueUi(
        val title: String,
        val message: String,
        val recovery: String
    )

    data class CrashRecoveryState(
        val code: Int,
        val isSignal: Boolean,
        val summaryText: String,
        val reportText: String,
        val isOutOfMemory: Boolean
    )

    data class UiState(
        val initializing: Boolean = true,
        val busy: Boolean = false,
        val busyOperation: UiBusyOperation = UiBusyOperation.NONE,
        val busyMessage: UiText? = null,
        val busyProgressPercent: Int? = null,
        val dependencyMods: List<ModItemUi> = emptyList(),
        val optionalMods: List<ModItemUi> = emptyList(),
        val storageIssue: StorageIssueUi? = null,
        val crashRecovery: CrashRecoveryState? = null,
        val controlsEnabled: Boolean = true,
        val gameProcessRunning: Boolean = false,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val modSuggestions: Map<String, String> = emptyMap(),
        val modFolders: List<ModFolder> = emptyList(),
        val folderAssignments: Map<String, String> = emptyMap(),
        val folderCollapsed: Map<String, Boolean> = emptyMap(),
        val unassignedCollapsed: Boolean = false,
        val dependencyFolderCollapsed: Boolean = true,
        val unassignedFolderName: String = DEFAULT_UNASSIGNED_FOLDER_NAME,
        val unassignedFolderOrder: Int = 0
    )

    sealed interface Effect {
        data class ShowSnackbar(
            val message: UiText,
            val duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT
        ) : Effect
        data class ShowDialog(val title: UiText, val message: UiText) : Effect
        data class OpenExportModPicker(
            val sourcePath: String,
            val suggestedName: String
        ) : Effect
        data class LaunchIntent(val intent: Intent) : Effect
    }

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 32)
    val effects = _effects.asSharedFlow()
    private val suggestionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentModSuggestions: Map<String, String> = emptyMap()
    private var modSuggestionSyncInProgress = false
    private var lastSuccessfulModSuggestionSyncSignature: String? = null

    var uiState by mutableStateOf(UiState())
        private set

    private val modManagementController = MainModManagementController(
        object : MainModManagementController.Host {
            override fun canEditMainScreenState(): Boolean {
                return this@MainScreenViewModel.canEditMainScreenState()
            }

            override fun isBusy(): Boolean {
                return uiState.busy
            }

            override fun setBusy(
                busy: Boolean,
                message: UiText?,
                operation: UiBusyOperation,
                progressPercent: Int?
            ) {
                this@MainScreenViewModel.setBusy(busy, message, operation, progressPercent)
            }

            override fun republish(host: Activity) {
                this@MainScreenViewModel.republish(host)
            }

            override fun emitEffect(effect: Effect) {
                _effects.tryEmit(effect)
            }
        }
    )

    fun refresh(host: Activity) {
        val storageIssue = detectStorageIssue(host)
        val hasJar = hasValidImportedStsJar(host)
        val hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar")
        val hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD)
        val hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB)
        currentModSuggestions = ModSuggestionService.loadCachedSuggestionMap(host)

        modManagementController.refresh(host, storageAccessible = storageIssue == null)
        publishUiState(
            host = host,
            hasJar = hasJar,
            hasMts = hasMts,
            hasBaseMod = hasBaseMod,
            hasStsLib = hasStsLib,
            storageIssue = storageIssue
        )
    }

    fun syncModSuggestionsIfNeeded(host: Activity) {
        val cachedSuggestions = ModSuggestionService.loadCachedSuggestionMap(host)
        if (cachedSuggestions != currentModSuggestions) {
            currentModSuggestions = cachedSuggestions
            uiState = uiState.copy(modSuggestions = currentModSuggestions)
        }

        val selectedSource = UpdateSource.normalizePreferredUserSource(
            LauncherPreferences.readPreferredUpdateMirrorId(host)
        )
        val syncSignature = "${ModSuggestionService.currentLocaleKey(host)}|${selectedSource.id}"
        if (modSuggestionSyncInProgress || lastSuccessfulModSuggestionSyncSignature == syncSignature) {
            return
        }

        modSuggestionSyncInProgress = true
        suggestionExecutor.execute {
            val result = runCatching {
                ModSuggestionService.sync(host, selectedSource)
            }.getOrNull()
            host.runOnUiThread {
                modSuggestionSyncInProgress = false
                if (host.isFinishing || host.isDestroyed || result == null) {
                    return@runOnUiThread
                }

                lastSuccessfulModSuggestionSyncSignature = syncSignature
                currentModSuggestions = result.snapshot.suggestions
                uiState = uiState.copy(modSuggestions = currentModSuggestions)
                if (result.contentChanged) {
                    _effects.tryEmit(
                        Effect.ShowDialog(
                            title = UiText.StringResource(R.string.main_mod_suggestion_update_title),
                            message = UiText.StringResource(R.string.main_mod_suggestion_update_message)
                        )
                    )
                }
            }
        }
    }

    fun onDeleteMod(host: Activity, mod: ModItemUi) {
        modManagementController.onDeleteMod(host, mod)
    }

    fun onExportMod(host: Activity, mod: ModItemUi) {
        modManagementController.onExportMod(host, mod)
    }

    fun onExportModPicked(host: Activity, sourcePath: String?, uri: Uri?) {
        modManagementController.onExportModPicked(host, sourcePath, uri)
    }

    fun onShareMod(host: Activity, mod: ModItemUi) {
        modManagementController.onShareMod(host, mod)
    }

    fun onRenameModFile(host: Activity, mod: ModItemUi, newFileNameInput: String) {
        modManagementController.onRenameModFile(host, mod, newFileNameInput)
    }

    fun onToggleMod(host: Activity, mod: ModItemUi, enabled: Boolean) {
        modManagementController.onToggleMod(host, mod, enabled)
    }

    fun onTogglePriorityLoad(host: Activity, mod: ModItemUi, enabled: Boolean) {
        modManagementController.onTogglePriorityLoad(host, mod, enabled)
    }

    fun onLaunch(host: Activity) {
        if (uiState.busy) {
            return
        }
        dismissCrashRecovery()
        prepareAndLaunch(host, StsLaunchSpec.LAUNCH_MODE_MTS, forceJvmCrash = false)
    }

    fun dismissCrashRecovery() {
        if (uiState.crashRecovery == null) {
            return
        }
        uiState = uiState.copy(crashRecovery = null)
    }

    fun retryLaunchAfterCrash(host: Activity) {
        dismissCrashRecovery()
        onLaunch(host)
    }

    fun copyCrashRecoveryReport(host: Activity) {
        val crashRecovery = uiState.crashRecovery ?: return
        val clipboard = host.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("sts-crash-report", crashRecovery.reportText)
        )
        _effects.tryEmit(
            Effect.ShowSnackbar(
                message = UiText.StringResource(R.string.sts_crash_page_copy_success),
                duration = LauncherTransientNoticeDuration.SHORT
            )
        )
    }

    fun shareCrashRecoveryReport(host: Activity) {
        val crashRecovery = uiState.crashRecovery ?: return
        shareCrashLogs(
            host = host,
            code = crashRecovery.code,
            isSignal = crashRecovery.isSignal,
            detail = crashRecovery.reportText
        )
    }

    fun suggestNextFolderName(): String {
        return modManagementController.suggestNextFolderName()
    }

    fun addFolder(host: Activity, name: String) {
        modManagementController.addFolder(host, name)
    }

    fun renameFolder(host: Activity, folderId: String, newName: String) {
        modManagementController.renameFolder(host, folderId, newName)
    }

    fun deleteFolder(host: Activity, folderId: String) {
        modManagementController.deleteFolder(host, folderId)
    }

    fun assignModToFolder(host: Activity, modId: String, folderId: String) {
        modManagementController.assignModToFolder(host, modId, folderId)
    }

    fun assignModToFolder(host: Activity, mod: ModItemUi, folderId: String) {
        modManagementController.assignModToFolder(host, mod, folderId)
    }

    fun moveModToUnassigned(host: Activity, modId: String) {
        modManagementController.moveModToUnassigned(host, modId)
    }

    fun moveModToUnassigned(host: Activity, mod: ModItemUi) {
        modManagementController.moveModToUnassigned(host, mod)
    }

    fun setFolderSelected(host: Activity, folderId: String, selected: Boolean) {
        modManagementController.setFolderSelected(host, folderId, selected)
    }

    fun setUnassignedSelected(host: Activity, selected: Boolean) {
        modManagementController.setUnassignedSelected(host, selected)
    }

    fun toggleFolderCollapsed(host: Activity, folderId: String) {
        modManagementController.toggleFolderCollapsed(host, folderId)
    }

    fun setFolderCollapsed(host: Activity, folderId: String, collapsed: Boolean) {
        modManagementController.setFolderCollapsed(host, folderId, collapsed)
    }

    fun toggleUnassignedCollapsed(host: Activity) {
        modManagementController.toggleUnassignedCollapsed(host)
    }

    fun setUnassignedCollapsed(host: Activity, collapsed: Boolean) {
        modManagementController.setUnassignedCollapsed(host, collapsed)
    }

    fun toggleDependencyFolderCollapsed(host: Activity) {
        modManagementController.toggleDependencyFolderCollapsed(host)
    }

    fun setDependencyFolderCollapsed(host: Activity, collapsed: Boolean) {
        modManagementController.setDependencyFolderCollapsed(host, collapsed)
    }

    fun moveFolderUp(host: Activity, folderId: String) {
        modManagementController.moveFolderUp(host, folderId)
    }

    fun moveFolderDown(host: Activity, folderId: String) {
        modManagementController.moveFolderDown(host, folderId)
    }

    fun moveUnassignedUp(host: Activity) {
        modManagementController.moveUnassignedUp(host)
    }

    fun moveUnassignedDown(host: Activity) {
        modManagementController.moveUnassignedDown(host)
    }

    fun moveFolderTokenToIndex(host: Activity, draggedFolderId: String, targetIndex: Int) {
        modManagementController.moveFolderTokenToIndex(host, draggedFolderId, targetIndex)
    }

    fun revealFolderToken(host: Activity, folderTokenId: String) {
        modManagementController.revealFolderToken(host, folderTokenId)
    }

    fun onModJarsPicked(host: Activity, uris: List<android.net.Uri>?) {
        modManagementController.onModJarsPicked(host, uris)
    }

    fun handleIncomingIntent(host: Activity, intent: Intent?): Boolean {
        val safeIntent = intent ?: return false
        maybeLaunchFromDebugExtra(host, safeIntent)
        return false
    }

    private fun canEditMainScreenState(): Boolean {
        return resolveControlsEnabled(uiState.busy, uiState.busyOperation, uiState.storageIssue != null)
    }

    private fun hasValidImportedStsJar(host: Activity): Boolean {
        return StsJarValidator.isValid(RuntimePaths.importedStsJar(host))
    }

    fun handleGameProcessExitAnalysis(
        host: Activity,
        intent: Intent?,
        launchStartedAtMs: Long,
        allowProcessExitCrashFallback: Boolean = true
    ): Boolean {
        LogcatCaptureProcessClient.stopCapture(host)
        val action = LauncherReturnActionResolver.resolve(
            buildLauncherReturnSnapshot(
                host = host,
                intent = intent,
                launchStartedAtMs = launchStartedAtMs,
                allowProcessExitCrashFallback = allowProcessExitCrashFallback
            )
        )
        return when (action) {
            LauncherReturnAction.None -> false
            LauncherReturnAction.ExpectedBackExit -> {
                GameLaunchReturnTracker.terminateTrackedGameProcess(host, includeCached = true)
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                suppressFutureProcessExitCrashFallback(host, launchStartedAtMs)
                dismissCrashRecovery()
                showExpectedBackExitDialog(host)
                true
            }

            LauncherReturnAction.ExpectedCleanShutdown -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                if (intent != null) {
                    clearCrashExtras(intent)
                }
                suppressFutureProcessExitCrashFallback(host, launchStartedAtMs)
                dismissCrashRecovery()
                true
            }

            LauncherReturnAction.HeapPressureWarning -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                dismissCrashRecovery()
                maybeShowHeapPressureDialog(host, intent ?: return false)
            }

            is LauncherReturnAction.ExplicitCrash -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                clearCrashExtras(intent ?: return false)
                suppressFutureProcessExitCrashFallback(host, launchStartedAtMs)
                showCrashRecovery(
                    code = action.payload.code,
                    isSignal = action.payload.isSignal,
                    detail = action.payload.detail,
                    fallbackMessage = buildCrashDialogMessage(host, action.payload)
                )
                true
            }

            is LauncherReturnAction.ProcessExitCrash -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                ProcessExitInfoCapture.markLatestInterestingProcessExitInfoHandled(host, launchStartedAtMs)
                val detail = buildProcessExitCrashDetail(host, action.summary)
                val code = action.summary.status.takeIf { it != 0 } ?: -1
                showCrashRecovery(
                    code = code,
                    isSignal = action.summary.isSignal,
                    detail = detail,
                    fallbackMessage = host.getString(R.string.sts_crash_detail_format, detail)
                )
                true
            }
        }
    }

    private fun buildLauncherReturnSnapshot(
        host: Activity,
        intent: Intent?,
        launchStartedAtMs: Long,
        allowProcessExitCrashFallback: Boolean
    ): LauncherReturnSnapshot {
        val expectedCleanShutdown = LatestLogCleanShutdownDetector.detect(host) != null
        val explicitCrash = if (expectedCleanShutdown) {
            null
        } else {
            buildExplicitCrashPayload(intent)
        }
        val processExitCrash = if (allowProcessExitCrashFallback && !expectedCleanShutdown && explicitCrash == null) {
            ProcessExitInfoCapture.peekLatestInterestingProcessExitInfo(host, launchStartedAtMs)
        } else {
            null
        }
        val heapPressureWarning = intent?.getBooleanExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_WARNING, false) == true
        return LauncherReturnSnapshot(
            explicitCrash = explicitCrash,
            processExitCrash = processExitCrash,
            heapPressureWarning = heapPressureWarning,
            expectedBackExitRecent = BackExitNotice.isExpectedBackExitRecent(host),
            expectedCleanShutdown = expectedCleanShutdown
        )
    }

    private fun buildExplicitCrashPayload(intent: Intent?): CrashReturnPayload? {
        if (intent == null || !intent.getBooleanExtra(LauncherActivity.EXTRA_CRASH_OCCURRED, false)) {
            return null
        }
        return CrashReturnPayload(
            code = intent.getIntExtra(LauncherActivity.EXTRA_CRASH_CODE, -1),
            isSignal = intent.getBooleanExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL, false),
            detail = intent.getStringExtra(LauncherActivity.EXTRA_CRASH_DETAIL)
        )
    }

    private fun buildDependencyFolderMods(
        host: Activity,
        requiredMods: List<ModItemUi>,
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean
    ): List<ModItemUi> {
        val requiredModsById = requiredMods.associateBy { normalizeModId(it.modId) }
        val baseMod = requiredModsById[ModManager.MOD_ID_BASEMOD]
            ?.copy(enabled = hasBaseMod)
            ?: buildSyntheticDependencyMod(
                storageKey = "__dependency__/BaseMod.jar",
                modId = ModManager.MOD_ID_BASEMOD,
                displayName = "BaseMod.jar",
                version = host.getString(
                    if (hasBaseMod) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_basemod_description),
                installed = hasBaseMod
            )
        val stsLib = requiredModsById[ModManager.MOD_ID_STSLIB]
            ?.copy(enabled = hasStsLib)
            ?: buildSyntheticDependencyMod(
                storageKey = "__dependency__/StSLib.jar",
                modId = ModManager.MOD_ID_STSLIB,
                displayName = "StSLib.jar",
                version = host.getString(
                    if (hasStsLib) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_stslib_description),
                installed = hasStsLib
            )
        return listOf(
            buildSyntheticDependencyMod(
                storageKey = "__dependency__/desktop-1.0.jar",
                modId = "desktop-1.0.jar",
                displayName = "desktop-1.0.jar",
                version = host.getString(
                    if (hasJar) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_desktop_description),
                installed = hasJar
            ),
            buildSyntheticDependencyMod(
                storageKey = "__dependency__/ModTheSpire.jar",
                modId = "modthespire",
                displayName = "ModTheSpire.jar",
                version = host.getString(
                    if (hasMts) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_mts_description),
                installed = hasMts
            ),
            baseMod,
            stsLib
        )
    }

    private fun buildSyntheticDependencyMod(
        storageKey: String,
        modId: String,
        displayName: String,
        version: String,
        description: String,
        installed: Boolean
    ): ModItemUi {
        return ModItemUi(
            modId = modId,
            manifestModId = modId,
            storagePath = storageKey,
            name = displayName,
            version = version,
            description = description,
            dependencies = emptyList(),
            required = true,
            installed = installed,
            enabled = installed,
            priorityRoot = false,
            priorityLoad = false
        )
    }

    private fun maybeLaunchFromDebugExtra(host: Activity, intent: Intent) {
        val debugLaunchMode = intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_LAUNCH_MODE)
        val forceJvmCrash = intent.getBooleanExtra(LauncherActivity.EXTRA_DEBUG_FORCE_JVM_CRASH, false)
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA &&
            !StsLaunchSpec.isMtsLaunchMode(debugLaunchMode)
        ) {
            return
        }

        prepareAndLaunch(host, debugLaunchMode ?: StsLaunchSpec.LAUNCH_MODE_VANILLA, forceJvmCrash = forceJvmCrash)
    }

    private fun prepareAndLaunch(host: Activity, launchMode: String, forceJvmCrash: Boolean) {
        if (showGameAlreadyRunningToastIfNeeded(host)) {
            refresh(host)
            return
        }
        if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            if (showLegacyDesktopJarReimportDialogIfNeeded(host)) {
                return
            }
            val optionalMods = modManagementController.currentOptionalMods()
            val duplicateGroups = modManagementController.findEnabledDuplicateModIdGroups(optionalMods)
            if (duplicateGroups.isNotEmpty()) {
                showDuplicateModIdDialog(host, duplicateGroups)
                return
            }
            val invalidMods = modManagementController.findEnabledMtsLaunchValidationIssues(optionalMods)
            if (invalidMods.isNotEmpty()) {
                showMtsLaunchValidationDialog(host, invalidMods)
                return
            }
        }
        try {
            modManagementController.applyPendingSelection(host)
        } catch (error: Throwable) {
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.DynamicString(
                        StsExternalStorageAccess.buildFailureMessage(
                            host,
                            "Failed to apply mod selection",
                            error
                        )
                    ),
                    duration = LauncherTransientNoticeDuration.LONG
                )
            )
            return
        }
        val backBehavior = readBackBehaviorSelection(host)
        val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)
        val launcherSettingsSynced = try {
            LauncherPreferences.syncLauncherPrefsToDisk(host)
        } catch (_: Throwable) {
            false
        }
        if (!launcherSettingsSynced) {
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.StringResource(R.string.main_launch_settings_sync_failed),
                    duration = LauncherTransientNoticeDuration.LONG
                )
            )
            return
        }

        val launchStartedAtMs = GameLaunchReturnTracker.markGameLaunchStarted(host)
        if (LauncherPreferences.isLogcatCaptureEnabled(host)) {
            LogcatCaptureProcessClient.startCapture(host, launchStartedAtMs)
        } else {
            LogcatCaptureProcessClient.stopAndClearCapture(host)
        }
        try {
            StsGameActivity.launch(
                host,
                launchMode,
                backBehavior,
                manualDismissBootOverlay,
                forceJvmCrash
            )
        } catch (error: Throwable) {
            LogcatCaptureProcessClient.stopCapture(host)
            GameLaunchReturnTracker.clearPendingGameLaunch(host)
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.StringResource(
                        R.string.main_launch_game_failed,
                        error.message ?: error.javaClass.simpleName
                    ),
                    duration = LauncherTransientNoticeDuration.LONG
                )
            )
        }
    }

    private fun showGameAlreadyRunningToastIfNeeded(host: Activity): Boolean {
        if (!GameLaunchReturnTracker.isGameProcessRunning(host)) {
            return false
        }
        _effects.tryEmit(
            Effect.ShowSnackbar(
                message = UiText.StringResource(R.string.main_launch_game_already_running),
                duration = LauncherTransientNoticeDuration.LONG
            )
        )
        return true
    }

    private fun showLegacyDesktopJarReimportDialogIfNeeded(host: Activity): Boolean {
        StsDesktopJarPatcher.detectLegacyWholeClassUiPatch(
            stsJar = RuntimePaths.importedStsJar(host),
            patchJar = RuntimePaths.gdxPatchJar(host)
        ) ?: return false
        AlertDialog.Builder(host)
            .setTitle(R.string.settings_reimport_sts_jar_title)
            .setMessage(host.getString(R.string.startup_failure_legacy_patched_desktop_jar_requires_reimport))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun showDuplicateModIdDialog(host: Activity, duplicateGroups: Map<String, List<ModItemUi>>) {
        if (duplicateGroups.isEmpty()) {
            return
        }
        val message = buildString {
            append(host.getString(R.string.main_duplicate_modid_message_intro))
            append('\n')
            duplicateGroups.forEach { (modId, mods) ->
                append("\nmodid: ").append(modId).append('\n')
                mods.forEach { mod ->
                    append("- ").append(resolveModDisplayName(mod))
                    val fileName = resolveModFileName(mod.storagePath)
                    if (fileName.isNotBlank()) {
                        append(" [").append(fileName).append("]")
                    }
                    append('\n')
                }
            }
            append('\n')
            append(host.getString(R.string.main_duplicate_modid_message_footer))
        }.trimEnd()
        AlertDialog.Builder(host)
            .setTitle(R.string.main_duplicate_modid_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMtsLaunchValidationDialog(host: Activity, issues: List<MainMtsLaunchValidationIssue>) {
        if (issues.isEmpty()) {
            return
        }
        val message = buildString {
            append(host.getString(R.string.main_mts_validation_message_intro))
            append('\n')
            issues.forEach { issue ->
                append("\n- ").append(resolveModDisplayName(issue.mod))
                val fileName = resolveModFileName(issue.mod.storagePath)
                if (fileName.isNotBlank()) {
                    append(" [").append(fileName).append("]")
                }
                append("\n  ")
                append(host.getString(R.string.main_mts_validation_reason, issue.reason))
            }
            append("\n\n")
            append(host.getString(R.string.main_mts_validation_footer_1))
            append('\n')
            append(host.getString(R.string.main_mts_validation_footer_2))
        }.trimEnd()
        AlertDialog.Builder(host)
            .setTitle(R.string.main_mts_validation_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildCrashDialogMessage(host: Activity, payload: CrashReturnPayload): String {
        val detail = payload.detail
        return if (isOutOfMemoryCrash(payload.code, detail)) {
            host.getString(R.string.sts_oom_exit)
        } else if (!detail.isNullOrBlank()) {
            host.getString(R.string.sts_crash_detail_format, detail.trim())
        } else {
            val messageId = if (payload.isSignal) R.string.sts_signal_exit else R.string.sts_normal_exit
            host.getString(messageId, payload.code)
        }
    }

    private fun buildProcessExitCrashDetail(host: Activity, exitSummary: ProcessExitSummary): String {
        val latestCrash = LatestLogCrashDetector.detect(host)
        val lastLogLine = LatestLogCrashDetector.readLastNonBlankLine(host)
        val signalDumpSummary = SignalCrashDumpReader.readSummary(host)
        return buildString {
            if (latestCrash != null) {
                append(latestCrash.detail.trim())
                append("\n\n")
            } else {
                append(host.getString(R.string.sts_process_exit_detected))
                append('\n')
            }
            append(host.getString(R.string.sts_process_exit_reason, exitSummary.reasonName))
            val statusLabel = if (exitSummary.isSignal) {
                host.getString(R.string.sts_process_exit_signal, exitSummary.status)
            } else {
                host.getString(R.string.sts_process_exit_status, exitSummary.status)
            }
            append('\n')
            append(statusLabel)
            if (exitSummary.description.isNotBlank()) {
                append('\n')
                append(host.getString(R.string.sts_process_exit_description, exitSummary.description))
            }
            if (!lastLogLine.isNullOrBlank()) {
                append('\n')
                append(host.getString(R.string.sts_process_exit_last_log, lastLogLine))
            }
            if (!signalDumpSummary.isNullOrBlank()) {
                append("\n\n")
                append(host.getString(R.string.sts_process_exit_signal_dump, signalDumpSummary))
            }
        }.trim()
    }

    private fun showCrashRecovery(
        code: Int,
        isSignal: Boolean,
        detail: String?,
        fallbackMessage: String
    ) {
        val report = CrashRecoveryReportFormatter.format(detail, fallbackMessage)
        uiState = uiState.copy(
            crashRecovery = CrashRecoveryState(
                code = code,
                isSignal = isSignal,
                summaryText = report.summaryText,
                reportText = report.reportText,
                isOutOfMemory = isOutOfMemoryCrash(code, detail)
            )
        )
    }

    private fun maybeShowHeapPressureDialog(
        host: Activity,
        intent: Intent
    ): Boolean {
        if (!intent.getBooleanExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_WARNING, false)) {
            return false
        }

        val peakHeapUsedBytes = intent.getLongExtra(
            LauncherActivity.EXTRA_HEAP_PRESSURE_PEAK_USED_BYTES,
            -1L
        )
        val peakHeapMaxBytes = intent.getLongExtra(
            LauncherActivity.EXTRA_HEAP_PRESSURE_HEAP_MAX_BYTES,
            -1L
        )
        val currentHeapMaxMb = intent.getIntExtra(
            LauncherActivity.EXTRA_HEAP_PRESSURE_CURRENT_HEAP_MB,
            -1
        )
        val suggestedHeapMaxMb = intent.getIntExtra(
            LauncherActivity.EXTRA_HEAP_PRESSURE_SUGGESTED_HEAP_MB,
            -1
        )

        clearHeapPressureExtras(intent)

        if (peakHeapUsedBytes <= 0L || peakHeapMaxBytes <= 0L) {
            return false
        }

        val peakHeapUsedMb = bytesToMegabytesRoundedUp(peakHeapUsedBytes)
        val peakHeapMaxMb = bytesToMegabytesRoundedUp(peakHeapMaxBytes)
        val usagePercent = ((peakHeapUsedBytes * 100L) / peakHeapMaxBytes)
            .coerceIn(0L, 999L)
            .toInt()
        val safeCurrentHeapMaxMb = currentHeapMaxMb
            .takeIf { it > 0 }
            ?: peakHeapMaxMb.toInt()
        val safeSuggestedHeapMaxMb = suggestedHeapMaxMb
            .takeIf { it > 0 }
            ?: safeCurrentHeapMaxMb

        val message = if (safeSuggestedHeapMaxMb > safeCurrentHeapMaxMb) {
            host.getString(
                R.string.heap_pressure_dialog_message_recommend,
                peakHeapUsedMb,
                peakHeapMaxMb,
                usagePercent,
                safeCurrentHeapMaxMb,
                safeSuggestedHeapMaxMb
            )
        } else {
            host.getString(
                R.string.heap_pressure_dialog_message_at_limit,
                peakHeapUsedMb,
                peakHeapMaxMb,
                usagePercent,
                safeCurrentHeapMaxMb
            )
        }

        AlertDialog.Builder(host)
            .setTitle(R.string.heap_pressure_dialog_title)
            .setView(createScrollableDialogMessageView(host, message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun createScrollableDialogMessageView(host: Activity, message: String): ScrollView {
        val density = host.resources.displayMetrics.density
        val horizontalPaddingPx = (24f * density).toInt()
        val verticalPaddingPx = (12f * density).toInt()
        val minHeightPx = (120f * density).toInt()
        val maxHeightPx = (320f * density).toInt()

        val textView = TextView(host).apply {
            text = message
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.1f)
        }

        return ScrollView(host).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
            addView(
                textView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = minHeightPx
            if (layoutParams is ViewGroup.MarginLayoutParams) {
                (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = verticalPaddingPx
            }
            post {
                if (height > maxHeightPx) {
                    layoutParams = layoutParams.apply {
                        height = maxHeightPx
                    }
                }
            }
        }
    }

    private fun shareCrashLogs(host: Activity, code: Int, isSignal: Boolean, detail: String?) {
        if (uiState.busy) {
            return
        }
        setBusy(true, UiText.StringResource(R.string.common_busy_preparing_jvm_log_bundle))
        runCatching {
            val payload = JvmLogShareService.prepareCrashSharePayload(
                host,
                code,
                isSignal,
                detail
            )
            val shareIntent = JvmLogShareService.buildShareIntent(payload)
            val chooserIntent = Intent.createChooser(
                shareIntent,
                host.getString(R.string.sts_share_crash_chooser_title)
            )
            _effects.tryEmit(Effect.LaunchIntent(chooserIntent))
            setBusy(false, null)
        }.onFailure {
            setBusy(false, null)
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.StringResource(R.string.sts_share_crash_report_failed),
                    duration = LauncherTransientNoticeDuration.LONG
                )
            )
        }
    }

    private fun clearCrashExtras(intent: Intent) {
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_OCCURRED)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_CODE)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_IS_SIGNAL)
        intent.removeExtra(LauncherActivity.EXTRA_CRASH_DETAIL)
    }

    private fun suppressFutureProcessExitCrashFallback(host: Activity, launchStartedAtMs: Long) {
        ProcessExitInfoCapture.markLatestInterestingProcessExitInfoHandled(host, launchStartedAtMs)
        host.window.decorView.postDelayed({
            if (!host.isFinishing && !host.isDestroyed) {
                ProcessExitInfoCapture.markLatestInterestingProcessExitInfoHandled(
                    host,
                    launchStartedAtMs
                )
            }
        }, 1200L)
    }

    private fun clearHeapPressureExtras(intent: Intent) {
        intent.removeExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_WARNING)
        intent.removeExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_PEAK_USED_BYTES)
        intent.removeExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_HEAP_MAX_BYTES)
        intent.removeExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_CURRENT_HEAP_MB)
        intent.removeExtra(LauncherActivity.EXTRA_HEAP_PRESSURE_SUGGESTED_HEAP_MB)
    }

    private fun bytesToMegabytesRoundedUp(bytes: Long): Long {
        if (bytes <= 0L) {
            return 0L
        }
        val oneMegabyte = 1024L * 1024L
        return (bytes + oneMegabyte - 1L) / oneMegabyte
    }

    private fun isOutOfMemoryCrash(code: Int, detail: String?): Boolean {
        if (code == -8) {
            return true
        }
        if (detail.isNullOrBlank()) {
            return false
        }
        val lower = detail.lowercase(Locale.ROOT)
        return lower.contains("outofmemoryerror") ||
            lower.contains("java heap space") ||
            lower.contains("gc overhead limit exceeded")
    }

    private fun showExpectedBackExitDialog(host: Activity) {
        AlertDialog.Builder(host)
            .setTitle(R.string.main_expected_back_exit_title)
            .setMessage(host.getString(R.string.main_expected_back_exit_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun resolveModDisplayName(mod: ModItemUi): String {
        return io.stamethyst.ui.main.resolveModDisplayName(mod, showModFileName = false)
    }

    private fun resolveModFileName(storagePath: String): String {
        val normalized = storagePath.trim()
        if (normalized.isEmpty()) {
            return ""
        }
        return java.io.File(normalized).name.trim()
    }

    private fun readBackBehaviorSelection(host: Activity): BackBehavior {
        return LauncherPreferences.readBackBehavior(host)
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return LauncherPreferences.readManualDismissBootOverlay(host)
    }

    private fun republish(host: Activity) {
        publishUiState(
            host = host,
            hasJar = hasValidImportedStsJar(host),
            hasMts = RuntimePaths.importedMtsJar(host).exists() || hasBundledAsset(host, "components/mods/ModTheSpire.jar"),
            hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD),
            hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB),
            storageIssue = detectStorageIssue(host)
        )
    }

    private fun publishUiState(
        host: Activity,
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean,
        storageIssue: StorageIssueUi?
    ) {
        val snapshot = modManagementController.snapshot()
        val currentBusy = uiState.busy
        val currentBusyOperation = uiState.busyOperation
        val currentBusyMessage = uiState.busyMessage
        val currentBusyProgressPercent = uiState.busyProgressPercent
        val gameProcessRunning = GameLaunchReturnTracker.isGameProcessRunning(host)
        uiState = uiState.copy(
            initializing = false,
            busy = currentBusy,
            busyOperation = if (currentBusy) currentBusyOperation else UiBusyOperation.NONE,
            busyMessage = if (currentBusy) currentBusyMessage else null,
            busyProgressPercent = if (currentBusy) currentBusyProgressPercent else null,
            dependencyMods = buildDependencyFolderMods(
                host = host,
                requiredMods = snapshot.requiredMods,
                hasJar = hasJar,
                hasMts = hasMts,
                hasBaseMod = hasBaseMod,
                hasStsLib = hasStsLib
            ),
            optionalMods = snapshot.optionalMods,
            storageIssue = storageIssue,
            controlsEnabled = resolveControlsEnabled(currentBusy, currentBusyOperation, storageIssue != null),
            gameProcessRunning = gameProcessRunning,
            showModFileName = LauncherPreferences.readShowModFileName(host),
            modSuggestions = currentModSuggestions,
            modFolders = snapshot.modFolders,
            folderAssignments = snapshot.folderAssignments,
            folderCollapsed = snapshot.folderCollapsed,
            unassignedCollapsed = snapshot.unassignedCollapsed,
            dependencyFolderCollapsed = snapshot.dependencyFolderCollapsed,
            unassignedFolderName = snapshot.unassignedFolderName,
            unassignedFolderOrder = snapshot.unassignedFolderOrder
        )
    }

    private fun setBusy(
        busy: Boolean,
        message: UiText?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
        progressPercent: Int? = null
    ) {
        val hasStorageIssue = uiState.storageIssue != null
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyOperation = operation,
                busyMessage = message,
                busyProgressPercent = progressPercent?.coerceIn(0, 100),
                controlsEnabled = resolveControlsEnabled(true, operation, hasStorageIssue)
            )
        } else {
            uiState.copy(
                busy = false,
                busyOperation = UiBusyOperation.NONE,
                busyMessage = null,
                busyProgressPercent = null,
                controlsEnabled = resolveControlsEnabled(false, UiBusyOperation.NONE, hasStorageIssue)
            )
        }
    }

    private fun detectStorageIssue(host: Activity): StorageIssueUi? {
        val issue = StsExternalStorageAccess.buildUiModel(host) ?: return null
        return StorageIssueUi(
            title = issue.title,
            message = issue.message,
            recovery = issue.recovery
        )
    }

    private fun resolveControlsEnabled(
        busy: Boolean,
        operation: UiBusyOperation,
        hasStorageIssue: Boolean
    ): Boolean {
        return !hasStorageIssue && (!busy || operation.usesBlockingOverlay())
    }

    private fun isRequiredModAvailable(host: Activity, modId: String): Boolean {
        return when (modId) {
            ModManager.MOD_ID_BASEMOD ->
                RuntimePaths.importedBaseModJar(host).exists() || hasBundledAsset(host, "components/mods/BaseMod.jar")

            ModManager.MOD_ID_STSLIB ->
                RuntimePaths.importedStsLibJar(host).exists() || hasBundledAsset(host, "components/mods/StSLib.jar")

            else -> true
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use { true }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        private val DEFAULT_UNASSIGNED_FOLDER_NAME: String = if (Locale.getDefault().language.startsWith("zh")) {
            "未分类"
        } else {
            "Uncategorized"
        }
    }

    override fun onCleared() {
        suggestionExecutor.shutdownNow()
        modManagementController.shutdown()
        super.onCleared()
    }
}
