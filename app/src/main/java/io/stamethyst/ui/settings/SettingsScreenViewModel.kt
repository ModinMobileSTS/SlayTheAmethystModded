package io.stamethyst.ui.settings

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BuildConfig
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesConfigFile
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.MobileGluesPreset
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.launch.MtsClasspathWarmupCoordinator
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.LauncherUpdateUiReducer
import io.stamethyst.backend.update.LauncherUpdateVersioning
import io.stamethyst.backend.update.UpdateCheckExecutionResult
import io.stamethyst.backend.update.UpdateDownloadResolution
import io.stamethyst.backend.update.UpdateReleaseInfo
import io.stamethyst.backend.update.UpdateUiMessage
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherThemeController
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.StsExternalStorageAccess
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.VupShionPatchedDialog
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Stable
class SettingsScreenViewModel : ViewModel() {
    sealed interface Effect {
        data object OpenImportJarPicker : Effect
        data object OpenImportModsPicker : Effect
        data object OpenImportSavesPicker : Effect
        data class OpenExportModsPicker(val fileName: String) : Effect
        data class OpenExportSavesPicker(val fileName: String) : Effect
        data class OpenExportLogsPicker(val fileName: String) : Effect
        data class ShareJvmLogsBundle(val payload: JvmLogsSharePayload) : Effect
        data object OpenCompatibility : Effect
        data object OpenMobileGluesSettings : Effect
        data object OpenFeedback : Effect
    }

    data class UpdatePromptState(
        val currentVersion: String,
        val latestVersion: String,
        val publishedAtText: String,
        val downloadSourceDisplayName: String,
        val notesText: String,
        val downloadUrl: String,
    )

    data class RendererBackendOptionState(
        val backend: RendererBackend,
        val available: Boolean,
        val reasonText: String? = null
    )

    data class UiState(
        val busy: Boolean = false,
        val busyOperation: UiBusyOperation = UiBusyOperation.NONE,
        val busyMessage: String? = null,
        val playerName: String = LauncherPreferences.DEFAULT_PLAYER_NAME,
        val selectedRenderScale: Float = RenderScaleService.DEFAULT_RENDER_SCALE,
        val selectedTargetFps: Int = LauncherPreferences.DEFAULT_TARGET_FPS,
        val renderSurfaceBackend: RenderSurfaceBackend = LauncherPreferences.DEFAULT_RENDER_SURFACE_BACKEND,
        val rendererSelectionMode: RendererSelectionMode =
            LauncherPreferences.DEFAULT_RENDERER_SELECTION_MODE,
        val manualRendererBackend: RendererBackend =
            LauncherPreferences.DEFAULT_MANUAL_RENDERER_BACKEND,
        val mobileGluesAnglePolicy: MobileGluesAnglePolicy =
            LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_POLICY,
        val mobileGluesNoErrorPolicy: MobileGluesNoErrorPolicy =
            LauncherPreferences.DEFAULT_MOBILEGLUES_NO_ERROR_POLICY,
        val mobileGluesMultidrawMode: MobileGluesMultidrawMode =
            LauncherPreferences.DEFAULT_MOBILEGLUES_MULTIDRAW_MODE,
        val mobileGluesExtComputeShaderEnabled: Boolean =
            LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
        val mobileGluesExtTimerQueryEnabled: Boolean =
            LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
        val mobileGluesExtDirectStateAccessEnabled: Boolean =
            LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
        val mobileGluesGlslCacheSizePreset: MobileGluesGlslCacheSizePreset =
            LauncherPreferences.DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET,
        val mobileGluesAngleDepthClearFixMode: MobileGluesAngleDepthClearFixMode =
            LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
        val mobileGluesCustomGlVersion: MobileGluesCustomGlVersion =
            LauncherPreferences.DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION,
        val mobileGluesFsr1QualityPreset: MobileGluesFsr1QualityPreset =
            LauncherPreferences.DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET,
        val autoSelectedRendererBackend: RendererBackend =
            LauncherPreferences.DEFAULT_MANUAL_RENDERER_BACKEND,
        val effectiveRendererBackend: RendererBackend =
            LauncherPreferences.DEFAULT_MANUAL_RENDERER_BACKEND,
        val effectiveRenderSurfaceBackend: RenderSurfaceBackend =
            LauncherPreferences.DEFAULT_RENDER_SURFACE_BACKEND,
        val rendererBackendOptions: List<RendererBackendOptionState> = RendererBackend.entries.map {
            RendererBackendOptionState(backend = it, available = true)
        },
        val rendererFallbackText: String? = null,
        val surfaceBackendForcedByRenderer: Boolean = false,
        val themeMode: LauncherThemeMode = LauncherPreferences.DEFAULT_THEME_MODE,
        val selectedJvmHeapMaxMb: Int = LauncherPreferences.DEFAULT_JVM_HEAP_MAX_MB,
        val compressedPointersEnabled: Boolean = LauncherPreferences.DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED,
        val stringDeduplicationEnabled: Boolean =
            LauncherPreferences.DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED,
        val jvmHeapMinMb: Int = LauncherPreferences.MIN_JVM_HEAP_MAX_MB,
        val jvmHeapMaxMb: Int = LauncherPreferences.MAX_JVM_HEAP_MAX_MB,
        val jvmHeapStepMb: Int = LauncherPreferences.JVM_HEAP_STEP_MB,
        val backBehavior: BackBehavior = LauncherPreferences.DEFAULT_BACK_BEHAVIOR,
        val manualDismissBootOverlay: Boolean = LauncherPreferences.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY,
        val showFloatingMouseWindow: Boolean = LauncherPreferences.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW,
        val touchMouseDoubleTapLockEnabled: Boolean =
            LauncherPreferences.DEFAULT_TOUCH_MOUSE_DOUBLE_TAP_LOCK_ENABLED,
        val longPressMouseShowsKeyboard: Boolean = LauncherPreferences.DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD,
        val autoSwitchLeftAfterRightClick: Boolean = LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val mobileHudEnabled: Boolean = LauncherPreferences.DEFAULT_MOBILE_HUD_ENABLED,
        val compendiumUpgradeTouchFixEnabled: Boolean =
            LauncherPreferences.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED,
        val avoidDisplayCutout: Boolean = LauncherPreferences.DEFAULT_AVOID_DISPLAY_CUTOUT,
        val cropScreenBottom: Boolean = LauncherPreferences.DEFAULT_CROP_SCREEN_BOTTOM,
        val showGamePerformanceOverlay: Boolean = LauncherPreferences.DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY,
        val sustainedPerformanceModeEnabled: Boolean =
            LauncherPreferences.DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED,
        val systemGameModeDisplayName: String = "不可用",
        val systemGameModeDescription: String = "当前还没有读取系统 Game Mode 状态。",
        val lwjglDebugEnabled: Boolean = LauncherPreferences.DEFAULT_LWJGL_DEBUG,
        val preloadAllJreLibrariesEnabled: Boolean =
            LauncherPreferences.DEFAULT_PRELOAD_ALL_JRE_LIBRARIES,
        val logcatCaptureEnabled: Boolean = LauncherPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED,
        val jvmLogcatMirrorEnabled: Boolean = LauncherPreferences.DEFAULT_JVM_LOGCAT_MIRROR_ENABLED,
        val gdxPadCursorDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GDX_PAD_CURSOR_DEBUG,
        val glBridgeSwapHeartbeatDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG,
        val touchscreenEnabled: Boolean = GameplaySettingsService.DEFAULT_TOUCHSCREEN_ENABLED,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Int> = LauncherPreferences.TARGET_FPS_OPTIONS.toList(),
        val autoCheckUpdatesEnabled: Boolean = LauncherPreferences.DEFAULT_AUTO_CHECK_UPDATES_ENABLED,
        val preferredUpdateMirror: UpdateSource = UpdateSource.DEFAULT_PREFERRED_USER_SOURCE,
        val availableUpdateMirrors: List<UpdateSource> = UpdateSource.userSelectableSources(),
        val currentVersionText: String = BuildConfig.VERSION_NAME,
        val updateStatusSummary: String = "",
        val updateCheckInProgress: Boolean = false,
        val updatePromptState: UpdatePromptState? = null,
    )

    private data class CoreDependencyStatus(
        val label: String,
        val available: Boolean,
        val source: String,
        val version: String
    )

    private data class DeviceRuntimeStatus(
        val cpuModel: String,
        val cpuArch: String,
        val availableMemoryBytes: Long,
        val totalMemoryBytes: Long
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    private var startupAutoUpdateCheckRequested = false

    var uiState by mutableStateOf(UiState())
        private set

    val effects = _effects.asSharedFlow()

    fun bind(
        activity: Activity,
        targetFpsOptions: IntArray = LauncherPreferences.TARGET_FPS_OPTIONS
    ) {
        syncThemeMode(activity)
        val options = targetFpsOptions.toList()
        if (uiState.targetFpsOptions != options) {
            uiState = uiState.copy(targetFpsOptions = options)
        }
        syncStoredUpdateState(activity)

        if (uiState.statusText.isBlank()) {
            refreshStatus(activity, clearBusy = false)
        }
    }

    fun startStartupAutoUpdateCheck(host: Activity) {
        if (startupAutoUpdateCheckRequested) {
            return
        }
        startupAutoUpdateCheckRequested = true
        syncStoredUpdateState(host)
        if (!LauncherPreferences.isAutoCheckUpdatesEnabled(host)) {
            return
        }
        runUpdateCheck(host, userInitiated = false)
    }

    fun onAutoCheckUpdatesChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setAutoCheckUpdatesEnabled(host, enabled)
        syncStoredUpdateState(host)
    }

    fun syncThemeMode(host: Activity) {
        uiState = uiState.copy(themeMode = readThemeModeSelection(host))
    }

    fun onThemeModeChanged(host: Activity, themeMode: LauncherThemeMode) {
        saveThemeModeSelection(host, themeMode)
        syncThemeMode(host)
    }

    fun onPreferredUpdateMirrorChanged(host: Activity, source: UpdateSource) {
        if (!source.userSelectable) {
            return
        }
        LauncherPreferences.savePreferredUpdateMirrorId(host, source.id)
        syncStoredUpdateState(host)
    }

    fun onManualCheckUpdates(host: Activity) {
        if (uiState.updateCheckInProgress || uiState.busy) {
            return
        }
        runUpdateCheck(host, userInitiated = true)
    }

    fun dismissUpdatePrompt() {
        if (uiState.updatePromptState != null) {
            uiState = uiState.copy(updatePromptState = null)
        }
    }

    private fun runUpdateCheck(host: Activity, userInitiated: Boolean) {
        if (uiState.updateCheckInProgress) {
            return
        }
        uiState = uiState.copy(updateCheckInProgress = true)
        val preferredSource = UpdateSource.normalizePreferredUserSource(
            LauncherPreferences.readPreferredUpdateMirrorId(host)
        )
        executor.execute {
            val result = LauncherUpdateService.checkForUpdates(
                currentVersion = BuildConfig.VERSION_NAME,
                preferredUserSource = preferredSource
            )
            host.runOnUiThread {
                val toastMessage = when (result) {
                    is UpdateCheckExecutionResult.Success ->
                        handleUpdateCheckSuccess(host, result, userInitiated)
                    is UpdateCheckExecutionResult.Failure ->
                        handleUpdateCheckFailure(host, result, userInitiated)
                }
                if (!toastMessage.isNullOrBlank()) {
                    Toast.makeText(host, toastMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleUpdateCheckSuccess(
        host: Activity,
        result: UpdateCheckExecutionResult.Success,
        userInitiated: Boolean,
    ): String? {
        val decision = LauncherUpdateUiReducer.reduce(result, userInitiated)
        val checkedAtMs = System.currentTimeMillis()
        LauncherPreferences.saveLastUpdateCheckAtMs(host, checkedAtMs)
        LauncherPreferences.saveLastKnownRemoteTag(host, result.release.normalizedVersion)
        LauncherPreferences.saveLastSuccessfulMetadataSourceId(host, result.metadataSource.id)
        LauncherPreferences.saveLastUpdateErrorSummary(host, null)
        if (result.downloadResolution != null) {
            LauncherPreferences.saveLastSuccessfulDownloadSourceId(
                host,
                result.downloadResolution.source.id
            )
        }
        val promptState = if (decision.showPrompt) {
            buildUpdatePromptState(host, result.release, result.downloadResolution)
        } else {
            null
        }
        syncStoredUpdateState(
            host = host,
            updateCheckInProgress = false,
            updatePromptState = promptState
        )
        return when (decision.message) {
            UpdateUiMessage.LATEST -> host.getString(R.string.update_check_result_latest)
            UpdateUiMessage.FAILURE -> host.getString(
                R.string.update_check_result_failed,
                host.getString(R.string.update_status_result_failed)
            )
            null -> null
        }
    }

    private fun handleUpdateCheckFailure(
        host: Activity,
        result: UpdateCheckExecutionResult.Failure,
        userInitiated: Boolean,
    ): String? {
        val decision = LauncherUpdateUiReducer.reduce(result, userInitiated)
        LauncherPreferences.saveLastUpdateCheckAtMs(host, System.currentTimeMillis())
        LauncherPreferences.saveLastUpdateErrorSummary(host, result.errorSummary)
        if (result.release != null) {
            LauncherPreferences.saveLastKnownRemoteTag(host, result.release.normalizedVersion)
        }
        if (result.metadataSource != null) {
            LauncherPreferences.saveLastSuccessfulMetadataSourceId(host, result.metadataSource.id)
        }
        syncStoredUpdateState(
            host = host,
            updateCheckInProgress = false,
            updatePromptState = null
        )
        return when (decision.message) {
            UpdateUiMessage.FAILURE -> {
                host.getString(R.string.update_check_result_failed, result.errorSummary)
            }

            UpdateUiMessage.LATEST -> host.getString(R.string.update_check_result_latest)
            null -> null
        }
    }

    private fun buildUpdatePromptState(
        host: Activity,
        release: UpdateReleaseInfo,
        downloadResolution: UpdateDownloadResolution?,
    ): UpdatePromptState? {
        val resolvedDownload = downloadResolution ?: return null
        return UpdatePromptState(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = release.normalizedVersion,
            publishedAtText = release.publishedAtDisplayText.ifBlank {
                host.getString(R.string.update_unknown_date)
            },
            downloadSourceDisplayName = resolvedDownload.source.displayName,
            notesText = release.notesText.ifBlank {
                host.getString(R.string.update_dialog_notes_empty)
            },
            downloadUrl = resolvedDownload.resolvedUrl
        )
    }

    private fun syncStoredUpdateState(
        host: Activity,
        updateCheckInProgress: Boolean = uiState.updateCheckInProgress,
        updatePromptState: UpdatePromptState? = uiState.updatePromptState,
    ) {
        uiState = uiState.copy(
            autoCheckUpdatesEnabled = LauncherPreferences.isAutoCheckUpdatesEnabled(host),
            preferredUpdateMirror = UpdateSource.normalizePreferredUserSource(
                LauncherPreferences.readPreferredUpdateMirrorId(host)
            ),
            availableUpdateMirrors = UpdateSource.userSelectableSources(),
            currentVersionText = BuildConfig.VERSION_NAME,
            updateStatusSummary = buildUpdateStatusSummary(host),
            updateCheckInProgress = updateCheckInProgress,
            updatePromptState = updatePromptState
        )
    }

    private fun buildUpdateStatusSummary(host: Activity): String {
        val lastCheckedAtMs = LauncherPreferences.readLastUpdateCheckAtMs(host)
        if (lastCheckedAtMs <= 0L) {
            return host.getString(R.string.update_status_not_checked)
        }

        val lines = mutableListOf<String>()
        lines += host.getString(
            R.string.update_status_last_checked,
            formatUpdateCheckTime(lastCheckedAtMs)
        )

        val remoteTag = LauncherPreferences.readLastKnownRemoteTag(host)
        if (!remoteTag.isNullOrBlank()) {
            lines += host.getString(R.string.update_status_remote_version, remoteTag)
        }

        val metadataSource = resolveUpdateSourceDisplayName(
            LauncherPreferences.readLastSuccessfulMetadataSourceId(host)
        )
        if (metadataSource != null) {
            lines += host.getString(R.string.update_status_metadata_source, metadataSource)
        }

        val errorSummary = LauncherPreferences.readLastUpdateErrorSummary(host)
        if (!errorSummary.isNullOrBlank()) {
            lines += host.getString(
                R.string.update_status_result,
                host.getString(R.string.update_status_result_failed)
            )
            lines += errorSummary
            return lines.joinToString("\n")
        }

        val hasUpdate = !remoteTag.isNullOrBlank() &&
            LauncherUpdateVersioning.isRemoteNewer(BuildConfig.VERSION_NAME, remoteTag)
        lines += host.getString(
            R.string.update_status_result,
            if (hasUpdate) {
                host.getString(R.string.update_status_result_available)
            } else {
                host.getString(R.string.update_status_result_latest)
            }
        )

        if (hasUpdate) {
            val downloadSource = resolveUpdateSourceDisplayName(
                LauncherPreferences.readLastSuccessfulDownloadSourceId(host)
            )
            if (downloadSource != null) {
                lines += host.getString(R.string.update_status_download_source, downloadSource)
            }
        }

        return lines.joinToString("\n")
    }

    private fun resolveUpdateSourceDisplayName(sourceId: String?): String? {
        return UpdateSource.fromPersistedValue(sourceId)?.displayName
    }

    private fun formatUpdateCheckTime(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestampMs))
    }

    private fun hasValidImportedStsJar(host: Activity): Boolean {
        return StsJarValidator.isValid(RuntimePaths.importedStsJar(host))
    }

    fun refreshStatus(host: Activity, clearBusy: Boolean = true) {
        executor.execute {
            try {
                val hasJar = hasValidImportedStsJar(host)

                val renderScale = RenderScaleService.readValue(host)
                val playerName = readPlayerNameSelection(host)
                val targetFps = readTargetFpsSelection(host)
                val renderSurfaceBackend = readRenderSurfaceBackendSelection(host)
                val rendererSelectionMode = readRendererSelectionModeSelection(host)
                val manualRendererBackend = readManualRendererBackendSelection(host)
                val mobileGluesSettings = readMobileGluesSettingsSelection(host)
                val rendererDecision = RendererBackendResolver.resolve(
                    context = host,
                    requestedSurfaceBackend = renderSurfaceBackend,
                    selectionMode = rendererSelectionMode,
                    manualBackend = manualRendererBackend
                )
                val rendererBackendOptions = rendererDecision.availableBackends.map { availability ->
                    RendererBackendOptionState(
                        backend = availability.backend,
                        available = availability.available,
                        reasonText = availability.describeUnavailable()
                    )
                }
                val jvmHeapMaxMb = readJvmHeapMaxSelection(host)
                val jvmHeapStartMb = LauncherPreferences.resolveJvmHeapStartMb(jvmHeapMaxMb)
                val compressedPointersEnabled = readJvmCompressedPointersSelection(host)
                val stringDeduplicationEnabled = readJvmStringDeduplicationSelection(host)
                val backBehavior = readBackBehaviorSelection(host)
                val manualDismissBootOverlay = readManualDismissBootOverlaySelection(host)
                val showFloatingMouseWindow = readShowFloatingMouseWindowSelection(host)
                val touchMouseDoubleTapLockEnabled = readTouchMouseDoubleTapLockSelection(host)
                val longPressMouseShowsKeyboard = readLongPressMouseShowsKeyboardSelection(host)
                val autoSwitchLeftAfterRightClick = readAutoSwitchLeftAfterRightClickSelection(host)
                val showModFileName = readShowModFileNameSelection(host)
                val mobileHudEnabled = readMobileHudEnabledSelection(host)
                val compendiumUpgradeTouchFixEnabled =
                    readCompendiumUpgradeTouchFixSelection(host)
                val avoidDisplayCutout = readDisplayCutoutAvoidanceSelection(host)
                val cropScreenBottom = readScreenBottomCropSelection(host)
                val showGamePerformanceOverlay = readGamePerformanceOverlaySelection(host)
                val sustainedPerformanceModeEnabled =
                    readSustainedPerformanceModeSelection(host)
                val systemGameMode = AndroidGameModeSupport.readCurrentMode(host)
                val lwjglDebugEnabled = readLwjglDebugSelection(host)
                val preloadAllJreLibrariesEnabled = readPreloadAllJreLibrariesSelection(host)
                val logcatCaptureEnabled = readLogcatCaptureSelection(host)
                val jvmLogcatMirrorEnabled = readJvmLogcatMirrorSelection(host)
                val gdxPadCursorDebugEnabled = readGdxPadCursorDebugSelection(host)
                val glBridgeSwapHeartbeatDebugEnabled = readGlBridgeSwapHeartbeatDebugSelection(host)
                val touchscreenEnabled = readTouchscreenEnabledSelection(host)
                val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(host)
                val globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(host)
                val modManifestRootCompatEnabled = CompatibilitySettings.isModManifestRootCompatEnabled(host)
                val runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(host)
                val forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(host)

                val mods = ModManager.listInstalledMods(host)
                var optionalTotal = 0
                var optionalEnabled = 0
                mods.forEach { mod ->
                    if (!mod.required) {
                        optionalTotal++
                        if (mod.enabled) {
                            optionalEnabled++
                        }
                    }
                }

                val coreMtsStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "ModTheSpire",
                    importedJar = RuntimePaths.importedMtsJar(host),
                    bundledAssetPath = "components/mods/ModTheSpire.jar"
                )
                val coreBaseModStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "BaseMod",
                    importedJar = RuntimePaths.importedBaseModJar(host),
                    bundledAssetPath = "components/mods/BaseMod.jar"
                )
                val coreStsLibStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "StSLib",
                    importedJar = RuntimePaths.importedStsLibJar(host),
                    bundledAssetPath = "components/mods/StSLib.jar"
                )
                val deviceRuntimeStatus = collectDeviceRuntimeStatus(host)

                val status = buildString {
                    append("核心依赖状态")
                    append("\ndesktop-1.0.jar: ").append(if (hasJar) "可用" else "缺失")
                    append('\n').append(formatCoreDependencyLine(coreMtsStatus))
                    append('\n').append(formatCoreDependencyLine(coreBaseModStatus))
                    append('\n').append(formatCoreDependencyLine(coreStsLibStatus))
                    append("\nOptional mods enabled: $optionalEnabled/$optionalTotal")

                    append("\n\n设备信息")
                    append("\nCPU 型号: ").append(deviceRuntimeStatus.cpuModel)
                    append("\nCPU 架构: ").append(deviceRuntimeStatus.cpuArch)
                    append("\n可用内存: ")
                        .append(formatBytes(deviceRuntimeStatus.availableMemoryBytes))
                    append(" / 总内存: ")
                        .append(formatBytes(deviceRuntimeStatus.totalMemoryBytes))

                    append("\n\n启动与兼容设置")
                    append("\nPlayer name: ").append(playerName)
                    append("\nRender scale: ${RenderScaleService.format(renderScale)} (0.50-1.00)")
                    append("\nTarget FPS: $targetFps")
                    append("\nRenderer selection mode: ")
                        .append(rendererSelectionMode.displayName())
                    append("\nRenderer auto select: ")
                        .append(rendererDecision.autoSelectionSummary())
                    append("\nRenderer effective: ")
                        .append(rendererDecision.effectiveRendererSummary())
                    rendererDecision.fallbackSummary()?.let {
                        append("\nRenderer fallback: ").append(it)
                    }
                    append("\nMobileGlues ANGLE policy: ")
                        .append(mobileGluesSettings.anglePolicy.displayName)
                    append("\nMobileGlues multidraw: ")
                        .append(mobileGluesSettings.multidrawMode.displayName)
                    append("\nMobileGlues no-error: ")
                        .append(mobileGluesSettings.noErrorPolicy.displayName)
                    append("\nMobileGlues custom GL version: ")
                        .append(mobileGluesSettings.customGlVersion.displayName)
                    append("\nMobileGlues FSR1: ")
                        .append(mobileGluesSettings.fsr1QualityPreset.displayName)
                    append("\nRender surface requested: ")
                        .append(renderSurfaceBackend.displayName(host))
                    append("\nRender surface effective: ")
                        .append(rendererDecision.effectiveSurfaceBackend.displayName(host))
                    if (rendererDecision.surfaceBackendForced) {
                        append(" (forced by ")
                            .append(rendererDecision.effectiveBackend.displayName)
                            .append(")")
                    }
                    append("\nJVM heap start/max: ${jvmHeapStartMb}/${jvmHeapMaxMb} MB")
                    append("\nCompressed Oops / Class Pointers: ")
                        .append(if (compressedPointersEnabled) "ON" else "OFF")
                    append("\nString Deduplication: ")
                        .append(if (stringDeduplicationEnabled) "ON" else "OFF")
                    append("\nBack behavior: ${backBehavior.displayName()}")
                    append("\nTouchscreen Enabled: ").append(if (touchscreenEnabled) "ON" else "OFF")
                    append("\nMobile HUD Enabled: ").append(if (mobileHudEnabled) "ON" else "OFF")
                    append("\nCompendium upgrade touch fix: ")
                        .append(if (compendiumUpgradeTouchFixEnabled) "ON" else "OFF")
                    append("\nAvoid display cutout: ")
                        .append(if (avoidDisplayCutout) "ON" else "OFF")
                    append("\nCrop screen bottom: ")
                        .append(if (cropScreenBottom) "ON" else "OFF")
                    append("\nPerformance overlay: ")
                        .append(if (showGamePerformanceOverlay) "ON" else "OFF")
                    append("\nSustained performance mode: ")
                        .append(if (sustainedPerformanceModeEnabled) "ON" else "OFF")
                    append("\nSystem Game Mode: ")
                        .append(systemGameMode.displayName)
                    append("\nSystem Game Mode detail: ")
                        .append(systemGameMode.description)
                    append("\nManual dismiss boot overlay: ")
                        .append(if (manualDismissBootOverlay) "ON" else "OFF")
                    append("\n")
                        .append(
                            host.getString(
                                R.string.status_floating_touch_mouse_window_format,
                                if (showFloatingMouseWindow) "ON" else "OFF"
                            )
                        )
                    append("\n")
                        .append(
                            host.getString(
                                R.string.status_touch_mouse_double_tap_lock_format,
                                if (touchMouseDoubleTapLockEnabled) "ON" else "OFF"
                            )
                        )
                    append("\n")
                        .append(
                            host.getString(
                                R.string.status_touch_mouse_long_press_keyboard_format,
                                if (longPressMouseShowsKeyboard) "ON" else "OFF"
                            )
                        )
                    append("\nRight click auto switch to left: ")
                        .append(if (autoSwitchLeftAfterRightClick) "ON" else "OFF")
                    append("\nMod card name from file: ").append(if (showModFileName) "ON" else "OFF")
                    append("\nLWJGL Debug: ").append(if (lwjglDebugEnabled) "ON" else "OFF")
                    append("\nPreload all JRE libraries: ")
                        .append(if (preloadAllJreLibrariesEnabled) "ON" else "OFF")
                    append("\nLogcat diagnostics capture: ")
                        .append(if (logcatCaptureEnabled) "ON" else "OFF")
                    append("\nJVM logcat mirror: ").append(if (jvmLogcatMirrorEnabled) "ON" else "OFF")
                    append("\nGDX pad cursor debug log: ")
                        .append(if (gdxPadCursorDebugEnabled) "ON" else "OFF")
                    append("\nGLBridge swap heartbeat log: ")
                        .append(if (glBridgeSwapHeartbeatDebugEnabled) "ON" else "OFF")
                    append("\nVirtual FBO PoC: ").append(if (virtualFboPocEnabled) "ON" else "OFF")
                    append("\nGlobal atlas filter compat: ")
                        .append(if (globalAtlasFilterCompatEnabled) "ON" else "OFF")
                    append("\nMod manifest root compat: ")
                        .append(if (modManifestRootCompatEnabled) "ON" else "OFF")
                    append("\nRuntime texture compat: ")
                        .append(if (runtimeTextureCompatEnabled) "ON" else "OFF")
                    append("\nForce linear mipmap filter: ")
                        .append(if (forceLinearMipmapFilterEnabled) "ON" else "OFF")
                    append("\nBundled JRE path: app/src/main/assets/components/jre")
                }

                host.runOnUiThread {
                    uiState = uiState.copy(
                        busy = if (clearBusy) false else uiState.busy,
                        busyOperation = if (clearBusy) UiBusyOperation.NONE else uiState.busyOperation,
                        busyMessage = if (clearBusy) null else uiState.busyMessage,
                        playerName = playerName,
                        selectedRenderScale = renderScale,
                        selectedTargetFps = targetFps,
                        renderSurfaceBackend = renderSurfaceBackend,
                        rendererSelectionMode = rendererSelectionMode,
                        manualRendererBackend = manualRendererBackend,
                        mobileGluesAnglePolicy = mobileGluesSettings.anglePolicy,
                        mobileGluesNoErrorPolicy = mobileGluesSettings.noErrorPolicy,
                        mobileGluesMultidrawMode = mobileGluesSettings.multidrawMode,
                        mobileGluesExtComputeShaderEnabled = mobileGluesSettings.extComputeShaderEnabled,
                        mobileGluesExtTimerQueryEnabled = mobileGluesSettings.extTimerQueryEnabled,
                        mobileGluesExtDirectStateAccessEnabled =
                            mobileGluesSettings.extDirectStateAccessEnabled,
                        mobileGluesGlslCacheSizePreset = mobileGluesSettings.glslCacheSizePreset,
                        mobileGluesAngleDepthClearFixMode =
                            mobileGluesSettings.angleDepthClearFixMode,
                        mobileGluesCustomGlVersion = mobileGluesSettings.customGlVersion,
                        mobileGluesFsr1QualityPreset = mobileGluesSettings.fsr1QualityPreset,
                        autoSelectedRendererBackend = rendererDecision.automaticBackend,
                        effectiveRendererBackend = rendererDecision.effectiveBackend,
                        effectiveRenderSurfaceBackend = rendererDecision.effectiveSurfaceBackend,
                        rendererBackendOptions = rendererBackendOptions,
                        rendererFallbackText = rendererDecision.fallbackSummary(),
                        surfaceBackendForcedByRenderer = rendererDecision.surfaceBackendForced,
                        selectedJvmHeapMaxMb = jvmHeapMaxMb,
                        compressedPointersEnabled = compressedPointersEnabled,
                        stringDeduplicationEnabled = stringDeduplicationEnabled,
                        backBehavior = backBehavior,
                        manualDismissBootOverlay = manualDismissBootOverlay,
                        showFloatingMouseWindow = showFloatingMouseWindow,
                        touchMouseDoubleTapLockEnabled = touchMouseDoubleTapLockEnabled,
                        longPressMouseShowsKeyboard = longPressMouseShowsKeyboard,
                        autoSwitchLeftAfterRightClick = autoSwitchLeftAfterRightClick,
                        showModFileName = showModFileName,
                        mobileHudEnabled = mobileHudEnabled,
                        compendiumUpgradeTouchFixEnabled = compendiumUpgradeTouchFixEnabled,
                        avoidDisplayCutout = avoidDisplayCutout,
                        cropScreenBottom = cropScreenBottom,
                        showGamePerformanceOverlay = showGamePerformanceOverlay,
                        sustainedPerformanceModeEnabled = sustainedPerformanceModeEnabled,
                        systemGameModeDisplayName = systemGameMode.displayName,
                        systemGameModeDescription = systemGameMode.description,
                        lwjglDebugEnabled = lwjglDebugEnabled,
                        preloadAllJreLibrariesEnabled = preloadAllJreLibrariesEnabled,
                        logcatCaptureEnabled = logcatCaptureEnabled,
                        jvmLogcatMirrorEnabled = jvmLogcatMirrorEnabled,
                        gdxPadCursorDebugEnabled = gdxPadCursorDebugEnabled,
                        glBridgeSwapHeartbeatDebugEnabled = glBridgeSwapHeartbeatDebugEnabled,
                        touchscreenEnabled = touchscreenEnabled,
                        statusText = status,
                        logPathText = buildLogPathText(host)
                    )
                }
            } catch (_: Throwable) {
                host.runOnUiThread {
                    if (clearBusy) {
                        uiState = uiState.copy(
                            busy = false,
                            busyMessage = null
                        )
                    }
                }
            }
        }
    }

    fun onRenderScaleSelected(host: Activity, value: Float) {
        if (uiState.busy) {
            return
        }
        val clampedValue = value.coerceIn(
            RenderScaleService.MIN_RENDER_SCALE,
            RenderScaleService.MAX_RENDER_SCALE
        )
        if (kotlin.math.abs(clampedValue - uiState.selectedRenderScale) < 0.0001f) {
            return
        }
        val normalized = try {
            RenderScaleService.save(host, clampedValue)
        } catch (error: IOException) {
            Toast.makeText(host, error.message ?: "Failed to save render scale", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedValue = normalized.toFloatOrNull() ?: clampedValue
        uiState = uiState.copy(selectedRenderScale = normalizedValue)
        updateStatusRenderScaleLine(normalized)
        refreshStatus(host)
    }

    fun onImportJar() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportJarPicker)
    }

    fun onImportMods() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportModsPicker)
    }

    fun onImportSaves() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenImportSavesPicker)
    }

    fun onExportMods() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportModsPicker(SettingsFileService.buildModsExportFileName()))
    }

    fun onExportSaves() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportSavesPicker(SettingsFileService.buildSaveExportFileName()))
    }

    fun onExportLogsToFile() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenExportLogsPicker(SettingsFileService.buildJvmLogExportFileName()))
    }

    fun onExportLogs(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, "Preparing JVM log bundle...")
        executor.execute {
            try {
                val payload = JvmLogShareService.prepareSharePayload(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(Effect.ShareJvmLogsBundle(payload))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(
                        host,
                        StsExternalStorageAccess.buildFailureMessage(host, "Log share failed", error),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onLogsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting JVM logs...")
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportJvmLogBundle(host, uri)
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        Toast.makeText(host, "Log archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(host, "Log archive exported (no logs yet)", Toast.LENGTH_LONG).show()
                    }
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(
                        host,
                        StsExternalStorageAccess.buildFailureMessage(host, "Log export failed", error),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onModsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting mods archive...")
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportModsBundle(host, uri)
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        Toast.makeText(host, "Mod archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(host, "Mod archive exported (no mods yet)", Toast.LENGTH_LONG).show()
                    }
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(
                        host,
                        StsExternalStorageAccess.buildFailureMessage(host, "Mod export failed", error),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onTargetFpsSelected(host: Activity, targetFps: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedTargetFps = LauncherPreferences.normalizeTargetFps(targetFps)
        uiState = uiState.copy(selectedTargetFps = normalizedTargetFps)
        saveTargetFpsSelection(host, normalizedTargetFps)
        refreshStatus(host)
    }

    fun onRenderSurfaceBackendChanged(host: Activity, backend: RenderSurfaceBackend) {
        if (uiState.busy || uiState.renderSurfaceBackend == backend) {
            return
        }
        uiState = uiState.copy(renderSurfaceBackend = backend)
        saveRenderSurfaceBackendSelection(host, backend)
        refreshStatus(host)
    }

    fun onRendererSelectionModeChanged(host: Activity, mode: RendererSelectionMode) {
        if (uiState.busy || uiState.rendererSelectionMode == mode) {
            return
        }
        uiState = uiState.copy(rendererSelectionMode = mode)
        saveRendererSelectionModeSelection(host, mode)
        refreshStatus(host)
    }

    fun onManualRendererBackendChanged(host: Activity, backend: RendererBackend) {
        if (uiState.busy || uiState.manualRendererBackend == backend) {
            return
        }
        uiState = uiState.copy(manualRendererBackend = backend)
        saveManualRendererBackendSelection(host, backend)
        refreshStatus(host)
    }

    fun onMobileGluesAnglePolicyChanged(host: Activity, policy: MobileGluesAnglePolicy) {
        if (uiState.busy || uiState.mobileGluesAnglePolicy == policy) {
            return
        }
        uiState = uiState.copy(mobileGluesAnglePolicy = policy)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues ANGLE policy"
        ) { it.copy(anglePolicy = policy) }
        refreshStatus(host)
    }

    fun onMobileGluesNoErrorPolicyChanged(host: Activity, policy: MobileGluesNoErrorPolicy) {
        if (uiState.busy || uiState.mobileGluesNoErrorPolicy == policy) {
            return
        }
        uiState = uiState.copy(mobileGluesNoErrorPolicy = policy)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues no-error policy"
        ) { it.copy(noErrorPolicy = policy) }
        refreshStatus(host)
    }

    fun onMobileGluesMultidrawModeChanged(host: Activity, mode: MobileGluesMultidrawMode) {
        if (uiState.busy || uiState.mobileGluesMultidrawMode == mode) {
            return
        }
        uiState = uiState.copy(mobileGluesMultidrawMode = mode)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues multidraw mode"
        ) { it.copy(multidrawMode = mode) }
        refreshStatus(host)
    }

    fun onMobileGluesExtComputeShaderChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || uiState.mobileGluesExtComputeShaderEnabled == enabled) {
            return
        }
        uiState = uiState.copy(mobileGluesExtComputeShaderEnabled = enabled)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues compute shader setting"
        ) { it.copy(extComputeShaderEnabled = enabled) }
        refreshStatus(host)
    }

    fun onMobileGluesExtTimerQueryChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || uiState.mobileGluesExtTimerQueryEnabled == enabled) {
            return
        }
        uiState = uiState.copy(mobileGluesExtTimerQueryEnabled = enabled)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues timer query setting"
        ) { it.copy(extTimerQueryEnabled = enabled) }
        refreshStatus(host)
    }

    fun onMobileGluesExtDirectStateAccessChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || uiState.mobileGluesExtDirectStateAccessEnabled == enabled) {
            return
        }
        uiState = uiState.copy(mobileGluesExtDirectStateAccessEnabled = enabled)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues direct state access setting"
        ) { it.copy(extDirectStateAccessEnabled = enabled) }
        refreshStatus(host)
    }

    fun onMobileGluesGlslCacheSizePresetChanged(
        host: Activity,
        preset: MobileGluesGlslCacheSizePreset
    ) {
        if (uiState.busy || uiState.mobileGluesGlslCacheSizePreset == preset) {
            return
        }
        uiState = uiState.copy(mobileGluesGlslCacheSizePreset = preset)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues GLSL cache size"
        ) { it.copy(glslCacheSizePreset = preset) }
        refreshStatus(host)
    }

    fun onMobileGluesAngleDepthClearFixModeChanged(
        host: Activity,
        mode: MobileGluesAngleDepthClearFixMode
    ) {
        if (uiState.busy || uiState.mobileGluesAngleDepthClearFixMode == mode) {
            return
        }
        uiState = uiState.copy(mobileGluesAngleDepthClearFixMode = mode)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues ANGLE depth clear fix mode"
        ) { it.copy(angleDepthClearFixMode = mode) }
        refreshStatus(host)
    }

    fun onMobileGluesCustomGlVersionChanged(
        host: Activity,
        version: MobileGluesCustomGlVersion
    ) {
        if (uiState.busy || uiState.mobileGluesCustomGlVersion == version) {
            return
        }
        uiState = uiState.copy(mobileGluesCustomGlVersion = version)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues custom GL version"
        ) { it.copy(customGlVersion = version) }
        refreshStatus(host)
    }

    fun onMobileGluesFsr1QualityPresetChanged(
        host: Activity,
        preset: MobileGluesFsr1QualityPreset
    ) {
        if (uiState.busy || uiState.mobileGluesFsr1QualityPreset == preset) {
            return
        }
        uiState = uiState.copy(mobileGluesFsr1QualityPreset = preset)
        persistMobileGluesSettings(
            host = host,
            failureMessage = "Failed to save MobileGlues FSR1 setting"
        ) { it.copy(fsr1QualityPreset = preset) }
        refreshStatus(host)
    }

    fun onApplyMobileGluesPreset(host: Activity, preset: MobileGluesPreset) {
        if (uiState.busy) {
            return
        }
        applyResolvedMobileGluesSettings(
            host = host,
            settings = preset.settings,
            failureMessage = "Failed to apply MobileGlues preset"
        )
        refreshStatus(host)
    }

    fun onResetMobileGluesSettings(host: Activity) {
        if (uiState.busy) {
            return
        }
        applyResolvedMobileGluesSettings(
            host = host,
            settings = MobileGluesSettings(
                anglePolicy = LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_POLICY,
                noErrorPolicy = LauncherPreferences.DEFAULT_MOBILEGLUES_NO_ERROR_POLICY,
                multidrawMode = LauncherPreferences.DEFAULT_MOBILEGLUES_MULTIDRAW_MODE,
                extComputeShaderEnabled = LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
                extTimerQueryEnabled = LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
                extDirectStateAccessEnabled = LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
                glslCacheSizePreset = LauncherPreferences.DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET,
                angleDepthClearFixMode = LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
                customGlVersion = LauncherPreferences.DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION,
                fsr1QualityPreset = LauncherPreferences.DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET
            ),
            failureMessage = "Failed to reset MobileGlues settings"
        )
        refreshStatus(host)
    }

    fun onJvmHeapMaxSelected(host: Activity, heapMaxMb: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedHeapMax = LauncherPreferences.normalizeJvmHeapMaxMb(heapMaxMb)
        if (normalizedHeapMax == uiState.selectedJvmHeapMaxMb) {
            return
        }
        uiState = uiState.copy(selectedJvmHeapMaxMb = normalizedHeapMax)
        saveJvmHeapMaxSelection(host, normalizedHeapMax)
        refreshStatus(host)
    }

    fun onJvmCompressedPointersChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (enabled == uiState.compressedPointersEnabled) {
            return
        }
        uiState = uiState.copy(compressedPointersEnabled = enabled)
        saveJvmCompressedPointersSelection(host, enabled)
        refreshStatus(host)
    }

    fun onJvmStringDeduplicationChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (enabled == uiState.stringDeduplicationEnabled) {
            return
        }
        uiState = uiState.copy(stringDeduplicationEnabled = enabled)
        saveJvmStringDeduplicationSelection(host, enabled)
        refreshStatus(host)
    }

    fun onPlayerNameChanged(host: Activity, name: String): Boolean {
        if (uiState.busy) {
            return false
        }
        val normalizedPlayerName = LauncherPreferences.normalizePlayerName(name)
        if (normalizedPlayerName == uiState.playerName) {
            return true
        }
        if (!savePlayerNameSelection(host, normalizedPlayerName)) {
            return false
        }
        uiState = uiState.copy(playerName = normalizedPlayerName)
        refreshStatus(host)
        return true
    }

    fun onBackBehaviorChanged(host: Activity, behavior: BackBehavior) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(backBehavior = behavior)
        saveBackBehaviorSelection(host, behavior)
        refreshStatus(host)
    }

    fun onManualDismissBootOverlayChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(manualDismissBootOverlay = enabled)
        saveManualDismissBootOverlaySelection(host, enabled)
        refreshStatus(host)
    }

    fun onShowFloatingMouseWindowChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showFloatingMouseWindow = enabled)
        saveShowFloatingMouseWindowSelection(host, enabled)
        refreshStatus(host)
    }

    fun onTouchMouseDoubleTapLockChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchMouseDoubleTapLockEnabled = enabled)
        saveTouchMouseDoubleTapLockSelection(host, enabled)
        refreshStatus(host)
    }

    fun onLongPressMouseShowsKeyboardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(longPressMouseShowsKeyboard = enabled)
        saveLongPressMouseShowsKeyboardSelection(host, enabled)
        refreshStatus(host)
    }

    fun onAutoSwitchLeftAfterRightClickChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(autoSwitchLeftAfterRightClick = enabled)
        saveAutoSwitchLeftAfterRightClickSelection(host, enabled)
        refreshStatus(host)
    }

    fun onShowModFileNameChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showModFileName = enabled)
        saveShowModFileNameSelection(host, enabled)
        refreshStatus(host)
    }

    fun onLwjglDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(lwjglDebugEnabled = enabled)
        saveLwjglDebugSelection(host, enabled)
        refreshStatus(host)
    }

    fun onPreloadAllJreLibrariesChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(preloadAllJreLibrariesEnabled = enabled)
        savePreloadAllJreLibrariesSelection(host, enabled)
        refreshStatus(host)
    }

    fun onLogcatCaptureChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(logcatCaptureEnabled = enabled)
        saveLogcatCaptureSelection(host, enabled)
        if (!enabled) {
            LogcatCaptureProcessClient.stopAndClearCapture(host)
        }
        refreshStatus(host)
    }

    fun onJvmLogcatMirrorChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(jvmLogcatMirrorEnabled = enabled)
        saveJvmLogcatMirrorSelection(host, enabled)
        refreshStatus(host)
    }

    fun onGdxPadCursorDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(gdxPadCursorDebugEnabled = enabled)
        saveGdxPadCursorDebugSelection(host, enabled)
        refreshStatus(host)
    }

    fun onGlBridgeSwapHeartbeatDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(glBridgeSwapHeartbeatDebugEnabled = enabled)
        saveGlBridgeSwapHeartbeatDebugSelection(host, enabled)
        refreshStatus(host)
    }

    fun onMobileHudEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(mobileHudEnabled = enabled)
        saveMobileHudEnabledSelection(host, enabled)
        refreshStatus(host)
    }

    fun onCompendiumUpgradeTouchFixEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(compendiumUpgradeTouchFixEnabled = enabled)
        saveCompendiumUpgradeTouchFixSelection(host, enabled)
        refreshStatus(host)
    }

    fun onDisplayCutoutAvoidanceChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(avoidDisplayCutout = enabled)
        saveDisplayCutoutAvoidanceSelection(host, enabled)
        refreshStatus(host)
    }

    fun onScreenBottomCropChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(cropScreenBottom = enabled)
        saveScreenBottomCropSelection(host, enabled)
        refreshStatus(host)
    }

    fun onGamePerformanceOverlayChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showGamePerformanceOverlay = enabled)
        saveGamePerformanceOverlaySelection(host, enabled)
        refreshStatus(host)
    }

    fun onSustainedPerformanceModeChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(sustainedPerformanceModeEnabled = enabled)
        saveSustainedPerformanceModeSelection(host, enabled)
        refreshStatus(host)
    }

    fun onTouchscreenEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (!saveTouchscreenEnabledSelection(host, enabled)) {
            return
        }
        uiState = uiState.copy(touchscreenEnabled = enabled)
        refreshStatus(host)
    }

    fun onOpenCompatibility() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenCompatibility)
    }

    fun onOpenMobileGluesSettings() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenMobileGluesSettings)
    }

    fun onOpenFeedback() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenFeedback)
    }

    fun onJarPicked(
        host: Activity,
        uri: Uri?,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = host.getString(R.string.sts_jar_import_busy),
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            try {
                SettingsFileService.importUriToFileAtomically(
                    host = host,
                    uri = uri,
                    targetFile = RuntimePaths.importedStsJar(host),
                    validator = StsJarValidator::validate
                )
                MtsClasspathWarmupCoordinator.invalidateCache(host)
                val warmupWarning = prewarmMtsClasspathAfterImport(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        host.getString(R.string.sts_jar_import_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (warmupWarning != null) {
                        Toast.makeText(host, warmupWarning, Toast.LENGTH_LONG).show()
                    }
                    refreshStatus(host)
                    onCompleted?.invoke(true)
//                    todo: host.notifyMainDataChanged()
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        host.getString(
                            R.string.sts_jar_import_failed,
                            resolveThrowableMessage(host, error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                    onCompleted?.invoke(false)
                }
            }
        }
    }

    fun onModJarsPicked(
        host: Activity,
        uris: List<Uri>?,
        onCompleted: (() -> Unit)? = null
    ) {
        if (uiState.busy || uris.isNullOrEmpty()) {
            return
        }
        startModJarImport(host, uris, onCompleted)
    }

    private fun startModJarImport(
        host: Activity,
        uris: List<Uri>,
        onCompleted: (() -> Unit)? = null,
        replaceExistingDuplicates: Boolean = false,
        skipDuplicateCheck: Boolean = false
    ) {
        setBusy(
            busy = true,
            message = host.getString(R.string.mod_import_busy_message),
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            try {
                if (!skipDuplicateCheck) {
                    val duplicateConflicts = SettingsFileService.findDuplicateModImportConflicts(host, uris)
                    if (duplicateConflicts.isNotEmpty()) {
                        host.runOnUiThread {
                            setBusy(false, null)
                            showDuplicateModImportDialog(host, uris, duplicateConflicts, onCompleted)
                        }
                        return@execute
                    }
                }
                val batchResult = SettingsFileService.importModJars(
                    host = host,
                    uris = uris,
                    replaceExistingDuplicates = replaceExistingDuplicates
                )
                val importedCount = batchResult.importedCount
                val failedCount = batchResult.failedCount
                val blockedCount = batchResult.blockedCount
                val compressedArchiveCount = batchResult.compressedArchiveCount
                val firstError = batchResult.firstError
                val blockedList = batchResult.blockedComponents
                val compressedArchiveList = batchResult.compressedArchives
                val patchedResults = batchResult.patchedResults
                host.runOnUiThread {
                    setBusy(false, null)
                    if (blockedList.isNotEmpty()) {
                        AlertDialog.Builder(host)
                            .setTitle(R.string.mod_import_dialog_reserved_title)
                            .setMessage(
                                SettingsFileService.buildReservedModImportMessage(
                                    context = host,
                                    blockedComponents = blockedList
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    if (compressedArchiveList.isNotEmpty()) {
                        showCompressedArchiveWarningDialog(host, compressedArchiveList)
                    }
                    if (patchedResults.isNotEmpty()) {
                        showAtlasPatchSummaryDialog(host, patchedResults)
                        showManifestRootPatchSummaryDialog(host, patchedResults)
                        showFrierenPatchSummaryDialog(host, patchedResults)
                        showDownfallPatchSummaryDialog(host, patchedResults)
                        showVupShionPatchSummaryDialog(host, patchedResults)
                    }
                    when {
                        importedCount > 0 && failedCount == 0 -> {
                            Toast.makeText(
                                host,
                                host.getString(R.string.mod_import_result_success, importedCount),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        importedCount > 0 -> {
                            Toast.makeText(
                                host,
                                host.getString(
                                    R.string.mod_import_result_partial,
                                    importedCount,
                                    failedCount,
                                    resolveErrorMessage(host, firstError)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        failedCount > 0 -> {
                            Toast.makeText(
                                host,
                                host.getString(
                                    R.string.mod_import_result_failed,
                                    resolveErrorMessage(host, firstError)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        blockedCount > 0 -> {
                            Toast.makeText(
                                host,
                                host.getString(
                                    R.string.mod_import_result_blocked_builtin,
                                    blockedCount
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        compressedArchiveCount > 0 -> {
                            Toast.makeText(
                                host,
                                host.getString(R.string.mod_import_result_archive_detected),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    refreshStatus(host)
                    onCompleted?.invoke()
//                todo host.notifyMainDataChanged()
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    Toast.makeText(
                        host,
                        host.getString(
                            R.string.mod_import_result_failed,
                            resolveThrowableMessage(host, error)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                    onCompleted?.invoke()
                }
            }
        }
    }

    private fun showDuplicateModImportDialog(
        host: Activity,
        uris: List<Uri>,
        duplicateConflicts: List<DuplicateModImportConflict>,
        onCompleted: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_duplicate_title)
            .setMessage(
                SettingsFileService.buildDuplicateModImportMessage(
                    context = host,
                    conflicts = duplicateConflicts
                )
            )
            .setNegativeButton(R.string.mod_import_dialog_duplicate_cancel) { _, _ ->
                Toast.makeText(host, host.getString(R.string.mod_import_cancelled), Toast.LENGTH_SHORT).show()
                onCompleted?.invoke()
            }
            .setNeutralButton(R.string.mod_import_dialog_duplicate_replace_existing) { _, _ ->
                startModJarImport(
                    host = host,
                    uris = uris,
                    onCompleted = onCompleted,
                    replaceExistingDuplicates = true,
                    skipDuplicateCheck = true
                )
            }
            .setPositiveButton(R.string.mod_import_dialog_duplicate_keep_both) { _, _ ->
                startModJarImport(host, uris, onCompleted, skipDuplicateCheck = true)
            }
            .setOnCancelListener {
                Toast.makeText(host, host.getString(R.string.mod_import_cancelled), Toast.LENGTH_SHORT).show()
                onCompleted?.invoke()
            }
            .show()
    }

    private fun showAtlasPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasAtlasPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_atlas_patched_title)
            .setMessage(
                SettingsFileService.buildAtlasPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManifestRootPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasManifestRootPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_manifest_root_patched_title)
            .setMessage(
                SettingsFileService.buildManifestRootPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showFrierenPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasFrierenAntiPiratePatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_frieren_patched_title)
            .setMessage(
                SettingsFileService.buildFrierenPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showDownfallPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasDownfallPatched }) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_downfall_patched_title)
            .setMessage(
                SettingsFileService.buildDownfallPatchImportSummaryMessage(
                    context = host,
                    patchedResults = patchedResults
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showVupShionPatchSummaryDialog(host: Activity, patchedResults: List<ModImportResult>) {
        if (patchedResults.none { it.wasVupShionPatched }) {
            return
        }
        VupShionPatchedDialog.show(host)
    }

    private fun showCompressedArchiveWarningDialog(host: Activity, archiveDisplayNames: List<String>) {
        AlertDialog.Builder(host)
            .setTitle(R.string.mod_import_dialog_archive_title)
            .setMessage(
                SettingsFileService.buildCompressedArchiveImportMessage(
                    context = host,
                    archiveDisplayNames = archiveDisplayNames
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun prewarmMtsClasspathAfterImport(host: Activity): String? {
        return try {
            val prepared = MtsClasspathWarmupCoordinator.prewarmIfReady(host) { _, message ->
                host.runOnUiThread {
                    setBusy(
                        busy = true,
                        message = message,
                        operation = UiBusyOperation.MOD_IMPORT
                    )
                }
            }
            if (prepared) {
                null
            } else {
                null
            }
        } catch (error: Throwable) {
            host.getString(
                R.string.sts_jar_import_prewarm_failed,
                resolveThrowableMessage(host, error)
            )
        }
    }

    private fun resolveErrorMessage(host: Activity, message: String?): String {
        return message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: host.getString(R.string.mod_import_error_unknown)
    }

    private fun resolveThrowableMessage(host: Activity, error: Throwable): String {
        return resolveErrorMessage(host, error.message ?: error.javaClass.simpleName)
    }

    fun onSavesArchivePicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        importSavesArchive(host, uri)
    }

    private fun importSavesArchive(host: Activity, uri: Uri) {
        setBusy(true, "Importing save archive...")
        executor.execute {
            try {
                val result = SettingsFileService.importSaveArchive(host, uri)
                host.runOnUiThread {
                    val message = if (result.backupLabel.isNullOrEmpty()) {
                        "Imported ${result.importedFiles} save files"
                    } else {
                        "Imported ${result.importedFiles} save files (backup: ${result.backupLabel})"
                    }
                    Toast.makeText(host, message, Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "Save import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSavesExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting save archive...")
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportSaveBundle(host, uri)
                host.runOnUiThread {
                    Toast.makeText(host, "Save archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(
                        host,
                        StsExternalStorageAccess.buildFailureMessage(host, "Save export failed", error),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatus(host)
                }
            }
        }
    }

    private fun setBusy(
        busy: Boolean,
        message: String?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY
    ) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyOperation = operation,
                busyMessage = message
            )
        } else {
            uiState.copy(
                busy = false,
                busyOperation = UiBusyOperation.NONE,
                busyMessage = null
            )
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return try {
            host.assets.open(assetPath).use {
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun resolveCoreDependencyStatus(
        host: Activity,
        label: String,
        importedJar: File,
        bundledAssetPath: String
    ): CoreDependencyStatus {
        if (importedJar.isFile) {
            val importedVersion = resolveJarVersionFromFile(importedJar) ?: "unknown"
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "imported",
                version = importedVersion
            )
        }

        val bundledVersion = resolveJarVersionFromAsset(host, bundledAssetPath)
        if (bundledVersion != null) {
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "bundled",
                version = bundledVersion
            )
        }
        if (hasBundledAsset(host, bundledAssetPath)) {
            return CoreDependencyStatus(
                label = label,
                available = true,
                source = "bundled",
                version = "unknown"
            )
        }

        return CoreDependencyStatus(
            label = label,
            available = false,
            source = "missing",
            version = "unknown"
        )
    }

    private fun formatCoreDependencyLine(status: CoreDependencyStatus): String {
        if (!status.available) {
            return "${status.label}: 缺失"
        }
        val sourceLabel = when (status.source.lowercase(Locale.ROOT)) {
            "imported" -> "本地文件"
            "bundled" -> "内置组件"
            else -> status.source
        }
        return "${status.label}: 可用 (来源: $sourceLabel, 版本: ${status.version})"
    }

    private fun collectDeviceRuntimeStatus(host: Activity): DeviceRuntimeStatus {
        val activityManager = host.getSystemService(Activity.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val availableMemoryBytes: Long
        val totalMemoryBytes: Long
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo)
            availableMemoryBytes = memoryInfo.availMem
            totalMemoryBytes = memoryInfo.totalMem
        } else {
            availableMemoryBytes = 0L
            totalMemoryBytes = 0L
        }

        return DeviceRuntimeStatus(
            cpuModel = resolveCpuModel(),
            cpuArch = resolveCpuArch(),
            availableMemoryBytes = availableMemoryBytes,
            totalMemoryBytes = totalMemoryBytes
        )
    }

    private fun resolveCpuModel(): String {
        val fromProcCpuInfo = readCpuModelFromProcCpuInfo()
        if (!fromProcCpuInfo.isNullOrBlank()) {
            return fromProcCpuInfo
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socModel = normalizeInfoValue(Build.SOC_MODEL)
            if (socModel != "unknown") {
                return socModel
            }
        }

        val hardware = normalizeInfoValue(Build.HARDWARE)
        if (hardware != "unknown") {
            return hardware
        }

        val model = normalizeInfoValue(Build.MODEL)
        if (model != "unknown") {
            return model
        }
        return "unknown"
    }

    private fun readCpuModelFromProcCpuInfo(): String? {
        val cpuInfoFile = File("/proc/cpuinfo")
        if (!cpuInfoFile.isFile) {
            return null
        }

        var hardware: String? = null
        var modelName: String? = null
        var processor: String? = null
        var cpuModel: String? = null
        return try {
            cpuInfoFile.forEachLine { rawLine ->
                val separator = rawLine.indexOf(':')
                if (separator <= 0) {
                    return@forEachLine
                }
                val key = rawLine.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = rawLine.substring(separator + 1).trim()
                if (value.isEmpty()) {
                    return@forEachLine
                }
                when (key) {
                    "hardware" -> if (hardware.isNullOrBlank()) hardware = value
                    "model name" -> if (modelName.isNullOrBlank()) modelName = value
                    "processor" -> if (processor.isNullOrBlank()) processor = value
                    "cpu model" -> if (cpuModel.isNullOrBlank()) cpuModel = value
                }
            }
            hardware ?: modelName ?: processor ?: cpuModel
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCpuArch(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val abiText = supportedAbis.joinToString(", ")
        val osArch = normalizeInfoValue(System.getProperty("os.arch"))
        return when {
            abiText.isNotEmpty() && osArch != "unknown" -> "$osArch (ABI: $abiText)"
            abiText.isNotEmpty() -> abiText
            osArch != "unknown" -> osArch
            else -> "unknown"
        }
    }

    private fun normalizeInfoValue(value: String?): String {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) {
            return "unknown"
        }
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
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

    private fun resolveJarVersionFromFile(jarFile: File): String? {
        if (!jarFile.isFile) {
            return null
        }
        try {
            val manifest = ModJarSupport.readModManifest(jarFile)
            manifest.version.trim().takeIf { it.isNotEmpty() }?.let { return it }
        } catch (_: Throwable) {
        }

        return try {
            jarFile.inputStream().use { input ->
                resolveJarVersionFromStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveJarVersionFromAsset(host: Activity, assetPath: String): String? {
        return try {
            host.assets.open(assetPath).use { input ->
                resolveJarVersionFromStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveJarVersionFromStream(input: InputStream): String? {
        ZipInputStream(BufferedInputStream(input)).use { zipInput ->
            var modManifestVersion: String? = null
            var jarManifestVersion: String? = null
            while (true) {
                val entry = zipInput.nextEntry ?: break
                if (entry.isDirectory) {
                    continue
                }

                val entryName = entry.name.trim()
                if (modManifestVersion == null &&
                    entryName.equals("ModTheSpire.json", ignoreCase = true)
                ) {
                    val jsonBytes = readCurrentZipEntryBytes(zipInput)
                    modManifestVersion = parseModManifestVersionFromJson(String(jsonBytes, Charsets.UTF_8))
                    if (!modManifestVersion.isNullOrBlank()) {
                        return modManifestVersion
                    }
                    continue
                }

                if (jarManifestVersion == null &&
                    entryName.equals("META-INF/MANIFEST.MF", ignoreCase = true)
                ) {
                    val manifestBytes = readCurrentZipEntryBytes(zipInput)
                    jarManifestVersion = parseJarManifestVersionFromBytes(manifestBytes)
                }
            }
            return jarManifestVersion
        }
    }

    private fun readCurrentZipEntryBytes(zipInput: ZipInputStream): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = zipInput.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseJarManifestVersionFromBytes(manifestBytes: ByteArray): String? {
        return try {
            val attributes = Manifest(manifestBytes.inputStream()).mainAttributes
            val keys = arrayOf(
                "Implementation-Version",
                "Bundle-Version",
                "Specification-Version",
                "Version"
            )
            for (key in keys) {
                val value = attributes.getValue(key)?.trim()
                if (!value.isNullOrEmpty()) {
                    return value
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseModManifestVersionFromJson(jsonText: String): String? {
        val regex = Regex("\"(?:version|Version)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        val match = regex.find(jsonText) ?: return null
        val rawVersion = match.groupValues.getOrNull(1) ?: return null
        return unescapeJsonText(rawVersion)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun unescapeJsonText(text: String): String {
        return text
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun updateStatusRenderScaleLine(normalizedRenderScale: String) {
        val status = uiState.statusText
        if (status.isBlank()) {
            return
        }
        val lines = status.lines().toMutableList()
        val lineIndex = lines.indexOfFirst { it.startsWith("Render scale: ") }
        if (lineIndex < 0) {
            return
        }
        lines[lineIndex] = "Render scale: $normalizedRenderScale (0.50-1.00)"
        uiState = uiState.copy(statusText = lines.joinToString("\n"))
    }

    private fun readBackBehaviorSelection(host: Activity): BackBehavior {
        return LauncherPreferences.readBackBehavior(host)
    }

    private fun saveBackBehaviorSelection(host: Activity, behavior: BackBehavior) {
        LauncherPreferences.saveBackBehavior(host, behavior)
    }

    private fun readManualDismissBootOverlaySelection(host: Activity): Boolean {
        return LauncherPreferences.readManualDismissBootOverlay(host)
    }

    private fun saveManualDismissBootOverlaySelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveManualDismissBootOverlay(host, enabled)
    }

    private fun readShowFloatingMouseWindowSelection(host: Activity): Boolean {
        return LauncherPreferences.readShowFloatingMouseWindow(host)
    }

    private fun saveShowFloatingMouseWindowSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveShowFloatingMouseWindow(host, enabled)
    }

    private fun readTouchMouseDoubleTapLockSelection(host: Activity): Boolean {
        return LauncherPreferences.readTouchMouseDoubleTapLockEnabled(host)
    }

    private fun saveTouchMouseDoubleTapLockSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveTouchMouseDoubleTapLockEnabled(host, enabled)
    }

    private fun readLongPressMouseShowsKeyboardSelection(host: Activity): Boolean {
        return LauncherPreferences.readLongPressMouseShowsKeyboard(host)
    }

    private fun saveLongPressMouseShowsKeyboardSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveLongPressMouseShowsKeyboard(host, enabled)
    }

    private fun readAutoSwitchLeftAfterRightClickSelection(host: Activity): Boolean {
        return LauncherPreferences.readAutoSwitchLeftAfterRightClick(host)
    }

    private fun saveAutoSwitchLeftAfterRightClickSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveAutoSwitchLeftAfterRightClick(host, enabled)
    }

    private fun readShowModFileNameSelection(host: Activity): Boolean {
        return LauncherPreferences.readShowModFileName(host)
    }

    private fun saveShowModFileNameSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveShowModFileName(host, enabled)
    }

    private fun readMobileHudEnabledSelection(host: Activity): Boolean {
        return LauncherPreferences.readMobileHudEnabled(host)
    }

    private fun saveMobileHudEnabledSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveMobileHudEnabled(host, enabled)
    }

    private fun readCompendiumUpgradeTouchFixSelection(host: Activity): Boolean {
        return LauncherPreferences.readCompendiumUpgradeTouchFixEnabled(host)
    }

    private fun saveCompendiumUpgradeTouchFixSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveCompendiumUpgradeTouchFixEnabled(host, enabled)
    }

    private fun readDisplayCutoutAvoidanceSelection(host: Activity): Boolean {
        return LauncherPreferences.isDisplayCutoutAvoidanceEnabled(host)
    }

    private fun saveDisplayCutoutAvoidanceSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setDisplayCutoutAvoidanceEnabled(host, enabled)
    }

    private fun readScreenBottomCropSelection(host: Activity): Boolean {
        return LauncherPreferences.isScreenBottomCropEnabled(host)
    }

    private fun saveScreenBottomCropSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setScreenBottomCropEnabled(host, enabled)
    }

    private fun readLwjglDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isLwjglDebugEnabled(host)
    }

    private fun saveLwjglDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLwjglDebugEnabled(host, enabled)
    }

    private fun readPreloadAllJreLibrariesSelection(host: Activity): Boolean {
        return LauncherPreferences.isPreloadAllJreLibrariesEnabled(host)
    }

    private fun savePreloadAllJreLibrariesSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setPreloadAllJreLibrariesEnabled(host, enabled)
    }

    private fun readLogcatCaptureSelection(host: Activity): Boolean {
        return LauncherPreferences.isLogcatCaptureEnabled(host)
    }

    private fun saveLogcatCaptureSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLogcatCaptureEnabled(host, enabled)
    }

    private fun readJvmLogcatMirrorSelection(host: Activity): Boolean {
        return LauncherPreferences.isJvmLogcatMirrorEnabled(host)
    }

    private fun saveJvmLogcatMirrorSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmLogcatMirrorEnabled(host, enabled)
    }

    private fun readGdxPadCursorDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isGdxPadCursorDebugEnabled(host)
    }

    private fun saveGdxPadCursorDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGdxPadCursorDebugEnabled(host, enabled)
    }

    private fun readGlBridgeSwapHeartbeatDebugSelection(host: Activity): Boolean {
        return LauncherPreferences.isGlBridgeSwapHeartbeatDebugEnabled(host)
    }

    private fun saveGlBridgeSwapHeartbeatDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGlBridgeSwapHeartbeatDebugEnabled(host, enabled)
    }

    private fun readTargetFpsSelection(host: Activity): Int {
        return LauncherPreferences.readTargetFps(host)
    }

    private fun readRenderSurfaceBackendSelection(host: Activity): RenderSurfaceBackend {
        return LauncherPreferences.readRenderSurfaceBackend(host)
    }

    private fun readRendererSelectionModeSelection(host: Activity): RendererSelectionMode {
        return LauncherPreferences.readRendererSelectionMode(host)
    }

    private fun saveRendererSelectionModeSelection(
        host: Activity,
        mode: RendererSelectionMode
    ) {
        LauncherPreferences.saveRendererSelectionMode(host, mode)
    }

    private fun readManualRendererBackendSelection(host: Activity): RendererBackend {
        return LauncherPreferences.readManualRendererBackend(host)
    }

    private fun saveManualRendererBackendSelection(host: Activity, backend: RendererBackend) {
        LauncherPreferences.saveManualRendererBackend(host, backend)
    }

    private fun readMobileGluesSettingsSelection(host: Activity): MobileGluesSettings {
        return LauncherPreferences.readMobileGluesSettings(host)
    }

    private fun persistMobileGluesSettings(
        host: Activity,
        failureMessage: String,
        transform: (MobileGluesSettings) -> MobileGluesSettings
    ) {
        try {
            val updated = transform(readMobileGluesSettingsSelection(host))
            LauncherPreferences.saveMobileGluesSettings(host, updated)
            MobileGluesConfigFile.syncFromLauncherPreferences(host)
        } catch (error: IOException) {
            Toast.makeText(
                host,
                "$failureMessage: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyResolvedMobileGluesSettings(
        host: Activity,
        settings: MobileGluesSettings,
        failureMessage: String
    ) {
        uiState = uiState.copy(
            mobileGluesAnglePolicy = settings.anglePolicy,
            mobileGluesNoErrorPolicy = settings.noErrorPolicy,
            mobileGluesMultidrawMode = settings.multidrawMode,
            mobileGluesExtComputeShaderEnabled = settings.extComputeShaderEnabled,
            mobileGluesExtTimerQueryEnabled = settings.extTimerQueryEnabled,
            mobileGluesExtDirectStateAccessEnabled = settings.extDirectStateAccessEnabled,
            mobileGluesGlslCacheSizePreset = settings.glslCacheSizePreset,
            mobileGluesAngleDepthClearFixMode = settings.angleDepthClearFixMode,
            mobileGluesCustomGlVersion = settings.customGlVersion,
            mobileGluesFsr1QualityPreset = settings.fsr1QualityPreset
        )
        try {
            LauncherPreferences.saveMobileGluesSettings(host, settings)
            MobileGluesConfigFile.syncFromLauncherPreferences(host)
        } catch (error: IOException) {
            Toast.makeText(
                host,
                "$failureMessage: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readPlayerNameSelection(host: Activity): String {
        return LauncherPreferences.readPlayerName(host)
    }

    private fun readThemeModeSelection(host: Activity): LauncherThemeMode {
        return LauncherPreferences.readThemeMode(host)
    }

    private fun savePlayerNameSelection(host: Activity, name: String): Boolean {
        return try {
            LauncherPreferences.savePlayerName(host, name)
            true
        } catch (error: IOException) {
            Toast.makeText(
                host,
                "Failed to save player name: ${error.message}",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun saveTargetFpsSelection(host: Activity, targetFps: Int) {
        LauncherPreferences.saveTargetFps(host, targetFps)
    }

    private fun saveRenderSurfaceBackendSelection(
        host: Activity,
        backend: RenderSurfaceBackend
    ) {
        LauncherPreferences.saveRenderSurfaceBackend(host, backend)
    }

    private fun saveThemeModeSelection(host: Activity, themeMode: LauncherThemeMode) {
        LauncherPreferences.saveThemeMode(host, themeMode)
        LauncherThemeController.apply(themeMode)
    }

    private fun readJvmHeapMaxSelection(host: Activity): Int {
        return LauncherPreferences.readJvmHeapMaxMb(host)
    }

    private fun saveJvmHeapMaxSelection(host: Activity, heapMaxMb: Int) {
        LauncherPreferences.saveJvmHeapMaxMb(host, heapMaxMb)
    }

    private fun readJvmCompressedPointersSelection(host: Activity): Boolean {
        return LauncherPreferences.isJvmCompressedPointersEnabled(host)
    }

    private fun saveJvmCompressedPointersSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmCompressedPointersEnabled(host, enabled)
    }

    private fun readJvmStringDeduplicationSelection(host: Activity): Boolean {
        return LauncherPreferences.isJvmStringDeduplicationEnabled(host)
    }

    private fun saveJvmStringDeduplicationSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmStringDeduplicationEnabled(host, enabled)
    }

    private fun readGamePerformanceOverlaySelection(host: Activity): Boolean {
        return LauncherPreferences.isGamePerformanceOverlayEnabled(host)
    }

    private fun saveGamePerformanceOverlaySelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGamePerformanceOverlayEnabled(host, enabled)
    }

    private fun readSustainedPerformanceModeSelection(host: Activity): Boolean {
        return LauncherPreferences.isSustainedPerformanceModeEnabled(host)
    }

    private fun saveSustainedPerformanceModeSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSustainedPerformanceModeEnabled(host, enabled)
    }

    private fun readTouchscreenEnabledSelection(host: Activity): Boolean {
        return GameplaySettingsService.readTouchscreenEnabled(host)
    }

    private fun saveTouchscreenEnabledSelection(host: Activity, enabled: Boolean): Boolean {
        return try {
            GameplaySettingsService.saveTouchscreenEnabled(host, enabled)
            true
        } catch (error: IOException) {
            Toast.makeText(
                host,
                "Failed to save touchscreen setting: ${error.message}",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun buildLogPathText(host: Activity): String {
        val latestLog = RuntimePaths.latestLog(host)
        val archivedDir = RuntimePaths.jvmLogsDir(host)
        val logcatDir = RuntimePaths.logcatDir(host)
        val logs = JvmLogRotationManager.listLogFiles(host)
        return buildString {
            append("Log slots: ").append(logs.size).append('/').append(JvmLogRotationManager.MAX_LOG_SLOTS)
            append("\nlatest.log: ").append(latestLog.absolutePath)
            append("\narchive dir: ").append(archivedDir.absolutePath)
            append("\nlogcat dir: ").append(logcatDir.absolutePath)
            if (logs.isEmpty()) {
                append("\n(no logs yet)")
            } else {
                for (log in logs) {
                    append("\n- ").append(log.name)
                    append(" (").append(log.length()).append(" bytes)")
                }
            }
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        super.onCleared()
    }

    private fun BackBehavior.displayName(): String {
        return when (this) {
            BackBehavior.EXIT_TO_LAUNCHER -> "Exit to launcher"
            BackBehavior.SEND_ESCAPE -> "Send Esc"
            BackBehavior.NONE -> "No action"
        }
    }

    private fun RenderSurfaceBackend.displayName(host: Activity): String {
        return when (this) {
            RenderSurfaceBackend.SURFACE_VIEW ->
                host.getString(R.string.settings_render_surface_backend_surface_view_short)
            RenderSurfaceBackend.TEXTURE_VIEW ->
                host.getString(R.string.settings_render_surface_backend_texture_view_short)
        }
    }

    private fun RendererSelectionMode.displayName(): String {
        return when (this) {
            RendererSelectionMode.AUTO -> "Auto"
            RendererSelectionMode.MANUAL -> "Manual"
        }
    }
}
