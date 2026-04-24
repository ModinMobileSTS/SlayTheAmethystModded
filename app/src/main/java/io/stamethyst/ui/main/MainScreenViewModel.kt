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
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModSuggestionService
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAutoSyncPlanner
import io.stamethyst.backend.steamcloud.SteamCloudPullCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudPushCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudSyncProgress
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.mods.StsDesktopJarPatcher
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.StsExternalStorageAccess
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherTransientNoticeDuration
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.JvmLogShareService
import java.io.File
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
        val isOutOfMemory: Boolean,
        val isLaunchPreparationProcessDisconnected: Boolean
    )

    enum class SteamCloudIndicatorState {
        HIDDEN,
        UP_TO_DATE,
        CHECKING,
        CONFLICT,
        SYNCING,
        CONNECTION_FAILED,
    }

    data class SteamCloudIndicatorUi(
        val visible: Boolean = false,
        val state: SteamCloudIndicatorState = SteamCloudIndicatorState.HIDDEN,
        val plan: SteamCloudUploadPlan? = null,
        val errorSummary: String = "",
        val syncDirection: SteamCloudSyncDirection? = null,
        val progressMessage: String = "",
        val progressPercent: Int? = null,
        val progressCurrentPath: String = "",
        val lastCheckedAtMs: Long? = null,
    ) {
        val operationInFlight: Boolean
            get() = state == SteamCloudIndicatorState.CHECKING || state == SteamCloudIndicatorState.SYNCING
    }

    private data class ImportedStsJarFingerprint(
        val absolutePath: String,
        val exists: Boolean,
        val length: Long,
        val lastModified: Long
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
        val launchInFlight: Boolean = false,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val modSuggestions: Map<String, String> = emptyMap(),
        val readModSuggestionKeys: Set<String> = emptySet(),
        val pendingLaunchUnreadSuggestionModNames: List<String> = emptyList(),
        val modFolders: List<ModFolder> = emptyList(),
        val folderAssignments: Map<String, String> = emptyMap(),
        val folderCollapsed: Map<String, Boolean> = emptyMap(),
        val unassignedCollapsed: Boolean = false,
        val dependencyFolderCollapsed: Boolean = true,
        val dragLocked: Boolean = false,
        val unassignedFolderName: String = DEFAULT_UNASSIGNED_FOLDER_NAME,
        val unassignedFolderOrder: Int = 0,
        val steamCloudIndicator: SteamCloudIndicatorUi = SteamCloudIndicatorUi(),
    )

    sealed interface Effect {
        data class ShowSnackbar(
            val message: UiText,
            val duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT,
            val actionLabel: UiText? = null,
            val onAction: (() -> Unit)? = null
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
    private val diagnosticsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val launchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val steamCloudExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val importedStsJarValidationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentModSuggestions: Map<String, String> = emptyMap()
    private var currentReadModSuggestionKeys: Set<String> = emptySet()
    private var pendingLaunchUnreadSuggestionModNames: List<String> = emptyList()
    private var modSuggestionSyncInProgress = false
    private var lastSuccessfulModSuggestionSyncSignature: String? = null
    private var validatedImportedStsJarFingerprint: ImportedStsJarFingerprint? = null
    private var validatedImportedStsJarState: Boolean? = null
    private var validatingImportedStsJarFingerprint: ImportedStsJarFingerprint? = null
    @Volatile
    private var steamCloudCheckInFlight = false
    @Volatile
    private var steamCloudSyncInFlight = false
    @Volatile
    private var pendingLaunchAfterSteamCloudCheck = false
    @Volatile
    private var pendingLaunchBypassesSteamCloudPreLaunchSync = false
    @Volatile
    private var steamCloudCheckSessionId = 0L
    private var lastSteamCloudCheckAtMs: Long? = null
    @Volatile
    private var launchInFlight = false

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
        clearLaunchInFlightState()
        val storageIssue = detectStorageIssue(host)
        val dependencyAvailability = resolveDependencyAvailability(host)
        currentModSuggestions = ModSuggestionService.loadCachedSuggestionMap(host)
        currentReadModSuggestionKeys = ModSuggestionReadStateStore.loadReadKeys(host)

        modManagementController.refresh(host, storageAccessible = storageIssue == null)
        publishUiState(
            host = host,
            hasJar = dependencyAvailability.hasJar,
            hasMts = dependencyAvailability.hasMts,
            hasBaseMod = dependencyAvailability.hasBaseMod,
            hasStsLib = dependencyAvailability.hasStsLib,
            hasRuntimeCompat = dependencyAvailability.hasRuntimeCompat,
            storageIssue = storageIssue
        )
    }

    fun syncModSuggestionsIfNeeded(host: Activity) {
        val cachedSuggestions = ModSuggestionService.loadCachedSuggestionMap(host)
        if (cachedSuggestions != currentModSuggestions) {
            currentModSuggestions = cachedSuggestions
            uiState = uiState.copy(modSuggestions = currentModSuggestions)
        }

        val selectedSource = UpdateMirrorManager.current(host)
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

    fun syncSteamCloudIndicatorIfNeeded(host: Activity, force: Boolean = false): Boolean {
        if (uiState.busy) {
            return false
        }
        if (!isSteamCloudSaveModeEnabled(host)) {
            clearSteamCloudIndicatorState()
            return false
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            clearSteamCloudIndicatorState()
            return false
        }
        if (steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return false
        }
        val lastCheckedAtMs = lastSteamCloudCheckAtMs
        if (!force &&
            lastCheckedAtMs != null &&
            System.currentTimeMillis() - lastCheckedAtMs < STEAM_CLOUD_STATUS_REFRESH_INTERVAL_MS
        ) {
            return false
        }

        steamCloudCheckInFlight = true
        val checkSessionId = ++steamCloudCheckSessionId
        uiState = uiState.copy(
            steamCloudIndicator = uiState.steamCloudIndicator.copy(
                visible = true,
                state = SteamCloudIndicatorState.CHECKING,
                plan = null,
                errorSummary = "",
                syncDirection = null,
                progressMessage = "",
                progressPercent = null,
                progressCurrentPath = "",
            )
        )
        steamCloudExecutor.execute {
            try {
                val plan = SteamCloudPushCoordinator.buildUploadPlan(host, authMaterial)
                val checkedAtMs = System.currentTimeMillis()
                if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                    return@execute
                }
                when {
                    plan.conflicts.isNotEmpty() -> {
                        host.runOnUiThread {
                            if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                                return@runOnUiThread
                            }
                            steamCloudCheckInFlight = false
                            lastSteamCloudCheckAtMs = checkedAtMs
                            pendingLaunchAfterSteamCloudCheck = false
                            publishSteamCloudIndicatorPlan(plan, checkedAtMs)
                        }
                    }

                    plan.uploadCandidates.isEmpty() && plan.remoteOnlyChanges.isEmpty() -> {
                        host.runOnUiThread {
                            if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                                return@runOnUiThread
                            }
                            steamCloudCheckInFlight = false
                            lastSteamCloudCheckAtMs = checkedAtMs
                            publishSteamCloudIndicatorPlan(plan, checkedAtMs)
                            if (takePendingLaunchAfterSteamCloudCheck()) {
                                onLaunch(host)
                            }
                        }
                    }

                    else -> {
                        if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                            return@execute
                        }
                        val launchAfterSuccess = takePendingLaunchAfterSteamCloudCheck()
                        steamCloudCheckInFlight = false
                        steamCloudSyncInFlight = true
                        host.runOnUiThread {
                            lastSteamCloudCheckAtMs = checkedAtMs
                            publishSteamCloudIndicatorSyncing(
                                direction = resolveAutomaticSyncDirection(plan),
                                progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_auto_sync),
                                progressPercent = 0,
                                currentPath = "",
                            )
                        }
                        performAutomaticSteamCloudSync(
                            host = host,
                            authMaterial = authMaterial,
                            plan = plan,
                            userInitiated = force,
                            launchAfterSuccess = launchAfterSuccess,
                        )
                    }
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudAutoSyncError(error)
                val failedAtMs = System.currentTimeMillis()
                host.runOnUiThread {
                    if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                        return@runOnUiThread
                    }
                    steamCloudCheckInFlight = false
                    lastSteamCloudCheckAtMs = failedAtMs
                    pendingLaunchAfterSteamCloudCheck = false
                    publishSteamCloudIndicatorFailure(summary, failedAtMs)
                    if (force) {
                        _effects.tryEmit(
                            Effect.ShowSnackbar(
                                message = UiText.StringResource(
                                    R.string.main_steam_cloud_indicator_check_failed,
                                    summary
                                ),
                                duration = LauncherTransientNoticeDuration.LONG,
                            )
                        )
                    }
                }
            }
        }
        return true
    }

    fun cancelSteamCloudCheck(host: Activity) {
        if (!steamCloudCheckInFlight) {
            return
        }
        steamCloudCheckSessionId++
        steamCloudCheckInFlight = false
        pendingLaunchAfterSteamCloudCheck = false
        val cancelledAtMs = System.currentTimeMillis()
        lastSteamCloudCheckAtMs = cancelledAtMs
        publishSteamCloudIndicatorFailure(
            summary = host.getString(R.string.main_steam_cloud_check_cancelled_summary),
            checkedAtMs = cancelledAtMs,
        )
    }

    internal fun onLaunchRequested(host: Activity): LaunchRequestAction {
        if (uiState.busy || launchInFlight) {
            return LaunchRequestAction.NONE
        }
        if (!isSteamCloudSaveModeEnabled(host)) {
            clearSteamCloudIndicatorState()
            onLaunch(host)
            return LaunchRequestAction.NONE
        }
        if (steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET
        }

        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            pendingLaunchAfterSteamCloudCheck = false
            onLaunch(host)
            return LaunchRequestAction.NONE
        }

        val lastCheckedAtMs = lastSteamCloudCheckAtMs
        val needsFreshCheck = lastCheckedAtMs == null ||
            System.currentTimeMillis() - lastCheckedAtMs >= STEAM_CLOUD_STATUS_REFRESH_INTERVAL_MS
        if (needsFreshCheck) {
            pendingLaunchAfterSteamCloudCheck = true
            val started = syncSteamCloudIndicatorIfNeeded(host, force = true)
            if (!started) {
                pendingLaunchAfterSteamCloudCheck = false
                return LaunchRequestAction.NONE
            }
            return LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET
        }

        return when (uiState.steamCloudIndicator.state) {
            SteamCloudIndicatorState.CONFLICT,
            SteamCloudIndicatorState.CONNECTION_FAILED,
            SteamCloudIndicatorState.SYNCING,
            SteamCloudIndicatorState.CHECKING -> LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET

            SteamCloudIndicatorState.HIDDEN,
            SteamCloudIndicatorState.UP_TO_DATE -> {
                onLaunch(host)
                LaunchRequestAction.NONE
            }
        }
    }

    fun onUseLocalSteamCloudProgress(host: Activity) {
        if (uiState.busy || steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return
        }
        if (!isSteamCloudSaveModeEnabled(host)) {
            clearSteamCloudIndicatorState()
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            clearSteamCloudIndicatorState()
            return
        }

        steamCloudSyncInFlight = true
        publishSteamCloudIndicatorSyncing(
            direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
            progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_local_override),
            progressPercent = 0,
            currentPath = "",
        )
        steamCloudExecutor.execute {
            try {
                val result = SteamCloudPushCoordinator.overwriteRemoteWithLocal(
                    host,
                    authMaterial,
                ) { progress ->
                    host.runOnUiThread {
                        if (uiState.steamCloudIndicator.state == SteamCloudIndicatorState.SYNCING) {
                            applySteamCloudSyncProgress(host, progress)
                        }
                    }
                }
                host.runOnUiThread {
                    steamCloudSyncInFlight = false
                    lastSteamCloudCheckAtMs = result.completedAtMs
                    uiState = uiState.copy(
                        steamCloudIndicator = SteamCloudIndicatorUi(
                            visible = true,
                            state = SteamCloudIndicatorState.UP_TO_DATE,
                            lastCheckedAtMs = result.completedAtMs,
                        )
                    )
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_local_override_succeeded,
                                result.uploadedFileCount,
                                result.deletedRemoteFileCount
                            ),
                            duration = LauncherTransientNoticeDuration.SHORT,
                        )
                    )
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudAutoSyncError(error)
                host.runOnUiThread {
                    steamCloudSyncInFlight = false
                    publishSteamCloudIndicatorFailure(summary)
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_override_failed,
                                summary
                            ),
                            duration = LauncherTransientNoticeDuration.LONG,
                        )
                    )
                }
            }
        }
    }

    fun onUseCloudSteamCloudProgress(host: Activity) {
        if (uiState.busy || steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return
        }
        if (!isSteamCloudSaveModeEnabled(host)) {
            clearSteamCloudIndicatorState()
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            clearSteamCloudIndicatorState()
            return
        }

        steamCloudSyncInFlight = true
        publishSteamCloudIndicatorSyncing(
            direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
            progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_cloud_override),
            progressPercent = 0,
            currentPath = "",
        )
        steamCloudExecutor.execute {
            try {
                val result = SteamCloudPullCoordinator.pullAll(
                    host,
                    authMaterial,
                ) { progress ->
                    host.runOnUiThread {
                        if (uiState.steamCloudIndicator.state == SteamCloudIndicatorState.SYNCING) {
                            applySteamCloudSyncProgress(host, progress)
                        }
                    }
                }
                host.runOnUiThread {
                    steamCloudSyncInFlight = false
                    lastSteamCloudCheckAtMs = result.completedAtMs
                    uiState = uiState.copy(
                        steamCloudIndicator = SteamCloudIndicatorUi(
                            visible = true,
                            state = SteamCloudIndicatorState.UP_TO_DATE,
                            lastCheckedAtMs = result.completedAtMs,
                        )
                    )
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_cloud_override_succeeded,
                                result.appliedFileCount
                            ),
                            duration = LauncherTransientNoticeDuration.SHORT,
                        )
                    )
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudAutoSyncError(error)
                host.runOnUiThread {
                    steamCloudSyncInFlight = false
                    publishSteamCloudIndicatorFailure(summary)
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_override_failed,
                                summary
                            ),
                            duration = LauncherTransientNoticeDuration.LONG,
                        )
                    )
                }
            }
        }
    }

    private fun performAutomaticSteamCloudSync(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        userInitiated: Boolean,
        launchAfterSuccess: Boolean,
    ) {
        try {
            if (plan.remoteOnlyChanges.isNotEmpty()) {
                SteamCloudPullCoordinator.mergeRemoteOnlyChanges(
                    host = host,
                    authMaterial = authMaterial,
                    plan = plan,
                ) { progress ->
                    host.runOnUiThread {
                        if (uiState.steamCloudIndicator.state == SteamCloudIndicatorState.SYNCING) {
                            applySteamCloudSyncProgress(host, progress)
                        }
                    }
                }
            }

            if (plan.uploadCandidates.isNotEmpty()) {
                SteamCloudPushCoordinator.pushLocalChanges(
                    host = host,
                    authMaterial = authMaterial,
                    plan = plan,
                ) { progress ->
                    host.runOnUiThread {
                        if (uiState.steamCloudIndicator.state == SteamCloudIndicatorState.SYNCING) {
                            applySteamCloudSyncProgress(host, progress)
                        }
                    }
                }
            }

            val completedAtMs = System.currentTimeMillis()
            host.runOnUiThread {
                steamCloudSyncInFlight = false
                lastSteamCloudCheckAtMs = completedAtMs
                uiState = uiState.copy(
                    steamCloudIndicator = SteamCloudIndicatorUi(
                        visible = true,
                        state = SteamCloudIndicatorState.UP_TO_DATE,
                        lastCheckedAtMs = completedAtMs,
                    )
                )
                if (userInitiated) {
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(R.string.main_steam_cloud_auto_sync_succeeded),
                            duration = LauncherTransientNoticeDuration.SHORT,
                        )
                    )
                }
                if (launchAfterSuccess) {
                    onLaunch(host)
                }
            }
        } catch (error: Throwable) {
            val summary = summarizeSteamCloudAutoSyncError(error)
            host.runOnUiThread {
                steamCloudSyncInFlight = false
                pendingLaunchAfterSteamCloudCheck = false
                publishSteamCloudIndicatorFailure(summary)
                if (userInitiated) {
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_override_failed,
                                summary
                            ),
                            duration = LauncherTransientNoticeDuration.LONG,
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

    fun onSetPriority(host: Activity, mod: ModItemUi, priority: Int?) {
        modManagementController.onSetPriority(host, mod, priority)
    }

    fun onLaunch(host: Activity) {
        onLaunch(host, bypassSteamCloudPreLaunchSync = false)
    }

    fun onLaunchBypassingSteamCloudPreLaunchSync(host: Activity) {
        onLaunch(host, bypassSteamCloudPreLaunchSync = true)
    }

    private fun onLaunch(host: Activity, bypassSteamCloudPreLaunchSync: Boolean) {
        if (uiState.steamCloudIndicator.operationInFlight) {
            return
        }
        if (!tryBeginLaunchRequest()) {
            return
        }
        val unreadSuggestionModNames = collectEnabledUnreadSuggestionModDisplayNames(
            mods = modManagementController.currentOptionalMods(),
            suggestions = currentModSuggestions,
            readSuggestionKeys = currentReadModSuggestionKeys
        )
        if (unreadSuggestionModNames.isNotEmpty()) {
            pendingLaunchBypassesSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync
            pendingLaunchUnreadSuggestionModNames = unreadSuggestionModNames
            uiState = uiState.copy(pendingLaunchUnreadSuggestionModNames = unreadSuggestionModNames)
            return
        }
        pendingLaunchBypassesSteamCloudPreLaunchSync = false
        dismissCrashRecovery()
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
            bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
        )
    }

    fun confirmLaunchWithUnreadSuggestions(host: Activity) {
        if (pendingLaunchUnreadSuggestionModNames.isEmpty()) {
            return
        }
        if (!launchInFlight && !tryBeginLaunchRequest()) {
            return
        }
        val bypassSteamCloudPreLaunchSync = pendingLaunchBypassesSteamCloudPreLaunchSync
        clearPendingLaunchUnreadSuggestionDialog()
        dismissCrashRecovery()
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
            bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
        )
    }

    fun cancelLaunchWithUnreadSuggestions() {
        clearPendingLaunchUnreadSuggestionDialog()
        clearLaunchInFlightState()
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

    fun toggleDragLocked(host: Activity) {
        modManagementController.toggleDragLocked(host)
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

    private data class DependencyAvailabilitySnapshot(
        val hasJar: Boolean,
        val hasMts: Boolean,
        val hasBaseMod: Boolean,
        val hasStsLib: Boolean,
        val hasRuntimeCompat: Boolean
    )

    private fun resolveDependencyAvailability(host: Activity): DependencyAvailabilitySnapshot {
        val importedStsJarFingerprint = buildImportedStsJarFingerprint(host)
        val hasJar = resolveImportedStsJarStateForUi(importedStsJarFingerprint)
        if (importedStsJarFingerprint.exists) {
            ensureImportedStsJarValidation(host, importedStsJarFingerprint)
        } else {
            cacheImportedStsJarValidation(importedStsJarFingerprint, false)
        }
        return DependencyAvailabilitySnapshot(
            hasJar = hasJar,
            hasMts = RuntimePaths.importedMtsJar(host).exists() ||
                hasBundledAsset(host, "components/mods/ModTheSpire.jar"),
            hasBaseMod = isRequiredModAvailable(host, ModManager.MOD_ID_BASEMOD),
            hasStsLib = isRequiredModAvailable(host, ModManager.MOD_ID_STSLIB),
            hasRuntimeCompat = isRequiredModAvailable(host, ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT)
        )
    }

    private fun buildImportedStsJarFingerprint(host: Activity): ImportedStsJarFingerprint {
        val jarFile = RuntimePaths.importedStsJar(host)
        return buildImportedStsJarFingerprint(jarFile)
    }

    private fun buildImportedStsJarFingerprint(jarFile: File): ImportedStsJarFingerprint {
        val exists = jarFile.isFile
        return ImportedStsJarFingerprint(
            absolutePath = jarFile.absolutePath,
            exists = exists,
            length = if (exists) jarFile.length() else -1L,
            lastModified = if (exists) jarFile.lastModified() else -1L
        )
    }

    private fun resolveImportedStsJarStateForUi(
        importedStsJarFingerprint: ImportedStsJarFingerprint
    ): Boolean {
        if (!importedStsJarFingerprint.exists) {
            return false
        }
        val validatedState = validatedImportedStsJarState
        if (validatedImportedStsJarFingerprint == importedStsJarFingerprint && validatedState != null) {
            return validatedState
        }
        val currentDisplayedState = uiState.dependencyMods
            .firstOrNull { it.storagePath == "__dependency__/desktop-1.0.jar" }
            ?.installed
        return currentDisplayedState ?: true
    }

    private fun ensureImportedStsJarValidation(
        host: Activity,
        importedStsJarFingerprint: ImportedStsJarFingerprint
    ) {
        if (validatedImportedStsJarFingerprint == importedStsJarFingerprint) {
            return
        }
        if (validatingImportedStsJarFingerprint == importedStsJarFingerprint) {
            return
        }
        validatingImportedStsJarFingerprint = importedStsJarFingerprint
        importedStsJarValidationExecutor.execute {
            val isValid = StsJarValidator.isValid(File(importedStsJarFingerprint.absolutePath))
            host.runOnUiThread {
                if (validatingImportedStsJarFingerprint == importedStsJarFingerprint) {
                    validatingImportedStsJarFingerprint = null
                }
                cacheImportedStsJarValidation(importedStsJarFingerprint, isValid)
                if (host.isFinishing || host.isDestroyed) {
                    return@runOnUiThread
                }
                val currentFingerprint = buildImportedStsJarFingerprint(host)
                if (currentFingerprint != importedStsJarFingerprint) {
                    republish(host)
                    return@runOnUiThread
                }
                val currentDisplayedState = uiState.dependencyMods
                    .firstOrNull { it.storagePath == "__dependency__/desktop-1.0.jar" }
                    ?.installed
                if (uiState.initializing || currentDisplayedState != isValid) {
                    republish(host)
                }
            }
        }
    }

    fun markModSuggestionRead(host: Activity, mod: ModItemUi, suggestionText: String) {
        val readKey = resolveModSuggestionReadKey(mod, suggestionText) ?: return
        val stored = ModSuggestionReadStateStore.markRead(host, readKey)
        if (!stored && currentReadModSuggestionKeys.contains(readKey)) {
            return
        }
        currentReadModSuggestionKeys = currentReadModSuggestionKeys + readKey
        uiState = uiState.copy(readModSuggestionKeys = currentReadModSuggestionKeys)
    }

    private fun cacheImportedStsJarValidation(
        importedStsJarFingerprint: ImportedStsJarFingerprint,
        isValid: Boolean
    ) {
        validatedImportedStsJarFingerprint = importedStsJarFingerprint
        validatedImportedStsJarState = isValid
    }

    private fun clearPendingLaunchUnreadSuggestionDialog() {
        if (pendingLaunchUnreadSuggestionModNames.isEmpty() &&
            uiState.pendingLaunchUnreadSuggestionModNames.isEmpty()
        ) {
            return
        }
        pendingLaunchUnreadSuggestionModNames = emptyList()
        pendingLaunchBypassesSteamCloudPreLaunchSync = false
        uiState = uiState.copy(pendingLaunchUnreadSuggestionModNames = emptyList())
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
                clearLaunchInFlightState()
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
                clearLaunchInFlightState()
                dismissCrashRecovery()
                maybeStartSteamCloudAutoPushAfterCleanShutdown(host)
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
        val cleanShutdownSummary = LatestLogCleanShutdownDetector.detect(host)
        val expectedCleanShutdown =
            LatestLogCleanShutdownDetector.shouldSuppressCrashReport(cleanShutdownSummary)
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
        hasStsLib: Boolean,
        hasRuntimeCompat: Boolean
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
        val runtimeCompat = requiredModsById[ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT]
            ?.copy(enabled = hasRuntimeCompat)
            ?: buildSyntheticDependencyMod(
                storageKey = "__dependency__/AmethystRuntimeCompat.jar",
                modId = ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT,
                displayName = "AmethystRuntimeCompat.jar",
                version = host.getString(
                    if (hasRuntimeCompat) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_runtime_compat_description),
                installed = hasRuntimeCompat
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
            stsLib,
            runtimeCompat
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
            explicitPriority = null,
            effectivePriority = null
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

        if (!tryBeginLaunchRequest()) {
            return
        }
        beginLaunchFlow(
            host,
            debugLaunchMode ?: StsLaunchSpec.LAUNCH_MODE_VANILLA,
            forceJvmCrash = forceJvmCrash,
            bypassSteamCloudPreLaunchSync = false,
        )
    }

    private fun tryBeginLaunchRequest(): Boolean {
        if (uiState.busy || launchInFlight) {
            return false
        }
        markLaunchInFlight()
        return true
    }

    private fun beginLaunchFlow(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        bypassSteamCloudPreLaunchSync: Boolean,
    ) {
        if (GameLaunchReturnTracker.isGameProcessRunning(host, includeCached = true)) {
            cleanupResidualGameProcessAndLaunch(
                host = host,
                launchMode = launchMode,
                forceJvmCrash = forceJvmCrash,
                bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
            )
            return
        }
        prepareAndLaunch(
            host = host,
            launchMode = launchMode,
            forceJvmCrash = forceJvmCrash,
            bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
        )
    }

    private fun cleanupResidualGameProcessAndLaunch(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        bypassSteamCloudPreLaunchSync: Boolean,
    ) {
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_launch_game_cleanup_busy),
            operation = UiBusyOperation.GAME_PROCESS_CLEANUP
        )
        launchExecutor.execute {
            val cleaned = GameLaunchReturnTracker.terminateTrackedGameProcessAndWait(host)
            host.runOnUiThread {
                setBusy(false, null)
                if (host.isFinishing || host.isDestroyed) {
                    clearLaunchInFlightState()
                    return@runOnUiThread
                }
                if (!cleaned) {
                    notifyResidualGameProcessCleanupFailed(host)
                    return@runOnUiThread
                }
                GameLaunchReturnTracker.clearPendingGameLaunch(host)
                markLaunchInFlight()
                prepareAndLaunch(
                    host = host,
                    launchMode = launchMode,
                    forceJvmCrash = forceJvmCrash,
                    bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
                )
            }
        }
    }

    private fun prepareAndLaunch(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        bypassSteamCloudPreLaunchSync: Boolean,
    ) {
        if (GameLaunchReturnTracker.isGameProcessRunning(host, includeCached = true)) {
            notifyResidualGameProcessCleanupFailed(host)
            return
        }
        if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            if (showLegacyDesktopJarReimportDialogIfNeeded(host)) {
                clearLaunchInFlightState()
                return
            }
            val optionalMods = modManagementController.currentOptionalMods()
            val duplicateGroups = modManagementController.findEnabledDuplicateModIdGroups(optionalMods)
            if (duplicateGroups.isNotEmpty()) {
                showDuplicateModIdDialog(host, duplicateGroups)
                clearLaunchInFlightState()
                return
            }
            val invalidMods = modManagementController.findEnabledMtsLaunchValidationIssues(optionalMods)
            if (invalidMods.isNotEmpty()) {
                showMtsLaunchValidationDialog(host, invalidMods)
                clearLaunchInFlightState()
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
            clearLaunchInFlightState()
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
            clearLaunchInFlightState()
            return
        }

        if (shouldShowRamSaverResidencyLaunchWarning(host, launchMode)) {
            showRamSaverResidencyLaunchDialog(
                host = host,
                launchMode = launchMode,
                backBehavior = backBehavior,
                manualDismissBootOverlay = manualDismissBootOverlay,
                forceJvmCrash = forceJvmCrash,
                bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
            )
            return
        }

        launchGameActivity(
            host = host,
            launchMode = launchMode,
            backBehavior = backBehavior,
            manualDismissBootOverlay = manualDismissBootOverlay,
            forceJvmCrash = forceJvmCrash,
            bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
        )
    }

    private fun launchGameActivity(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        bypassSteamCloudPreLaunchSync: Boolean,
    ) {
        if (bypassSteamCloudPreLaunchSync) {
            launchGameActivityInternal(
                host = host,
                launchMode = launchMode,
                backBehavior = backBehavior,
                manualDismissBootOverlay = manualDismissBootOverlay,
                forceJvmCrash = forceJvmCrash,
            )
            return
        }
        maybeStartSteamCloudAutoPullBeforeLaunch(
            host = host,
            launchMode = launchMode,
            backBehavior = backBehavior,
            manualDismissBootOverlay = manualDismissBootOverlay,
            forceJvmCrash = forceJvmCrash,
        )
    }

    private fun launchGameActivityInternal(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean
    ) {
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
            clearLaunchInFlightState()
        }
    }

    private fun maybeStartSteamCloudAutoPullBeforeLaunch(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
    ) {
        if (LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD) {
            launchGameActivityInternal(
                host = host,
                launchMode = launchMode,
                backBehavior = backBehavior,
                manualDismissBootOverlay = manualDismissBootOverlay,
                forceJvmCrash = forceJvmCrash,
            )
            return
        }

        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            launchGameActivityInternal(
                host = host,
                launchMode = launchMode,
                backBehavior = backBehavior,
                manualDismissBootOverlay = manualDismissBootOverlay,
                forceJvmCrash = forceJvmCrash,
            )
            return
        }

        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_busy_steam_cloud_prelaunch_sync),
            operation = UiBusyOperation.STEAM_CLOUD_SYNC,
        )
        launchExecutor.execute {
            try {
                val uploadPlan = SteamCloudPushCoordinator.buildUploadPlan(host, authMaterial)
                val decision = SteamCloudAutoSyncPlanner.planPreLaunchPull(uploadPlan)
                when {
                    decision.shouldBlockLaunch -> {
                        host.runOnUiThread {
                            setBusy(false, null)
                            clearLaunchInFlightState()
                            publishSteamCloudIndicatorPlan(uploadPlan, System.currentTimeMillis())
                            showSteamCloudAutoPullBlockedDialog(host, uploadPlan)
                        }
                    }

                    decision.shouldPull -> {
                        SteamCloudPullCoordinator.pullAll(host, authMaterial)
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed) {
                                clearLaunchInFlightState()
                                return@runOnUiThread
                            }
                            setBusy(false, null)
                            launchGameActivityInternal(
                                host = host,
                                launchMode = launchMode,
                                backBehavior = backBehavior,
                                manualDismissBootOverlay = manualDismissBootOverlay,
                                forceJvmCrash = forceJvmCrash,
                            )
                        }
                    }

                    else -> {
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed) {
                                clearLaunchInFlightState()
                                return@runOnUiThread
                            }
                            setBusy(false, null)
                            launchGameActivityInternal(
                                host = host,
                                launchMode = launchMode,
                                backBehavior = backBehavior,
                                manualDismissBootOverlay = manualDismissBootOverlay,
                                forceJvmCrash = forceJvmCrash,
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    clearLaunchInFlightState()
                    publishSteamCloudIndicatorFailure(summarizeSteamCloudAutoSyncError(error))
                    showSteamCloudAutoPullFailedDialog(
                        host,
                        summarizeSteamCloudAutoSyncError(error)
                    )
                }
            }
        }
    }

    private fun maybeStartSteamCloudAutoPushAfterCleanShutdown(host: Activity) {
        if (LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD) {
            return
        }

        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
            ?: return

        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_busy_steam_cloud_postrun_sync),
            operation = UiBusyOperation.STEAM_CLOUD_SYNC,
        )
        launchExecutor.execute {
            try {
                val uploadPlan = SteamCloudPushCoordinator.buildUploadPlan(host, authMaterial)
                val decision = SteamCloudAutoSyncPlanner.planCleanShutdownPush(uploadPlan)
                when {
                    decision.blockedByConflicts -> {
                        host.runOnUiThread {
                            setBusy(false, null)
                            publishSteamCloudIndicatorPlan(uploadPlan, System.currentTimeMillis())
                            refresh(host)
                            _effects.tryEmit(
                                Effect.ShowSnackbar(
                                    message = UiText.StringResource(
                                        R.string.main_steam_cloud_auto_push_blocked,
                                        uploadPlan.conflicts.size
                                    ),
                                    duration = LauncherTransientNoticeDuration.LONG,
                                )
                            )
                        }
                    }

                    decision.shouldPush -> {
                        val result = SteamCloudPushCoordinator.pushLocalChanges(
                            host,
                            authMaterial,
                            uploadPlan,
                        )
                        host.runOnUiThread {
                            setBusy(false, null)
                            uiState = uiState.copy(
                                steamCloudIndicator = SteamCloudIndicatorUi(
                                    visible = true,
                                    state = SteamCloudIndicatorState.UP_TO_DATE,
                                    lastCheckedAtMs = result.completedAtMs,
                                )
                            )
                            lastSteamCloudCheckAtMs = result.completedAtMs
                            refresh(host)
                            _effects.tryEmit(
                                Effect.ShowSnackbar(
                                    message = UiText.StringResource(
                                        R.string.main_steam_cloud_auto_push_succeeded,
                                        result.uploadedFileCount
                                    ),
                                    duration = LauncherTransientNoticeDuration.SHORT,
                                )
                            )
                        }
                    }

                    else -> {
                        host.runOnUiThread {
                            setBusy(false, null)
                            refresh(host)
                        }
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    publishSteamCloudIndicatorFailure(summarizeSteamCloudAutoSyncError(error))
                    refresh(host)
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_steam_cloud_auto_push_failed,
                                summarizeSteamCloudAutoSyncError(error)
                            ),
                            duration = LauncherTransientNoticeDuration.LONG,
                        )
                    )
                }
            }
        }
    }

    private fun showSteamCloudAutoPullBlockedDialog(
        host: Activity,
        plan: SteamCloudUploadPlan,
    ) {
        _effects.tryEmit(
            Effect.ShowDialog(
                title = UiText.StringResource(R.string.main_steam_cloud_auto_pull_blocked_title),
                message = UiText.DynamicString(
                    host.getString(
                        R.string.main_steam_cloud_auto_pull_blocked_message,
                        plan.conflicts.size,
                        plan.uploadCandidates.size,
                        plan.remoteOnlyChanges.size
                    )
                ),
            )
        )
    }

    private fun showSteamCloudAutoPullFailedDialog(host: Activity, summary: String) {
        _effects.tryEmit(
            Effect.ShowDialog(
                title = UiText.StringResource(R.string.main_steam_cloud_auto_pull_failed_title),
                message = UiText.StringResource(
                    R.string.main_steam_cloud_auto_pull_failed_message,
                    summary
                ),
            )
        )
    }

    private fun summarizeSteamCloudAutoSyncError(error: Throwable): String {
        val cause = generateSequence(error) { current -> current.cause }
            .firstOrNull { current -> current.message?.trim()?.isNotEmpty() == true }
            ?: error
        val message = cause.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            cause.javaClass.simpleName
        }
    }

    private fun publishSteamCloudIndicatorPlan(
        plan: SteamCloudUploadPlan,
        checkedAtMs: Long,
    ) {
        uiState = uiState.copy(
            steamCloudIndicator = SteamCloudIndicatorUi(
                visible = true,
                state = if (plan.conflicts.isNotEmpty()) {
                    SteamCloudIndicatorState.CONFLICT
                } else {
                    SteamCloudIndicatorState.UP_TO_DATE
                },
                plan = plan.conflicts.takeIf { it.isNotEmpty() }?.let { plan },
                lastCheckedAtMs = checkedAtMs,
            )
        )
    }

    private fun resolveAutomaticSyncDirection(plan: SteamCloudUploadPlan): SteamCloudSyncDirection {
        return when {
            plan.remoteOnlyChanges.isNotEmpty() -> SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL
            else -> SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD
        }
    }

    private fun publishSteamCloudIndicatorFailure(summary: String) {
        uiState = uiState.copy(
            steamCloudIndicator = SteamCloudIndicatorUi(
                visible = true,
                state = SteamCloudIndicatorState.CONNECTION_FAILED,
                errorSummary = summary,
                lastCheckedAtMs = uiState.steamCloudIndicator.lastCheckedAtMs,
            )
        )
    }

    private fun publishSteamCloudIndicatorFailure(
        summary: String,
        checkedAtMs: Long,
    ) {
        uiState = uiState.copy(
            steamCloudIndicator = SteamCloudIndicatorUi(
                visible = true,
                state = SteamCloudIndicatorState.CONNECTION_FAILED,
                errorSummary = summary,
                lastCheckedAtMs = checkedAtMs,
            )
        )
    }

    private fun publishSteamCloudIndicatorSyncing(
        direction: SteamCloudSyncDirection,
        progressMessage: String,
        progressPercent: Int?,
        currentPath: String,
    ) {
        uiState = uiState.copy(
            steamCloudIndicator = SteamCloudIndicatorUi(
                visible = true,
                state = SteamCloudIndicatorState.SYNCING,
                syncDirection = direction,
                progressMessage = progressMessage,
                progressPercent = progressPercent?.coerceIn(0, 100),
                progressCurrentPath = currentPath,
                lastCheckedAtMs = uiState.steamCloudIndicator.lastCheckedAtMs,
            )
        )
    }

    private fun applySteamCloudSyncProgress(host: Activity, progress: SteamCloudSyncProgress) {
        publishSteamCloudIndicatorSyncing(
            direction = progress.direction,
            progressMessage = buildSteamCloudProgressMessage(host, progress),
            progressPercent = progress.progressPercent,
            currentPath = progress.currentPath,
        )
    }

    private fun takePendingLaunchAfterSteamCloudCheck(): Boolean {
        val pending = pendingLaunchAfterSteamCloudCheck
        pendingLaunchAfterSteamCloudCheck = false
        return pending
    }

    private fun isSteamCloudSaveModeEnabled(host: Activity): Boolean {
        return LauncherPreferences.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD
    }

    private fun clearSteamCloudIndicatorState() {
        steamCloudCheckInFlight = false
        steamCloudSyncInFlight = false
        pendingLaunchAfterSteamCloudCheck = false
        lastSteamCloudCheckAtMs = null
        if (uiState.steamCloudIndicator.visible ||
            uiState.steamCloudIndicator.state != SteamCloudIndicatorState.HIDDEN
        ) {
            uiState = uiState.copy(steamCloudIndicator = SteamCloudIndicatorUi())
        }
    }

    private fun isSteamCloudCheckSessionCurrent(checkSessionId: Long): Boolean {
        return steamCloudCheckInFlight && steamCloudCheckSessionId == checkSessionId
    }

    private fun buildSteamCloudProgressMessage(
        host: Activity,
        progress: SteamCloudSyncProgress,
    ): String {
        return when (progress.phase) {
            SteamCloudSyncPhase.CONNECTING -> host.getString(R.string.main_steam_cloud_progress_connecting)
            SteamCloudSyncPhase.LOGGING_ON -> host.getString(R.string.main_steam_cloud_progress_logging_on)
            SteamCloudSyncPhase.REFRESHING_MANIFEST -> host.getString(R.string.main_steam_cloud_progress_refreshing_manifest)
            SteamCloudSyncPhase.PREPARING_UPLOAD -> host.getString(R.string.main_steam_cloud_progress_preparing_upload)
            SteamCloudSyncPhase.UPLOADING -> host.getString(
                R.string.main_steam_cloud_progress_uploading,
                progress.completedFiles,
                progress.totalFiles
            )

            SteamCloudSyncPhase.DOWNLOADING -> host.getString(
                R.string.main_steam_cloud_progress_downloading,
                progress.completedFiles,
                progress.totalFiles
            )

            SteamCloudSyncPhase.BACKING_UP_LOCAL -> host.getString(R.string.main_steam_cloud_progress_backing_up_local)
            SteamCloudSyncPhase.APPLYING_TO_LOCAL -> host.getString(R.string.main_steam_cloud_progress_applying_to_local)
            SteamCloudSyncPhase.FINALIZING -> when (progress.direction) {
                SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD ->
                    host.getString(R.string.main_steam_cloud_progress_finalizing_upload)

                SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL ->
                    host.getString(R.string.main_steam_cloud_progress_finalizing_pull)
            }
        }
    }

    private fun shouldShowRamSaverResidencyLaunchWarning(
        host: Activity,
        launchMode: String
    ): Boolean {
        if (!StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            return false
        }
        if (!CompatibilitySettings.isTextureResidencyManagerCompatEnabled(host)) {
            return false
        }
        return modManagementController.currentOptionalMods().any(::isEnabledRamSaverMod)
    }

    private fun showRamSaverResidencyLaunchDialog(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        bypassSteamCloudPreLaunchSync: Boolean,
    ) {
        var proceed = false
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.main_ram_saver_texture_residency_title)
            .setMessage(host.getString(R.string.main_ram_saver_texture_residency_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.main_ram_saver_texture_residency_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                proceed = true
                dialog.dismiss()
                launchGameActivity(
                    host = host,
                    launchMode = launchMode,
                    backBehavior = backBehavior,
                    manualDismissBootOverlay = manualDismissBootOverlay,
                    forceJvmCrash = forceJvmCrash,
                    bypassSteamCloudPreLaunchSync = bypassSteamCloudPreLaunchSync,
                )
            }
        }
        dialog.setOnDismissListener {
            if (!proceed) {
                clearLaunchInFlightState()
            }
        }
        dialog.show()
    }

    private fun notifyResidualGameProcessCleanupFailed(host: Activity) {
        clearLaunchInFlightState()
        refresh(host)
        _effects.tryEmit(
            Effect.ShowSnackbar(
                message = UiText.StringResource(R.string.main_launch_game_cleanup_failed),
                duration = LauncherTransientNoticeDuration.LONG
            )
        )
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

    private fun isEnabledRamSaverMod(mod: ModItemUi): Boolean {
        if (!mod.enabled || mod.required) {
            return false
        }
        return isRamSaverModId(mod.modId) ||
            isRamSaverModId(mod.manifestModId) ||
            looksLikeRamSaverName(mod.name) ||
            looksLikeRamSaverName(resolveModFileName(mod.storagePath))
    }

    private fun isRamSaverModId(value: String?): Boolean {
        return ModManager.normalizeModId(value) == ModManager.MOD_ID_RAM_SAVER
    }

    private fun looksLikeRamSaverName(value: String?): Boolean {
        val normalized = value
            .orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .removeSuffix(".jar")
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
        return normalized == "ramsaver"
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
                isOutOfMemory = isOutOfMemoryCrash(code, detail),
                isLaunchPreparationProcessDisconnected =
                    report.isLaunchPreparationProcessDisconnected
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
        diagnosticsExecutor.execute {
            runCatching {
                val payload = JvmLogShareService.prepareCrashSharePayload(
                    host,
                    code,
                    isSignal,
                    detail
                )
                val shareIntent = JvmLogShareService.buildShareIntent(host, payload)
                Intent.createChooser(
                    shareIntent,
                    host.getString(R.string.sts_share_crash_chooser_title)
                )
            }.onSuccess { chooserIntent ->
                host.runOnUiThread {
                    setBusy(false, null)
                    if (host.isFinishing || host.isDestroyed) {
                        return@runOnUiThread
                    }
                    _effects.tryEmit(Effect.LaunchIntent(chooserIntent))
                }
            }.onFailure {
                host.runOnUiThread {
                    setBusy(false, null)
                    if (host.isFinishing || host.isDestroyed) {
                        return@runOnUiThread
                    }
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(R.string.sts_share_crash_report_failed),
                            duration = LauncherTransientNoticeDuration.LONG
                        )
                    )
                }
            }
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
        val dependencyAvailability = resolveDependencyAvailability(host)
        publishUiState(
            host = host,
            hasJar = dependencyAvailability.hasJar,
            hasMts = dependencyAvailability.hasMts,
            hasBaseMod = dependencyAvailability.hasBaseMod,
            hasStsLib = dependencyAvailability.hasStsLib,
            hasRuntimeCompat = dependencyAvailability.hasRuntimeCompat,
            storageIssue = detectStorageIssue(host)
        )
    }

    private fun publishUiState(
        host: Activity,
        hasJar: Boolean,
        hasMts: Boolean,
        hasBaseMod: Boolean,
        hasStsLib: Boolean,
        hasRuntimeCompat: Boolean,
        storageIssue: StorageIssueUi?
    ) {
        val snapshot = modManagementController.snapshot()
        val currentBusy = uiState.busy
        val currentBusyOperation = uiState.busyOperation
        val currentBusyMessage = uiState.busyMessage
        val currentBusyProgressPercent = uiState.busyProgressPercent
        val currentSteamCloudIndicator = uiState.steamCloudIndicator
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
                hasStsLib = hasStsLib,
                hasRuntimeCompat = hasRuntimeCompat
            ),
            optionalMods = snapshot.optionalMods,
            storageIssue = storageIssue,
            controlsEnabled = resolveControlsEnabled(currentBusy, currentBusyOperation, storageIssue != null),
            gameProcessRunning = gameProcessRunning,
            launchInFlight = launchInFlight,
            showModFileName = LauncherPreferences.readShowModFileName(host),
            modSuggestions = currentModSuggestions,
            readModSuggestionKeys = currentReadModSuggestionKeys,
            pendingLaunchUnreadSuggestionModNames = pendingLaunchUnreadSuggestionModNames,
            modFolders = snapshot.modFolders,
            folderAssignments = snapshot.folderAssignments,
            folderCollapsed = snapshot.folderCollapsed,
            unassignedCollapsed = snapshot.unassignedCollapsed,
            dependencyFolderCollapsed = snapshot.dependencyFolderCollapsed,
            dragLocked = snapshot.dragLocked,
            unassignedFolderName = snapshot.unassignedFolderName,
            unassignedFolderOrder = snapshot.unassignedFolderOrder,
            steamCloudIndicator = currentSteamCloudIndicator,
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

    private fun markLaunchInFlight() {
        launchInFlight = true
        if (!uiState.launchInFlight) {
            uiState = uiState.copy(launchInFlight = true)
        }
    }

    private fun clearLaunchInFlightState() {
        launchInFlight = false
        if (uiState.launchInFlight) {
            uiState = uiState.copy(launchInFlight = false)
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

            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT ->
                RuntimePaths.importedAmethystRuntimeCompatJar(host).exists() ||
                    hasBundledAsset(host, "components/mods/AmethystRuntimeCompat.jar")

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
        private const val STEAM_CLOUD_STATUS_REFRESH_INTERVAL_MS = 60_000L
        private val DEFAULT_UNASSIGNED_FOLDER_NAME: String = if (Locale.getDefault().language.startsWith("zh")) {
            "未分类"
        } else {
            "Uncategorized"
        }
    }

    override fun onCleared() {
        importedStsJarValidationExecutor.shutdownNow()
        steamCloudExecutor.shutdownNow()
        launchExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        suggestionExecutor.shutdownNow()
        modManagementController.shutdown()
        super.onCleared()
    }
}
