package io.stamethyst.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
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
import io.stamethyst.R
import io.stamethyst.StsGameActivity
import io.stamethyst.LauncherActivity
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.crash.ProcessExitSummary
import io.stamethyst.backend.crash.SignalCrashDumpReader
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.launch.BackExitNotice
import io.stamethyst.backend.launch.CrashReturnPayload
import io.stamethyst.backend.launch.ExpectedGameExitNotice
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import io.stamethyst.backend.launch.LauncherReturnAction
import io.stamethyst.backend.launch.LauncherReturnActionResolver
import io.stamethyst.backend.launch.LauncherReturnSnapshot
import io.stamethyst.backend.launch.MainProcessLaunchPreparationCoordinator
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.ModSuggestionService
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudNetworkEnvironment
import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudSyncProcessService
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.mods.StsDesktopJarPatcher
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.resources.RuntimeResourceProvider
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.MtsComponentUpdateProgress
import io.stamethyst.backend.update.MtsComponentUpdateService
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.backend.workshop.WorkshopAutoImporter
import io.stamethyst.backend.workshop.WorkshopAutoImportProgress
import io.stamethyst.backend.workshop.WorkshopAutoImportResult
import io.stamethyst.backend.workshop.WorkshopInstalledContentKind
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopDownloadProcessService
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import io.stamethyst.backend.workshop.WorkshopModCardState
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.backend.workshop.allLocalJarPaths
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.StsExternalStorageAccess
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.ui.LauncherTransientNoticeDuration
import io.stamethyst.ui.UiText
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.files.JvmLogShareService
import io.stamethyst.ui.workshop.WorkshopDownloadCenterStore
import io.stamethyst.ui.workshop.WorkshopDownloadTaskUi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    data class WorkshopJarSelectionCandidate(
        val id: String,
        val displayPath: String,
        val absolutePath: String,
        val sizeBytes: Long,
    )

    data class PendingWorkshopJarSelection(
        val requestId: Long,
        val appId: UInt,
        val publishedFileId: ULong,
        val title: String,
        val candidates: List<WorkshopJarSelectionCandidate>,
        val details: WorkshopItemDetails? = null,
    )

    data class PendingEnabledModSizeLaunchWarning(
        val totalBytes: Long,
        val launchMode: String,
        val forceJvmCrash: Boolean,
        val forceRuntimeCrash: Boolean,
    )

    data object PendingMtsComponentUpdate

    data class FailedModNameMigrationUi(
        val displayName: String,
        val storagePath: String,
        val reason: String
    )

    data class CrashRecoveryState(
        val code: Int,
        val isSignal: Boolean,
        val summaryText: String,
        val reportText: String,
        val isOutOfMemory: Boolean
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
        val expectedBackExitNoticeVisible: Boolean = false,
        val controlsEnabled: Boolean = true,
        val gameProcessRunning: Boolean = false,
        val launchInFlight: Boolean = false,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val modSuggestions: Map<String, String> = emptyMap(),
        val readModSuggestionKeys: Set<String> = emptySet(),
        val pendingLaunchUnreadSuggestionModNames: List<String> = emptyList(),
        val modLaunchProfiles: List<ModLaunchProfile> = emptyList(),
        val activeModLaunchProfileId: String = "default",
        val modFolders: List<ModFolder> = emptyList(),
        val folderAssignments: Map<String, String> = emptyMap(),
        val folderCollapsed: Map<String, Boolean> = emptyMap(),
        val unassignedCollapsed: Boolean = false,
        val dependencyFolderCollapsed: Boolean = true,
        val dragLocked: Boolean = false,
        val unassignedFolderName: String = DEFAULT_UNASSIGNED_FOLDER_NAME,
        val unassignedFolderOrder: Int = 0,
        val favoriteModKeys: Set<String> = emptySet(),
        val modAssociationState: ModAssociationState = ModAssociationState(),
        val showModFileNameRemovalNotice: Boolean = false,
        val steamCloudIndicator: SteamCloudIndicatorUi = SteamCloudIndicatorUi(),
        val pendingWorkshopJarSelection: PendingWorkshopJarSelection? = null,
        val pendingEnabledModSizeLaunchWarning: PendingEnabledModSizeLaunchWarning? = null,
        val pendingMtsComponentUpdate: PendingMtsComponentUpdate? = null,
    )

    sealed interface Effect {
        data class ShowSnackbar(
            val message: UiText,
            val duration: LauncherTransientNoticeDuration = LauncherTransientNoticeDuration.SHORT,
            val actionLabel: UiText? = null,
            val onAction: (() -> Unit)? = null
        ) : Effect
        data class ShowDialog(val title: UiText, val message: UiText) : Effect
        data class ShowModNameMigrationFailureDialog(
            val failedMods: List<FailedModNameMigrationUi>
        ) : Effect
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
    private val importedStsJarValidationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val workshopUpdateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modNameMigrationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mtsComponentUpdateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
    private var steamCloudCheckSessionId = 0L
    @Volatile
    private var steamCloudSyncSessionId = 0L
    @Volatile
    private var steamCloudSyncCancelRequested = false
    private var lastSteamCloudCheckAtMs: Long? = null
    private var steamCloudProcessEventReceiver: BroadcastReceiver? = null
    private var steamCloudProcessEventReceiverContext: Context? = null
    private var lastFullRefreshAtElapsedMs: Long? = null
    @Volatile
    private var launchInFlight = false
    /**
     * Latched when [maybeLaunchFromDebugExtra] sees debug autoplay extras, and consumed in
     * [launchGameActivityInternal] so the autoplay settings flow to [StsGameActivity] without
     * having to thread extra parameters through the long chain of dialog/cleanup steps. Reset
     * after every launch attempt so a follow-up manual launch doesn't inherit script settings.
     */
    @Volatile
    private var pendingAutoplay: Boolean = false
    @Volatile
    private var pendingAutoplaySaveMode: AutoplaySaveMode = AutoplaySaveMode.DEFAULT
    @Volatile
    private var pendingAutoplayMode: AutoplayMode = AutoplayMode.DEFAULT
    @Volatile
    private var pendingAutoplaySingleRoomSpecPath: String = ""
    @Volatile
    private var pendingAutoplayChoiceDelayMs: Long = 0L
    @Volatile
    private var pendingCardObtainEffectOwnershipCompatEnabled: Boolean = true
    @Volatile
    private var modNameMigrationInFlight = false
    private var modNameMigrationInsufficientNoticeShown = false
    private var modNameMigrationFailureSuppressed = false
    private var pendingFailedModNameMigrationResult: ModManifestNameMigrationResult? = null
    private var nextWorkshopJarSelectionRequestId = 1L
    @Volatile
    private var mtsComponentUpdateCheckInFlight = false
    @Volatile
    private var mtsComponentUpdateDismissedForSession = false

    var uiState by mutableStateOf(UiState())
        private set

    internal fun replaceUiStateForBenchmark(state: UiState) {
        uiState = state
    }

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
        if (!launchInFlight) {
            clearLaunchInFlightState(clearPendingEnabledModSizeWarning = false)
        }
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
            hasFloatingTools = dependencyAvailability.hasFloatingTools,
            hasRamSaver = dependencyAvailability.hasRamSaver,
            storageIssue = storageIssue
        )
        lastFullRefreshAtElapsedMs = SystemClock.elapsedRealtime()
        maybeStartStoredModNameMigration(host)
        maybePromptPendingWorkshopJarSelection(host)
        // Keep the ModTheSpire update implementation available, but do not auto-check it.
    }

    fun refreshIfStale(host: Activity) {
        val now = SystemClock.elapsedRealtime()
        val lastRefreshAt = lastFullRefreshAtElapsedMs
        if (lastRefreshAt != null && now - lastRefreshAt < PASSIVE_REFRESH_DEBOUNCE_MS) {
            return
        }
        refresh(host)
    }

    suspend fun refreshWorkshopDownloadCards(host: Activity): Boolean {
        val loadedTasks = withContext(Dispatchers.IO) {
            WorkshopDownloadCenterStore.loadLauncherCardTasksWithRecovery(host)
        }
        WorkshopDownloadCenterStore.replaceLauncherCardTasksInMemory(loadedTasks)

        val visibleTasks = loadedTasks.filter { task -> task.status.shouldShowLightweightWorkshopTask() }
        val activeTasks = loadedTasks.filter { task -> task.status.isActiveDownload() }
        val tasksByPublishedFileId = loadedTasks.associateBy { task -> task.publishedFileId }
        val currentOptionalMods = uiState.optionalMods
        val updatedOptionalMods = currentOptionalMods.mapNotNull { mod ->
            val workshop = mod.workshop ?: return@mapNotNull mod
            val task = tasksByPublishedFileId[workshop.publishedFileId] ?: return@mapNotNull mod
            val taskState = task.status.toWorkshopModStateOrNull()
            if (taskState == null) {
                if (mod.isStandaloneWorkshopTaskCard(workshop)) null else mod
            } else {
                mod.copy(
                    fileSizeBytes = task.fileSizeBytes.takeIf { it > 0L } ?: mod.fileSizeBytes,
                    workshop = workshop.copy(
                        state = taskState,
                        statusText = task.message.ifBlank { workshop.statusText },
                        downloadProgressPercent = task.progressPercent,
                    )
                )
            }
        }.toMutableList()

        val existingPublishedFileIds = updatedOptionalMods
            .mapNotNull { mod -> mod.workshop?.publishedFileId }
            .toSet()
        visibleTasks.asReversed().forEach { task ->
            if (task.publishedFileId !in existingPublishedFileIds) {
                updatedOptionalMods.add(0, task.toStandaloneWorkshopModItem())
            }
        }
        if (updatedOptionalMods != currentOptionalMods) {
            uiState = uiState.copy(optionalMods = updatedOptionalMods)
        }
        return activeTasks.isNotEmpty()
    }

    private fun clearNewlyImportedHighlights(host: Activity) {
        if (uiState.optionalMods.none { it.newlyImported }) {
            return
        }
        modManagementController.clearNewlyImportedHighlights(host)
        republish(host)
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
        ensureSteamCloudProcessEventReceiverRegistered(host.applicationContext)
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
        val receiver = buildSteamCloudSyncReceiver(
            host = host,
            checkSessionId = checkSessionId,
            userInitiated = force,
        )
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
        SteamCloudSyncProcessService.startCheckAndSync(
            context = host,
            userInitiated = force,
            receiver = receiver,
        )
        return true
    }

    fun cancelSteamCloudCheck(host: Activity) {
        if (!steamCloudCheckInFlight) {
            return
        }
        steamCloudCheckSessionId++
        steamCloudCheckInFlight = false
        val cancelledAtMs = System.currentTimeMillis()
        lastSteamCloudCheckAtMs = cancelledAtMs
        publishSteamCloudIndicatorFailure(
            summary = host.getString(R.string.main_steam_cloud_check_cancelled_summary),
            checkedAtMs = cancelledAtMs,
        )
        SteamCloudSyncProcessService.cancel(host)
    }

    fun cancelSteamCloudSync(host: Activity) {
        if (!steamCloudSyncInFlight) {
            return
        }
        steamCloudSyncCancelRequested = true
        steamCloudSyncSessionId++
        steamCloudSyncInFlight = false
        val cancelledAtMs = System.currentTimeMillis()
        lastSteamCloudCheckAtMs = cancelledAtMs
        publishSteamCloudIndicatorFailure(
            summary = host.getString(R.string.main_steam_cloud_sync_cancelled_summary),
            checkedAtMs = cancelledAtMs,
        )
        SteamCloudSyncProcessService.cancel(host)
    }

    internal fun onLaunchRequested(host: Activity): LaunchRequestAction {
        if (uiState.busy || launchInFlight) {
            return LaunchRequestAction.NONE
        }
        if (steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET
        }
        if (uiState.steamCloudIndicator.visible &&
            (uiState.steamCloudIndicator.state == SteamCloudIndicatorState.CONNECTION_FAILED ||
                uiState.steamCloudIndicator.state == SteamCloudIndicatorState.CONFLICT)
        ) {
            return LaunchRequestAction.OPEN_STEAM_CLOUD_SHEET
        }
        onLaunch(host)
        return LaunchRequestAction.NONE
    }

    fun onLaunchAfterSteamCloudError(host: Activity) {
        if (uiState.busy || launchInFlight || steamCloudCheckInFlight || steamCloudSyncInFlight) {
            return
        }
        onLaunch(host)
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
        ensureSteamCloudProcessEventReceiverRegistered(host.applicationContext)

        val syncSessionId = beginSteamCloudSync()
        publishSteamCloudIndicatorSyncing(
            direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
            progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_local_override),
            progressPercent = 0,
            currentPath = "",
        )
        SteamCloudSyncProcessService.startUseLocal(
            context = host,
            receiver = buildSteamCloudSyncReceiver(
                host = host,
                syncSessionId = syncSessionId,
                userInitiated = true,
            ),
        )
    }

    fun onBackgroundUseLocalSteamCloudProgressAndLaunch(host: Activity) {
        if (uiState.busy || launchInFlight || steamCloudCheckInFlight || steamCloudSyncInFlight) {
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
        ensureSteamCloudProcessEventReceiverRegistered(host.applicationContext)
        if (!tryBeginLaunchRequest()) {
            return
        }

        dismissCrashRecovery()
        val syncSessionId = beginSteamCloudSync()
        publishSteamCloudIndicatorSyncing(
            direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
            progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_background_local_override),
            progressPercent = 0,
            currentPath = "",
        )
        val started = SteamCloudSyncProcessService.startUseLocal(
            context = host,
            receiver = buildSteamCloudSyncReceiver(
                host = host,
                syncSessionId = syncSessionId,
                userInitiated = true,
            ),
        )
        if (!started) {
            clearLaunchInFlightState()
            return
        }
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
            skipEnabledModSizeWarning = true,
        )
    }

    fun onBackgroundSteamCloudSyncAndLaunch(host: Activity) {
        if (uiState.busy || launchInFlight || steamCloudCheckInFlight) {
            return
        }
        val indicator = uiState.steamCloudIndicator
        if (!steamCloudSyncInFlight ||
            indicator.state != SteamCloudIndicatorState.SYNCING ||
            indicator.syncDirection != SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD
        ) {
            return
        }
        if (!tryBeginLaunchRequest()) {
            return
        }

        dismissCrashRecovery()
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
            skipEnabledModSizeWarning = true,
        )
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
        ensureSteamCloudProcessEventReceiverRegistered(host.applicationContext)

        val syncSessionId = beginSteamCloudSync()
        publishSteamCloudIndicatorSyncing(
            direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
            progressMessage = host.getString(R.string.main_steam_cloud_progress_preparing_cloud_override),
            progressPercent = 0,
            currentPath = "",
        )
        SteamCloudSyncProcessService.startUseCloud(
            context = host,
            receiver = buildSteamCloudSyncReceiver(
                host = host,
                syncSessionId = syncSessionId,
                userInitiated = true,
            ),
        )
    }

    fun onDeleteMod(host: Activity, mod: ModItemUi) {
        modManagementController.onDeleteMod(host, mod)
    }

    fun onDeleteMods(host: Activity, mods: List<ModItemUi>) {
        modManagementController.onDeleteMods(host, mods)
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

    fun onRenameModAlias(host: Activity, mod: ModItemUi, aliasInput: String) {
        modManagementController.onRenameModAlias(host, mod, aliasInput)
    }

    fun onRestoreModOriginalName(host: Activity, mod: ModItemUi) {
        modManagementController.onRestoreModOriginalName(host, mod)
    }

    fun onApplyFileNameAliasesForRemovedShowFileName(host: Activity) {
        if (uiState.busy) {
            return
        }
        modNameMigrationInsufficientNoticeShown = false
        maybeStartStoredModNameMigration(host)
    }

    fun retryFailedModNameMigration(host: Activity) {
        if (uiState.busy) {
            return
        }
        pendingFailedModNameMigrationResult = null
        modNameMigrationFailureSuppressed = false
        modNameMigrationInsufficientNoticeShown = false
        maybeStartStoredModNameMigration(host)
    }

    fun abandonFailedModNameMigration(host: Activity) {
        if (uiState.busy) {
            return
        }
        val result = pendingFailedModNameMigrationResult ?: return
        runCatching {
            ModManifestNameMigration.abandonFailedStoredNameMigrations(host, result)
        }.onSuccess { abandonedCount ->
            pendingFailedModNameMigrationResult = null
            modNameMigrationFailureSuppressed = false
            modNameMigrationInsufficientNoticeShown = false
            refresh(host)
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    UiText.StringResource(
                        R.string.main_mod_name_migration_abandoned,
                        abandonedCount
                    )
                )
            )
        }.onFailure { error ->
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.StringResource(
                        R.string.main_mod_rename_failed,
                        error.message ?: host.getString(R.string.feedback_unknown_error)
                    ),
                    duration = LauncherTransientNoticeDuration.LONG
                )
            )
        }
    }

    fun onDismissRemovedShowFileNameNotice(host: Activity) {
        LauncherPreferences.saveShowModFileName(host, false)
        ModAliasStore.markShowFileNameRemovalNoticeHandled(host)
        uiState = uiState.copy(showModFileName = false, showModFileNameRemovalNotice = false)
    }

    fun onToggleMod(host: Activity, mod: ModItemUi, enabled: Boolean) {
        modManagementController.onToggleMod(host, mod, enabled)
        if (enabled && modManagementController.clearEnabledNewlyImportedHighlights(host)) {
            republish(host)
        }
    }

    fun associateMods(host: Activity, source: ModItemUi, target: ModItemUi) {
        modManagementController.associateMods(host, source, target)
    }

    fun removeModAssociation(host: Activity, source: ModItemUi, target: ModItemUi) {
        modManagementController.removeModAssociation(host, source, target)
    }

    fun clearModAssociationGroup(host: Activity, mod: ModItemUi) {
        modManagementController.clearModAssociationGroup(host, mod)
    }

    fun shouldPromptSteamCloudDirectMode(host: Activity): Boolean {
        return SteamCloudNetworkEnvironment.shouldPromptForDirectMode(host)
    }

    fun switchSteamCloudDirectMode(host: Activity) {
        SteamCloudNetworkEnvironment.switchToDirectMode(host)
    }

    fun onPatchWorkshopMod(host: Activity, mod: ModItemUi) {
        val workshop = mod.workshop ?: return
        val record = WorkshopMetadataStore(host).findByPublishedFileId(workshop.appId, workshop.publishedFileId)
        val jarCandidates = resolveWorkshopJarCandidates(host, workshop, record)
        val details = WorkshopDownloadTaskStore(host).find(workshop.publishedFileId)?.details
            ?: record?.toWorkshopItemDetails()
        if (jarCandidates.isEmpty()) {
            _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("未找到已下载的工坊 jar 文件")))
            return
        }
        if (jarCandidates.size > 1) {
            uiState = uiState.copy(
                pendingWorkshopJarSelection = createPendingWorkshopJarSelection(
                    appId = workshop.appId,
                    publishedFileId = workshop.publishedFileId,
                    title = mod.name.ifBlank { record?.title ?: workshop.publishedFileId.toString() },
                    candidates = jarCandidates,
                    details = details,
                )
            )
            return
        }
        startWorkshopJarMarketImport(
            host = host,
            pending = createPendingWorkshopJarSelection(
                appId = workshop.appId,
                publishedFileId = workshop.publishedFileId,
                title = mod.name.ifBlank { record?.title ?: workshop.publishedFileId.toString() },
                candidates = jarCandidates,
                details = details,
            ),
            selectedFiles = jarCandidates.map { it.file },
        )
    }

    fun confirmWorkshopJarSelection(
        host: Activity,
        requestId: Long,
        selectedCandidateIds: Set<String>,
    ) {
        val pending = uiState.pendingWorkshopJarSelection
            ?.takeIf { it.requestId == requestId }
            ?: return
        if (selectedCandidateIds.isEmpty()) {
            _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("请选择至少一个 jar 文件")))
            return
        }
        val selectedCandidates = pending.candidates.filter { candidate -> candidate.id in selectedCandidateIds }
        val selectedFiles = selectedCandidates
            .map { candidate -> File(candidate.absolutePath) }
            .distinctBy { file -> file.absolutePath }
        if (selectedCandidates.size != selectedCandidateIds.size || selectedFiles.isEmpty() || selectedFiles.any { file -> !file.isFile }) {
            markWorkshopJarSelectionNotImported(
                host = host,
                pending = pending,
                statusText = "已下载但未导入：选择的 jar 文件不可用",
            )
            return
        }
        startWorkshopJarMarketImport(
            host = host,
            pending = pending,
            selectedFiles = selectedFiles,
        )
    }

    private data class ResolvedWorkshopJarCandidate(
        val file: File,
        val displayPath: String,
        val sizeBytes: Long,
    ) {
        val id: String = file.absolutePath
    }

    private fun resolveWorkshopJarCandidates(
        host: Activity,
        workshop: WorkshopModUi,
        record: WorkshopInstalledModRecord?,
    ): List<ResolvedWorkshopJarCandidate> {
        return resolveWorkshopJarCandidates(
            host = host,
            appId = workshop.appId,
            publishedFileId = workshop.publishedFileId,
            localJarPaths = record?.allLocalJarPaths().orEmpty().ifEmpty { listOf(workshop.localJarPath) },
        )
    }

    private fun resolveWorkshopJarCandidates(
        host: Activity,
        record: WorkshopInstalledModRecord,
    ): List<ResolvedWorkshopJarCandidate> {
        return resolveWorkshopJarCandidates(
            host = host,
            appId = record.appId,
            publishedFileId = record.publishedFileId,
            localJarPaths = record.allLocalJarPaths(),
        )
    }

    private fun resolveWorkshopJarCandidates(
        host: Activity,
        appId: UInt,
        publishedFileId: ULong,
        localJarPaths: List<String>,
    ): List<ResolvedWorkshopJarCandidate> {
        val outputDir = File(host.filesDir, "workshop/$appId/$publishedFileId")
        return localJarPaths
            .asSequence()
            .map { path -> path.trim() }
            .filter { path -> path.isNotEmpty() }
            .mapNotNull { path ->
                val pathFile = File(path)
                val file = if (pathFile.isAbsolute) {
                    pathFile
                } else {
                    File(outputDir, path)
                }.absoluteFile
                if (!file.isFile) {
                    null
                } else {
                    ResolvedWorkshopJarCandidate(
                        file = file,
                        displayPath = if (pathFile.isAbsolute) file.name else path,
                        sizeBytes = file.length().coerceAtLeast(0L),
                    )
                }
            }
            .distinctBy { candidate -> candidate.file.absolutePath }
            .toList()
    }

    private fun createPendingWorkshopJarSelection(
        appId: UInt,
        publishedFileId: ULong,
        title: String,
        candidates: List<ResolvedWorkshopJarCandidate>,
        details: WorkshopItemDetails?,
    ): PendingWorkshopJarSelection {
        return PendingWorkshopJarSelection(
            requestId = nextWorkshopJarSelectionRequestId++,
            appId = appId,
            publishedFileId = publishedFileId,
            title = title,
            candidates = candidates.map { candidate ->
                WorkshopJarSelectionCandidate(
                    id = candidate.id,
                    displayPath = candidate.displayPath,
                    absolutePath = candidate.file.absolutePath,
                    sizeBytes = candidate.sizeBytes,
                )
            },
            details = details,
        )
    }

    private fun maybePromptPendingWorkshopJarSelection(host: Activity) {
        if (uiState.pendingWorkshopJarSelection != null || uiState.busy) return
        val metadataStore = WorkshopMetadataStore(host)
        val records = metadataStore.load()
        val taskStore = WorkshopDownloadTaskStore(host)
        records.firstNotNullOfOrNull { record ->
            if (!record.shouldAutoPromptWorkshopJarSelection()) return@firstNotNullOfOrNull null
            val candidates = resolveWorkshopJarCandidates(host, record)
            if (candidates.size <= 1) return@firstNotNullOfOrNull null
            val details = taskStore.find(record.publishedFileId)?.details ?: record.toWorkshopItemDetails()
            createPendingWorkshopJarSelection(
                appId = record.appId,
                publishedFileId = record.publishedFileId,
                title = record.title.ifBlank { record.publishedFileId.toString() },
                candidates = candidates,
                details = details,
            )
        }?.let { pending ->
            uiState = uiState.copy(pendingWorkshopJarSelection = pending)
        }
    }

    private fun maybeCheckMtsComponentUpdate(host: Activity) {
        if (mtsComponentUpdateDismissedForSession ||
            mtsComponentUpdateCheckInFlight ||
            uiState.busy ||
            uiState.pendingMtsComponentUpdate != null
        ) {
            return
        }
        mtsComponentUpdateCheckInFlight = true
        mtsComponentUpdateExecutor.execute {
            val isOutdated = runCatching {
                MtsComponentUpdateService.isBundledMtsOutdated(host.applicationContext)
            }.getOrDefault(false)
            host.runOnUiThread {
                mtsComponentUpdateCheckInFlight = false
                if (host.isFinishing || host.isDestroyed) {
                    return@runOnUiThread
                }
                if (
                    isOutdated &&
                    !mtsComponentUpdateDismissedForSession &&
                    uiState.pendingMtsComponentUpdate == null
                ) {
                    uiState = uiState.copy(
                        pendingMtsComponentUpdate = PendingMtsComponentUpdate
                    )
                }
            }
        }
    }

    fun dismissMtsComponentUpdatePrompt() {
        mtsComponentUpdateDismissedForSession = true
        if (uiState.pendingMtsComponentUpdate != null) {
            uiState = uiState.copy(pendingMtsComponentUpdate = null)
        }
    }

    fun installMtsComponentUpdate(host: Activity) {
        if (uiState.busy || uiState.pendingMtsComponentUpdate == null) {
            return
        }
        uiState = uiState.copy(pendingMtsComponentUpdate = null)
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_mts_component_update_downloading),
            operation = UiBusyOperation.MTS_COMPONENT_UPDATE,
            progressPercent = 0,
        )
        val preferredSource = UpdateMirrorManager.current(host)
        mtsComponentUpdateExecutor.execute {
            try {
                val result = MtsComponentUpdateService.installUpdate(
                    context = host.applicationContext,
                    preferredUserSource = preferredSource,
                ) { progress ->
                    host.runOnUiThread {
                        if (!uiState.busy ||
                            uiState.busyOperation != UiBusyOperation.MTS_COMPONENT_UPDATE
                        ) {
                            return@runOnUiThread
                        }
                        setBusy(
                            busy = true,
                            message = UiText.DynamicString(
                                buildMtsComponentUpdateProgressMessage(host, progress)
                            ),
                            operation = UiBusyOperation.MTS_COMPONENT_UPDATE,
                            progressPercent = progress.progressPercent,
                        )
                    }
                }
                mtsComponentUpdateDismissedForSession = true
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_mts_component_update_completed,
                                result.source.displayName
                            ),
                            duration = LauncherTransientNoticeDuration.SHORT,
                        )
                    )
                    refresh(host)
                }
            } catch (error: Throwable) {
                val summary = GithubMirrorFallback.summarize(error)
                mtsComponentUpdateDismissedForSession = true
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            message = UiText.StringResource(
                                R.string.main_mts_component_update_failed,
                                summary
                            ),
                            duration = LauncherTransientNoticeDuration.LONG,
                        )
                    )
                }
            }
        }
    }

    private fun buildMtsComponentUpdateProgressMessage(
        host: Activity,
        progress: MtsComponentUpdateProgress,
    ): String {
        val downloadedText = formatMtsComponentUpdateByteSize(progress.downloadedBytes)
        val totalText = progress.totalBytes?.let { formatMtsComponentUpdateByteSize(it) }
        return if (totalText != null) {
            host.getString(
                R.string.main_mts_component_update_downloading_with_total,
                progress.source.displayName,
                downloadedText,
                totalText,
            )
        } else {
            host.getString(
                R.string.main_mts_component_update_downloading_without_total,
                progress.source.displayName,
                downloadedText,
            )
        }
    }

    private fun formatMtsComponentUpdateByteSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun startWorkshopJarMarketImport(
        host: Activity,
        pending: PendingWorkshopJarSelection,
        selectedFiles: List<File>,
    ) {
        val importFiles = selectedFiles
            .filter { file -> file.isFile }
            .distinctBy { file -> file.absolutePath }
        if (importFiles.isEmpty()) {
            markWorkshopJarSelectionNotImported(
                host = host,
                pending = pending,
                statusText = "已下载但未导入：选择的 jar 文件不可用",
            )
            return
        }
        uiState = uiState.copy(pendingWorkshopJarSelection = null)
        setBusy(
            busy = true,
            message = UiText.DynamicString("正在导入市场模组：${pending.title}"),
            operation = UiBusyOperation.MOD_IMPORT,
            progressPercent = 0,
        )
        workshopUpdateExecutor.execute {
            val appContext = host.applicationContext
            val metadataStore = WorkshopMetadataStore(appContext)
            val taskStore = WorkshopDownloadTaskStore(appContext)
            try {
            val existingRecord = metadataStore.findByPublishedFileId(
                appId = pending.appId,
                publishedFileId = pending.publishedFileId,
            )
            val existingTask = taskStore.find(pending.publishedFileId)
            val details = pending.details
                ?: existingTask?.details
                ?: existingRecord?.toWorkshopItemDetails()
                ?: pending.toWorkshopItemDetails()
            val startMessage = "正在导入修补（0%）：等待开始"
            metadataStore.updateState(
                appId = pending.appId,
                publishedFileId = pending.publishedFileId,
                state = WorkshopModCardState.Downloading,
                statusText = startMessage,
            )
            taskStore.upsertOrUpdateWorkshopImportTask(
                details = details,
                status = WorkshopDownloadTaskStatus.Downloading,
                message = startMessage,
                progressPercent = 0,
            )
            taskStore.appendLog(pending.publishedFileId, "用户已选择 ${importFiles.size} 个 jar，进入市场导入修补")
            host.runOnUiThread { refresh(host) }
            var lastProgressMessage = ""
            val importResult = WorkshopAutoImporter.importDownloadedJars(
                context = appContext,
                details = details,
                jarFiles = importFiles,
                onProgress = { progress ->
                    val progressMessage = progress.toWorkshopImportStatusMessage()
                    taskStore.upsertOrUpdateWorkshopImportTask(
                        details = details,
                        status = WorkshopDownloadTaskStatus.Downloading,
                        message = progressMessage,
                        progressPercent = progress.percent.coerceIn(0, 100),
                    )
                    metadataStore.updateState(
                        appId = pending.appId,
                        publishedFileId = pending.publishedFileId,
                        state = WorkshopModCardState.Downloading,
                        statusText = progressMessage,
                    )
                    if (progressMessage != lastProgressMessage) {
                        taskStore.appendLog(pending.publishedFileId, progressMessage)
                        lastProgressMessage = progressMessage
                    }
                    host.runOnUiThread {
                        setBusy(
                            busy = true,
                            message = UiText.DynamicString(progressMessage),
                            operation = UiBusyOperation.MOD_IMPORT,
                            progressPercent = progress.percent.coerceIn(0, 100),
                        )
                    }
                },
            )
            when (importResult) {
                is WorkshopAutoImportResult.Imported -> {
                    val importedSummary = importResult.formatImportedSummary()
                    val message = "下载完成，已导入 $importedSummary"
                    metadataStore.markPatched(
                        appId = pending.appId,
                        publishedFileId = pending.publishedFileId,
                        localJarPaths = importResult.storagePaths,
                        statusText = "已安装 $importedSummary",
                    )
                    cleanWorkshopDownloadedContent(appContext.filesDir, pending.appId, pending.publishedFileId)
                    taskStore.upsertOrUpdateWorkshopImportTask(
                        details = details,
                        status = WorkshopDownloadTaskStatus.Completed,
                        message = message,
                        progressPercent = 100,
                    )
                    taskStore.appendLog(pending.publishedFileId, message)
                    host.runOnUiThread {
                        setBusy(false, null)
                        _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("已导入 $importedSummary")))
                        refresh(host)
                    }
                }
                is WorkshopAutoImportResult.Failed -> {
                    val message = "已下载但未导入：市场导入失败：${importResult.message}"
                    metadataStore.updateState(
                        appId = pending.appId,
                        publishedFileId = pending.publishedFileId,
                        state = WorkshopModCardState.ImportedUnpatched,
                        statusText = message,
                    )
                    taskStore.upsertOrUpdateWorkshopImportTask(
                        details = details,
                        status = WorkshopDownloadTaskStatus.Completed,
                        message = message,
                        progressPercent = 100,
                    )
                    taskStore.appendLog(pending.publishedFileId, message)
                    host.runOnUiThread {
                        setBusy(false, null)
                        _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString(message)))
                        refresh(host)
                    }
                }
            }
            } catch (error: Throwable) {
                val message = "已下载但未导入：市场导入异常：${error.message ?: error.javaClass.simpleName}"
                val details = pending.details
                    ?: taskStore.find(pending.publishedFileId)?.details
                    ?: pending.toWorkshopItemDetails()
                runCatching {
                    metadataStore.updateState(
                        appId = pending.appId,
                        publishedFileId = pending.publishedFileId,
                        state = WorkshopModCardState.ImportedUnpatched,
                        statusText = message,
                    )
                    taskStore.upsertOrUpdateWorkshopImportTask(
                        details = details,
                        status = WorkshopDownloadTaskStatus.Completed,
                        message = message,
                        progressPercent = 100,
                    )
                    taskStore.appendLog(pending.publishedFileId, message)
                    taskStore.appendLog(pending.publishedFileId, error.stackTraceToString())
                }
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString(message)))
                    refresh(host)
                }
            }
        }
    }

    private fun markWorkshopJarSelectionNotImported(
        host: Activity,
        pending: PendingWorkshopJarSelection,
        statusText: String,
    ) {
        WorkshopMetadataStore(host).updateState(
            appId = pending.appId,
            publishedFileId = pending.publishedFileId,
            state = WorkshopModCardState.ImportedUnpatched,
            statusText = statusText,
        )
        uiState = uiState.copy(pendingWorkshopJarSelection = null)
        _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString(statusText)))
        refresh(host)
    }

    fun onRetryWorkshopDownload(host: Activity, mod: ModItemUi) {
        val workshop = mod.workshop ?: return
        workshopUpdateExecutor.execute {
            val store = WorkshopMetadataStore(host)
            val record = store.findByPublishedFileId(workshop.appId, workshop.publishedFileId)
            if (record == null) {
                _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("未找到创意工坊下载记录")))
                return@execute
            }
            val taskStore = WorkshopDownloadTaskStore(host)
            val existingTask = taskStore.find(record.publishedFileId)
            val preservePartialDownload = when (workshop.state) {
                WorkshopModState.DownloadPaused,
                WorkshopModState.DownloadFailed -> true
                else -> false
            }
            if (existingTask == null || existingTask.status == WorkshopDownloadTaskStatus.Completed) {
                taskStore.upsert(record.toWorkshopDownloadTaskRecord().copy(preservePartialDownload = preservePartialDownload))
            } else {
                taskStore.update(record.publishedFileId) { task ->
                    task.copy(
                        status = WorkshopDownloadTaskStatus.Queued,
                        message = "等待下载",
                        updatedAtMillis = System.currentTimeMillis(),
                        progressPercent = if (preservePartialDownload) task.progressPercent else null,
                        downloadedBytes = if (preservePartialDownload) task.downloadedBytes else 0L,
                        completedFiles = if (preservePartialDownload) task.completedFiles else null,
                        completedChunks = if (preservePartialDownload) task.completedChunks else null,
                        errorClass = "",
                        errorMessage = "",
                        errorStackTrace = "",
                        preservePartialDownload = preservePartialDownload,
                    )
                }
            }
            store.updateState(
                appId = record.appId,
                publishedFileId = record.publishedFileId,
                state = WorkshopModCardState.Downloading,
                statusText = "等待下载",
            )
            WorkshopDownloadProcessService.startNextQueued(host.applicationContext)
            host.runOnUiThread { refresh(host) }
        }
    }

    fun onUpdateWorkshopMod(host: Activity, mod: ModItemUi) {
        val workshop = mod.workshop ?: return
        workshopUpdateExecutor.execute {
            val store = WorkshopMetadataStore(host)
            val record = store.findByPublishedFileId(workshop.appId, workshop.publishedFileId)
            if (record == null) {
                _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("未找到创意工坊下载记录")))
                return@execute
            }
            val taskStore = WorkshopDownloadTaskStore(host)
            if (taskStore.find(record.publishedFileId)?.status?.isActiveDownload() == true) {
                _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("该模组已在更新队列中")))
                return@execute
            }
            val existingTask = taskStore.find(record.publishedFileId)
            taskStore.upsert(
                record.toWorkshopUpdatePlaceholderTaskRecord(message = "正在准备更新")
            )
            host.runOnUiThread { refresh(host) }
            try {
                val details = runBlocking {
                    WorkshopService(host.applicationContext)
                        .getDetails(
                            appId = record.appId,
                            publishedFileId = record.publishedFileId,
                            includeCommunityData = false,
                            includeDependencyData = false,
                        )
                }
                val task = details.toWorkshopDownloadTaskRecord(message = "等待更新")
                taskStore.upsert(task)
                store.updateState(
                    appId = record.appId,
                    publishedFileId = record.publishedFileId,
                    state = WorkshopModCardState.Downloading,
                    statusText = "等待更新",
                )
                WorkshopDownloadProcessService.startNextQueued(host.applicationContext)
                host.runOnUiThread {
                    _effects.tryEmit(Effect.ShowSnackbar(UiText.DynamicString("已加入更新队列：${record.title}")))
                    refresh(host)
                }
            } catch (error: Throwable) {
                store.updateState(
                    appId = record.appId,
                    publishedFileId = record.publishedFileId,
                    state = WorkshopModCardState.UpdateAvailable,
                    statusText = "准备更新失败：${error.message ?: error.javaClass.simpleName}",
                )
                if (existingTask == null) {
                    taskStore.remove(record.publishedFileId)
                } else {
                    taskStore.upsert(existingTask)
                }
                host.runOnUiThread {
                    _effects.tryEmit(
                        Effect.ShowSnackbar(
                            UiText.DynamicString("准备更新失败：${error.message ?: error.javaClass.simpleName}")
                        )
                    )
                    refresh(host)
                }
            }
        }
    }

    private fun WorkshopInstalledModRecord.toWorkshopDownloadTaskRecord(): WorkshopDownloadTaskRecord {
        val summary = WorkshopItemSummary(
            appId = appId,
            publishedFileId = publishedFileId,
            title = title,
            previewUrl = previewUrl,
            description = description,
            updatedAtMillis = updatedAtMillis,
        )
        return WorkshopDownloadTaskRecord(
            publishedFileId = publishedFileId,
            title = title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = "等待下载",
            details = WorkshopItemDetails(summary = summary),
            previewUrl = previewUrl,
            description = description,
            fileSizeBytes = 0L,
        )
    }

    private fun WorkshopInstalledModRecord.toWorkshopUpdatePlaceholderTaskRecord(message: String): WorkshopDownloadTaskRecord {
        val summary = WorkshopItemSummary(
            appId = appId,
            publishedFileId = publishedFileId,
            title = title,
            previewUrl = previewUrl,
            description = description,
            updatedAtMillis = updatedAtMillis,
        )
        return WorkshopDownloadTaskRecord(
            publishedFileId = publishedFileId,
            title = title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = message,
            details = WorkshopItemDetails(summary = summary),
            previewUrl = previewUrl,
            description = description,
            fileSizeBytes = 0L,
        )
    }

    private fun WorkshopItemDetails.toWorkshopDownloadTaskRecord(message: String): WorkshopDownloadTaskRecord {
        return WorkshopDownloadTaskRecord(
            publishedFileId = summary.publishedFileId,
            title = summary.title,
            status = WorkshopDownloadTaskStatus.Queued,
            message = message,
            details = this,
            previewUrl = summary.previewUrl,
            description = summary.description,
            authorName = summary.authorName,
            fileSizeBytes = summary.fileSizeBytes,
            progressPercent = null,
            downloadedBytes = 0L,
            totalBytes = summary.fileSizeBytes.takeIf { it > 0L },
            completedFiles = null,
            completedChunks = null,
            errorClass = "",
            errorMessage = "",
            errorStackTrace = "",
        )
    }

    private fun WorkshopInstalledModRecord.toWorkshopItemDetails(): WorkshopItemDetails {
        return WorkshopItemDetails(
            summary = WorkshopItemSummary(
                appId = appId,
                publishedFileId = publishedFileId,
                title = title,
                previewUrl = previewUrl,
                description = description,
                fileSizeBytes = 0L,
                updatedAtMillis = updatedAtMillis,
            ),
            dependencies = dependencies,
        )
    }

    private fun PendingWorkshopJarSelection.toWorkshopItemDetails(): WorkshopItemDetails {
        return WorkshopItemDetails(
            summary = WorkshopItemSummary(
                appId = appId,
                publishedFileId = publishedFileId,
                title = title,
                previewUrl = "",
                description = "",
            ),
        )
    }

    private fun WorkshopInstalledModRecord.shouldAutoPromptWorkshopJarSelection(): Boolean {
        return contentKind == WorkshopInstalledContentKind.JarMod &&
            cardState == WorkshopModCardState.ImportedUnpatched &&
            statusText.contains("选择要导入")
    }

    private fun WorkshopDownloadTaskStore.upsertOrUpdateWorkshopImportTask(
        details: WorkshopItemDetails,
        status: WorkshopDownloadTaskStatus,
        message: String,
        progressPercent: Int?,
    ) {
        val publishedFileId = details.summary.publishedFileId
        val existing = find(publishedFileId)
        if (existing == null) {
            upsert(
                WorkshopDownloadTaskRecord(
                    publishedFileId = publishedFileId,
                    title = details.summary.title,
                    status = status,
                    message = message,
                    details = details,
                    progressPercent = progressPercent,
                    downloadedBytes = details.summary.fileSizeBytes.coerceAtLeast(0L),
                    totalBytes = details.summary.fileSizeBytes.takeIf { it > 0L },
                    completedFiles = null,
                    completedChunks = null,
                    preservePartialDownload = false,
                )
            )
        } else {
            update(publishedFileId) { task ->
                task.copy(
                    status = status,
                    message = message,
                    progressPercent = progressPercent,
                    downloadedBytes = (task.totalBytes ?: task.downloadedBytes).coerceAtLeast(task.downloadedBytes),
                    completedFiles = task.totalFiles ?: task.completedFiles,
                    updatedAtMillis = System.currentTimeMillis(),
                    preservePartialDownload = false,
                )
            }
        }
    }

    private fun WorkshopAutoImportProgress.toWorkshopImportStatusMessage(): String {
        val progressText = if (totalSteps > 0) {
            val step = when {
                percent >= 100 -> totalSteps
                currentStep < totalSteps -> currentStep + 1
                else -> currentStep
            }.coerceIn(1, totalSteps)
            "（$step/$totalSteps，${percent.coerceIn(0, 100)}%）"
        } else {
            "（${percent.coerceIn(0, 100)}%）"
        }
        return "正在导入修补$progressText：$message"
    }

    private fun WorkshopAutoImportResult.Imported.formatImportedSummary(): String {
        val names = modNames
        return when {
            names.isEmpty() -> "${storagePaths.size} 个模组"
            names.size == 1 -> names.single()
            else -> "${names.size} 个模组：${names.joinToString("、")}"
        }
    }

    private fun cleanWorkshopDownloadedContent(filesDir: File, appId: UInt, publishedFileId: ULong) {
        val outputDir = File(filesDir, "workshop/$appId/$publishedFileId")
        if (!outputDir.isDirectory) return
        outputDir.listFiles().orEmpty().forEach { file ->
            if (!file.name.startsWith("preview.", ignoreCase = true)) {
                file.deleteRecursively()
            }
        }
    }

    fun addModLaunchProfile(host: Activity, name: String) {
        modManagementController.addModLaunchProfile(host, name)
    }

    fun renameModLaunchProfile(host: Activity, profileId: String, name: String) {
        modManagementController.renameModLaunchProfile(host, profileId, name)
    }

    fun selectModLaunchProfile(host: Activity, profileId: String) {
        modManagementController.selectModLaunchProfile(host, profileId)
    }

    fun deleteModLaunchProfile(host: Activity, profileId: String) {
        modManagementController.deleteModLaunchProfile(host, profileId)
    }

    fun setModsSelected(host: Activity, mods: List<ModItemUi>, selected: Boolean) {
        modManagementController.setModsSelected(host, mods, selected)
        if (selected && modManagementController.clearEnabledNewlyImportedHighlights(host)) {
            republish(host)
        }
    }

    fun onSetPriority(host: Activity, mod: ModItemUi, priority: Int?) {
        modManagementController.onSetPriority(host, mod, priority)
    }

    fun onSetModFavorite(host: Activity, mod: ModItemUi, favorite: Boolean) {
        modManagementController.setModFavorite(host, mod, favorite)
    }

    fun onLaunch(host: Activity) {
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
            pendingLaunchUnreadSuggestionModNames = unreadSuggestionModNames
            uiState = uiState.copy(pendingLaunchUnreadSuggestionModNames = unreadSuggestionModNames)
            return
        }
        dismissCrashRecovery()
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
        )
    }

    fun confirmLaunchWithUnreadSuggestions(host: Activity) {
        if (pendingLaunchUnreadSuggestionModNames.isEmpty()) {
            return
        }
        if (!launchInFlight && !tryBeginLaunchRequest()) {
            return
        }
        clearPendingLaunchUnreadSuggestionDialog()
        dismissCrashRecovery()
        beginLaunchFlow(
            host = host,
            launchMode = StsLaunchSpec.LAUNCH_MODE_MTS,
            forceJvmCrash = false,
        )
    }

    fun cancelLaunchWithUnreadSuggestions() {
        clearPendingLaunchUnreadSuggestionDialog()
        clearLaunchInFlightState()
    }

    fun confirmLaunchWithEnabledModSizeWarning(host: Activity, dontRemindAgain: Boolean) {
        val pending = uiState.pendingEnabledModSizeLaunchWarning ?: return
        if (!launchInFlight && !tryBeginLaunchRequest()) {
            return
        }
        if (dontRemindAgain) {
            LauncherPreferences.setEnabledModSizeWarningDismissed(host, true)
        }
        uiState = uiState.copy(pendingEnabledModSizeLaunchWarning = null)
        prepareAndLaunch(
            host = host,
            launchMode = pending.launchMode,
            forceJvmCrash = pending.forceJvmCrash,
            forceRuntimeCrash = pending.forceRuntimeCrash,
            skipEnabledModSizeWarning = true,
        )
    }

    fun cancelLaunchWithEnabledModSizeWarning() {
        if (uiState.pendingEnabledModSizeLaunchWarning != null) {
            uiState = uiState.copy(pendingEnabledModSizeLaunchWarning = null)
        }
        clearLaunchInFlightState()
    }

    fun dismissCrashRecovery() {
        if (uiState.crashRecovery == null) {
            return
        }
        uiState = uiState.copy(crashRecovery = null)
    }

    fun dismissExpectedBackExitNotice() {
        if (!uiState.expectedBackExitNoticeVisible) {
            return
        }
        uiState = uiState.copy(expectedBackExitNoticeVisible = false)
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

    fun assignModsToFolder(host: Activity, mods: List<ModItemUi>, folderId: String) {
        modManagementController.assignModsToFolder(host, mods, folderId)
    }

    fun moveModToUnassigned(host: Activity, modId: String) {
        modManagementController.moveModToUnassigned(host, modId)
    }

    fun moveModToUnassigned(host: Activity, mod: ModItemUi) {
        modManagementController.moveModToUnassigned(host, mod)
    }

    fun moveModsToUnassigned(host: Activity, mods: List<ModItemUi>) {
        modManagementController.moveModsToUnassigned(host, mods)
    }

    fun setFolderSelected(host: Activity, folderId: String, selected: Boolean) {
        modManagementController.setFolderSelected(host, folderId, selected)
        if (selected && modManagementController.clearEnabledNewlyImportedHighlights(host)) {
            republish(host)
        }
    }

    fun setUnassignedSelected(host: Activity, selected: Boolean) {
        modManagementController.setUnassignedSelected(host, selected)
        if (selected && modManagementController.clearEnabledNewlyImportedHighlights(host)) {
            republish(host)
        }
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
        val hasRuntimeCompat: Boolean,
        val hasFloatingTools: Boolean,
        val hasRamSaver: Boolean
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
            hasRuntimeCompat = isRequiredModAvailable(host, ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT),
            hasFloatingTools = isRequiredModAvailable(host, ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS),
            hasRamSaver = isRequiredModAvailable(host, ModManager.MOD_ID_RAM_SAVER)
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
                ExpectedGameExitNotice.consumeExpectedGameExitIfRecent(host, launchStartedAtMs)
                suppressFutureProcessExitCrashFallback(host, launchStartedAtMs)
                clearLaunchInFlightState()
                dismissCrashRecovery()
                showExpectedBackExitNotice()
                true
            }

            LauncherReturnAction.ExpectedCleanShutdown -> {
                GameLaunchReturnTracker.terminateTrackedGameProcess(host, includeCached = true)
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                ExpectedGameExitNotice.consumeExpectedGameExitIfRecent(host, launchStartedAtMs)
                if (intent != null) {
                    clearCrashExtras(intent)
                }
                suppressFutureProcessExitCrashFallback(host, launchStartedAtMs)
                clearLaunchInFlightState()
                dismissCrashRecovery()
                true
            }

            LauncherReturnAction.HeapPressureWarning -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                ExpectedGameExitNotice.consumeExpectedGameExitIfRecent(host, launchStartedAtMs)
                dismissCrashRecovery()
                maybeShowHeapPressureDialog(host, intent ?: return false)
            }

            is LauncherReturnAction.ExplicitCrash -> {
                BackExitNotice.consumeExpectedBackExitIfRecent(host)
                ExpectedGameExitNotice.consumeExpectedGameExitIfRecent(host, launchStartedAtMs)
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
                ExpectedGameExitNotice.consumeExpectedGameExitIfRecent(host, launchStartedAtMs)
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
        val explicitCrash = buildExplicitCrashPayload(intent)
        val expectedCleanShutdown = explicitCrash == null &&
            LatestLogCrashDetector.detect(host) == null &&
            ExpectedGameExitNotice.isExpectedGameExitRecent(host, launchStartedAtMs)
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
        hasRuntimeCompat: Boolean,
        hasFloatingTools: Boolean,
        hasRamSaver: Boolean
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
        val floatingTools = requiredModsById[ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS]
            ?.copy(enabled = hasFloatingTools)
            ?: buildSyntheticDependencyMod(
                storageKey = "__dependency__/AmethystFloatingTools.jar",
                modId = ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS,
                displayName = "AmethystFloatingTools.jar",
                version = host.getString(
                    if (hasFloatingTools) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_floating_tools_description),
                installed = hasFloatingTools
            )
        val ramSaver = requiredModsById[ModManager.MOD_ID_RAM_SAVER]
            ?.copy(enabled = hasRamSaver)
            ?: buildSyntheticDependencyMod(
                storageKey = "__dependency__/RamSaver.jar",
                modId = ModManager.MOD_ID_RAM_SAVER,
                displayName = "RamSaver.jar",
                version = host.getString(
                    if (hasRamSaver) {
                        R.string.settings_status_available
                    } else {
                        R.string.settings_status_missing
                    }
                ),
                description = host.getString(R.string.main_dependency_ram_saver_description),
                installed = hasRamSaver
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
            runtimeCompat,
            floatingTools,
            ramSaver
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
        val forceRuntimeCrash = intent.getBooleanExtra(
            LauncherActivity.EXTRA_DEBUG_FORCE_RUNTIME_CRASH,
            false
        )
        val autoplay = intent.getBooleanExtra(LauncherActivity.EXTRA_DEBUG_AUTOPLAY, false)
        val autoplaySaveMode = AutoplaySaveMode.fromPersistedValue(
            intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_AUTOPLAY_SAVE_MODE)
        )
        val autoplayMode = AutoplayMode.fromPersistedValue(
            intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_AUTOPLAY_MODE)
        )
        val autoplaySingleRoomSpecPath =
            intent.getStringExtra(LauncherActivity.EXTRA_DEBUG_AUTOPLAY_SINGLE_ROOM_SPEC)
                .orEmpty()
        val autoplayChoiceDelayMs = intent.getLongExtra(
            LauncherActivity.EXTRA_DEBUG_AUTOPLAY_CHOICE_DELAY_MS,
            0L
        ).coerceAtLeast(0L)
        val cardObtainEffectOwnershipCompatEnabled = !intent.getBooleanExtra(
            LauncherActivity.EXTRA_DEBUG_DISABLE_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT,
            false
        )
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA &&
            !StsLaunchSpec.isMtsLaunchMode(debugLaunchMode)
        ) {
            return
        }

        if (!tryBeginLaunchRequest()) {
            return
        }
        // Stash the autoplay flag on the ViewModel so it survives the long chain of
        // dialog/cleanup steps between here and StsGameActivity.launch() without having to
        // thread an extra parameter through every method in the launch pipeline.
        // Consumed (and cleared) in launchGameActivityInternal.
        pendingAutoplay = autoplay
        pendingAutoplaySaveMode = autoplaySaveMode
        pendingAutoplayMode = autoplayMode
        pendingAutoplaySingleRoomSpecPath = autoplaySingleRoomSpecPath
        pendingAutoplayChoiceDelayMs = autoplayChoiceDelayMs
        pendingCardObtainEffectOwnershipCompatEnabled = cardObtainEffectOwnershipCompatEnabled
        beginLaunchFlow(
            host,
            debugLaunchMode ?: StsLaunchSpec.LAUNCH_MODE_VANILLA,
            forceJvmCrash = forceJvmCrash,
            forceRuntimeCrash = forceRuntimeCrash,
        )
    }

    fun copyCrashRecoveryAiPrompt(host: Activity) {
        val crashRecovery = uiState.crashRecovery ?: return
        val clipboard = host.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "sts-crash-ai-prompt",
                host.getString(R.string.sts_crash_page_ai_prompt_format, crashRecovery.reportText)
            )
        )
        _effects.tryEmit(
            Effect.ShowSnackbar(
                message = UiText.StringResource(R.string.sts_crash_page_ai_prompt_copy_success),
                duration = LauncherTransientNoticeDuration.SHORT
            )
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
        forceRuntimeCrash: Boolean = false,
        skipEnabledModSizeWarning: Boolean = false,
    ) {
        if (GameLaunchReturnTracker.isGameProcessRunning(host, includeCached = true)) {
            cleanupResidualGameProcessAndLaunch(
                host = host,
                launchMode = launchMode,
                forceJvmCrash = forceJvmCrash,
                forceRuntimeCrash = forceRuntimeCrash,
                skipEnabledModSizeWarning = skipEnabledModSizeWarning,
            )
            return
        }
        prepareAndLaunch(
            host = host,
            launchMode = launchMode,
            forceJvmCrash = forceJvmCrash,
            forceRuntimeCrash = forceRuntimeCrash,
            skipEnabledModSizeWarning = skipEnabledModSizeWarning,
        )
    }

    private fun cleanupResidualGameProcessAndLaunch(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
        skipEnabledModSizeWarning: Boolean = false,
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
                    forceRuntimeCrash = forceRuntimeCrash,
                    skipEnabledModSizeWarning = skipEnabledModSizeWarning,
                )
            }
        }
    }

    private fun prepareAndLaunch(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean = false,
        skipEnabledModSizeWarning: Boolean = false,
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
            if (!skipEnabledModSizeWarning &&
                !LauncherPreferences.isEnabledModSizeWarningDismissed(host)
            ) {
                val enabledModTotalBytes = calculateEnabledOptionalModTotalBytes(optionalMods)
                if (enabledModTotalBytes > ENABLED_MOD_SIZE_WARNING_THRESHOLD_BYTES) {
                    showEnabledModSizeLaunchDialog(
                        host = host,
                        launchMode = launchMode,
                        forceJvmCrash = forceJvmCrash,
                        forceRuntimeCrash = forceRuntimeCrash,
                        totalBytes = enabledModTotalBytes,
                    )
                    return
                }
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
                forceRuntimeCrash = forceRuntimeCrash,
            )
            return
        }

        launchGameActivity(
            host = host,
            launchMode = launchMode,
            backBehavior = backBehavior,
            manualDismissBootOverlay = manualDismissBootOverlay,
            forceJvmCrash = forceJvmCrash,
            forceRuntimeCrash = forceRuntimeCrash,
        )
    }

    private fun launchGameActivity(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
    ) {
        prepareLaunchAndLaunch(
            host = host,
            launchMode = launchMode,
            backBehavior = backBehavior,
            manualDismissBootOverlay = manualDismissBootOverlay,
            forceJvmCrash = forceJvmCrash,
            forceRuntimeCrash = forceRuntimeCrash,
        )
    }

    private fun prepareLaunchAndLaunch(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
    ) {
        val progressPublisher = DelayedLaunchPreparationProgressPublisher(host)
        launchExecutor.execute {
            try {
                MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch(
                    context = host.applicationContext,
                    launchMode = launchMode,
                    progressCallback = StartupProgressCallback { percent, message ->
                        progressPublisher.onProgress(percent, message)
                    }
                )
                host.runOnUiThread {
                    progressPublisher.cancel()
                    setBusy(false, null)
                    if (host.isFinishing || host.isDestroyed) {
                        clearLaunchInFlightState()
                        return@runOnUiThread
                    }
                    launchGameActivityInternal(
                        host = host,
                        launchMode = launchMode,
                        backBehavior = backBehavior,
                        manualDismissBootOverlay = manualDismissBootOverlay,
                        forceJvmCrash = forceJvmCrash,
                        forceRuntimeCrash = forceRuntimeCrash
                    )
                }
            } catch (error: Throwable) {
                Log.e(LOGCAT_TAG, "Game launch preparation failed before launch", error)
                writePreGameLaunchFailureEvent(
                    host = host,
                    message = "Game launch preparation failed before launch: " +
                        (error.message ?: error.javaClass.simpleName)
                )
                host.runOnUiThread {
                    progressPublisher.cancel()
                    setBusy(false, null)
                    if (host.isFinishing || host.isDestroyed) {
                        clearLaunchInFlightState()
                        return@runOnUiThread
                    }
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
        }
    }

    private inner class DelayedLaunchPreparationProgressPublisher(
        private val host: Activity
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var showScheduled = false
        private var visible = false
        private var cancelled = false
        private var latestProgressPercent = 0
        private var latestMessage = ""
        private val showRunnable = Runnable {
            showScheduled = false
            if (!canPublish()) {
                return@Runnable
            }
            visible = true
            publishLatest()
        }

        fun onProgress(percent: Int, message: String) {
            handler.post {
                if (cancelled) {
                    return@post
                }
                latestProgressPercent = percent.coerceIn(0, 100)
                latestMessage = message
                if (visible) {
                    publishLatest()
                } else if (!showScheduled) {
                    showScheduled = true
                    handler.postDelayed(
                        showRunnable,
                        MTS_LAUNCH_PREPARATION_BUSY_OVERLAY_DELAY_MS
                    )
                }
            }
        }

        fun cancel() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cancelOnMain()
            } else {
                handler.post { cancelOnMain() }
            }
        }

        private fun cancelOnMain() {
            cancelled = true
            showScheduled = false
            handler.removeCallbacks(showRunnable)
        }

        private fun canPublish(): Boolean {
            return !cancelled && !host.isFinishing && !host.isDestroyed && launchInFlight
        }

        private fun publishLatest() {
            if (!canPublish()) {
                return
            }
            setBusy(
                busy = true,
                message = UiText.DynamicString(latestMessage),
                operation = UiBusyOperation.GAME_STARTUP_WARMUP,
                progressPercent = latestProgressPercent
            )
        }
    }

    private fun writePreGameLaunchFailureEvent(host: Activity, message: String) {
        try {
            val eventsFile = RuntimePaths.bootBridgeEventsLog(host)
            val parent = eventsFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val safeMessage = message
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
            FileOutputStream(eventsFile, true).use { output ->
                output.write("FAIL\t-1\t$safeMessage\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun launchGameActivityInternal(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean
    ) {
        val launchStartedAtMs = GameLaunchReturnTracker.markGameLaunchStarted(host)
        ExpectedGameExitNotice.clearExpectedGameExit(host)
        if (LauncherPreferences.isLogcatCaptureEnabled(host)) {
            LogcatCaptureProcessClient.startCapture(host, launchStartedAtMs)
        } else {
            LogcatCaptureProcessClient.stopAndClearCapture(host)
        }
        // One-shot consume: every launch attempt drains the autoplay latch so a follow-up
        // manual press of "Play" doesn't accidentally run autoplay again.
        val autoplay = pendingAutoplay
        val autoplaySaveMode = pendingAutoplaySaveMode
        val autoplayMode = pendingAutoplayMode
        val autoplaySingleRoomSpecPath = pendingAutoplaySingleRoomSpecPath
        val autoplayChoiceDelayMs = pendingAutoplayChoiceDelayMs
        val cardObtainEffectOwnershipCompatEnabled = pendingCardObtainEffectOwnershipCompatEnabled
        pendingAutoplay = false
        pendingAutoplaySaveMode = AutoplaySaveMode.DEFAULT
        pendingAutoplayMode = AutoplayMode.DEFAULT
        pendingAutoplaySingleRoomSpecPath = ""
        pendingAutoplayChoiceDelayMs = 0L
        pendingCardObtainEffectOwnershipCompatEnabled = true
        try {
            (host as? LauncherActivity)?.markBackgroundForGameLaunch()
            StsGameActivity.launch(
                host,
                launchMode,
                backBehavior,
                manualDismissBootOverlay,
                forceJvmCrash,
                forceRuntimeCrash,
                autoplay,
                autoplaySaveMode,
                autoplayMode,
                autoplaySingleRoomSpecPath,
                autoplayChoiceDelayMs,
                cardObtainEffectOwnershipCompatEnabled
            )
            clearNewlyImportedHighlights(host)
        } catch (error: Throwable) {
            (host as? LauncherActivity)?.clearBackgroundForGameLaunch()
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

    private fun buildSteamCloudSyncReceiver(
        host: Activity,
        checkSessionId: Long? = null,
        syncSessionId: Long? = null,
        userInitiated: Boolean = false,
    ): ResultReceiver {
        val appContext = host.applicationContext
        var activeSyncSessionId: Long? = syncSessionId
        return object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val data = resultData ?: Bundle.EMPTY
                when (resultCode) {
                    SteamCloudSyncProcessService.RESULT_CHECKING -> {
                        if (checkSessionId != null && !isSteamCloudCheckSessionCurrent(checkSessionId)) {
                            return
                        }
                    }

                    SteamCloudSyncProcessService.RESULT_PLAN_READY -> {
                        if (checkSessionId == null || !isSteamCloudCheckSessionCurrent(checkSessionId)) {
                            return
                        }
                        val checkedAtMs = data.steamCloudLongOrNull(
                            SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS
                        ) ?: System.currentTimeMillis()
                        val plan = data.steamCloudUploadPlanOrNull()
                        if (plan == null) {
                            steamCloudCheckInFlight = false
                            lastSteamCloudCheckAtMs = checkedAtMs
                            publishSteamCloudIndicatorFailure("Steam Cloud upload plan missing.", checkedAtMs)
                            return
                        }
                        steamCloudCheckInFlight = false
                        lastSteamCloudCheckAtMs = checkedAtMs
                        publishSteamCloudIndicatorPlan(plan, checkedAtMs)
                    }

                    SteamCloudSyncProcessService.RESULT_SYNC_STARTED -> {
                        val activeSessionId = when {
                            syncSessionId != null -> {
                                if (!isSteamCloudSyncSessionCurrent(syncSessionId)) {
                                    return
                                }
                                syncSessionId
                            }

                            checkSessionId != null -> {
                                if (!isSteamCloudCheckSessionCurrent(checkSessionId)) {
                                    return
                                }
                                steamCloudCheckInFlight = false
                                beginSteamCloudSync()
                            }

                            else -> return
                        }
                        activeSyncSessionId = activeSessionId
                        data.steamCloudLongOrNull(SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS)?.let { checkedAtMs ->
                            lastSteamCloudCheckAtMs = checkedAtMs
                        }
                        val currentIndicator = uiState.steamCloudIndicator
                        publishSteamCloudIndicatorSyncing(
                            direction = data.steamCloudSyncDirectionOrNull(
                                SteamCloudSyncProcessService.EXTRA_SYNC_DIRECTION
                            ) ?: currentIndicator.syncDirection ?: SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                            progressMessage = data.getString(SteamCloudSyncProcessService.EXTRA_PROGRESS_MESSAGE)
                                ?.takeIf { it.isNotBlank() }
                                ?: currentIndicator.progressMessage.takeIf { it.isNotBlank() }
                                ?: appContext.getString(R.string.main_steam_cloud_progress_preparing_auto_sync),
                            progressPercent = currentIndicator.progressPercent ?: 0,
                            currentPath = currentIndicator.progressCurrentPath,
                        )
                    }

                    SteamCloudSyncProcessService.RESULT_PROGRESS -> {
                        val activeSessionId = activeSyncSessionId ?: return
                        if (!isSteamCloudSyncSessionCurrent(activeSessionId)) {
                            return
                        }
                        val currentIndicator = uiState.steamCloudIndicator
                        publishSteamCloudIndicatorSyncing(
                            direction = data.steamCloudSyncDirectionOrNull(
                                SteamCloudSyncProcessService.EXTRA_PROGRESS_DIRECTION
                            ) ?: currentIndicator.syncDirection ?: SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                            progressMessage = data.getString(SteamCloudSyncProcessService.EXTRA_PROGRESS_MESSAGE)
                                ?.takeIf { it.isNotBlank() }
                                ?: currentIndicator.progressMessage,
                            progressPercent = data.steamCloudIntOrNull(
                                SteamCloudSyncProcessService.EXTRA_PROGRESS_PERCENT
                            ) ?: currentIndicator.progressPercent,
                            currentPath = data.getString(
                                SteamCloudSyncProcessService.EXTRA_PROGRESS_CURRENT_PATH
                            ).orEmpty(),
                        )
                    }

                    SteamCloudSyncProcessService.RESULT_UP_TO_DATE -> {
                        if (checkSessionId == null || !isSteamCloudCheckSessionCurrent(checkSessionId)) {
                            return
                        }
                        val checkedAtMs = data.steamCloudLongOrNull(
                            SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS
                        ) ?: System.currentTimeMillis()
                        steamCloudCheckInFlight = false
                        lastSteamCloudCheckAtMs = checkedAtMs
                        uiState = uiState.copy(
                            steamCloudIndicator = SteamCloudIndicatorUi(
                                visible = true,
                                state = SteamCloudIndicatorState.UP_TO_DATE,
                                lastCheckedAtMs = checkedAtMs,
                            )
                        )
                    }

                    SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED -> {
                        val activeSessionId = activeSyncSessionId ?: return
                        if (!isSteamCloudSyncSessionCurrent(activeSessionId)) {
                            return
                        }
                        val completedAtMs = data.steamCloudLongOrNull(
                            SteamCloudSyncProcessService.EXTRA_COMPLETED_AT_MS
                        ) ?: System.currentTimeMillis()
                        completeSteamCloudSync(completedAtMs)
                        if (userInitiated ||
                            data.getBoolean(SteamCloudSyncProcessService.EXTRA_USER_INITIATED, false)
                        ) {
                            _effects.tryEmit(
                                Effect.ShowSnackbar(
                                    message = UiText.StringResource(R.string.main_steam_cloud_auto_sync_succeeded),
                                    duration = LauncherTransientNoticeDuration.SHORT,
                                )
                            )
                        }
                    }

                    SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED -> {
                        val activeSessionId = activeSyncSessionId ?: return
                        if (!isSteamCloudSyncSessionCurrent(activeSessionId)) {
                            return
                        }
                        val completedAtMs = data.steamCloudLongOrNull(
                            SteamCloudSyncProcessService.EXTRA_COMPLETED_AT_MS
                        ) ?: System.currentTimeMillis()
                        completeSteamCloudSync(completedAtMs)
                        _effects.tryEmit(
                            Effect.ShowSnackbar(
                                message = UiText.StringResource(
                                    R.string.main_steam_cloud_local_override_succeeded,
                                    data.getInt(SteamCloudSyncProcessService.EXTRA_UPLOADED_FILE_COUNT),
                                    data.getInt(SteamCloudSyncProcessService.EXTRA_DELETED_REMOTE_FILE_COUNT)
                                ),
                                duration = LauncherTransientNoticeDuration.SHORT,
                            )
                        )
                    }

                    SteamCloudSyncProcessService.RESULT_CLOUD_OVERRIDE_COMPLETED -> {
                        val activeSessionId = activeSyncSessionId ?: return
                        if (!isSteamCloudSyncSessionCurrent(activeSessionId)) {
                            return
                        }
                        val completedAtMs = data.steamCloudLongOrNull(
                            SteamCloudSyncProcessService.EXTRA_COMPLETED_AT_MS
                        ) ?: System.currentTimeMillis()
                        completeSteamCloudSync(completedAtMs)
                        _effects.tryEmit(
                            Effect.ShowSnackbar(
                                message = UiText.StringResource(
                                    R.string.main_steam_cloud_cloud_override_succeeded,
                                    data.getInt(SteamCloudSyncProcessService.EXTRA_APPLIED_FILE_COUNT)
                                ),
                                duration = LauncherTransientNoticeDuration.SHORT,
                            )
                        )
                    }

                    SteamCloudSyncProcessService.RESULT_FAILURE,
                    SteamCloudSyncProcessService.RESULT_CANCELLED -> {
                        handleSteamCloudServiceFailure(
                            appContext = appContext,
                            data = data,
                            checkSessionId = checkSessionId,
                            syncSessionId = activeSyncSessionId,
                            userInitiated = userInitiated,
                            isCancellation = resultCode == SteamCloudSyncProcessService.RESULT_CANCELLED,
                        )
                    }
                }
            }
        }
    }

    private fun ensureSteamCloudProcessEventReceiverRegistered(appContext: Context) {
        if (steamCloudProcessEventReceiver != null && steamCloudProcessEventReceiverContext === appContext) {
            return
        }
        unregisterSteamCloudProcessEventReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != SteamCloudSyncProcessService.ACTION_SYNC_EVENT) {
                    return
                }
                val extras = intent.extras ?: Bundle.EMPTY
                if (!extras.containsKey(SteamCloudSyncProcessService.EXTRA_EVENT_RESULT_CODE)) {
                    return
                }
                handleSteamCloudProcessEvent(
                    appContext = appContext,
                    resultCode = extras.getInt(SteamCloudSyncProcessService.EXTRA_EVENT_RESULT_CODE),
                    data = Bundle(extras),
                )
            }
        }
        val filter = IntentFilter(SteamCloudSyncProcessService.ACTION_SYNC_EVENT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            steamCloudProcessEventReceiver = receiver
            steamCloudProcessEventReceiverContext = appContext
        } catch (error: Throwable) {
            Log.w(LOGCAT_TAG, "Failed to register Steam Cloud process event receiver", error)
        }
    }

    private fun unregisterSteamCloudProcessEventReceiver() {
        val receiver = steamCloudProcessEventReceiver ?: return
        val receiverContext = steamCloudProcessEventReceiverContext
        steamCloudProcessEventReceiver = null
        steamCloudProcessEventReceiverContext = null
        if (receiverContext == null) {
            return
        }
        try {
            receiverContext.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private fun handleSteamCloudProcessEvent(
        appContext: Context,
        resultCode: Int,
        data: Bundle,
    ) {
        when (resultCode) {
            SteamCloudSyncProcessService.RESULT_CHECKING -> {
                if (!steamCloudCheckInFlight) {
                    return
                }
            }

            SteamCloudSyncProcessService.RESULT_PLAN_READY -> {
                if (!steamCloudCheckInFlight) {
                    return
                }
                val checkedAtMs = data.steamCloudLongOrNull(
                    SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS
                ) ?: System.currentTimeMillis()
                val plan = data.steamCloudUploadPlanOrNull()
                if (plan == null) {
                    steamCloudCheckInFlight = false
                    lastSteamCloudCheckAtMs = checkedAtMs
                    publishSteamCloudIndicatorFailure("Steam Cloud upload plan missing.", checkedAtMs)
                    return
                }
                steamCloudCheckInFlight = false
                lastSteamCloudCheckAtMs = checkedAtMs
                publishSteamCloudIndicatorPlan(plan, checkedAtMs)
            }

            SteamCloudSyncProcessService.RESULT_SYNC_STARTED -> {
                if (steamCloudCheckInFlight) {
                    steamCloudCheckInFlight = false
                    beginSteamCloudSync()
                } else if (!steamCloudSyncInFlight || steamCloudSyncCancelRequested) {
                    return
                }
                data.steamCloudLongOrNull(SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS)?.let { checkedAtMs ->
                    lastSteamCloudCheckAtMs = checkedAtMs
                }
                val currentIndicator = uiState.steamCloudIndicator
                publishSteamCloudIndicatorSyncing(
                    direction = data.steamCloudSyncDirectionOrNull(
                        SteamCloudSyncProcessService.EXTRA_SYNC_DIRECTION
                    ) ?: currentIndicator.syncDirection ?: SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    progressMessage = data.getString(SteamCloudSyncProcessService.EXTRA_PROGRESS_MESSAGE)
                        ?.takeIf { it.isNotBlank() }
                        ?: currentIndicator.progressMessage.takeIf { it.isNotBlank() }
                        ?: appContext.getString(R.string.main_steam_cloud_progress_preparing_auto_sync),
                    progressPercent = currentIndicator.progressPercent ?: 0,
                    currentPath = currentIndicator.progressCurrentPath,
                )
            }

            SteamCloudSyncProcessService.RESULT_PROGRESS -> {
                if (!steamCloudSyncInFlight || steamCloudSyncCancelRequested) {
                    return
                }
                val currentIndicator = uiState.steamCloudIndicator
                publishSteamCloudIndicatorSyncing(
                    direction = data.steamCloudSyncDirectionOrNull(
                        SteamCloudSyncProcessService.EXTRA_PROGRESS_DIRECTION
                    ) ?: currentIndicator.syncDirection ?: SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    progressMessage = data.getString(SteamCloudSyncProcessService.EXTRA_PROGRESS_MESSAGE)
                        ?.takeIf { it.isNotBlank() }
                        ?: currentIndicator.progressMessage,
                    progressPercent = data.steamCloudIntOrNull(
                        SteamCloudSyncProcessService.EXTRA_PROGRESS_PERCENT
                    ) ?: currentIndicator.progressPercent,
                    currentPath = data.getString(
                        SteamCloudSyncProcessService.EXTRA_PROGRESS_CURRENT_PATH
                    ).orEmpty(),
                )
            }

            SteamCloudSyncProcessService.RESULT_UP_TO_DATE -> {
                if (!steamCloudCheckInFlight) {
                    return
                }
                val checkedAtMs = data.steamCloudLongOrNull(
                    SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS
                ) ?: System.currentTimeMillis()
                steamCloudCheckInFlight = false
                lastSteamCloudCheckAtMs = checkedAtMs
                uiState = uiState.copy(
                    steamCloudIndicator = SteamCloudIndicatorUi(
                        visible = true,
                        state = SteamCloudIndicatorState.UP_TO_DATE,
                        lastCheckedAtMs = checkedAtMs,
                    )
                )
            }

            SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED,
            SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED,
            SteamCloudSyncProcessService.RESULT_CLOUD_OVERRIDE_COMPLETED -> {
                if (!steamCloudCheckInFlight && !steamCloudSyncInFlight) {
                    return
                }
                val completedAtMs = data.steamCloudLongOrNull(
                    SteamCloudSyncProcessService.EXTRA_COMPLETED_AT_MS
                ) ?: System.currentTimeMillis()
                completeSteamCloudSync(completedAtMs)
            }

            SteamCloudSyncProcessService.RESULT_FAILURE,
            SteamCloudSyncProcessService.RESULT_CANCELLED -> {
                if (!steamCloudCheckInFlight && !steamCloudSyncInFlight) {
                    return
                }
                handleSteamCloudServiceFailure(
                    appContext = appContext,
                    data = data,
                    checkSessionId = steamCloudCheckSessionId.takeIf { steamCloudCheckInFlight },
                    syncSessionId = steamCloudSyncSessionId.takeIf {
                        steamCloudSyncInFlight && !steamCloudSyncCancelRequested
                    },
                    userInitiated = false,
                    isCancellation = resultCode == SteamCloudSyncProcessService.RESULT_CANCELLED,
                )
            }
        }
    }

    private fun handleSteamCloudServiceFailure(
        appContext: android.content.Context,
        data: Bundle,
        checkSessionId: Long?,
        syncSessionId: Long?,
        userInitiated: Boolean,
        isCancellation: Boolean,
    ) {
        val checkCurrent = checkSessionId?.let { isSteamCloudCheckSessionCurrent(it) } == true
        val syncCurrent = syncSessionId?.let { isSteamCloudSyncSessionCurrent(it) } == true
        if (!checkCurrent && !syncCurrent) {
            return
        }
        val failedAtMs = data.steamCloudLongOrNull(SteamCloudSyncProcessService.EXTRA_CHECKED_AT_MS)
            ?: System.currentTimeMillis()
        val summary = data.getString(SteamCloudSyncProcessService.EXTRA_ERROR_SUMMARY)
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(
                if (isCancellation) {
                    R.string.main_steam_cloud_sync_cancelled_summary
                } else {
                    R.string.main_steam_cloud_bar_summary_failed
                }
            )
        steamCloudCheckInFlight = false
        steamCloudSyncInFlight = false
        steamCloudSyncCancelRequested = false
        lastSteamCloudCheckAtMs = failedAtMs
        publishSteamCloudIndicatorFailure(summary, failedAtMs)
        if (userInitiated && !isCancellation) {
            _effects.tryEmit(
                Effect.ShowSnackbar(
                    message = UiText.StringResource(
                        if (syncCurrent || syncSessionId != null) {
                            R.string.main_steam_cloud_override_failed
                        } else {
                            R.string.main_steam_cloud_indicator_check_failed
                        },
                        summary
                    ),
                    duration = LauncherTransientNoticeDuration.LONG,
                )
            )
        }
    }

    private fun completeSteamCloudSync(completedAtMs: Long) {
        steamCloudCheckInFlight = false
        steamCloudSyncInFlight = false
        steamCloudSyncCancelRequested = false
        lastSteamCloudCheckAtMs = completedAtMs
        uiState = uiState.copy(
            steamCloudIndicator = SteamCloudIndicatorUi(
                visible = true,
                state = SteamCloudIndicatorState.UP_TO_DATE,
                lastCheckedAtMs = completedAtMs,
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun Bundle.steamCloudUploadPlanOrNull(): SteamCloudUploadPlan? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializable(
                SteamCloudSyncProcessService.EXTRA_PLAN,
                SteamCloudUploadPlan::class.java
            )
        } else {
            getSerializable(SteamCloudSyncProcessService.EXTRA_PLAN) as? SteamCloudUploadPlan
        }
    }

    private fun Bundle.steamCloudSyncDirectionOrNull(key: String): SteamCloudSyncDirection? {
        return getString(key)?.let { value ->
            runCatching { SteamCloudSyncDirection.valueOf(value) }.getOrNull()
        }
    }

    private fun Bundle.steamCloudLongOrNull(key: String): Long? {
        return if (containsKey(key)) {
            getLong(key)
        } else {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.steamCloudIntOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        return when (val value = get(key)) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
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

    private fun isSteamCloudSaveModeEnabled(host: Activity): Boolean {
        return LauncherPreferences.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD
    }

    private fun clearSteamCloudIndicatorState() {
        steamCloudCheckInFlight = false
        steamCloudSyncInFlight = false
        steamCloudSyncCancelRequested = false
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

    private fun beginSteamCloudSync(): Long {
        steamCloudSyncCancelRequested = false
        steamCloudSyncInFlight = true
        return ++steamCloudSyncSessionId
    }

    private fun isSteamCloudSyncSessionCurrent(syncSessionId: Long): Boolean {
        return steamCloudSyncInFlight &&
            !steamCloudSyncCancelRequested &&
            steamCloudSyncSessionId == syncSessionId
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
        return ModManager.isRamSaverEnabled(host)
    }

    private fun showRamSaverResidencyLaunchDialog(
        host: Activity,
        launchMode: String,
        backBehavior: BackBehavior,
        manualDismissBootOverlay: Boolean,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
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
                    forceRuntimeCrash = forceRuntimeCrash,
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

    private fun calculateEnabledOptionalModTotalBytes(optionalMods: List<ModItemUi>): Long {
        return optionalMods.asSequence()
            .filter { mod -> mod.enabled && mod.installed && !mod.required }
            .map { mod -> File(mod.storagePath) }
            .filter { file -> file.isFile }
            .sumOf { file -> file.length().coerceAtLeast(0L) }
    }

    private fun showEnabledModSizeLaunchDialog(
        host: Activity,
        launchMode: String,
        forceJvmCrash: Boolean,
        forceRuntimeCrash: Boolean,
        totalBytes: Long,
    ) {
        uiState = uiState.copy(
            pendingEnabledModSizeLaunchWarning = PendingEnabledModSizeLaunchWarning(
                totalBytes = totalBytes,
                launchMode = launchMode,
                forceJvmCrash = forceJvmCrash,
                forceRuntimeCrash = forceRuntimeCrash,
            )
        )
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

    private fun showExpectedBackExitNotice() {
        uiState = uiState.copy(expectedBackExitNoticeVisible = true)
    }

    private fun resolveModDisplayName(mod: ModItemUi): String {
        return io.stamethyst.ui.main.resolveModDisplayName(mod)
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
            hasFloatingTools = dependencyAvailability.hasFloatingTools,
            hasRamSaver = dependencyAvailability.hasRamSaver,
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
        hasFloatingTools: Boolean,
        hasRamSaver: Boolean,
        storageIssue: StorageIssueUi?
    ) {
        val snapshot = modManagementController.snapshot()
        val currentBusy = uiState.busy
        val currentBusyOperation = uiState.busyOperation
        val currentBusyMessage = uiState.busyMessage
        val currentBusyProgressPercent = uiState.busyProgressPercent
        val currentSteamCloudIndicator = resolveSteamCloudIndicatorAvailability(host)
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
                hasRuntimeCompat = hasRuntimeCompat,
                hasFloatingTools = hasFloatingTools,
                hasRamSaver = hasRamSaver
            ),
            optionalMods = snapshot.optionalMods,
            storageIssue = storageIssue,
            controlsEnabled = resolveControlsEnabled(currentBusy, currentBusyOperation, storageIssue != null),
            gameProcessRunning = gameProcessRunning,
            launchInFlight = launchInFlight,
            showModFileName = false,
            modSuggestions = currentModSuggestions,
            readModSuggestionKeys = currentReadModSuggestionKeys,
            pendingLaunchUnreadSuggestionModNames = pendingLaunchUnreadSuggestionModNames,
            modLaunchProfiles = snapshot.modLaunchProfiles,
            activeModLaunchProfileId = snapshot.activeModLaunchProfileId,
            modFolders = snapshot.modFolders,
            folderAssignments = snapshot.folderAssignments,
            folderCollapsed = snapshot.folderCollapsed,
            unassignedCollapsed = snapshot.unassignedCollapsed,
            dependencyFolderCollapsed = snapshot.dependencyFolderCollapsed,
            dragLocked = snapshot.dragLocked,
            unassignedFolderName = snapshot.unassignedFolderName,
            unassignedFolderOrder = snapshot.unassignedFolderOrder,
            favoriteModKeys = snapshot.favoriteModKeys,
            modAssociationState = snapshot.modAssociationState,
            showModFileNameRemovalNotice = false,
            steamCloudIndicator = currentSteamCloudIndicator,
        )
    }

    private fun maybeStartStoredModNameMigration(host: Activity) {
        if (modNameMigrationInFlight ||
            modNameMigrationFailureSuppressed ||
            uiState.busy ||
            uiState.storageIssue != null
        ) {
            return
        }
        val spaceCheck = runCatching {
            ModManifestNameMigration.evaluateStoredNameMigrationSpace(host)
        }.getOrNull() ?: return
        if (!spaceCheck.hasPendingMigration) {
            return
        }
        if (!spaceCheck.hasEnoughSpace) {
            showModNameMigrationStorageInsufficientNotice(host, spaceCheck)
            return
        }

        modNameMigrationInFlight = true
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_mod_name_migration_busy),
            operation = UiBusyOperation.MOD_NAME_MIGRATION,
            progressPercent = 0
        )
        modNameMigrationExecutor.execute {
            val outcome = runCatching {
                ModManifestNameMigration.migrateStoredNamesIfNeeded(
                    context = host,
                    requireSufficientSpace = true,
                    onProgress = { progress ->
                        host.runOnUiThread {
                            if (!modNameMigrationInFlight || host.isFinishing || host.isDestroyed) {
                                return@runOnUiThread
                            }
                            setBusy(
                                busy = true,
                                message = buildModNameMigrationProgressText(progress),
                                operation = UiBusyOperation.MOD_NAME_MIGRATION,
                                progressPercent = progress.progressPercent
                            )
                        }
                    }
                )
            }
            host.runOnUiThread {
                modNameMigrationInFlight = false
                setBusy(false, null)
                if (host.isFinishing || host.isDestroyed) {
                    return@runOnUiThread
                }
                outcome.onSuccess { result ->
                    modNameMigrationInsufficientNoticeShown = false
                    if (result.failedCount > 0) {
                        modNameMigrationFailureSuppressed = true
                        pendingFailedModNameMigrationResult = result
                        _effects.tryEmit(
                            Effect.ShowModNameMigrationFailureDialog(
                                failedMods = result.failedItems.map { failure ->
                                    FailedModNameMigrationUi(
                                        displayName = failure.displayName,
                                        storagePath = failure.storagePath,
                                        reason = failure.reason
                                    )
                                }
                            )
                        )
                    } else {
                        pendingFailedModNameMigrationResult = null
                    }
                    refresh(host)
                    if (result.failedCount == 0 && result.appliedCount > 0) {
                        _effects.tryEmit(
                            Effect.ShowSnackbar(
                                UiText.StringResource(
                                    R.string.main_mod_name_migration_done,
                                    result.appliedCount
                                )
                            )
                        )
                    }
                }.onFailure { error ->
                    if (error is ModManifestNameMigrationStorageException) {
                        showModNameMigrationStorageInsufficientNotice(host, error.spaceCheck)
                    } else {
                        modNameMigrationFailureSuppressed = true
                        pendingFailedModNameMigrationResult = null
                        _effects.tryEmit(
                            Effect.ShowSnackbar(
                                message = UiText.StringResource(
                                    R.string.main_mod_rename_failed,
                                    error.message ?: host.getString(R.string.feedback_unknown_error)
                                ),
                                duration = LauncherTransientNoticeDuration.LONG
                            )
                        )
                    }
                }
            }
        }
    }

    private fun buildModNameMigrationProgressText(progress: ModManifestNameMigrationProgress): UiText {
        return UiText.StringResource(
            R.string.main_mod_name_migration_progress,
            progress.currentIndex,
            progress.totalCount,
            progress.currentModName
        )
    }

    private fun showModNameMigrationStorageInsufficientNotice(
        host: Activity,
        spaceCheck: ModManifestNameMigrationSpaceCheck
    ) {
        if (modNameMigrationInsufficientNoticeShown) {
            return
        }
        modNameMigrationInsufficientNoticeShown = true
        _effects.tryEmit(
            Effect.ShowDialog(
                title = UiText.StringResource(R.string.main_mod_name_migration_storage_insufficient_title),
                message = UiText.StringResource(
                    R.string.main_mod_name_migration_storage_insufficient_message,
                    formatMigrationByteSize(spaceCheck.requiredExtraBytes),
                    formatMigrationByteSize(spaceCheck.availableBytes)
                )
            )
        )
    }

    private fun formatMigrationByteSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun resolveSteamCloudIndicatorAvailability(host: Activity): SteamCloudIndicatorUi {
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (!isSteamCloudSaveModeEnabled(host) || authMaterial == null) {
            return SteamCloudIndicatorUi()
        }
        return uiState.steamCloudIndicator.copy(visible = true)
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

    private fun clearLaunchInFlightState(clearPendingEnabledModSizeWarning: Boolean = true) {
        launchInFlight = false
        // Drop the autoplay latch too — if we're aborting a launch, the next user-triggered
        // launch shouldn't silently inherit autoplay from the cancelled debug intent.
        pendingAutoplay = false
        pendingAutoplaySaveMode = AutoplaySaveMode.DEFAULT
        pendingAutoplayMode = AutoplayMode.DEFAULT
        pendingAutoplaySingleRoomSpecPath = ""
        pendingAutoplayChoiceDelayMs = 0L
        pendingCardObtainEffectOwnershipCompatEnabled = true
        if (uiState.launchInFlight ||
            (clearPendingEnabledModSizeWarning && uiState.pendingEnabledModSizeLaunchWarning != null)
        ) {
            uiState = uiState.copy(
                launchInFlight = false,
                pendingEnabledModSizeLaunchWarning = if (clearPendingEnabledModSizeWarning) {
                    null
                } else {
                    uiState.pendingEnabledModSizeLaunchWarning
                },
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

            ModManager.MOD_ID_AMETHYST_RUNTIME_COMPAT ->
                RuntimePaths.importedAmethystRuntimeCompatJar(host).exists() ||
                    hasBundledAsset(host, "components/mods/AmethystRuntimeCompat.jar")

            ModManager.MOD_ID_AMETHYST_FLOATING_TOOLS ->
                RuntimePaths.importedAmethystFloatingToolsJar(host).exists() ||
                    hasBundledAsset(host, "components/mods/AmethystFloatingTools.jar")

            ModManager.MOD_ID_RAM_SAVER ->
                RuntimePaths.importedRamSaverJar(host).exists() || hasBundledAsset(host, "components/mods/RamSaver.jar")

            else -> true
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return RuntimeResourceProvider(host).exists(assetPath)
    }

    companion object {
        private const val LOGCAT_TAG = "STS-MainScreenVM"
        private const val PASSIVE_REFRESH_DEBOUNCE_MS = 750L
        private const val STEAM_CLOUD_STATUS_REFRESH_INTERVAL_MS = 60_000L
        private const val MTS_LAUNCH_PREPARATION_BUSY_OVERLAY_DELAY_MS = 500L
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val ENABLED_MOD_SIZE_WARNING_THRESHOLD_BYTES = 1024L * BYTES_PER_MIB
        private val DEFAULT_UNASSIGNED_FOLDER_NAME: String = if (Locale.getDefault().language.startsWith("zh")) {
            "未分类"
        } else {
            "Uncategorized"
        }
    }

    override fun onCleared() {
        unregisterSteamCloudProcessEventReceiver()
        importedStsJarValidationExecutor.shutdownNow()
        launchExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        suggestionExecutor.shutdownNow()
        workshopUpdateExecutor.shutdownNow()
        modNameMigrationExecutor.shutdownNow()
        mtsComponentUpdateExecutor.shutdownNow()
        modManagementController.shutdown()
        super.onCleared()
    }
}

private fun WorkshopDownloadTaskStatus.shouldShowLightweightWorkshopTask(): Boolean = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling,
    WorkshopDownloadTaskStatus.Paused,
    WorkshopDownloadTaskStatus.Failed -> true
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Cancelled -> false
}

private fun WorkshopDownloadTaskStatus.toWorkshopModStateOrNull(): WorkshopModState? = when (this) {
    WorkshopDownloadTaskStatus.Queued,
    WorkshopDownloadTaskStatus.Resolving,
    WorkshopDownloadTaskStatus.Downloading,
    WorkshopDownloadTaskStatus.Pausing,
    WorkshopDownloadTaskStatus.Cancelling -> WorkshopModState.Downloading
    WorkshopDownloadTaskStatus.Paused -> WorkshopModState.DownloadPaused
    WorkshopDownloadTaskStatus.Failed -> WorkshopModState.DownloadFailed
    WorkshopDownloadTaskStatus.Completed,
    WorkshopDownloadTaskStatus.Cancelled -> null
}

private fun WorkshopDownloadTaskStatus.defaultWorkshopStatusText(): String = when (this) {
    WorkshopDownloadTaskStatus.Queued -> "等待下载"
    WorkshopDownloadTaskStatus.Resolving -> "正在解析下载内容"
    WorkshopDownloadTaskStatus.Downloading -> "正在下载"
    WorkshopDownloadTaskStatus.Pausing -> "正在暂停"
    WorkshopDownloadTaskStatus.Cancelling -> "正在取消"
    WorkshopDownloadTaskStatus.Paused -> "下载已暂停，可继续"
    WorkshopDownloadTaskStatus.Failed -> "下载失败"
    WorkshopDownloadTaskStatus.Completed -> "下载完成"
    WorkshopDownloadTaskStatus.Cancelled -> "下载已取消"
}

private fun ModItemUi.isStandaloneWorkshopTaskCard(workshop: WorkshopModUi): Boolean {
    return !installed && storagePath == "workshop:${workshop.appId}:${workshop.publishedFileId}"
}

private fun WorkshopDownloadTaskUi.toStandaloneWorkshopModItem(): ModItemUi {
    val summary = details.summary
    val state = status.toWorkshopModStateOrNull() ?: WorkshopModState.DownloadFailed
    return ModItemUi(
        modId = "workshop:${publishedFileId}",
        manifestModId = "workshop:${publishedFileId}",
        storagePath = "workshop:${summary.appId}:${publishedFileId}",
        name = title.ifBlank { summary.title.ifBlank { publishedFileId.toString() } },
        version = summary.updatedAtMillis.toString(),
        fileSizeBytes = fileSizeBytes.takeIf { it > 0L } ?: totalBytes ?: summary.fileSizeBytes,
        description = description.ifBlank { summary.description },
        dependencies = emptyList(),
        required = false,
        installed = false,
        enabled = false,
        explicitPriority = null,
        effectivePriority = null,
        workshop = WorkshopModUi(
            appId = summary.appId,
            publishedFileId = publishedFileId,
            state = state,
            statusText = message.ifBlank { status.defaultWorkshopStatusText() },
            downloadProgressPercent = progressPercent,
        ),
    )
}
