package io.stamethyst.ui.settings.core

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.importing.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BuildConfig
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.diag.LauncherLogcatCaptureProcessClient
import io.stamethyst.backend.fs.LauncherJunkFileCleaner
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.steamcloud.STEAM_CLOUD_APP_ID
import io.stamethyst.backend.steamcloud.SteamCloudAuthCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudBaselineStore
import io.stamethyst.backend.steamcloud.SteamCloudClient
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallenge
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallengeKind
import io.stamethyst.backend.steamcloud.SteamCloudManifestSnapshot
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamCloudNetworkEnvironment
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.backend.steamcloud.SteamCloudPhase0ManifestProbe
import io.stamethyst.backend.steamcloud.SteamCloudPhase0Store
import io.stamethyst.backend.steamcloud.SteamCloudProfileService
import io.stamethyst.backend.steamcloud.SteamCloudPullCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudPullResult
import io.stamethyst.backend.steamcloud.SteamCloudPushCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudRootKind
import io.stamethyst.backend.steamcloud.SteamCloudSaveProfileManager
import io.stamethyst.backend.steamcloud.SteamCloudSyncBlacklist
import io.stamethyst.backend.steamcloud.SteamCloudSyncBaseline
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.steam.SteamStsJarDownloadPhase
import io.stamethyst.backend.steam.SteamStsJarDownloadProgress
import io.stamethyst.backend.steam.SteamAnonymousDepotAccessException
import io.stamethyst.backend.steam.SteamStsJarDownloadService
import io.stamethyst.backend.nativelib.NativeLibraryMarketAvailability
import io.stamethyst.backend.nativelib.NativeLibraryMarketCatalogEntry
import io.stamethyst.backend.nativelib.NativeLibraryMarketInstallProgress
import io.stamethyst.backend.nativelib.NativeLibraryMarketPackageState
import io.stamethyst.backend.nativelib.NativeLibraryMarketService
import io.stamethyst.backend.resources.RuntimeResourceProvider
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
import io.stamethyst.backend.render.RendererAvailability
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.launch.MtsClasspathWarmupCoordinator
import io.stamethyst.backend.launch.MtsPatchCacheCoordinator
import io.stamethyst.backend.launch.MtsStartupCacheCoordinator
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.mods.ImportDownscaleMaterialPolicy
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.RuntimeDownscaleMaterialPolicy
import io.stamethyst.backend.mods.StsDesktopJarIntegrity
import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.LauncherUpdateUiReducer
import io.stamethyst.backend.update.UpdateCheckExecutionResult
import io.stamethyst.backend.update.UpdateDownloadResolution
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateReleaseHistoryEntry
import io.stamethyst.backend.update.UpdateReleaseHistoryResult
import io.stamethyst.backend.update.UpdateReleaseInfo
import io.stamethyst.backend.update.UpdateUiMessage
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.workshop.BaiduTranslationCredentials
import io.stamethyst.backend.workshop.BaiduTranslationCredentialsRepository
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherIconController
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeController
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.config.StsExternalStorageAccess
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.config.TouchscreenInputMode
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.UiText
import io.stamethyst.ui.main.ModManifestNameMigration
import io.stamethyst.ui.main.ModManifestNameMigrationProgress
import io.stamethyst.ui.main.ModManifestNameMigrationSpaceCheck
import io.stamethyst.ui.main.ModManifestNameMigrationStorageException
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val STEAM_CLOUD_BACKUP_DOWNLOAD_SUBDIR = "SlayTheAmethystBackup"

private enum class QuickStartSteamImportMode {
    AUTHENTICATED,
    ANONYMOUS,
}

@StringRes
private fun TouchMouseInteractionMode.displayNameResId(): Int {
    return when (this) {
        TouchMouseInteractionMode.OPEN_MENU_ON_TAP ->
            R.string.settings_touch_mouse_interaction_mode_open_menu
        TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP ->
            R.string.settings_touch_mouse_interaction_mode_toggle_button
    }
}

@StringRes
private fun CardPlayOptimizationMode.displayNameResId(): Int {
    return when (this) {
        CardPlayOptimizationMode.RELEASE_POP_BACK ->
            R.string.settings_card_play_optimization_release_pop_back
        CardPlayOptimizationMode.RELEASE_KEEP_OPEN ->
            R.string.settings_card_play_optimization_release_keep_open
        CardPlayOptimizationMode.TAP_CARD_THEN_TARGET ->
            R.string.settings_card_play_optimization_tap_then_target
        CardPlayOptimizationMode.VANILLA ->
            R.string.settings_card_play_optimization_vanilla
    }
}

@StringRes
private fun SpecialKeyInputMode.displayNameResId(): Int {
    return when (this) {
        SpecialKeyInputMode.LEGACY_FLOATING_WINDOW ->
            R.string.settings_special_key_input_mode_legacy_floating_window
        SpecialKeyInputMode.BUILT_IN_MOD ->
            R.string.settings_special_key_input_mode_built_in_mod
        SpecialKeyInputMode.DISABLED ->
            R.string.settings_special_key_input_mode_disabled
    }
}

@Stable
@SuppressLint("SuspiciousIndentation")
class SettingsScreenViewModel : ViewModel() {
    sealed interface Effect {
        data object OpenImportJarPicker : Effect
        data object OpenImportModsPicker : Effect
        data object OpenImportSavesPicker : Effect
        data class OpenExportModsPicker(val fileName: String) : Effect
        data class OpenExportSavesPicker(val fileName: String) : Effect
        data class OpenExportLogsPicker(val fileName: String) : Effect
        data class OpenBootOverlayImagePicker(val slot: BootOverlayImageSlot) : Effect
        data class ShareJvmLogsBundle(val payload: JvmLogsSharePayload) : Effect
        data object OpenCompatibility : Effect
        data object OpenMobileGluesSettings : Effect
        data object OpenFeedback : Effect
    }

    data class UpdateDownloadOptionState(
        val source: UpdateSource,
        val label: String,
        val url: String,
    )

    data class UpdatePromptState(
        val currentVersion: String,
        val latestVersion: String,
        val publishedAtText: String,
        val downloadSourceDisplayName: String,
        val notesText: String,
        val downloadOptions: List<UpdateDownloadOptionState>,
        val defaultDownloadSourceId: String,
    )

    data class UpdateHistoryEntryState(
        val version: String,
        val publishedAtText: String,
        val notesText: String,
    )

    data class UpdateHistoryDialogState(
        val metadataSourceDisplayName: String,
        val entries: List<UpdateHistoryEntryState>,
    )

    data class CloudControlConfigDialogState(
        val sourceDisplayName: String,
        val requestUrl: String,
        val rawText: String,
    )

    data class StsJarIntegrityDialogState(
        val displayName: String,
        val expectedSha1: String,
        val actualSha1: String,
        val forceConfirmationVisible: Boolean = false,
    )

    data class RendererBackendOptionState(
        val backend: RendererBackend,
        val available: Boolean,
        val reasonText: String? = null
    )

    data class UiState(
        val busy: Boolean = false,
        val busyOperation: UiBusyOperation = UiBusyOperation.NONE,
        val busyMessage: UiText? = null,
        val busyProgressPercent: Int? = null,
        val quickStartSteamDownloadPhase: SteamStsJarDownloadPhase? = null,
        val quickStartSteamDownloadedBytes: Long = 0L,
        val quickStartSteamTotalBytes: Long? = null,
        val quickStartSteamAccelerationSwitchEnabled: Boolean = true,
        val quickStartSteamPaused: Boolean = false,
        val quickStartSteamFailed: Boolean = false,
        val quickStartSteamFailureMessage: UiText? = null,
        val quickStartAutomaticImportRequiresLogin: Boolean = false,
        val playerName: String = LauncherPreferences.DEFAULT_PLAYER_NAME,
        val selectedRenderScale: Float = RenderScaleService.DEFAULT_RENDER_SCALE,
        val selectedTargetFps: Int = LauncherPreferences.DEFAULT_TARGET_FPS,
        val virtualResolutionMode: VirtualResolutionMode =
            LauncherPreferences.DEFAULT_VIRTUAL_RESOLUTION_MODE,
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
        val gpuResourceGuardianMode: GpuResourceGuardianMode =
            LauncherPreferences.DEFAULT_GPU_RESOURCE_GUARDIAN_MODE,
        val gpuResourceGuardianPressureDownscaleEnabled: Boolean =
            LauncherPreferences.DEFAULT_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE_ENABLED,
        val themeMode: LauncherThemeMode = LauncherPreferences.DEFAULT_THEME_MODE,
        val themeColor: LauncherThemeColor = LauncherPreferences.DEFAULT_THEME_COLOR,
        val launcherIconMode: LauncherIconMode = LauncherPreferences.DEFAULT_LAUNCHER_ICON_MODE,
        val chromeBackgroundOpacity: Float =
            LauncherPreferences.DEFAULT_CHROME_BACKGROUND_OPACITY,
        val bootOverlayStyle: BootOverlayStyle =
            LauncherPreferences.DEFAULT_BOOT_OVERLAY_STYLE,
        val bootOverlayAnimation: BootOverlayAnimation =
            LauncherPreferences.DEFAULT_BOOT_OVERLAY_ANIMATION,
        val bootOverlayImageConfig: BootOverlayImageConfig = BootOverlayImageConfig(
            mode = LauncherPreferences.DEFAULT_BOOT_OVERLAY_IMAGE_MODE
        ),
        val selectedJvmHeapMaxMb: Int = LauncherPreferences.DEFAULT_JVM_HEAP_MAX_MB,
        val compressedPointersEnabled: Boolean = LauncherPreferences.DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED,
        val stringDeduplicationEnabled: Boolean =
            LauncherPreferences.DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED,
        val jvmHeapMinMb: Int = LauncherPreferences.MIN_JVM_HEAP_MAX_MB,
        val jvmHeapMaxMb: Int = LauncherPreferences.MAX_JVM_HEAP_MAX_MB,
        val jvmHeapStepMb: Int = LauncherPreferences.JVM_HEAP_STEP_MB,
        val backBehavior: BackBehavior = LauncherPreferences.DEFAULT_BACK_BEHAVIOR,
        val manualDismissBootOverlay: Boolean = LauncherPreferences.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY,
        val specialKeyInputMode: SpecialKeyInputMode =
            LauncherPreferences.DEFAULT_SPECIAL_KEY_INPUT_MODE,
        val showFloatingMouseWindow: Boolean = LauncherPreferences.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW,
        val touchMouseInteractionMode: TouchMouseInteractionMode =
            LauncherPreferences.DEFAULT_TOUCH_MOUSE_INTERACTION_MODE,
        val touchDoubleClickAsRightClick: Boolean =
            LauncherPreferences.DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK,
        val ignoreLongPressRightClickWhilePlayingCard: Boolean =
            LauncherPreferences.DEFAULT_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD,
        val builtInSoftKeyboardEnabled: Boolean =
            LauncherPreferences.DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED,
        val hapticFeedbackEnabled: Boolean = LauncherPreferences.DEFAULT_HAPTIC_FEEDBACK_ENABLED,
        val autoSwitchLeftAfterRightClick: Boolean = LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val mobileHudEnabled: Boolean = LauncherPreferences.DEFAULT_MOBILE_HUD_ENABLED,
        val compendiumUpgradeTouchFixEnabled: Boolean =
            LauncherPreferences.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED,
        val avoidDisplayCutout: Boolean = LauncherPreferences.DEFAULT_AVOID_DISPLAY_CUTOUT,
        val cropScreenBottom: Boolean = LauncherPreferences.DEFAULT_CROP_SCREEN_BOTTOM,
        val ramSaverEnabled: Boolean = LauncherPreferences.DEFAULT_RAM_SAVER_ENABLED,
        val mtsPatchCacheEnabled: Boolean = LauncherPreferences.DEFAULT_MTS_PATCH_CACHE_ENABLED,
        val keepScreenOnTimeoutMinutes: Int =
            LauncherPreferences.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
        val showGamePerformanceOverlay: Boolean = LauncherPreferences.DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY,
        val sustainedPerformanceModeEnabled: Boolean =
            LauncherPreferences.DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED,
        val systemGameModeDisplayName: String = "",
        val systemGameModeDescription: String = "",
        val lwjglDebugEnabled: Boolean = LauncherPreferences.DEFAULT_LWJGL_DEBUG,
        val preloadAllJreLibrariesEnabled: Boolean =
            LauncherPreferences.DEFAULT_PRELOAD_ALL_JRE_LIBRARIES,
        val logcatCaptureEnabled: Boolean = LauncherPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED,
        val launcherLogcatCaptureEnabled: Boolean =
            LauncherPreferences.DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED,
        val jvmLogcatMirrorEnabled: Boolean = LauncherPreferences.DEFAULT_JVM_LOGCAT_MIRROR_ENABLED,
        val gpuResourceDiagEnabled: Boolean = LauncherPreferences.DEFAULT_GPU_RESOURCE_DIAG_ENABLED,
        val gdxPadCursorDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GDX_PAD_CURSOR_DEBUG,
        val glBridgeSwapHeartbeatDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG,
        val touchscreenInputMode: TouchscreenInputMode =
            GameplaySettingsService.DEFAULT_TOUCHSCREEN_INPUT_MODE,
        val cardPlayOptimizationMode: CardPlayOptimizationMode =
            GameplaySettingsService.DEFAULT_CARD_PLAY_OPTIMIZATION_MODE,
        val touchIndicatorEnabled: Boolean = GameplaySettingsService.DEFAULT_TOUCH_INDICATOR_ENABLED,
        val gameplayFontScale: Float = GameplaySettingsService.DEFAULT_FONT_SCALE,
        val gameplayLargerUiEnabled: Boolean = GameplaySettingsService.DEFAULT_LARGER_UI_ENABLED,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Int> = LauncherPreferences.TARGET_FPS_OPTIONS.toList(),
        val keepScreenOnTimeoutMinuteOptions: List<Int> =
            LauncherPreferences.KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS.toList(),
        val autoCheckUpdatesEnabled: Boolean = LauncherPreferences.DEFAULT_AUTO_CHECK_UPDATES_ENABLED,
        val preferredUpdateMirror: UpdateSource = UpdateSource.DEFAULT_PREFERRED_USER_SOURCE,
        val availableUpdateMirrors: List<UpdateSource> = UpdateMirrorManager.selectableSources(),
        val currentVersionText: String = BuildConfig.VERSION_NAME,
        val updateStatusSummary: String = "",
        val updateCheckInProgress: Boolean = false,
        val availableUpdatePromptState: UpdatePromptState? = null,
        val updatePromptState: UpdatePromptState? = null,
        val releaseHistoryLoading: Boolean = false,
        val releaseHistoryDialogState: UpdateHistoryDialogState? = null,
        val cloudControlConfigLoading: Boolean = false,
        val cloudControlConfigDialogState: CloudControlConfigDialogState? = null,
        val stsJarIntegrityDialogState: StsJarIntegrityDialogState? = null,
        val nativeLibraryMarketPackages: List<NativeLibraryMarketPackageState> = emptyList(),
        val nativeLibraryMarketLoading: Boolean = false,
        val nativeLibraryMarketErrorText: String? = null,
        val steamCloudAccountName: String = "",
        val steamCloudRefreshTokenConfigured: Boolean = false,
        val steamCloudGuardDataConfigured: Boolean = false,
        val steamCloudPersonaName: String = "",
        val steamCloudAvatarUrl: String = "",
        val steamCloudSaveMode: SteamCloudSaveMode = LauncherPreferences.DEFAULT_STEAM_CLOUD_SAVE_MODE,
        val steamCloudSyncBlacklistPaths: Set<String> =
            LauncherPreferences.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
        val steamCloudSyncBlacklistCandidates: List<String> = emptyList(),
        val steamCloudWattAccelerationEnabled: Boolean =
            LauncherPreferences.DEFAULT_STEAM_CLOUD_WATT_ACCELERATION_ENABLED,
        val steamCloudAutoLaunchAfterSyncEnabled: Boolean =
            LauncherPreferences.DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED,
        val workshopMaxConcurrentDownloads: Int =
            LauncherPreferences.DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS,
        val workshopDownloadThreads: Int = LauncherPreferences.DEFAULT_WORKSHOP_DOWNLOAD_THREADS,
        val workshopWattAccelerationEnabled: Boolean =
            LauncherPreferences.DEFAULT_WORKSHOP_WATT_ACCELERATION_ENABLED,
        val workshopSteamLanguage: SteamLanguagePreference =
            LauncherPreferences.DEFAULT_WORKSHOP_STEAM_LANGUAGE,
        val workshopAutoImportEnabled: Boolean =
            LauncherPreferences.DEFAULT_WORKSHOP_AUTO_IMPORT_ENABLED,
        val workshopAutoImportAtlasDownscaleEnabled: Boolean =
            LauncherPreferences.DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED,
        val workshopAutoImportAtlasDownscaleMaxEdgePx: Int =
            LauncherPreferences.DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX,
        val baiduTranslationCredentialsConfigured: Boolean = false,
        val steamCloudCredentialsSummary: String = "",
        val steamCloudStatusText: String = "",
        val steamCloudManifestSummary: String = "",
        val steamCloudManifestAvailable: Boolean = false,
        val steamCloudManifestDialogSnapshot: SteamCloudManifestSnapshot? = null,
        val steamCloudUploadPlanDialogSnapshot: SteamCloudUploadPlan? = null,
        val steamCloudUploadConfirmPlan: SteamCloudUploadPlan? = null,
        val steamCloudLoginChallenge: SteamCloudLoginChallenge? = null,
        val steamCloudPhase0AccountName: String = "",
        val steamCloudPhase0RefreshTokenConfigured: Boolean = false,
        val steamCloudPhase0ProxyUrl: String = "",
        val steamCloudPhase0CredentialsSummary: String = "",
        val steamCloudPhase0StatusText: String = "",
    )

    private data class CoreDependencyStatus(
        val label: String,
        val available: Boolean,
        val source: String,
        val version: String
    )

    private data class PendingStsJarImport(
        val stagedFile: File,
        val displayName: String,
        val showSuccessToast: Boolean,
        val onCompleted: ((Boolean) -> Unit)?,
    )

    private data class DeviceRuntimeStatus(
        val cpuModel: String,
        val cpuArch: String,
        val availableMemoryBytes: Long,
        val totalMemoryBytes: Long
    )

    private class PauseController {
        private val lock = ReentrantLock()
        private val resumed: Condition = lock.newCondition()
        @Volatile
        var paused: Boolean = false
            private set

        fun pause() {
            lock.withLock {
                paused = true
            }
        }

        fun resume() {
            lock.withLock {
                paused = false
                resumed.signalAll()
            }
        }

        fun awaitIfPaused() {
            lock.withLock {
                while (paused) {
                    resumed.await()
                }
            }
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    private var nativeLibraryMarketCatalog: List<NativeLibraryMarketCatalogEntry> = emptyList()
    private var pendingSteamCloudCodeFuture: CompletableFuture<String>? = null
    private var pendingSteamCloudConfirmationFuture: CompletableFuture<Boolean>? = null
    private var pendingSteamCloudLoginTask: Future<*>? = null
    private var pendingSteamCloudLoginCancellationHandle: SteamCloudAuthCoordinator.CancellationHandle? = null
    private var steamCloudLoginGeneration: Long = 0L
    private var quickStartSteamImportTask: Future<*>? = null
    private var quickStartSteamImportCompletion: ((Boolean) -> Unit)? = null
    private var quickStartSteamPauseController: PauseController? = null
    private var quickStartSteamImportMode: QuickStartSteamImportMode = QuickStartSteamImportMode.AUTHENTICATED
    private var pendingStsJarImport: PendingStsJarImport? = null
    @Volatile
    private var quickStartSteamImportGeneration: Int = 0

    private val automaticallyPromptedUpdateVersions = LinkedHashSet<String>()

    var uiState by mutableStateOf(UiState())
        private set

    val effects = _effects.asSharedFlow()

    fun bind(
        activity: Activity,
        targetFpsOptions: IntArray = LauncherPreferences.TARGET_FPS_OPTIONS,
        keepScreenOnTimeoutMinuteOptions: IntArray =
            LauncherPreferences.KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS
    ) {
        syncThemeAppearance(activity)
        val options = targetFpsOptions.toList()
        if (uiState.targetFpsOptions != options) {
            uiState = uiState.copy(targetFpsOptions = options)
        }
        val keepScreenOnOptions = keepScreenOnTimeoutMinuteOptions.toList()
        if (uiState.keepScreenOnTimeoutMinuteOptions != keepScreenOnOptions) {
            uiState = uiState.copy(keepScreenOnTimeoutMinuteOptions = keepScreenOnOptions)
        }
        syncStoredUpdateState(activity)
        runCatching {
            SettingsRepository.loadSettingsSnapshot(activity)
        }.getOrNull()?.let { snapshot ->
            applySnapshot(activity, snapshot)
        }
        refreshStatus(activity, clearBusy = false)
    }

    fun startGameReturnAutoUpdateCheck(host: Activity) {
        startAutomaticUpdateCheck(host)
    }

    fun startMainScreenAutoUpdateCheck(host: Activity) {
        startAutomaticUpdateCheck(host)
    }

    fun onAutoCheckUpdatesChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setAutoCheckUpdatesEnabled(host, enabled)
        syncStoredUpdateState(host)
    }

    fun syncThemeAppearance(host: Activity) {
        uiState = uiState.copy(
            themeMode = SettingsRepository.loadThemeMode(host),
            themeColor = SettingsRepository.loadThemeColor(host),
            launcherIconMode = SettingsRepository.loadLauncherIconMode(host),
            chromeBackgroundOpacity = SettingsRepository.loadChromeBackgroundOpacity(host),
            bootOverlayStyle = SettingsRepository.loadBootOverlayStyle(host),
            bootOverlayAnimation = SettingsRepository.loadBootOverlayAnimation(host),
            bootOverlayImageConfig = SettingsRepository.loadBootOverlayImageConfig(host)
        )
    }

    fun onThemeModeChanged(host: Activity, themeMode: LauncherThemeMode) {
        saveThemeModeSelection(host, themeMode)
        syncThemeAppearance(host)
    }

    fun onThemeColorChanged(host: Activity, themeColor: LauncherThemeColor) {
        saveThemeColorSelection(host, themeColor)
        syncThemeAppearance(host)
    }

    fun onLauncherIconModeChanged(host: Activity, iconMode: LauncherIconMode) {
        if (uiState.busy || uiState.launcherIconMode == iconMode) {
            return
        }
        saveLauncherIconModeSelection(host, iconMode)
        syncThemeAppearance(host)
    }

    fun onChromeBackgroundOpacityChanged(host: Activity, opacity: Float) {
        val normalized = LauncherPreferences.normalizeChromeBackgroundOpacity(opacity)
        if (uiState.busy || uiState.chromeBackgroundOpacity == normalized) {
            return
        }
        uiState = uiState.copy(chromeBackgroundOpacity = normalized)
        saveChromeBackgroundOpacitySelection(host, normalized)
    }

    fun onBootOverlayAnimationChanged(host: Activity, animation: BootOverlayAnimation) {
        if (uiState.busy || uiState.bootOverlayAnimation == animation) {
            return
        }
        uiState = uiState.copy(bootOverlayAnimation = animation)
        saveBootOverlayAnimationSelection(host, animation)
        refreshStatus(host)
    }

    fun onBootOverlayStyleChanged(host: Activity, style: BootOverlayStyle) {
        if (uiState.busy || uiState.bootOverlayStyle == style) {
            return
        }
        uiState = uiState.copy(bootOverlayStyle = style)
        saveBootOverlayStyleSelection(host, style)
        refreshStatus(host)
    }

    fun onBootOverlayImageModeChanged(host: Activity, mode: BootOverlayImageMode) {
        if (uiState.busy || uiState.bootOverlayImageConfig.mode == mode) {
            return
        }
        val nextConfig = BootOverlayImageService.saveMode(host, mode)
        uiState = uiState.copy(bootOverlayImageConfig = nextConfig)
        refreshStatus(host)
    }

    fun onPickBootOverlayImage(slot: BootOverlayImageSlot) {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(Effect.OpenBootOverlayImagePicker(slot))
    }

    fun onBootOverlayImagePicked(host: Activity, slot: BootOverlayImageSlot, uri: Uri?) {
        if (uiState.busy || uri == null) {
            return
        }
        try {
            val nextConfig = BootOverlayImageService.importImage(host, slot, uri)
            uiState = uiState.copy(bootOverlayImageConfig = nextConfig)
            showToast(host, UiText.StringResource(R.string.settings_boot_overlay_custom_image_saved), Toast.LENGTH_SHORT)
            refreshStatus(host)
        } catch (error: Throwable) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_boot_overlay_custom_image_failed,
                    error.message ?: error.javaClass.simpleName
                ),
                Toast.LENGTH_LONG
            )
            refreshStatus(host)
        }
    }

    fun onBootOverlayImageCropFailed(host: Activity, error: Throwable) {
        if (uiState.busy) {
            return
        }
        showToast(
            host,
            UiText.StringResource(
                R.string.settings_boot_overlay_custom_image_failed,
                error.message ?: error.javaClass.simpleName
            ),
            Toast.LENGTH_LONG
        )
        refreshStatus(host)
    }

    fun onResetBootOverlayImages(host: Activity) {
        if (uiState.busy) {
            return
        }
        val nextConfig = BootOverlayImageService.reset(host)
        uiState = uiState.copy(bootOverlayImageConfig = nextConfig)
        showToast(host, UiText.StringResource(R.string.settings_boot_overlay_custom_image_reset), Toast.LENGTH_SHORT)
        refreshStatus(host)
    }

    fun onPreferredUpdateMirrorChanged(host: Activity, source: UpdateSource) {
        if (!source.userSelectable) {
            return
        }
        UpdateMirrorManager.saveCurrent(host, source)
        nativeLibraryMarketCatalog = emptyList()
        uiState = uiState.copy(
            nativeLibraryMarketPackages = emptyList(),
            nativeLibraryMarketErrorText = null
        )
        syncStoredUpdateState(host)
    }

    fun onManualCheckUpdates(host: Activity) {
        if (uiState.updateCheckInProgress || uiState.releaseHistoryLoading || uiState.busy) {
            return
        }
        runUpdateCheck(host, userInitiated = true)
    }

    fun dismissUpdatePrompt() {
        if (uiState.updatePromptState != null) {
            uiState = uiState.copy(updatePromptState = null)
        }
    }

    fun showUpdatePrompt() {
        val promptState = uiState.availableUpdatePromptState ?: return
        uiState = uiState.copy(updatePromptState = promptState)
    }

    fun onOpenReleaseHistory(host: Activity) {
        if (uiState.busy || uiState.updateCheckInProgress || uiState.releaseHistoryLoading) {
            return
        }
        uiState = uiState.copy(releaseHistoryLoading = true)
        val preferredSource = resolveSelectedMirrorSource(host)
        executor.execute {
            try {
                val result = LauncherUpdateService.fetchReleaseHistory(host, preferredSource)
                host.runOnUiThread {
                    uiState = uiState.copy(
                        releaseHistoryLoading = false,
                        releaseHistoryDialogState = buildUpdateHistoryDialogState(host, result)
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    uiState = uiState.copy(releaseHistoryLoading = false)
                    showToast(
                        host,
                        host.getString(
                            R.string.update_history_load_failed,
                            GithubMirrorFallback.summarize(error)
                        ),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    fun dismissReleaseHistoryDialog() {
        if (uiState.releaseHistoryDialogState != null) {
            uiState = uiState.copy(releaseHistoryDialogState = null)
        }
    }

    fun onOpenCloudControlConfig(host: Activity) {
        if (uiState.busy || uiState.cloudControlConfigLoading) {
            return
        }
        uiState = uiState.copy(cloudControlConfigLoading = true)
        executor.execute {
            try {
                val remoteConfig = CloudControlConfig.fetchRemoteConfigText(host)
                host.runOnUiThread {
                    uiState = uiState.copy(
                        cloudControlConfigLoading = false,
                        cloudControlConfigDialogState = CloudControlConfigDialogState(
                            sourceDisplayName = remoteConfig.sourceDisplayName,
                            requestUrl = remoteConfig.requestUrl,
                            rawText = remoteConfig.rawText
                        )
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    uiState = uiState.copy(cloudControlConfigLoading = false)
                    showToast(
                        host,
                        host.getString(
                            R.string.settings_developer_cloud_control_load_failed,
                            GithubMirrorFallback.summarize(error)
                        ),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }
    }

    fun dismissCloudControlConfigDialog() {
        if (uiState.cloudControlConfigDialogState != null) {
            uiState = uiState.copy(cloudControlConfigDialogState = null)
        }
    }

    fun dismissStsJarIntegrityDialog() {
        clearPendingStsJarImport(notifyCancelled = true)
    }

    fun requestStsJarForceImportConfirmation() {
        val dialogState = uiState.stsJarIntegrityDialogState ?: return
        uiState = uiState.copy(
            stsJarIntegrityDialogState = dialogState.copy(forceConfirmationVisible = true)
        )
    }

    fun dismissStsJarForceImportConfirmation() {
        val dialogState = uiState.stsJarIntegrityDialogState ?: return
        if (!dialogState.forceConfirmationVisible) {
            return
        }
        uiState = uiState.copy(
            stsJarIntegrityDialogState = dialogState.copy(forceConfirmationVisible = false)
        )
    }

    fun confirmPendingStsJarForceImport(host: Activity) {
        val pendingImport = pendingStsJarImport ?: return
        pendingStsJarImport = null
        uiState = uiState.copy(stsJarIntegrityDialogState = null)
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.sts_jar_import_busy),
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            importPreparedLocalStsJar(
                host = host,
                sourceFile = pendingImport.stagedFile,
                showSuccessToast = pendingImport.showSuccessToast,
                onCompleted = pendingImport.onCompleted,
            )
        }
    }

    fun onOpenNativeLibraryMarket(host: Activity) {
        if (uiState.nativeLibraryMarketPackages.isEmpty() && !uiState.nativeLibraryMarketLoading) {
            refreshNativeLibraryMarket(host)
        }
    }

    fun refreshNativeLibraryMarket(host: Activity) {
        if (uiState.nativeLibraryMarketLoading) {
            return
        }
        val mirrorSource = resolveSelectedMirrorSource(host)
        uiState = uiState.copy(
            nativeLibraryMarketLoading = true,
            nativeLibraryMarketErrorText = null
        )
        executor.execute {
            try {
                val catalog = NativeLibraryMarketService.fetchCatalog(host, mirrorSource)
                nativeLibraryMarketCatalog = catalog
                val packageStates = NativeLibraryMarketService.resolvePackageStates(host, catalog)
                host.runOnUiThread {
                    uiState = uiState.copy(
                        nativeLibraryMarketPackages = packageStates,
                        nativeLibraryMarketLoading = false,
                        nativeLibraryMarketErrorText = null
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    uiState = uiState.copy(
                        nativeLibraryMarketLoading = false,
                        nativeLibraryMarketErrorText = summarizeNativeLibraryMarketError(
                            host,
                            error,
                            mirrorSource
                        )
                    )
                }
            }
        }
    }

    fun onInstallNativeLibraryPackage(host: Activity, packageId: String) {
        if (uiState.busy) {
            return
        }
        val entry = nativeLibraryMarketCatalog.firstOrNull { it.id == packageId } ?: return
        val packageState = uiState.nativeLibraryMarketPackages.firstOrNull {
            it.catalogEntry.id == packageId
        } ?: return
        if (packageState.availability != NativeLibraryMarketAvailability.NOT_INSTALLED) {
            return
        }

        setBusy(
            true,
            UiText.StringResource(R.string.settings_native_library_market_installing, entry.displayName),
            operation = UiBusyOperation.NATIVE_LIBRARY_INSTALL
        )
        val mirrorSource = resolveSelectedMirrorSource(host)
        executor.execute {
            try {
                NativeLibraryMarketService.installPackage(host, entry, mirrorSource) { progress ->
                    host.runOnUiThread {
                        if (!uiState.busy ||
                            uiState.busyOperation != UiBusyOperation.NATIVE_LIBRARY_INSTALL
                        ) {
                            return@runOnUiThread
                        }
                        setBusy(
                            true,
                            UiText.DynamicString(
                                buildNativeLibraryInstallProgressMessage(host, progress)
                            ),
                            operation = UiBusyOperation.NATIVE_LIBRARY_INSTALL,
                            progressPercent = progress.progressPercent
                        )
                    }
                }
                val packageStates = NativeLibraryMarketService.resolvePackageStates(
                    host,
                    nativeLibraryMarketCatalog
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    uiState = uiState.copy(
                        nativeLibraryMarketPackages = packageStates,
                        nativeLibraryMarketErrorText = null
                    )
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_native_library_market_installed,
                            entry.displayName
                        )
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    val summary = summarizeNativeLibraryMarketError(host, error, mirrorSource)
                    uiState = uiState.copy(nativeLibraryMarketErrorText = summary)
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_native_library_market_install_failed,
                            entry.displayName,
                            summary
                        )
                    )
                }
            }
        }
    }

    fun onRemoveNativeLibraryPackage(host: Activity, packageId: String) {
        if (uiState.busy) {
            return
        }
        val entry = nativeLibraryMarketCatalog.firstOrNull { it.id == packageId } ?: return
        val packageState = uiState.nativeLibraryMarketPackages.firstOrNull {
            it.catalogEntry.id == packageId
        } ?: return
        if (packageState.availability != NativeLibraryMarketAvailability.INSTALLED) {
            return
        }

        setBusy(
            true,
            UiText.StringResource(R.string.settings_native_library_market_removing, entry.displayName)
        )
        executor.execute {
            try {
                NativeLibraryMarketService.uninstallPackage(host, entry.id)
                val packageStates = NativeLibraryMarketService.resolvePackageStates(
                    host,
                    nativeLibraryMarketCatalog
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    uiState = uiState.copy(
                        nativeLibraryMarketPackages = packageStates,
                        nativeLibraryMarketErrorText = null
                    )
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_native_library_market_removed,
                            entry.displayName
                        )
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    val summary = summarizeNativeLibraryMarketError(host, error)
                    uiState = uiState.copy(nativeLibraryMarketErrorText = summary)
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_native_library_market_remove_failed,
                            entry.displayName,
                            summary
                        )
                    )
                }
            }
        }
    }

    private fun runUpdateCheck(host: Activity, userInitiated: Boolean) {
        if (uiState.updateCheckInProgress) {
            return
        }
        uiState = uiState.copy(updateCheckInProgress = true)
        val preferredSource = resolveSelectedMirrorSource(host)
        executor.execute {
            val result = LauncherUpdateService.checkForUpdates(
                context = host,
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
                    showToast(host, toastMessage, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun handleUpdateCheckSuccess(
        host: Activity,
        result: UpdateCheckExecutionResult.Success,
        userInitiated: Boolean,
    ): String? {
        val suppressAutomaticPrompt = result.hasUpdate &&
            !userInitiated &&
            automaticallyPromptedUpdateVersions.contains(result.release.normalizedVersion)
        val decision = LauncherUpdateUiReducer.reduce(
            result = result,
            userInitiated = userInitiated,
            suppressAutomaticPrompt = suppressAutomaticPrompt
        )
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
        val promptState = if (result.hasUpdate) {
            buildUpdatePromptState(host, result.release, result.downloadResolution)
        } else {
            null
        }
        syncStoredUpdateState(
            host = host,
            updateCheckInProgress = false,
            availableUpdatePromptState = promptState,
            updatePromptState = if (decision.showPrompt) promptState else null
        )
        if (decision.showPrompt && result.hasUpdate && !userInitiated) {
            automaticallyPromptedUpdateVersions += result.release.normalizedVersion
        }
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
        val downloadOptions = UpdateSource.oneShotDownloadSelectionSources(
            primarySource = resolvedDownload.source,
            bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(host),
        )
            .map { source ->
                UpdateDownloadOptionState(
                    source = source,
                    label = if (source == UpdateSource.OFFICIAL) {
                        host.getString(R.string.update_dialog_download_option_direct)
                    } else {
                        source.displayName
                    },
                    url = source.buildUrl(release.assetDownloadUrl)
                )
            }
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
            downloadOptions = downloadOptions,
            defaultDownloadSourceId = resolvedDownload.source.id
        )
    }

    private fun buildUpdateHistoryDialogState(
        host: Activity,
        result: UpdateReleaseHistoryResult,
    ): UpdateHistoryDialogState {
        return UpdateHistoryDialogState(
            metadataSourceDisplayName = result.metadataSource.displayName,
            entries = result.entries.map { entry ->
                buildUpdateHistoryEntryState(host, entry)
            }
        )
    }

    private fun buildUpdateHistoryEntryState(
        host: Activity,
        entry: UpdateReleaseHistoryEntry,
    ): UpdateHistoryEntryState {
        return UpdateHistoryEntryState(
            version = entry.normalizedVersion,
            publishedAtText = entry.publishedAtDisplayText.ifBlank {
                host.getString(R.string.update_unknown_date)
            },
            notesText = entry.notesText.ifBlank {
                host.getString(R.string.update_dialog_notes_empty)
            }
        )
    }

    private fun resolveSelectedMirrorSource(host: Activity): UpdateSource {
        return UpdateMirrorManager.current(host)
    }

    private fun syncStoredUpdateState(
        host: Activity,
        updateCheckInProgress: Boolean = uiState.updateCheckInProgress,
        availableUpdatePromptState: UpdatePromptState? = uiState.availableUpdatePromptState,
        updatePromptState: UpdatePromptState? = uiState.updatePromptState,
        releaseHistoryLoading: Boolean = uiState.releaseHistoryLoading,
        releaseHistoryDialogState: UpdateHistoryDialogState? = uiState.releaseHistoryDialogState,
    ) {
        val snapshot = SettingsRepository.loadUpdateStateSnapshot(host)
        uiState = uiState.copy(
            autoCheckUpdatesEnabled = snapshot.autoCheckUpdatesEnabled,
            preferredUpdateMirror = snapshot.preferredUpdateMirror,
            availableUpdateMirrors = snapshot.availableUpdateMirrors,
            currentVersionText = snapshot.currentVersionText,
            updateStatusSummary = snapshot.statusSummary,
            updateCheckInProgress = updateCheckInProgress,
            availableUpdatePromptState = availableUpdatePromptState,
            updatePromptState = updatePromptState,
            releaseHistoryLoading = releaseHistoryLoading,
            releaseHistoryDialogState = releaseHistoryDialogState
        )
    }

    private fun hasValidImportedStsJar(host: Activity): Boolean {
        return StsJarValidator.isValid(RuntimePaths.importedStsJar(host))
    }

    fun refreshStatus(host: Activity, clearBusy: Boolean = true) {
        executor.execute {
            try {
                val hasJar = hasValidImportedStsJar(host)
                val snapshot = SettingsRepository.loadSettingsSnapshot(host)
                val rendering = snapshot.rendering
                val jvm = snapshot.jvm
                val input = snapshot.input
                val diagnostics = snapshot.diagnostics
                val compatibility = snapshot.compatibility
                val rendererDecision = rendering.rendererDecision
                val mobileGluesSettings = rendering.mobileGluesSettings
                val rendererBackendOptions = rendererDecision.availableBackends.map { availability ->
                    RendererBackendOptionState(
                        backend = availability.backend,
                        available = availability.available,
                        reasonText = availability.describeUnavailable(host)
                    )
                }

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
                val coreRuntimeCompatStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "Amethyst Runtime Compat",
                    importedJar = RuntimePaths.importedAmethystRuntimeCompatJar(host),
                    bundledAssetPath = "components/mods/AmethystRuntimeCompat.jar"
                )
                val coreFloatingToolsStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "Amethyst Floating Tools",
                    importedJar = RuntimePaths.importedAmethystFloatingToolsJar(host),
                    bundledAssetPath = "components/mods/AmethystFloatingTools.jar"
                )
                val coreRamSaverStatus = resolveCoreDependencyStatus(
                    host = host,
                    label = "Ram Saver",
                    importedJar = RuntimePaths.importedRamSaverJar(host),
                    bundledAssetPath = "components/mods/RamSaver.jar"
                )
                val deviceRuntimeStatus = collectDeviceRuntimeStatus(host)
                val steamCloudAuthSnapshot = runCatching {
                    SteamCloudAuthStore.readSnapshot(host)
                }.getOrElse { error ->
                    SteamCloudAuthStore.AuthSnapshot(
                        accountName = "",
                        refreshTokenConfigured = false,
                        guardDataConfigured = false,
                        steamId64 = "",
                        personaName = "",
                        avatarUrl = "",
                        lastAuthAtMs = null,
                        lastManifestAtMs = null,
                        lastPullAtMs = null,
                        lastPushAtMs = null,
                        lastError = summarizeSteamCloudError(host, error)
                    )
                }
                val steamCloudManifestSnapshot = runCatching {
                    SteamCloudManifestStore.readSnapshot(host)
                }.getOrNull()
                val steamCloudBaselineSnapshot = runCatching {
                    SteamCloudBaselineStore.readSnapshot(host)
                }.getOrNull()
                val steamCloudPhase0Snapshot = SteamCloudPhase0Store.readSnapshot(host)
                var steamCloudSaveMode = LauncherPreferences.readSteamCloudSaveMode(host)
                val steamCloudSyncBlacklistPaths =
                    LauncherPreferences.readSteamCloudSyncBlacklistPaths(host)
                val steamCloudSyncBlacklistCandidates =
                    runCatching {
                        SteamCloudSyncBlacklist.listSelectableLocalRelativePaths(
                            stsRoot = RuntimePaths.stsRoot(host),
                            configuredBlacklist = steamCloudSyncBlacklistPaths,
                        )
                    }.getOrElse {
                        steamCloudSyncBlacklistPaths.toList().sorted()
                    }
                if (!steamCloudAuthSnapshot.isComplete &&
                    steamCloudSaveMode == SteamCloudSaveMode.STEAM_CLOUD
                ) {
                    runCatching {
                        SteamCloudSaveProfileManager.switchMode(
                            context = host,
                            fromMode = SteamCloudSaveMode.STEAM_CLOUD,
                            toMode = SteamCloudSaveMode.INDEPENDENT,
                        )
                        LauncherPreferences.saveSteamCloudSaveMode(
                            host,
                            SteamCloudSaveMode.INDEPENDENT,
                        )
                        steamCloudSaveMode = SteamCloudSaveMode.INDEPENDENT
                    }
                }

                val status = buildStatusText(
                    host = host,
                    snapshot = snapshot,
                    hasJar = hasJar,
                    optionalEnabled = optionalEnabled,
                    optionalTotal = optionalTotal,
                    coreMtsStatus = coreMtsStatus,
                    coreBaseModStatus = coreBaseModStatus,
                    coreStsLibStatus = coreStsLibStatus,
                    coreRuntimeCompatStatus = coreRuntimeCompatStatus,
                    coreFloatingToolsStatus = coreFloatingToolsStatus,
                    coreRamSaverStatus = coreRamSaverStatus,
                    deviceRuntimeStatus = deviceRuntimeStatus
                )
                val steamCloudStatusText = buildSteamCloudStatusText(
                    host,
                    steamCloudAuthSnapshot,
                    steamCloudManifestSnapshot,
                    steamCloudBaselineSnapshot,
                )
                val steamCloudPhase0StatusText = buildSteamCloudPhase0StatusText(
                    host,
                    steamCloudPhase0Snapshot
                )

                host.runOnUiThread {
                    applySnapshot(host, snapshot)
                    uiState = uiState.copy(
                        busy = if (clearBusy) false else uiState.busy,
                        busyOperation = if (clearBusy) UiBusyOperation.NONE else uiState.busyOperation,
                        busyMessage = if (clearBusy) null else uiState.busyMessage,
                        busyProgressPercent = if (clearBusy) null else uiState.busyProgressPercent,
                        statusText = status,
                        logPathText = buildLogPathText(host),
                        steamCloudAccountName = steamCloudAuthSnapshot.accountName,
                        steamCloudRefreshTokenConfigured = steamCloudAuthSnapshot.isComplete,
                        steamCloudGuardDataConfigured = steamCloudAuthSnapshot.guardDataConfigured,
                        steamCloudPersonaName = steamCloudAuthSnapshot.personaName,
                        steamCloudAvatarUrl = steamCloudAuthSnapshot.avatarUrl,
                        steamCloudSaveMode = steamCloudSaveMode,
                        steamCloudSyncBlacklistPaths = steamCloudSyncBlacklistPaths,
                        steamCloudSyncBlacklistCandidates = steamCloudSyncBlacklistCandidates,
                        steamCloudWattAccelerationEnabled =
                            LauncherPreferences.isSteamCloudWattAccelerationEnabled(host),
                        steamCloudAutoLaunchAfterSyncEnabled =
                            LauncherPreferences.isSteamCloudAutoLaunchAfterSyncEnabled(host),
                        workshopMaxConcurrentDownloads = LauncherPreferences.readWorkshopMaxConcurrentDownloads(host),
                        workshopDownloadThreads = LauncherPreferences.readWorkshopDownloadThreads(host),
                        workshopWattAccelerationEnabled = LauncherPreferences.isWorkshopWattAccelerationEnabled(host),
                        baiduTranslationCredentialsConfigured = BaiduTranslationCredentialsRepository(host).hasConfiguredCredentials(),
                        steamCloudCredentialsSummary = buildSteamCloudCredentialsSummary(
                            host,
                            steamCloudAuthSnapshot
                        ),
                        steamCloudStatusText = steamCloudStatusText,
                        steamCloudManifestSummary = buildSteamCloudManifestSummary(
                            host,
                            steamCloudManifestSnapshot
                        ),
                        steamCloudManifestAvailable = steamCloudManifestSnapshot != null,
                        steamCloudPhase0AccountName = steamCloudPhase0Snapshot.accountName,
                        steamCloudPhase0RefreshTokenConfigured = steamCloudPhase0Snapshot.hasRefreshToken,
                        steamCloudPhase0ProxyUrl = steamCloudPhase0Snapshot.proxyUrl,
                        steamCloudPhase0CredentialsSummary = buildSteamCloudPhase0CredentialsSummary(
                            host,
                            steamCloudPhase0Snapshot
                        ),
                        steamCloudPhase0StatusText = steamCloudPhase0StatusText,
                    )
                }
            } catch (_: Throwable) {
                host.runOnUiThread {
                    if (clearBusy) {
                        uiState = uiState.copy(
                            busy = false,
                            busyMessage = null,
                            busyProgressPercent = null
                        )
                    }
                }
            }
        }
    }

    private fun startAutomaticUpdateCheck(host: Activity) {
        syncStoredUpdateState(host)
        if (!uiState.autoCheckUpdatesEnabled) {
            return
        }
        if (uiState.updateCheckInProgress || uiState.releaseHistoryLoading || uiState.busy) {
            return
        }
        runUpdateCheck(host, userInitiated = false)
    }

    fun onStartSteamCloudLogin(
        host: Activity,
        username: String,
        password: String,
    ): Boolean {
        if (uiState.busy) {
            return false
        }
        val normalizedUsername = username.trim()
        if (normalizedUsername.isEmpty()) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_username_required))
            return false
        }
        if (password.isBlank()) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_password_required))
            return false
        }

        cancelActiveSteamCloudLogin("Steam Cloud login restarted.", clearBusy = false)
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_login))
        val cancellationHandle = SteamCloudAuthCoordinator.CancellationHandle()
        val loginGeneration = steamCloudLoginGeneration + 1L
        steamCloudLoginGeneration = loginGeneration
        pendingSteamCloudLoginCancellationHandle = cancellationHandle
        val loginTask = FutureTask<Unit> {
            try {
                val existingGuardData = runCatching {
                    SteamCloudAuthStore.readAuthMaterial(host)?.guardData.orEmpty()
                }.getOrDefault("")
                val hadIncompleteAuth = runCatching {
                    val snapshot = SteamCloudAuthStore.readSnapshot(host)
                    snapshot.refreshTokenConfigured && !snapshot.isComplete
                }.getOrDefault(false)
                if (hadIncompleteAuth) {
                    runCatching { SteamCloudAuthStore.clear(host) }
                    runCatching { SteamCloudManifestStore.clear(host) }
                    runCatching { SteamCloudBaselineStore.clear(host) }
                }
                val authResult = SteamCloudAuthCoordinator.authenticateWithCredentials(
                    context = host,
                    username = normalizedUsername,
                    password = password,
                    existingGuardData = existingGuardData,
                    prompt = buildSteamCloudAuthPrompt(
                        host = host,
                        cancellationHandle = cancellationHandle,
                        loginGeneration = loginGeneration,
                    ),
                    cancellationHandle = cancellationHandle,
                )
                SteamCloudAuthStore.recordAuthSuccess(
                    host,
                    authResult.accountName,
                    authResult.refreshToken,
                    authResult.guardData,
                    authResult.steamId64,
                )
                val loginDiagnosticsExtraLines = mutableListOf(
                    "Refresh token received: ${authResult.refreshToken.length} chars",
                    "Guard data returned: ${if (authResult.guardData.isBlank()) "no" else "yes"}",
                    "SteamID64 resolved: ${if (authResult.steamId64.isBlank()) "no" else "yes"}",
                    "Resolved SteamID64 value: ${authResult.steamId64.ifBlank { "<blank>" }}",
                )
                if (authResult.steamId64.isNotBlank()) {
                    val profileResult = runCatching {
                        SteamCloudProfileService.fetchProfile(host, authResult.steamId64)
                    }
                    profileResult.getOrNull()?.let { profile ->
                        SteamCloudAuthStore.recordProfile(
                            host,
                            profile.steamId64,
                            profile.personaName,
                            profile.avatarUrl,
                        )
                        loginDiagnosticsExtraLines +=
                            "Profile fetch result: success for ${profile.steamId64.ifBlank { "<blank>" }}"
                        loginDiagnosticsExtraLines +=
                            "Profile persona name: ${profile.personaName.ifBlank { "<blank>" }}"
                        loginDiagnosticsExtraLines +=
                            "Profile avatar URL: ${profile.avatarUrl.ifBlank { "<blank>" }}"
                    }
                    profileResult.exceptionOrNull()?.let { profileError ->
                        loginDiagnosticsExtraLines +=
                            "Profile fetch result: failed (${summarizeSteamCloudError(host, profileError)})"
                    }
                    if (profileResult.isSuccess && profileResult.getOrNull() == null) {
                        loginDiagnosticsExtraLines += "Profile fetch result: returned no matching public profile"
                    }
                } else {
                    loginDiagnosticsExtraLines += "Profile fetch skipped: SteamID64 was blank after refresh-token logon"
                }
                runCatching {
                    SteamCloudDiagnosticsStore.writeSummary(
                        context = host,
                        operation = "credentials_login",
                        outcome = "SUCCESS",
                        accountName = authResult.accountName,
                        startedAtMs = authResult.diagnosticsStartedAtMs,
                        completedAtMs = authResult.diagnosticsCompletedAtMs,
                        diagnostics = authResult.diagnosticsSnapshot,
                        extraLines = loginDiagnosticsExtraLines,
                    )
                }
                SteamCloudManifestStore.clear(host)
                SteamCloudBaselineStore.clear(host)
                val shouldPromptSteamCloudSaveModeSwitch =
                    LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD
                host.runOnUiThread {
                    if (steamCloudLoginGeneration != loginGeneration) {
                        return@runOnUiThread
                    }
                    clearPendingSteamCloudChallengeState()
                    pendingSteamCloudLoginCancellationHandle = null
                    pendingSteamCloudLoginTask = null
                    dismissSteamCloudManifestDialog()
                    dismissSteamCloudUploadPlanDialog()
                    dismissSteamCloudPushConfirmDialog()
                    setBusy(false, null)
                    if (shouldPromptSteamCloudSaveModeSwitch) {
                        showSteamCloudSwitchPromptAfterLoginDialog(host, authResult.accountName)
                    } else {
                        showToast(
                            host,
                            UiText.StringResource(
                                R.string.settings_steam_cloud_login_succeeded,
                                authResult.accountName
                            )
                        )
                    }
                    refreshStatus(host, clearBusy = false)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                val loginCancelled = cancellationHandle.isCancelled || isSteamCloudLoginCancellation(error)
                if (!loginCancelled) {
                    runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                }
                host.runOnUiThread {
                    if (steamCloudLoginGeneration != loginGeneration) {
                        return@runOnUiThread
                    }
                    clearPendingSteamCloudChallengeState()
                    pendingSteamCloudLoginCancellationHandle = null
                    pendingSteamCloudLoginTask = null
                    dismissSteamCloudManifestDialog()
                    if (loginCancelled) {
                        showToast(host, UiText.StringResource(R.string.settings_steam_cloud_login_cancelled))
                    } else {
                        showToast(
                            host,
                            UiText.StringResource(
                                R.string.settings_steam_cloud_login_failed,
                                summary
                            )
                        )
                    }
                    refreshStatus(host)
                }
            } finally {
                if (pendingSteamCloudLoginCancellationHandle === cancellationHandle &&
                    steamCloudLoginGeneration == loginGeneration
                ) {
                    pendingSteamCloudLoginCancellationHandle = null
                    pendingSteamCloudLoginTask = null
                }
            }
        }
        pendingSteamCloudLoginTask = loginTask
        executor.execute(loginTask)
        return true
    }

    fun onRefreshSteamCloudManifest(host: Activity) {
        if (uiState.busy) {
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_refresh))
        executor.execute {
            try {
                val snapshot = SteamCloudOperationMutex.runExclusive {
                    SteamCloudPullCoordinator.refreshManifest(host, authMaterial)
                }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_manifest_refresh_succeeded,
                            snapshot.fileCount
                        )
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_manifest_refresh_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onOpenSteamCloudManifestDialog(host: Activity) {
        if (uiState.busy) {
            return
        }
        val snapshot = runCatching { SteamCloudManifestStore.readSnapshot(host) }.getOrNull()
        if (snapshot == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_manifest_missing))
            return
        }
        uiState = uiState.copy(steamCloudManifestDialogSnapshot = snapshot)
    }

    fun dismissSteamCloudManifestDialog() {
        if (uiState.steamCloudManifestDialogSnapshot != null) {
            uiState = uiState.copy(steamCloudManifestDialogSnapshot = null)
        }
    }

    fun onReviewSteamCloudUploadPlan(host: Activity) {
        if (uiState.busy) {
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_plan_upload))
        executor.execute {
            try {
                val plan = SteamCloudOperationMutex.runExclusive {
                    SteamCloudPushCoordinator.buildUploadPlan(host, authMaterial)
                }
                host.runOnUiThread {
                    dismissSteamCloudPushConfirmDialog()
                    uiState = uiState.copy(steamCloudUploadPlanDialogSnapshot = plan)
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_upload_plan_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun dismissSteamCloudUploadPlanDialog() {
        if (uiState.steamCloudUploadPlanDialogSnapshot != null) {
            uiState = uiState.copy(steamCloudUploadPlanDialogSnapshot = null)
        }
    }

    fun onStartSteamCloudPush(host: Activity) {
        if (uiState.busy) {
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_plan_upload))
        executor.execute {
            try {
                val plan = SteamCloudOperationMutex.runExclusive {
                    SteamCloudPushCoordinator.buildUploadPlan(host, authMaterial)
                }
                host.runOnUiThread {
                    dismissSteamCloudPushConfirmDialog()
                    when {
                        plan.conflicts.isNotEmpty() -> {
                            uiState = uiState.copy(steamCloudUploadPlanDialogSnapshot = plan)
                            showToast(
                                host,
                                UiText.StringResource(
                                    R.string.settings_steam_cloud_push_blocked_by_conflicts,
                                    plan.conflicts.size
                                )
                            )
                        }

                        plan.uploadCandidates.isEmpty() && plan.remoteDeleteCandidates.isEmpty() -> {
                            uiState = uiState.copy(steamCloudUploadPlanDialogSnapshot = plan)
                            showToast(
                                host,
                                UiText.StringResource(R.string.settings_steam_cloud_push_no_changes)
                            )
                        }

                        else -> {
                            dismissSteamCloudUploadPlanDialog()
                            uiState = uiState.copy(steamCloudUploadConfirmPlan = plan)
                        }
                    }
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_upload_plan_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onConfirmSteamCloudPush(host: Activity) {
        if (uiState.busy) {
            return
        }
        val plan = uiState.steamCloudUploadConfirmPlan ?: return
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_push))
        executor.execute {
            try {
                val result = SteamCloudOperationMutex.runExclusive {
                    SteamCloudPushCoordinator.pushLocalChanges(host, authMaterial, plan)
                }
                host.runOnUiThread {
                    dismissSteamCloudPushConfirmDialog()
                    dismissSteamCloudUploadPlanDialog()
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_push_succeeded,
                            result.uploadedFileCount,
                            result.deletedRemoteFileCount
                        )
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_push_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun dismissSteamCloudPushConfirmDialog() {
        if (uiState.steamCloudUploadConfirmPlan != null) {
            uiState = uiState.copy(steamCloudUploadConfirmPlan = null)
        }
    }

    fun onPullSteamCloudFromManifest(host: Activity) {
        if (uiState.busy) {
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_pull))
        executor.execute {
            try {
                val result = SteamCloudOperationMutex.runExclusive {
                    SteamCloudPullCoordinator.pullAll(host, authMaterial)
                }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_pull_succeeded,
                            result.appliedFileCount
                        )
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_pull_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSteamCloudWattAccelerationChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamCloudWattAccelerationEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onSteamCloudAutoLaunchAfterSyncChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamCloudAutoLaunchAfterSyncEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onClearSteamCloudNetworkCache(host: Activity) {
        if (uiState.busy) {
            return
        }
        SteamCloudNetworkEnvironment.clearNetworkCache(host)
        SteamCloudAvatarCacheStore.clear(host)
        showToast(host, UiText.StringResource(R.string.settings_steam_cloud_network_cache_cleared))
        refreshStatus(host)
    }

    fun onWorkshopMaxConcurrentDownloadsChanged(host: Activity, value: Int) {
        LauncherPreferences.saveWorkshopMaxConcurrentDownloads(host, value)
        refreshStatus(host)
    }

    fun onWorkshopDownloadThreadsChanged(host: Activity, value: Int) {
        LauncherPreferences.saveWorkshopDownloadThreads(host, value)
        refreshStatus(host)
    }

    fun onWorkshopWattAccelerationChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setWorkshopWattAccelerationEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onQuickStartSteamAccelerationChanged(host: Activity, enabled: Boolean) {
        if (!uiState.quickStartSteamAccelerationSwitchEnabled) {
            return
        }
        val completion = quickStartSteamImportCompletion
        val pauseController = PauseController()
        quickStartSteamPauseController?.resume()
        quickStartSteamPauseController = pauseController
        uiState = uiState.copy(quickStartSteamAccelerationSwitchEnabled = false)
        val generation = nextQuickStartSteamImportGeneration()
        quickStartSteamImportTask?.cancel(true)
        quickStartSteamImportTask = executor.submit {
            LauncherPreferences.setWorkshopWattAccelerationEnabled(host, enabled)
            host.runOnUiThread {
                refreshStatus(host)
            }
            runQuickStartSteamImport(
                host = host,
                generation = generation,
                onCompleted = completion,
                keepAccelerationSwitchDisabled = true,
                pauseController = pauseController,
                mode = quickStartSteamImportMode,
            )
        }
    }

    fun onQuickStartSteamPauseChanged(paused: Boolean) {
        val controller = quickStartSteamPauseController ?: return
        if (paused) {
            controller.pause()
        } else {
            controller.resume()
        }
        uiState = uiState.copy(
            quickStartSteamPaused = paused,
            busyMessage = if (paused) {
                UiText.StringResource(R.string.quick_start_steam_download_paused)
            } else {
                uiState.busyMessage
            }
        )
    }

    fun onRetryQuickStartSteamImport(host: Activity) {
        if (uiState.busy) {
            return
        }
        val completion = quickStartSteamImportCompletion
            ?: { success: Boolean ->
                uiState = uiState.copy(
                    quickStartSteamFailed = !success,
                    quickStartSteamFailureMessage = null,
                )
            }
        startQuickStartSteamImport(
            host = host,
            onCompleted = completion,
            mode = quickStartSteamImportMode,
        )
    }

    fun onExitQuickStartAutomaticImport(host: Activity) {
        quickStartSteamImportCompletion = null
        quickStartSteamImportMode = QuickStartSteamImportMode.ANONYMOUS
        quickStartSteamPauseController?.resume()
        quickStartSteamPauseController = null
        nextQuickStartSteamImportGeneration()
        quickStartSteamImportTask?.cancel(true)
        quickStartSteamImportTask = null
        setBusy(false, null)
        uiState = uiState.copy(
            quickStartSteamDownloadPhase = null,
            quickStartSteamDownloadedBytes = 0L,
            quickStartSteamTotalBytes = null,
            quickStartSteamAccelerationSwitchEnabled = true,
            quickStartSteamPaused = false,
            quickStartSteamFailed = false,
            quickStartSteamFailureMessage = null,
            quickStartAutomaticImportRequiresLogin = false,
        )
        Thread({
            SteamStsJarDownloadService.clearQuickStartSteamCache(host.applicationContext)
        }, "QuickStartSteamCacheCleanup").apply {
            isDaemon = true
            start()
        }
    }

    fun onWorkshopSteamLanguageChanged(host: Activity, language: SteamLanguagePreference) {
        LauncherPreferences.saveWorkshopSteamLanguage(host, language)
        refreshStatus(host)
    }

    fun onWorkshopAutoImportChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setWorkshopAutoImportEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onWorkshopAutoImportAtlasDownscaleChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setWorkshopAutoImportAtlasDownscaleEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged(host: Activity, maxEdgePx: Int) {
        LauncherPreferences.saveWorkshopAutoImportAtlasDownscaleMaxEdgePx(host, maxEdgePx)
        refreshStatus(host)
    }

    fun onClearWorkshopPreviewCache(host: Activity) {
        executor.execute {
            val deletedCount = WorkshopPreviewCacheStore.clear(host.applicationContext)
            host.runOnUiThread {
                showToast(
                    host,
                    host.getString(R.string.settings_market_clear_preview_cache_done, deletedCount)
                )
            }
        }
    }

    fun onClearJunkFiles(host: Activity) {
        executor.execute {
            val result = LauncherJunkFileCleaner.clear(host.applicationContext)
            val deletedBytesText = Formatter.formatFileSize(host, result.deletedBytes)
            val message = when {
                result.failedTargetCount > 0 -> host.getString(
                    R.string.settings_developer_clear_junk_files_partial,
                    deletedBytesText,
                    result.deletedTargetCount,
                    result.failedTargetCount
                )
                result.deletedTargetCount > 0 -> host.getString(
                    R.string.settings_developer_clear_junk_files_done,
                    deletedBytesText,
                    result.deletedTargetCount
                )
                else -> host.getString(R.string.settings_developer_clear_junk_files_empty)
            }
            host.runOnUiThread {
                showToast(host, message)
            }
        }
    }

    internal fun readBaiduTranslationCredentials(host: Activity): BaiduTranslationCredentials =
        BaiduTranslationCredentialsRepository(host).getCredentials()

    fun onSaveBaiduTranslationCredentials(
        host: Activity,
        appId: String,
        apiKey: String,
    ) {
        val repository = BaiduTranslationCredentialsRepository(host)
        repository.setCredentials(BaiduTranslationCredentials(appId = appId, apiKey = apiKey))
        val savedCredentials = repository.getCredentials()
        uiState = uiState.copy(
            baiduTranslationCredentialsConfigured = savedCredentials.isConfigured(),
        )
        showToast(
            host,
            UiText.DynamicString(
                when {
                    savedCredentials.isConfigured() -> host.getString(R.string.settings_baidu_translation_credentials_saved)
                    savedCredentials.appId.isBlank() && savedCredentials.apiKey.isBlank() ->
                        host.getString(R.string.settings_baidu_translation_credentials_cleared)
                    else -> host.getString(R.string.settings_baidu_translation_credentials_incomplete)
                },
            ),
        )
    }

    fun onSteamCloudSaveModeChanged(host: Activity, mode: SteamCloudSaveMode) {
        if (uiState.busy) {
            return
        }
        val targetMode = mode
        if (targetMode == SteamCloudSaveMode.STEAM_CLOUD) {
            val loggedIn = runCatching {
                SteamCloudAuthStore.readSnapshot(host).isComplete
            }.getOrDefault(false)
            if (!loggedIn) {
                showToast(host, UiText.StringResource(R.string.settings_steam_cloud_save_mode_cloud_requires_login))
                refreshStatus(host)
                return
            }
        }

        val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
        if (currentMode == targetMode) {
            refreshStatus(host)
            return
        }

        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_save_mode_switch))
        executor.execute {
            try {
                SteamCloudSaveProfileManager.switchMode(
                    context = host,
                    fromMode = currentMode,
                    toMode = targetMode,
                )
                LauncherPreferences.saveSteamCloudSaveMode(host, targetMode)
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_save_mode_switch_succeeded,
                            targetMode.displayName(host),
                        )
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_save_mode_switch_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSteamCloudSyncBlacklistPathChanged(
        host: Activity,
        localRelativePath: String,
        selected: Boolean,
    ) {
        if (uiState.busy) {
            return
        }
        val normalizedPath = SteamCloudSyncBlacklist.normalizeLocalRelativePath(localRelativePath)
            ?: run {
                refreshStatus(host)
                return
            }
        val nextPaths = LauncherPreferences.readSteamCloudSyncBlacklistPaths(host)
            .toMutableSet()
        if (selected) {
            nextPaths += normalizedPath
        } else {
            nextPaths -= normalizedPath
        }
        LauncherPreferences.saveSteamCloudSyncBlacklistPaths(host, nextPaths)
        refreshStatus(host)
    }

    fun onForceIndependentSaveOverwriteCloud(host: Activity) {
        if (uiState.busy) {
            return
        }
        if (LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD) {
            refreshStatus(host)
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            refreshStatus(host)
            return
        }
        if (!SteamCloudSaveProfileManager.profileHasRegularFiles(host, SteamCloudSaveMode.INDEPENDENT)) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_independent_profile_missing))
            refreshStatus(host)
            return
        }

        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.settings_busy_steam_cloud_force_independent_override),
            operation = UiBusyOperation.STEAM_CLOUD_SYNC,
        )
        executor.execute {
            try {
                val (remoteBackupLabel, result) = SteamCloudOperationMutex.runExclusive {
                    SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.STEAM_CLOUD)
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val remoteBackupLabel = backupRemoteSteamCloudProfile(host, authMaterial, timestamp)
                    SettingsSaveBackupService.backupSaveProfileToDownloads(
                        host = host,
                        sourceRoot = SteamCloudSaveProfileManager.profileRoot(
                            host,
                            SteamCloudSaveMode.INDEPENDENT
                        ),
                        backupFileName = "independent-saves-before-steam-cloud-overwrite-$timestamp.zip",
                        relativeSubdirectory = STEAM_CLOUD_BACKUP_DOWNLOAD_SUBDIR,
                    )

                    val result = SteamCloudPushCoordinator.overwriteRemoteWithLocal(
                        host = host,
                        authMaterial = authMaterial,
                        sourceRoot = SteamCloudSaveProfileManager.profileRoot(
                            host,
                            SteamCloudSaveMode.INDEPENDENT
                        ),
                    )
                    SteamCloudSaveProfileManager.restoreProfile(host, SteamCloudSaveMode.INDEPENDENT)
                    SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.STEAM_CLOUD)
                    LauncherPreferences.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
                    remoteBackupLabel to result
                }

                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_force_independent_override_succeeded,
                            result.uploadedFileCount,
                            result.deletedRemoteFileCount,
                            remoteBackupLabel.substringBeforeLast('/').ifBlank {
                                "Download/$STEAM_CLOUD_BACKUP_DOWNLOAD_SUBDIR"
                            },
                        ),
                        Toast.LENGTH_LONG,
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                runCatching {
                    if (LauncherPreferences.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD) {
                        SteamCloudSaveProfileManager.restoreProfile(host, SteamCloudSaveMode.STEAM_CLOUD)
                    }
                }
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_force_independent_override_failed,
                            summary,
                        ),
                        Toast.LENGTH_LONG,
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onClearSteamCloudCredentials(host: Activity) {
        if (uiState.busy) {
            return
        }
        cancelPendingSteamCloudChallenge("Steam Cloud credentials cleared.")
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_save_mode_switch))
        executor.execute {
            try {
                val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
                if (currentMode != SteamCloudSaveMode.INDEPENDENT) {
                    SteamCloudSaveProfileManager.switchMode(
                        context = host,
                        fromMode = currentMode,
                        toMode = SteamCloudSaveMode.INDEPENDENT,
                    )
                }
                LauncherPreferences.saveSteamCloudSaveMode(host, SteamCloudSaveMode.INDEPENDENT)
                runCatching { SteamCloudAuthStore.clear(host) }
                runCatching { SteamCloudAvatarCacheStore.clear(host) }
                SteamCloudManifestStore.clear(host)
                SteamCloudBaselineStore.clear(host)
                SteamCloudDiagnosticsStore.clear(host)
                host.runOnUiThread {
                    dismissSteamCloudManifestDialog()
                    dismissSteamCloudUploadPlanDialog()
                    dismissSteamCloudPushConfirmDialog()
                    showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_cleared))
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_save_mode_switch_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSubmitSteamCloudChallengeCode(code: String) {
        val future = pendingSteamCloudCodeFuture ?: return
        pendingSteamCloudCodeFuture = null
        uiState = uiState.copy(steamCloudLoginChallenge = null)
        future.complete(code.trim())
    }

    fun onAcceptSteamCloudDeviceConfirmation() {
        val future = pendingSteamCloudConfirmationFuture ?: return
        pendingSteamCloudConfirmationFuture = null
        uiState = uiState.copy(steamCloudLoginChallenge = null)
        future.complete(true)
    }

    fun onCancelSteamCloudChallenge(host: Activity? = null) {
        cancelActiveSteamCloudLogin("Steam Cloud login cancelled by user.", clearBusy = true)
        host?.let {
            showToast(it, UiText.StringResource(R.string.settings_steam_cloud_login_cancelled))
        }
    }

    fun onSaveSteamCloudPhase0Credentials(
        host: Activity,
        accountName: String,
        refreshToken: String,
        proxyUrl: String,
    ): Boolean {
        if (uiState.busy) {
            return false
        }
        val normalizedAccountName = accountName.trim()
        val normalizedRefreshToken = refreshToken.trim()
        val normalizedProxyUrl = proxyUrl.trim()
        val existingSnapshot = SteamCloudPhase0Store.readSnapshot(host)
        if (normalizedAccountName.isEmpty()) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_phase0_account_required))
            return false
        }
        if (normalizedRefreshToken.isEmpty() && !existingSnapshot.hasRefreshToken) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_phase0_token_required))
            return false
        }
        SteamCloudPhase0Store.saveCredentials(
            host,
            normalizedAccountName,
            normalizedRefreshToken.ifEmpty { null },
            normalizedProxyUrl
        )
        showToast(host, UiText.StringResource(R.string.settings_steam_cloud_phase0_credentials_saved))
        refreshStatus(host)
        return true
    }

    fun onRunSteamCloudPhase0Probe(host: Activity) {
        if (uiState.busy) {
            return
        }
        val credentials = SteamCloudPhase0Store.readCredentials(host)
        if (credentials == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_phase0_credentials_missing))
            return
        }
        setBusy(
            true,
            UiText.StringResource(R.string.settings_busy_steam_cloud_phase0_probe)
        )
        executor.execute {
            try {
                val result = SteamCloudPhase0ManifestProbe.run(
                    host,
                    credentials.accountName,
                    credentials.refreshToken,
                    credentials.proxyUrl
                )
                SteamCloudPhase0Store.recordSuccess(host, result)
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_phase0_probe_succeeded,
                            result.fileCount
                        )
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                val probeFailure = error as? SteamCloudPhase0ManifestProbe.ProbeFailureException
                val summary = probeFailure?.message?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: error.message?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error.javaClass.simpleName
                SteamCloudPhase0Store.recordFailure(
                    host,
                    summary,
                    probeFailure?.summaryFile?.absolutePath
                )
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_phase0_probe_failed,
                            summary
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onClearSteamCloudPhase0Credentials(host: Activity) {
        if (uiState.busy) {
            return
        }
        SteamCloudPhase0Store.clearCredentials(host)
        showToast(host, UiText.StringResource(R.string.settings_steam_cloud_phase0_credentials_cleared))
        refreshStatus(host)
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
            showToast(
                host,
                UiText.DynamicString(
                    error.message ?: host.getString(R.string.settings_render_scale_save_failed)
                ),
                Toast.LENGTH_SHORT
            )
            return
        }
        val normalizedValue = normalized.toFloatOrNull() ?: clampedValue
        uiState = uiState.copy(selectedRenderScale = normalizedValue)
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
        setBusy(true, UiText.StringResource(R.string.common_busy_preparing_jvm_log_bundle))
        executor.execute {
            try {
                val payload = JvmLogShareService.prepareSharePayload(host)
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(Effect.ShareJvmLogsBundle(payload))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_log_share_failed_prefix),
                                error
                            )
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onLogsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_exporting_jvm_logs))
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportJvmLogBundle(host, uri)
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        showToast(
                            host,
                            UiText.StringResource(R.string.settings_logs_exported, exportedCount)
                        )
                    } else {
                        showToast(host, UiText.StringResource(R.string.settings_logs_exported_empty))
                    }
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_log_export_failed_prefix),
                                error
                            )
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onModsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(
                R.string.settings_busy_exporting_mods_archive_with_progress,
                0
            ),
            operation = UiBusyOperation.MOD_IMPORT,
            progressPercent = 0
        )
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportModsBundle(host, uri) { percent ->
                    host.runOnUiThread {
                        if (!uiState.busy) {
                            return@runOnUiThread
                        }
                        setBusy(
                            busy = true,
                            message = UiText.StringResource(
                                R.string.settings_busy_exporting_mods_archive_with_progress,
                                percent.coerceIn(0, 100)
                            ),
                            operation = UiBusyOperation.MOD_IMPORT,
                            progressPercent = percent
                        )
                    }
                }
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        showToast(
                            host,
                            UiText.StringResource(R.string.settings_mods_exported, exportedCount)
                        )
                    } else {
                        showToast(host, UiText.StringResource(R.string.settings_mods_exported_empty))
                    }
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_mod_export_failed_prefix),
                                error
                            )
                        )
                    )
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

    fun onVirtualResolutionModeChanged(host: Activity, mode: VirtualResolutionMode) {
        if (uiState.busy || uiState.virtualResolutionMode == mode) {
            return
        }
        uiState = uiState.copy(virtualResolutionMode = mode)
        saveVirtualResolutionModeSelection(host, mode)
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

    fun onGpuResourceGuardianModeChanged(host: Activity, mode: GpuResourceGuardianMode) {
        if (uiState.busy || uiState.gpuResourceGuardianMode == mode) {
            return
        }
        uiState = uiState.copy(gpuResourceGuardianMode = mode)
        saveGpuResourceGuardianModeSelection(host, mode)
        refreshStatus(host)
    }

    fun onGpuResourceGuardianPressureDownscaleChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || uiState.gpuResourceGuardianPressureDownscaleEnabled == enabled) {
            return
        }
        uiState = uiState.copy(gpuResourceGuardianPressureDownscaleEnabled = enabled)
        LauncherPreferences.setGpuResourceGuardianPressureDownscaleEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onMobileGluesAnglePolicyChanged(host: Activity, policy: MobileGluesAnglePolicy) {
        if (uiState.busy || uiState.mobileGluesAnglePolicy == policy) {
            return
        }
        uiState = uiState.copy(mobileGluesAnglePolicy = policy)
        persistMobileGluesSettings(
            host = host,
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_save_failed
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
            failureMessageResId = R.string.settings_mobileglues_apply_preset_failed
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
            failureMessageResId = R.string.settings_mobileglues_reset_failed
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

    fun onSpecialKeyInputModeChanged(host: Activity, mode: SpecialKeyInputMode) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(
            specialKeyInputMode = mode,
            showFloatingMouseWindow = mode == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW
        )
        saveSpecialKeyInputModeSelection(host, mode)
        refreshStatus(host)
    }

    fun onTouchMouseInteractionModeChanged(host: Activity, mode: TouchMouseInteractionMode) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchMouseInteractionMode = mode)
        saveTouchMouseInteractionModeSelection(host, mode)
        refreshStatus(host)
    }

    fun onTouchDoubleClickAsRightClickChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchDoubleClickAsRightClick = enabled)
        saveTouchDoubleClickAsRightClickSelection(host, enabled)
        refreshStatus(host)
    }

    fun onIgnoreLongPressRightClickWhilePlayingCardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(ignoreLongPressRightClickWhilePlayingCard = enabled)
        saveIgnoreLongPressRightClickWhilePlayingCardSelection(host, enabled)
        refreshStatus(host)
    }

    fun onBuiltInSoftKeyboardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(builtInSoftKeyboardEnabled = enabled)
        saveBuiltInSoftKeyboardSelection(host, enabled)
        refreshStatus(host)
    }

    fun onHapticFeedbackChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(hapticFeedbackEnabled = enabled)
        saveHapticFeedbackSelection(host, enabled)
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

    fun onApplyModFileNameAliases(host: Activity) {
        if (uiState.busy) {
            return
        }
        val spaceCheck = runCatching {
            ModManifestNameMigration.evaluateFileNameMigrationSpace(host)
        }.getOrNull()
        if (spaceCheck != null &&
            spaceCheck.hasPendingMigration &&
            !spaceCheck.hasEnoughSpace
        ) {
            showModNameMigrationStorageInsufficientDialog(host, spaceCheck)
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.main_mod_name_migration_busy),
            operation = UiBusyOperation.MOD_NAME_MIGRATION,
            progressPercent = 0
        )
        executor.execute {
            try {
                val result = ModManifestNameMigration.applyFileNamesToInstalledOptionalMods(
                    context = host,
                    requireSufficientSpace = true,
                    onProgress = { progress ->
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed) {
                                return@runOnUiThread
                            }
                            if (!uiState.busy ||
                                uiState.busyOperation != UiBusyOperation.MOD_NAME_MIGRATION
                            ) {
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
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_mod_alias_apply_file_names_done,
                            result.appliedCount
                        ),
                        Toast.LENGTH_SHORT
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    if (error is ModManifestNameMigrationStorageException) {
                        showModNameMigrationStorageInsufficientDialog(host, error.spaceCheck)
                    } else {
                        showToast(
                            host,
                            UiText.StringResource(
                                R.string.main_mod_rename_failed,
                                error.message ?: host.getString(R.string.feedback_unknown_error)
                            ),
                            Toast.LENGTH_LONG
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

    fun onLauncherLogcatCaptureChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(launcherLogcatCaptureEnabled = enabled)
        saveLauncherLogcatCaptureSelection(host, enabled)
        if (enabled) {
            LauncherLogcatCaptureProcessClient.startCapture(host)
        } else {
            LauncherLogcatCaptureProcessClient.stopAndClearCapture(host)
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

    fun onGpuResourceDiagChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(gpuResourceDiagEnabled = enabled)
        saveGpuResourceDiagSelection(host, enabled)
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

    fun onRamSaverEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(ramSaverEnabled = enabled)
        saveRamSaverEnabledSelection(host, enabled)
        refreshStatus(host)
    }

    fun onMtsPatchCacheEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(mtsPatchCacheEnabled = enabled)
        saveMtsPatchCacheEnabledSelection(host, enabled)
        refreshStatus(host)
    }

    fun onKeepScreenOnTimeoutSelected(host: Activity, timeoutMinutes: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedTimeout = LauncherPreferences.normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes)
        if (uiState.keepScreenOnTimeoutMinutes == normalizedTimeout) {
            return
        }
        uiState = uiState.copy(keepScreenOnTimeoutMinutes = normalizedTimeout)
        saveKeepScreenOnTimeoutSelection(host, normalizedTimeout)
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

    fun onTouchscreenInputModeChanged(host: Activity, mode: TouchscreenInputMode) {
        if (uiState.busy) {
            return
        }
        if (!saveTouchscreenInputModeSelection(host, mode)) {
            return
        }
        uiState = uiState.copy(touchscreenInputMode = mode)
        refreshStatus(host)
    }

    fun onCardPlayOptimizationModeChanged(host: Activity, mode: CardPlayOptimizationMode) {
        if (uiState.busy) {
            return
        }
        if (!saveCardPlayOptimizationModeSelection(host, mode)) {
            return
        }
        uiState = uiState.copy(cardPlayOptimizationMode = mode)
        refreshStatus(host)
    }

    fun onTouchIndicatorEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchIndicatorEnabled = enabled)
        GameplaySettingsService.setTouchIndicatorEnabled(host, enabled)
        refreshStatus(host)
    }

    fun onGameplayFontScaleChanged(host: Activity, value: Float) {
        if (uiState.busy) {
            return
        }
        val normalized = GameplaySettingsService.normalizeFontScale(value)
        if (!saveGameplayFontScaleSelection(host, normalized)) {
            return
        }
        uiState = uiState.copy(gameplayFontScale = normalized)
        refreshStatus(host)
    }

    fun onGameplayLargerUiChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (!saveGameplayLargerUiSelection(host, enabled)) {
            return
        }
        uiState = uiState.copy(gameplayLargerUiEnabled = enabled)
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

    fun onResetLauncherSettingsToDefaults(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_reset_defaults))
        executor.execute {
            try {
                SettingsRepository.resetLauncherSettingsToDefaults(host)
                if (LauncherPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED) {
                    LogcatCaptureProcessClient.startCapture(host, System.currentTimeMillis())
                } else {
                    LogcatCaptureProcessClient.stopAndClearCapture(host)
                }
                if (LauncherPreferences.DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED) {
                    LauncherLogcatCaptureProcessClient.startCapture(host)
                } else {
                    LauncherLogcatCaptureProcessClient.stopAndClearCapture(host)
                }
                host.runOnUiThread {
                    nativeLibraryMarketCatalog = emptyList()
                    LauncherThemeController.apply(host, LauncherPreferences.DEFAULT_THEME_MODE)
                    LauncherIconController.apply(host, LauncherPreferences.DEFAULT_LAUNCHER_ICON_MODE)
                    syncThemeAppearance(host)
                    syncStoredUpdateState(host)
                    uiState = uiState.copy(
                        nativeLibraryMarketPackages = emptyList(),
                        nativeLibraryMarketErrorText = null
                    )
                    showToast(host, UiText.StringResource(R.string.settings_reset_defaults_succeeded))
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_reset_defaults_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    fun onJarPicked(
        host: Activity,
        uri: Uri?,
        showSuccessToast: Boolean = true,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        if (uri == null || uiState.busy || uiState.stsJarIntegrityDialogState != null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.sts_jar_import_busy),
            operation = UiBusyOperation.MOD_IMPORT
        )
        executor.execute {
            var stagedFile: File? = null
            try {
                val displayName = SettingsFileService.resolveDisplayName(host, uri)
                val preparedFile = createStsJarImportScratchFile(host)
                stagedFile = preparedFile
                SettingsFileService.copyUriToFile(host, uri, preparedFile)
                StsJarValidator.validate(preparedFile)
                val integrity = StsDesktopJarIntegrity.inspect(preparedFile)
                if (!integrity.matchesExpected) {
                    val pendingImport = PendingStsJarImport(
                        stagedFile = preparedFile,
                        displayName = displayName,
                        showSuccessToast = showSuccessToast,
                        onCompleted = onCompleted,
                    )
                    stagedFile = null
                    host.runOnUiThread {
                        replacePendingStsJarImport(pendingImport)
                        setBusy(false, null)
                        uiState = uiState.copy(
                            stsJarIntegrityDialogState = StsJarIntegrityDialogState(
                                displayName = pendingImport.displayName,
                                expectedSha1 = integrity.expectedSha1.uppercase(Locale.ROOT),
                                actualSha1 = integrity.actualSha1.uppercase(Locale.ROOT),
                            )
                        )
                    }
                    return@execute
                }
                importPreparedLocalStsJar(
                    host = host,
                    sourceFile = preparedFile,
                    showSuccessToast = showSuccessToast,
                    onCompleted = onCompleted,
                )
                stagedFile = null
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.sts_jar_import_failed,
                            resolveThrowableMessage(host, error)
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshStatus(host)
                    onCompleted?.invoke(false)
                }
            } finally {
                stagedFile?.delete()
            }
        }
    }

    fun onStartQuickStartSteamImport(
        host: Activity,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        startQuickStartSteamImport(
            host = host,
            onCompleted = onCompleted,
            mode = QuickStartSteamImportMode.AUTHENTICATED,
        )
    }

    fun onStartQuickStartAutomaticImport(
        host: Activity,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        startQuickStartSteamImport(
            host = host,
            onCompleted = onCompleted,
            mode = QuickStartSteamImportMode.ANONYMOUS,
        )
    }

    private fun startQuickStartSteamImport(
        host: Activity,
        onCompleted: ((Boolean) -> Unit)?,
        mode: QuickStartSteamImportMode,
    ) {
        if (uiState.busy) {
            return
        }
        quickStartSteamImportCompletion = onCompleted
        quickStartSteamImportMode = mode
        val pauseController = PauseController()
        quickStartSteamPauseController = pauseController
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.quick_start_steam_download_connecting),
            operation = UiBusyOperation.OTHER_BUSY,
            progressPercent = 0
        )
        uiState = uiState.copy(
            quickStartSteamAccelerationSwitchEnabled = true,
            quickStartSteamPaused = false,
            quickStartSteamFailed = false,
            quickStartSteamFailureMessage = null,
        )
        val generation = nextQuickStartSteamImportGeneration()
        quickStartSteamImportTask = executor.submit {
            runQuickStartSteamImport(
                host = host,
                generation = generation,
                onCompleted = onCompleted,
                keepAccelerationSwitchDisabled = false,
                pauseController = pauseController,
                mode = mode,
            )
        }
    }

    private fun runQuickStartSteamImport(
        host: Activity,
        generation: Int,
        onCompleted: ((Boolean) -> Unit)?,
        keepAccelerationSwitchDisabled: Boolean,
        pauseController: PauseController,
        mode: QuickStartSteamImportMode,
    ) {
        host.runOnUiThread {
            if (generation == quickStartSteamImportGeneration) {
                setBusy(
                    busy = true,
                    message = UiText.StringResource(R.string.quick_start_steam_download_connecting),
                    operation = UiBusyOperation.OTHER_BUSY,
                    progressPercent = uiState.busyProgressPercent ?: 0
                )
                uiState = uiState.copy(
                    quickStartSteamAccelerationSwitchEnabled = !keepAccelerationSwitchDisabled,
                    quickStartSteamFailed = false,
                    quickStartSteamFailureMessage = null,
                    quickStartAutomaticImportRequiresLogin = false,
                )
            }
        }
        try {
            val downloadService = SteamStsJarDownloadService(host.applicationContext)
            val downloadedJar = when (mode) {
                QuickStartSteamImportMode.AUTHENTICATED -> downloadService.downloadDesktopJar(
                    onProgress = { progress ->
                        host.runOnUiThread {
                            if (generation == quickStartSteamImportGeneration) {
                                updateQuickStartSteamDownloadProgress(progress)
                                setBusy(
                                    busy = true,
                                    message = UiText.DynamicString(
                                        buildQuickStartSteamDownloadProgressMessage(host, progress)
                                    ),
                                    operation = UiBusyOperation.OTHER_BUSY,
                                    progressPercent = progress.progressPercent
                                )
                                if (keepAccelerationSwitchDisabled &&
                                    progress.phase == SteamStsJarDownloadPhase.DOWNLOADING
                                ) {
                                    uiState = uiState.copy(quickStartSteamAccelerationSwitchEnabled = true)
                                }
                            }
                        }
                    },
                    waitIfPaused = {
                        throwIfQuickStartSteamImportCancelled(generation)
                        pauseController.awaitIfPaused()
                        throwIfQuickStartSteamImportCancelled(generation)
                    },
                )

                QuickStartSteamImportMode.ANONYMOUS -> downloadService.downloadDesktopJarWithoutAuth(
                    onProgress = { progress ->
                        host.runOnUiThread {
                            if (generation == quickStartSteamImportGeneration) {
                                updateQuickStartSteamDownloadProgress(progress)
                                setBusy(
                                    busy = true,
                                    message = UiText.DynamicString(
                                        buildQuickStartSteamDownloadProgressMessage(host, progress)
                                    ),
                                    operation = UiBusyOperation.OTHER_BUSY,
                                    progressPercent = progress.progressPercent
                                )
                                if (keepAccelerationSwitchDisabled &&
                                    progress.phase == SteamStsJarDownloadPhase.DOWNLOADING
                                ) {
                                    uiState = uiState.copy(quickStartSteamAccelerationSwitchEnabled = true)
                                }
                            }
                        }
                    },
                    waitIfPaused = {
                        throwIfQuickStartSteamImportCancelled(generation)
                        pauseController.awaitIfPaused()
                        throwIfQuickStartSteamImportCancelled(generation)
                    },
                )
            }
            host.runOnUiThread {
                if (generation == quickStartSteamImportGeneration) {
                    updateQuickStartSteamDownloadProgress(
                        SteamStsJarDownloadProgress(
                            phase = SteamStsJarDownloadPhase.DOWNLOADING,
                            progressPercent = 100,
                            downloadedBytes = uiState.quickStartSteamTotalBytes
                                ?: uiState.quickStartSteamDownloadedBytes,
                            totalBytes = uiState.quickStartSteamTotalBytes
                        )
                    )
                    setBusy(
                        busy = true,
                        message = UiText.StringResource(R.string.quick_start_steam_importing_jar),
                        operation = UiBusyOperation.OTHER_BUSY,
                        progressPercent = 100
                    )
                    uiState = uiState.copy(quickStartSteamAccelerationSwitchEnabled = false)
                }
            }
            if (isQuickStartSteamImportCancelled(generation)) {
                downloadedJar.delete()
                throw CancellationException("Quick start Steam import was cancelled")
            }
            SettingsFileService.importFileToFileAtomically(
                sourceFile = downloadedJar,
                targetFile = RuntimePaths.importedStsJar(host),
                validator = StsJarValidator::validate
            )
            MtsStartupCacheCoordinator.invalidate(host)
            val warmupWarning = prewarmMtsClasspathAfterImport(host)
            val pullResult = if (mode == QuickStartSteamImportMode.AUTHENTICATED) {
                pullSteamCloudIntoEmptySlotAfterQuickStartImport(host)
            } else {
                null
            }
            host.runOnUiThread {
                if (generation == quickStartSteamImportGeneration) {
                    setBusy(false, null)
                    uiState = uiState.copy(quickStartSteamAccelerationSwitchEnabled = true)
                    quickStartSteamImportTask = null
                    quickStartSteamImportCompletion = null
                    quickStartSteamPauseController = null
                    showToast(host, UiText.StringResource(R.string.sts_jar_import_success), Toast.LENGTH_SHORT)
                    if (warmupWarning != null) {
                        showToast(host, warmupWarning, Toast.LENGTH_LONG)
                    }
                    if (pullResult != null) {
                        showToast(
                            host,
                            UiText.StringResource(
                                R.string.settings_steam_cloud_pull_succeeded,
                                pullResult.appliedFileCount,
                            ),
                            Toast.LENGTH_SHORT,
                        )
                    }
                    refreshStatus(host)
                    onCompleted?.invoke(true)
                }
            }
        } catch (error: Throwable) {
            if (generation != quickStartSteamImportGeneration || error is CancellationException) {
                SteamStsJarDownloadService.clearQuickStartSteamCache(host.applicationContext)
                return
            }
            host.runOnUiThread {
                if (generation == quickStartSteamImportGeneration) {
                    setBusy(false, null)
                    val anonymousRequiresLogin = mode == QuickStartSteamImportMode.ANONYMOUS &&
                        error.hasCause<SteamAnonymousDepotAccessException>()
                    val failureMessage = if (anonymousRequiresLogin) {
                        UiText.StringResource(
                            R.string.quick_start_auto_import_requires_login,
                            resolveThrowableMessage(host, error)
                        )
                    } else {
                        UiText.StringResource(
                            R.string.quick_start_steam_download_failed,
                            resolveThrowableMessage(host, error)
                        )
                    }
                    uiState = uiState.copy(
                        quickStartSteamAccelerationSwitchEnabled = true,
                        quickStartSteamPaused = false,
                        quickStartSteamFailed = true,
                        quickStartSteamFailureMessage = failureMessage,
                        quickStartAutomaticImportRequiresLogin = anonymousRequiresLogin,
                    )
                    quickStartSteamImportTask = null
                    quickStartSteamPauseController = null
                    showToast(
                        host,
                        failureMessage,
                        Toast.LENGTH_LONG
                    )
                    refreshStatus(host)
                    onCompleted?.invoke(false)
                }
            }
        }
    }

    private fun pullSteamCloudIntoEmptySlotAfterQuickStartImport(host: Activity): SteamCloudPullResult? {
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
            ?: return null
        host.runOnUiThread {
            setBusy(
                busy = true,
                message = UiText.StringResource(R.string.settings_busy_steam_cloud_pull),
                operation = UiBusyOperation.STEAM_CLOUD_SYNC,
                progressPercent = null,
            )
        }
        return SteamCloudOperationMutex.runExclusive {
            val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
            if (currentMode != SteamCloudSaveMode.STEAM_CLOUD) {
                SteamCloudSaveProfileManager.saveActiveProfile(host, currentMode)
            }
            SteamCloudRootKind.entries.forEach { rootKind ->
                File(RuntimePaths.stsRoot(host), rootKind.directoryName).deleteRecursively()
            }
            LauncherPreferences.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
            SteamCloudPullCoordinator.pullAll(host, authMaterial)
        }
    }

    private fun nextQuickStartSteamImportGeneration(): Int {
        quickStartSteamImportGeneration += 1
        return quickStartSteamImportGeneration
    }

    private fun isQuickStartSteamImportCancelled(generation: Int): Boolean =
        generation != quickStartSteamImportGeneration || Thread.currentThread().isInterrupted

    private fun throwIfQuickStartSteamImportCancelled(generation: Int) {
        if (isQuickStartSteamImportCancelled(generation)) {
            throw CancellationException("Quick start Steam import was cancelled")
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
        duplicateReplaceOptions: DuplicateModImportReplaceOptions = DuplicateModImportReplaceOptions(),
        skipDuplicateCheck: Boolean = false,
        importAtlasDownscaleStrategy: AtlasOfflineDownscaleStrategy? = null,
        skipAtlasDownscalePrompt: Boolean = false
    ) {
        ModImportFlowCoordinator.startModJarImport(
            host = host,
            executor = executor,
            uris = uris,
            callbacks = ModImportFlowCoordinator.Callbacks(
                setBusy = { busy, message, operation, progressPercent ->
                    setBusy(
                        busy = busy,
                        message = message,
                        operation = operation,
                        progressPercent = progressPercent
                    )
                },
                showNotice = { message, duration ->
                    showToast(host, message, duration)
                },
                onImportApplied = {
                    refreshStatus(host)
                },
                onFlowFinished = {
                    onCompleted?.invoke()
                }
            ),
            replaceExistingDuplicates = replaceExistingDuplicates,
            duplicateReplaceOptions = duplicateReplaceOptions,
            skipDuplicateCheck = skipDuplicateCheck,
            importAtlasDownscaleStrategy = importAtlasDownscaleStrategy,
            skipAtlasDownscalePrompt = skipAtlasDownscalePrompt
        )
    }

    private fun applySnapshot(
        host: Activity,
        snapshot: SettingsRepository.SettingsSnapshot
    ) {
        val rendering = snapshot.rendering
        val jvm = snapshot.jvm
        val input = snapshot.input
        val diagnostics = snapshot.diagnostics
        val market = snapshot.market
        val compatibility = snapshot.compatibility
        val rendererDecision = rendering.rendererDecision
        val mobileGluesSettings = rendering.mobileGluesSettings
        val rendererBackendOptions = rendererDecision.availableBackends.map { availability ->
            RendererBackendOptionState(
                backend = availability.backend,
                available = availability.available,
                reasonText = availability.describeUnavailable(host)
            )
        }
        uiState = uiState.copy(
            themeMode = snapshot.themeMode,
            themeColor = snapshot.themeColor,
            launcherIconMode = snapshot.launcherIconMode,
            chromeBackgroundOpacity = snapshot.chromeBackgroundOpacity,
            bootOverlayStyle = snapshot.bootOverlayStyle,
            bootOverlayAnimation = snapshot.bootOverlayAnimation,
            bootOverlayImageConfig = snapshot.bootOverlayImageConfig,
            playerName = snapshot.playerName,
            selectedRenderScale = rendering.renderScale,
            selectedTargetFps = rendering.targetFps,
            virtualResolutionMode = rendering.virtualResolutionMode,
            renderSurfaceBackend = rendering.renderSurfaceBackend,
            rendererSelectionMode = rendering.rendererSelectionMode,
            manualRendererBackend = rendering.manualRendererBackend,
            mobileGluesAnglePolicy = mobileGluesSettings.anglePolicy,
            mobileGluesNoErrorPolicy = mobileGluesSettings.noErrorPolicy,
            mobileGluesMultidrawMode = mobileGluesSettings.multidrawMode,
            mobileGluesExtComputeShaderEnabled = mobileGluesSettings.extComputeShaderEnabled,
            mobileGluesExtTimerQueryEnabled = mobileGluesSettings.extTimerQueryEnabled,
            mobileGluesExtDirectStateAccessEnabled = mobileGluesSettings.extDirectStateAccessEnabled,
            mobileGluesGlslCacheSizePreset = mobileGluesSettings.glslCacheSizePreset,
            mobileGluesAngleDepthClearFixMode = mobileGluesSettings.angleDepthClearFixMode,
            mobileGluesCustomGlVersion = mobileGluesSettings.customGlVersion,
            mobileGluesFsr1QualityPreset = mobileGluesSettings.fsr1QualityPreset,
            autoSelectedRendererBackend = rendererDecision.automaticBackend,
            effectiveRendererBackend = rendererDecision.effectiveBackend,
            effectiveRenderSurfaceBackend = rendererDecision.effectiveSurfaceBackend,
            rendererBackendOptions = rendererBackendOptions,
            rendererFallbackText = rendererDecision.fallbackSummary(host),
            surfaceBackendForcedByRenderer = rendererDecision.surfaceBackendForced,
            gpuResourceGuardianMode = rendering.gpuResourceGuardianMode,
            gpuResourceGuardianPressureDownscaleEnabled =
                rendering.gpuResourceGuardianPressureDownscaleEnabled,
            selectedJvmHeapMaxMb = jvm.heapMaxMb,
            compressedPointersEnabled = jvm.compressedPointersEnabled,
            stringDeduplicationEnabled = jvm.stringDeduplicationEnabled,
            backBehavior = input.backBehavior,
            manualDismissBootOverlay = input.manualDismissBootOverlay,
            specialKeyInputMode = input.specialKeyInputMode,
            showFloatingMouseWindow = input.showFloatingMouseWindow,
            touchMouseInteractionMode = input.touchMouseInteractionMode,
            touchDoubleClickAsRightClick = input.touchDoubleClickAsRightClick,
            ignoreLongPressRightClickWhilePlayingCard = input.ignoreLongPressRightClickWhilePlayingCard,
            builtInSoftKeyboardEnabled = input.builtInSoftKeyboardEnabled,
            hapticFeedbackEnabled = input.hapticFeedbackEnabled,
            autoSwitchLeftAfterRightClick = input.autoSwitchLeftAfterRightClick,
            showModFileName = input.showModFileName,
            mobileHudEnabled = input.mobileHudEnabled,
            compendiumUpgradeTouchFixEnabled = input.compendiumUpgradeTouchFixEnabled,
            avoidDisplayCutout = input.avoidDisplayCutout,
            cropScreenBottom = input.cropScreenBottom,
            ramSaverEnabled = input.ramSaverEnabled,
            mtsPatchCacheEnabled = input.mtsPatchCacheEnabled,
            keepScreenOnTimeoutMinutes = input.keepScreenOnTimeoutMinutes,
            showGamePerformanceOverlay = diagnostics.showGamePerformanceOverlay,
            sustainedPerformanceModeEnabled = diagnostics.sustainedPerformanceModeEnabled,
            systemGameModeDisplayName = host.getString(diagnostics.systemGameMode.displayNameResId),
            systemGameModeDescription = host.getString(diagnostics.systemGameMode.descriptionResId),
            lwjglDebugEnabled = diagnostics.lwjglDebugEnabled,
            preloadAllJreLibrariesEnabled = diagnostics.preloadAllJreLibrariesEnabled,
            logcatCaptureEnabled = diagnostics.logcatCaptureEnabled,
            launcherLogcatCaptureEnabled = diagnostics.launcherLogcatCaptureEnabled,
            jvmLogcatMirrorEnabled = diagnostics.jvmLogcatMirrorEnabled,
            gpuResourceDiagEnabled = diagnostics.gpuResourceDiagEnabled,
            gdxPadCursorDebugEnabled = diagnostics.gdxPadCursorDebugEnabled,
            glBridgeSwapHeartbeatDebugEnabled = diagnostics.glBridgeSwapHeartbeatDebugEnabled,
            touchscreenInputMode = TouchscreenInputMode.fromSettings(
                touchscreenEnabled = input.touchscreenEnabled,
                nativeTouchscreenAllowlistEnabled =
                    compatibility.nativeTouchscreenAllowlistCompatEnabled
            ),
            cardPlayOptimizationMode = input.cardPlayOptimizationMode,
            touchIndicatorEnabled = input.touchIndicatorEnabled,
            gameplayFontScale = input.fontScale,
            gameplayLargerUiEnabled = input.largerUiEnabled,
            workshopMaxConcurrentDownloads = market.workshopMaxConcurrentDownloads,
            workshopDownloadThreads = market.workshopDownloadThreads,
            workshopWattAccelerationEnabled = market.workshopWattAccelerationEnabled,
            workshopSteamLanguage = market.workshopSteamLanguage,
            workshopAutoImportEnabled = market.workshopAutoImportEnabled,
            workshopAutoImportAtlasDownscaleEnabled = market.workshopAutoImportAtlasDownscaleEnabled,
            workshopAutoImportAtlasDownscaleMaxEdgePx = market.workshopAutoImportAtlasDownscaleMaxEdgePx,
            baiduTranslationCredentialsConfigured = market.baiduTranslationCredentialsConfigured,
        )
    }

    private fun prewarmMtsClasspathAfterImport(host: Activity): String? {
        return try {
            val prepared = MtsClasspathWarmupCoordinator.prewarmIfReady(host) { _, message ->
                host.runOnUiThread {
                    setBusy(
                        busy = true,
                        message = UiText.DynamicString(message),
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

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        return generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
            .any { it is T }
    }

    fun onSavesArchivePicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        if (LauncherPreferences.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD) {
            showSteamCloudSaveImportNoticeDialog(host, uri)
            return
        }
        importSavesArchive(
            host = host,
            uri = uri,
            targetRoot = RuntimePaths.stsRoot(host),
            targetLabel = SteamCloudSaveMode.INDEPENDENT.displayName(host),
        )
    }

    private fun showSteamCloudSaveImportNoticeDialog(host: Activity, uri: Uri) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.settings_save_import_target_dialog_title)
            .setMessage(R.string.settings_save_import_target_dialog_message)
            .setPositiveButton(R.string.settings_save_import_target_independent_action) { _, _ ->
                importSavesArchive(
                    host = host,
                    uri = uri,
                    targetRoot = SteamCloudSaveProfileManager.profileRoot(
                        host,
                        SteamCloudSaveMode.INDEPENDENT
                    ),
                    targetLabel = SteamCloudSaveMode.INDEPENDENT.displayName(host),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSavesArchive(
        host: Activity,
        uri: Uri,
        targetRoot: File,
        targetLabel: String,
    ) {
        setBusy(true, UiText.StringResource(R.string.settings_busy_importing_save_archive))
        executor.execute {
            try {
                val result = SettingsFileService.importSaveArchive(host, uri, targetRoot)
                host.runOnUiThread {
                    val message = if (result.backupLabel.isNullOrEmpty()) {
                        UiText.StringResource(
                            R.string.settings_save_imported_to_target,
                            result.importedFiles,
                            targetLabel,
                        )
                    } else {
                        UiText.StringResource(
                            R.string.settings_save_imported_with_backup_to_target,
                            result.importedFiles,
                            targetLabel,
                            result.backupLabel
                        )
                    }
                    showToast(host, message)
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showSaveImportFailedDialog(host, error)
                    refreshStatus(host)
                }
            }
        }
    }

    fun onSavesExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_exporting_save_archive))
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportSaveBundle(host, uri)
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(R.string.settings_save_exported, exportedCount)
                    )
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_save_export_failed_prefix),
                                error
                            )
                        )
                    )
                    refreshStatus(host)
                }
            }
        }
    }

    private fun setBusy(
        busy: Boolean,
        message: UiText?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
        progressPercent: Int? = null
    ) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyOperation = operation,
                busyMessage = message,
                busyProgressPercent = progressPercent?.coerceIn(0, 100)
            )
        } else {
            uiState.copy(
                busy = false,
                busyOperation = UiBusyOperation.NONE,
                busyMessage = null,
                busyProgressPercent = null
            )
        }
    }

    private fun replacePendingStsJarImport(pendingImport: PendingStsJarImport) {
        val previous = pendingStsJarImport
        pendingStsJarImport = pendingImport
        previous?.stagedFile?.delete()
    }

    private fun clearPendingStsJarImport(notifyCancelled: Boolean) {
        val pendingImport = pendingStsJarImport
        pendingStsJarImport = null
        if (uiState.stsJarIntegrityDialogState != null) {
            uiState = uiState.copy(stsJarIntegrityDialogState = null)
        }
        pendingImport?.stagedFile?.delete()
        if (notifyCancelled) {
            pendingImport?.onCompleted?.invoke(false)
        }
    }

    private fun createStsJarImportScratchFile(host: Activity): File {
        val scratchDir = File(host.cacheDir, "sts-jar-import").apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Failed to create import scratch directory: $absolutePath")
            }
        }
        return File(scratchDir, "desktop-1.0-${System.nanoTime()}.jar")
    }

    private fun importPreparedLocalStsJar(
        host: Activity,
        sourceFile: File,
        showSuccessToast: Boolean,
        onCompleted: ((Boolean) -> Unit)?,
    ) {
        try {
            SettingsFileService.importFileToFileAtomically(
                sourceFile = sourceFile,
                targetFile = RuntimePaths.importedStsJar(host),
                validator = StsJarValidator::validate
            )
            MtsStartupCacheCoordinator.invalidate(host)
            val warmupWarning = prewarmMtsClasspathAfterImport(host)
            host.runOnUiThread {
                setBusy(false, null)
                if (showSuccessToast) {
                    showToast(host, UiText.StringResource(R.string.sts_jar_import_success), Toast.LENGTH_SHORT)
                }
                if (warmupWarning != null) {
                    showToast(host, warmupWarning, Toast.LENGTH_LONG)
                }
                refreshStatus(host)
                onCompleted?.invoke(true)
            }
        } catch (error: Throwable) {
            host.runOnUiThread {
                setBusy(false, null)
                showToast(
                    host,
                    UiText.StringResource(
                        R.string.sts_jar_import_failed,
                        resolveThrowableMessage(host, error)
                    ),
                    Toast.LENGTH_LONG
                )
                refreshStatus(host)
                onCompleted?.invoke(false)
            }
        } finally {
            if (sourceFile.exists()) {
                sourceFile.delete()
            }
        }
    }

    private fun showModNameMigrationStorageInsufficientDialog(
        host: Activity,
        spaceCheck: ModManifestNameMigrationSpaceCheck
    ) {
        AlertDialog.Builder(host)
            .setTitle(R.string.main_mod_name_migration_storage_insufficient_title)
            .setMessage(
                host.getString(
                    R.string.main_mod_name_migration_storage_insufficient_message,
                    formatMigrationBytes(spaceCheck.requiredExtraBytes),
                    formatMigrationBytes(spaceCheck.availableBytes)
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun updateQuickStartSteamDownloadProgress(progress: SteamStsJarDownloadProgress) {
        uiState = uiState.copy(
            quickStartSteamDownloadPhase = progress.phase,
            quickStartSteamDownloadedBytes = progress.downloadedBytes,
            quickStartSteamTotalBytes = progress.totalBytes
        )
    }

    private fun buildNativeLibraryInstallProgressMessage(
        host: Activity,
        progress: NativeLibraryMarketInstallProgress
    ): String {
        val downloadedText = NativeLibraryMarketService.formatTransferBytes(progress.downloadedBytes)
        val speedText = "${NativeLibraryMarketService.formatTransferBytes(progress.bytesPerSecond)}/s"
        val totalBytes = progress.totalBytes
        return if (totalBytes != null) {
            host.getString(
                R.string.settings_native_library_market_downloading_with_total,
                progress.fileName,
                progress.fileIndex,
                progress.fileCount,
                downloadedText,
                NativeLibraryMarketService.formatTransferBytes(totalBytes),
                speedText
            )
        } else {
            host.getString(
                R.string.settings_native_library_market_downloading_without_total,
                progress.fileName,
                progress.fileIndex,
                progress.fileCount,
                downloadedText,
                speedText
            )
        }
    }

    private fun buildQuickStartSteamDownloadProgressMessage(
        host: Activity,
        progress: SteamStsJarDownloadProgress
    ): String {
        return when (progress.phase) {
            SteamStsJarDownloadPhase.CONNECTING -> host.getString(R.string.quick_start_steam_download_connecting)
            SteamStsJarDownloadPhase.RESOLVING -> host.getString(R.string.quick_start_steam_download_resolving)
            SteamStsJarDownloadPhase.DOWNLOADING -> {
                val totalBytes = progress.totalBytes
                if (totalBytes != null && totalBytes > 0L) {
                    host.getString(
                        R.string.quick_start_steam_downloading_with_total,
                    )
                } else {
                    host.getString(
                        R.string.quick_start_steam_downloading_without_total,
                        formatQuickStartTransferBytes(progress.downloadedBytes)
                    )
                }
            }
        }
    }

    private fun formatQuickStartTransferBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun showToast(
        host: Activity,
        message: UiText,
        duration: Int = Toast.LENGTH_LONG
    ) {
        LauncherTransientNoticeBus.show(host, message, duration)
    }

    private fun showToast(
        host: Activity,
        message: String,
        duration: Int = Toast.LENGTH_LONG
    ) {
        LauncherTransientNoticeBus.show(host, message, duration)
    }

    private fun showSteamCloudSwitchPromptAfterLoginDialog(host: Activity, accountName: String) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.settings_steam_cloud_switch_prompt_title)
            .setMessage(
                host.getString(
                    R.string.settings_steam_cloud_switch_prompt_message,
                    accountName,
                )
            )
            .setPositiveButton(R.string.settings_steam_cloud_switch_prompt_confirm) { _, _ ->
                onSteamCloudSaveModeChanged(host, SteamCloudSaveMode.STEAM_CLOUD)
            }
            .setNegativeButton(R.string.settings_steam_cloud_switch_prompt_keep_independent, null)
            .setCancelable(false)
            .show()
        dialog.setCanceledOnTouchOutside(false)
    }

    private fun showSaveImportFailedDialog(host: Activity, error: Throwable) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        AlertDialog.Builder(host)
            .setTitle(R.string.settings_save_import_failed_dialog_title)
            .setMessage(
                host.getString(
                    R.string.settings_save_import_failed_dialog_message,
                    resolveThrowableMessage(host, error)
                )
            )
            .setPositiveButton(R.string.common_action_confirm, null)
            .show()
    }

    private fun summarizeNativeLibraryMarketError(
        host: Activity,
        error: Throwable,
        source: UpdateSource? = null,
    ): String {
        val message = GithubMirrorFallback.summarize(error)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: host.getString(R.string.settings_native_library_market_error_unknown)
        val displayName = source?.displayName?.trim().orEmpty()
        return if (displayName.isNotEmpty() && !message.contains("${displayName}:")) {
            "$displayName: $message"
        } else {
            message
        }
    }

    private fun hasBundledAsset(host: Activity, assetPath: String): Boolean {
        return RuntimeResourceProvider(host).exists(assetPath)
    }

    private fun buildStatusText(
        host: Activity,
        snapshot: SettingsRepository.SettingsSnapshot,
        hasJar: Boolean,
        optionalEnabled: Int,
        optionalTotal: Int,
        coreMtsStatus: CoreDependencyStatus,
        coreBaseModStatus: CoreDependencyStatus,
        coreStsLibStatus: CoreDependencyStatus,
        coreRuntimeCompatStatus: CoreDependencyStatus,
        coreFloatingToolsStatus: CoreDependencyStatus,
        coreRamSaverStatus: CoreDependencyStatus,
        deviceRuntimeStatus: DeviceRuntimeStatus
    ): String {
        val rendering = snapshot.rendering
        val jvm = snapshot.jvm
        val input = snapshot.input
        val diagnostics = snapshot.diagnostics
        val compatibility = snapshot.compatibility
        val rendererDecision = rendering.rendererDecision
        val lines = mutableListOf<String>()

        lines += host.getString(R.string.settings_status_section_core_dependencies)
        lines += host.getString(
            R.string.settings_status_desktop_jar,
            availabilityText(host, hasJar)
        )
        lines += formatCoreDependencyLine(host, coreMtsStatus)
        lines += formatCoreDependencyLine(host, coreBaseModStatus)
        lines += formatCoreDependencyLine(host, coreStsLibStatus)
        lines += formatCoreDependencyLine(host, coreRuntimeCompatStatus)
        lines += formatCoreDependencyLine(host, coreFloatingToolsStatus)
        lines += formatCoreDependencyLine(host, coreRamSaverStatus)
        lines += host.getString(
            R.string.settings_status_optional_mods,
            optionalEnabled,
            optionalTotal
        )

        lines += ""
        lines += host.getString(R.string.settings_status_section_device_info)
        lines += host.getString(
            R.string.settings_status_cpu_model,
            displayInfoValue(host, deviceRuntimeStatus.cpuModel)
        )
        lines += host.getString(
            R.string.settings_status_cpu_arch,
            displayInfoValue(host, deviceRuntimeStatus.cpuArch)
        )
        lines += host.getString(
            R.string.settings_status_memory,
            formatBytes(host, deviceRuntimeStatus.availableMemoryBytes),
            formatBytes(host, deviceRuntimeStatus.totalMemoryBytes)
        )

        lines += ""
        lines += host.getString(R.string.settings_status_section_launch_compat)
        lines += host.getString(R.string.settings_status_player_name, snapshot.playerName)
        lines += host.getString(
            R.string.settings_status_render_scale,
            RenderScaleService.format(rendering.renderScale)
        )
        lines += host.getString(R.string.settings_status_target_fps, rendering.targetFps)
        lines += host.getString(
            R.string.settings_status_virtual_resolution_mode,
            virtualResolutionModeDisplayName(host, rendering.virtualResolutionMode)
        )
        lines += host.getString(
            R.string.settings_status_renderer_selection_mode,
            rendering.rendererSelectionMode.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_renderer_auto_select,
            rendererDecision.autoSelectionSummary(host)
        )
        lines += host.getString(
            R.string.settings_status_renderer_effective,
            rendererDecision.effectiveRendererSummary(host)
        )
        rendererDecision.fallbackSummary(host)?.let {
            lines += host.getString(R.string.settings_status_renderer_fallback, it)
        }
        lines += host.getString(
            R.string.settings_status_mobileglues_angle_policy,
            rendering.mobileGluesSettings.anglePolicy.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_mobileglues_multidraw,
            rendering.mobileGluesSettings.multidrawMode.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_mobileglues_no_error,
            rendering.mobileGluesSettings.noErrorPolicy.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_mobileglues_custom_gl,
            rendering.mobileGluesSettings.customGlVersion.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_mobileglues_fsr1,
            rendering.mobileGluesSettings.fsr1QualityPreset.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_render_surface_requested,
            rendering.renderSurfaceBackend.displayName(host)
        )
        lines += buildString {
            append(
                host.getString(
                    R.string.settings_status_render_surface_effective,
                    rendererDecision.effectiveSurfaceBackend.displayName(host)
                )
            )
            if (rendererDecision.surfaceBackendForced) {
                append(
                    host.getString(
                        R.string.settings_status_render_surface_forced_suffix,
                        rendererDecision.effectiveBackend.displayName
                    )
                )
            }
        }
        lines += host.getString(
            R.string.settings_status_gpu_resource_guardian,
            rendering.gpuResourceGuardianMode.displayName(host)
        )
        val guardianPressureDownscaleActive =
            rendering.gpuResourceGuardianMode != GpuResourceGuardianMode.OFF &&
                rendering.gpuResourceGuardianMode != GpuResourceGuardianMode.LEGACY &&
                rendering.gpuResourceGuardianPressureDownscaleEnabled
        val runtimeTexturePressureDownscaleActive =
            (rendering.gpuResourceGuardianMode == GpuResourceGuardianMode.LEGACY ||
                guardianPressureDownscaleActive) &&
                compatibility.runtimeDownscaleMaterialPolicy.allowsAnyTextureDownscale()
        val runtimeFboPressureDownscaleActive =
            (rendering.gpuResourceGuardianMode == GpuResourceGuardianMode.LEGACY ||
                guardianPressureDownscaleActive) &&
                compatibility.runtimeDownscaleMaterialPolicy.offscreenFrameBuffers
        lines += host.getString(
            R.string.settings_status_gpu_resource_guardian_pressure_downscale,
            toggleStateText(host, guardianPressureDownscaleActive)
        )
        lines += host.getString(
            R.string.settings_status_runtime_texture_pressure_downscale,
            toggleStateText(host, runtimeTexturePressureDownscaleActive)
        )
        lines += host.getString(
            R.string.settings_status_runtime_fbo_pressure_downscale,
            toggleStateText(host, runtimeFboPressureDownscaleActive)
        )
        lines += host.getString(
            R.string.settings_status_runtime_downscale_materials,
            formatRuntimeDownscaleMaterials(host, compatibility.runtimeDownscaleMaterialPolicy)
        )
        lines += host.getString(
            R.string.settings_status_import_downscale_materials,
            formatImportDownscaleMaterials(host, compatibility.importDownscaleMaterialPolicy)
        )
        lines += host.getString(
            R.string.settings_status_jvm_heap,
            jvm.heapStartMb,
            jvm.heapMaxMb
        )
        lines += host.getString(
            R.string.settings_status_compressed_pointers,
            toggleStateText(host, jvm.compressedPointersEnabled)
        )
        lines += host.getString(
            R.string.settings_status_string_dedup,
            toggleStateText(host, jvm.stringDeduplicationEnabled)
        )
        lines += host.getString(
            R.string.settings_status_back_behavior,
            input.backBehavior.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_touchscreen_mode,
            TouchscreenInputMode.fromSettings(
                touchscreenEnabled = input.touchscreenEnabled,
                nativeTouchscreenAllowlistEnabled =
                    compatibility.nativeTouchscreenAllowlistCompatEnabled
            ).displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_card_play_optimization,
            input.cardPlayOptimizationMode.displayName(host)
        )
        lines += host.getString(
            R.string.settings_status_gameplay_font_scale,
            GameplaySettingsService.formatFontScale(input.fontScale)
        )
        lines += host.getString(
            R.string.settings_status_gameplay_larger_ui,
            toggleStateText(host, input.largerUiEnabled)
        )
        lines += host.getString(
            R.string.settings_status_mobile_hud,
            toggleStateText(host, input.mobileHudEnabled)
        )
        lines += host.getString(
            R.string.settings_status_compendium_touch_fix,
            toggleStateText(host, input.compendiumUpgradeTouchFixEnabled)
        )
        lines += host.getString(
            R.string.settings_status_avoid_display_cutout,
            toggleStateText(host, input.avoidDisplayCutout)
        )
        lines += host.getString(
            R.string.settings_status_crop_screen_bottom,
            toggleStateText(host, input.cropScreenBottom)
        )
        lines += host.getString(
            R.string.settings_status_keep_screen_on_timeout,
            keepScreenOnTimeoutLabel(host, input.keepScreenOnTimeoutMinutes)
        )
        lines += host.getString(
            R.string.settings_status_performance_overlay,
            toggleStateText(host, diagnostics.showGamePerformanceOverlay)
        )
        lines += host.getString(
            R.string.settings_status_sustained_performance,
            toggleStateText(host, diagnostics.sustainedPerformanceModeEnabled)
        )
        lines += host.getString(
            R.string.settings_status_system_game_mode,
            host.getString(diagnostics.systemGameMode.displayNameResId)
        )
        lines += host.getString(
            R.string.settings_status_system_game_mode_detail,
            host.getString(diagnostics.systemGameMode.descriptionResId)
        )
        lines += host.getString(
            R.string.settings_status_manual_dismiss_boot_overlay,
            toggleStateText(host, input.manualDismissBootOverlay)
        )
        lines += host.getString(
            R.string.settings_status_special_key_input_mode,
            host.getString(input.specialKeyInputMode.displayNameResId())
        )
        lines += host.getString(
            R.string.status_touch_mouse_interaction_format,
            touchMouseInteractionLabel(host, input.touchMouseInteractionMode)
        )
        lines += host.getString(
            R.string.status_built_in_soft_keyboard_format,
            toggleStateText(host, input.builtInSoftKeyboardEnabled)
        )
        lines += host.getString(
            R.string.status_haptic_feedback_format,
            toggleStateText(host, input.hapticFeedbackEnabled)
        )
        lines += host.getString(
            R.string.settings_status_auto_switch_left_after_right_click,
            toggleStateText(host, input.autoSwitchLeftAfterRightClick)
        )
        lines += host.getString(
            R.string.settings_status_lwjgl_debug,
            toggleStateText(host, diagnostics.lwjglDebugEnabled)
        )
        lines += host.getString(
            R.string.settings_status_preload_all_jre,
            toggleStateText(host, diagnostics.preloadAllJreLibrariesEnabled)
        )
        lines += host.getString(
            R.string.settings_status_logcat_capture,
            toggleStateText(host, diagnostics.logcatCaptureEnabled)
        )
        lines += host.getString(
            R.string.settings_status_launcher_logcat_capture,
            toggleStateText(host, diagnostics.launcherLogcatCaptureEnabled)
        )
        lines += host.getString(
            R.string.settings_status_jvm_logcat_mirror,
            toggleStateText(host, diagnostics.jvmLogcatMirrorEnabled)
        )
        lines += host.getString(
            R.string.settings_status_gpu_resource_diag,
            toggleStateText(host, diagnostics.gpuResourceDiagEnabled)
        )
        lines += host.getString(
            R.string.settings_status_gdx_pad_cursor_debug,
            toggleStateText(host, diagnostics.gdxPadCursorDebugEnabled)
        )
        lines += host.getString(
            R.string.settings_status_glbridge_swap_heartbeat,
            toggleStateText(host, diagnostics.glBridgeSwapHeartbeatDebugEnabled)
        )
        lines += host.getString(
            R.string.settings_status_global_atlas_filter_compat,
            toggleStateText(host, compatibility.globalAtlasFilterCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_mod_manifest_root_compat,
            toggleStateText(host, compatibility.modManifestRootCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_runtime_texture_compat,
            toggleStateText(host, compatibility.runtimeTextureCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_texture_pressure_downscale_divisor,
            compatibility.texturePressureDownscaleDivisor
        )
        lines += host.getString(
            R.string.settings_status_force_linear_mipmap_filter,
            toggleStateText(host, compatibility.forceLinearMipmapFilterEnabled)
        )
        lines += host.getString(
            R.string.settings_status_android_lwjgl_frame_pacing,
            toggleStateText(host, compatibility.androidLwjglFramePacingCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_lwjgl_hot_loop_noop_trim,
            toggleStateText(host, compatibility.lwjglHotLoopNoopTrimCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_default_framebuffer_fast_rebind,
            toggleStateText(host, compatibility.defaultFramebufferFastRebindCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_native_pre_swap_pacing,
            toggleStateText(host, compatibility.nativePreSwapPacingCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_egl_swap_interval_pacing,
            toggleStateText(host, compatibility.eglSwapIntervalPacingCompatEnabled)
        )
        lines += host.getString(
            R.string.settings_status_bundled_jre_path,
            "app/src/main/assets/components/jre"
        )
        return lines.joinToString("\n")
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

    private fun formatCoreDependencyLine(host: Activity, status: CoreDependencyStatus): String {
        if (!status.available) {
            return host.getString(R.string.settings_status_core_dependency_missing, status.label)
        }
        val sourceLabel = when (status.source.lowercase(Locale.ROOT)) {
            "imported" -> host.getString(R.string.settings_status_source_imported)
            "bundled" -> host.getString(R.string.settings_status_source_bundled)
            else -> status.source
        }
        return host.getString(
            R.string.settings_status_core_dependency_available,
            status.label,
            sourceLabel,
            displayInfoValue(host, status.version)
        )
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

    private fun formatBytes(host: Activity, bytes: Long): String {
        if (bytes <= 0L) {
            return host.getString(R.string.settings_status_unknown)
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

    private fun formatMigrationBytes(bytes: Long): String {
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

    private fun displayInfoValue(host: Activity, value: String): String {
        return if (value.equals("unknown", ignoreCase = true)) {
            host.getString(R.string.settings_status_unknown)
        } else {
            value
        }
    }

    private fun availabilityText(host: Activity, available: Boolean): String {
        return host.getString(
            if (available) {
                R.string.settings_status_available
            } else {
                R.string.settings_status_missing
            }
        )
    }

    private fun toggleStateText(host: Activity, enabled: Boolean): String {
        return host.getString(
            if (enabled) {
                R.string.settings_status_enabled
            } else {
                R.string.settings_status_disabled
            }
        )
    }

    private fun RuntimeDownscaleMaterialPolicy.allowsAnyTextureDownscale(): Boolean {
        return ordinaryTextures || textureAtlasPages.enabled || spineTextures
    }

    private fun formatRuntimeDownscaleMaterials(
        host: Activity,
        policy: RuntimeDownscaleMaterialPolicy
    ): String {
        val enabled = ArrayList<String>()
        if (policy.ordinaryTextures) enabled += host.getString(R.string.compat_runtime_downscale_ordinary_texture_title)
        if (policy.textureAtlasPages.enabled) {
            enabled += host.getString(R.string.compat_runtime_downscale_texture_atlas_title)
        }
        if (policy.spineTextures) enabled += host.getString(R.string.compat_runtime_downscale_spine_title)
        if (policy.offscreenFrameBuffers) enabled += host.getString(R.string.compat_runtime_downscale_fbo_title)
        return enabled.joinToString().ifBlank { host.getString(R.string.settings_status_none) }
    }

    private fun formatImportDownscaleMaterials(
        host: Activity,
        policy: ImportDownscaleMaterialPolicy
    ): String {
        val enabled = ArrayList<String>()
        if (policy.spineAtlasPages) enabled += host.getString(R.string.compat_import_downscale_spine_atlas_title)
        if (policy.ordinaryAtlasPages) enabled += host.getString(R.string.compat_import_downscale_ordinary_atlas_title)
        return enabled.joinToString().ifBlank { host.getString(R.string.settings_status_none) }
    }

    private fun touchMouseInteractionLabel(host: Activity, mode: TouchMouseInteractionMode): String {
        return host.getString(mode.displayNameResId())
    }

    private fun keepScreenOnTimeoutLabel(host: Activity, timeoutMinutes: Int): String {
        return if (timeoutMinutes == LauncherPreferences.KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES) {
            host.getString(R.string.settings_keep_screen_on_timeout_always)
        } else {
            host.getString(R.string.settings_keep_screen_on_timeout_minutes, timeoutMinutes)
        }
    }

    private fun virtualResolutionModeDisplayName(
        host: Activity,
        mode: VirtualResolutionMode
    ): String {
        return when (mode) {
            VirtualResolutionMode.FULLSCREEN_FILL ->
                host.getString(R.string.settings_virtual_resolution_mode_fullscreen_fill)
            VirtualResolutionMode.RESOLUTION_1080P ->
                host.getString(R.string.settings_virtual_resolution_mode_1080p)
            VirtualResolutionMode.RESOLUTION_720P ->
                host.getString(R.string.settings_virtual_resolution_mode_720p)
            VirtualResolutionMode.RATIO_4_3 ->
                host.getString(R.string.settings_virtual_resolution_mode_4_3)
            VirtualResolutionMode.RATIO_16_9 ->
                host.getString(R.string.settings_virtual_resolution_mode_16_9)
        }
    }

    private fun RendererAvailability.describeUnavailable(host: Activity): String? {
        if (available) {
            return null
        }
        val parts = ArrayList<String>(2)
        if (reasons.contains(io.stamethyst.backend.render.RendererAvailabilityReason.VULKAN_UNSUPPORTED)) {
            parts += host.getString(R.string.settings_renderer_requires_vulkan_support)
        }
        if (missingLibraries.isNotEmpty()) {
            parts += host.getString(
                R.string.settings_renderer_missing_libraries,
                missingLibraries.joinToString(", ")
            )
        }
        return parts.joinToString("; ")
            .ifBlank { host.getString(R.string.settings_renderer_unavailable) }
    }

    private fun RendererDecision.autoSelectionSummary(host: Activity): String {
        return automaticBackend.displayName
    }

    private fun RendererDecision.effectiveRendererSummary(host: Activity): String {
        return buildString {
            append(effectiveBackend.displayName)
            if (selectionMode == RendererSelectionMode.AUTO) {
                append(host.getString(R.string.settings_renderer_effective_auto_suffix))
            } else if (usedAutomaticFallback) {
                append(host.getString(R.string.settings_renderer_effective_auto_fallback_suffix))
            }
        }
    }

    private fun RendererDecision.fallbackSummary(host: Activity): String? {
        val fallback = manualFallbackAvailability ?: return null
        val manualLabel = manualBackend?.displayName
            ?: host.getString(R.string.settings_renderer_manual_label)
        val reasonText = fallback.describeUnavailable(host)
            ?: host.getString(R.string.settings_renderer_unavailable)
        return host.getString(
            R.string.settings_renderer_fallback_format,
            manualLabel,
            reasonText,
            automaticBackend.displayName
        )
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
            RuntimeResourceProvider(host).open(assetPath).use { input ->
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

    private fun saveBackBehaviorSelection(host: Activity, behavior: BackBehavior) {
        LauncherPreferences.saveBackBehavior(host, behavior)
    }

    private fun saveManualDismissBootOverlaySelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveManualDismissBootOverlay(host, enabled)
    }

    private fun saveSpecialKeyInputModeSelection(host: Activity, mode: SpecialKeyInputMode) {
        LauncherPreferences.saveSpecialKeyInputMode(host, mode)
    }

    private fun saveTouchMouseInteractionModeSelection(
        host: Activity,
        mode: TouchMouseInteractionMode
    ) {
        LauncherPreferences.saveTouchMouseInteractionMode(host, mode)
    }

    private fun saveTouchDoubleClickAsRightClickSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveTouchDoubleClickAsRightClick(host, enabled)
    }

    private fun saveIgnoreLongPressRightClickWhilePlayingCardSelection(
        host: Activity,
        enabled: Boolean
    ) {
        LauncherPreferences.saveIgnoreLongPressRightClickWhilePlayingCard(host, enabled)
    }

    private fun saveBuiltInSoftKeyboardSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setBuiltInSoftKeyboardEnabled(host, enabled)
    }

    private fun saveHapticFeedbackSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setHapticFeedbackEnabled(host, enabled)
    }

    private fun saveAutoSwitchLeftAfterRightClickSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveAutoSwitchLeftAfterRightClick(host, enabled)
    }

    private fun saveShowModFileNameSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveShowModFileName(host, enabled)
    }

    private fun saveMobileHudEnabledSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveMobileHudEnabled(host, enabled)
    }

    private fun saveCompendiumUpgradeTouchFixSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.saveCompendiumUpgradeTouchFixEnabled(host, enabled)
    }

    private fun saveDisplayCutoutAvoidanceSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setDisplayCutoutAvoidanceEnabled(host, enabled)
    }

    private fun saveScreenBottomCropSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setScreenBottomCropEnabled(host, enabled)
    }

    private fun saveRamSaverEnabledSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setRamSaverEnabled(host, enabled)
    }

    private fun saveMtsPatchCacheEnabledSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setMtsPatchCacheEnabled(host, enabled)
        if (!enabled) {
            MtsPatchCacheCoordinator.clear(host)
        }
    }

    private fun saveLwjglDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLwjglDebugEnabled(host, enabled)
    }

    private fun savePreloadAllJreLibrariesSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setPreloadAllJreLibrariesEnabled(host, enabled)
    }

    private fun saveLogcatCaptureSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLogcatCaptureEnabled(host, enabled)
    }

    private fun saveLauncherLogcatCaptureSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setLauncherLogcatCaptureEnabled(host, enabled)
    }

    private fun saveJvmLogcatMirrorSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmLogcatMirrorEnabled(host, enabled)
    }

    private fun saveGpuResourceDiagSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGpuResourceDiagEnabled(host, enabled)
    }

    private fun saveGdxPadCursorDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGdxPadCursorDebugEnabled(host, enabled)
    }

    private fun saveGlBridgeSwapHeartbeatDebugSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGlBridgeSwapHeartbeatDebugEnabled(host, enabled)
    }

    private fun saveRendererSelectionModeSelection(
        host: Activity,
        mode: RendererSelectionMode
    ) {
        LauncherPreferences.saveRendererSelectionMode(host, mode)
    }

    private fun saveManualRendererBackendSelection(host: Activity, backend: RendererBackend) {
        LauncherPreferences.saveManualRendererBackend(host, backend)
    }

    private fun saveGpuResourceGuardianModeSelection(host: Activity, mode: GpuResourceGuardianMode) {
        LauncherPreferences.saveGpuResourceGuardianMode(host, mode)
    }

    private fun persistMobileGluesSettings(
        host: Activity,
        @StringRes failureMessageResId: Int,
        transform: (MobileGluesSettings) -> MobileGluesSettings
    ) {
        try {
            val updated = transform(LauncherPreferences.readMobileGluesSettings(host))
            LauncherPreferences.saveMobileGluesSettings(host, updated)
            MobileGluesConfigFile.syncFromLauncherPreferences(host)
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    failureMessageResId,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun applyResolvedMobileGluesSettings(
        host: Activity,
        settings: MobileGluesSettings,
        @StringRes failureMessageResId: Int
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
            showToast(
                host,
                UiText.StringResource(
                    failureMessageResId,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun savePlayerNameSelection(host: Activity, name: String): Boolean {
        return try {
            LauncherPreferences.savePlayerName(host, name)
            true
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_player_name_save_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_SHORT
            )
            false
        }
    }

    private fun saveTargetFpsSelection(host: Activity, targetFps: Int) {
        LauncherPreferences.saveTargetFps(host, targetFps)
    }

    private fun saveKeepScreenOnTimeoutSelection(host: Activity, timeoutMinutes: Int) {
        LauncherPreferences.saveKeepScreenOnTimeoutMinutes(host, timeoutMinutes)
    }

    private fun saveVirtualResolutionModeSelection(
        host: Activity,
        mode: VirtualResolutionMode
    ) {
        LauncherPreferences.saveVirtualResolutionMode(host, mode)
    }

    private fun saveRenderSurfaceBackendSelection(
        host: Activity,
        backend: RenderSurfaceBackend
    ) {
        LauncherPreferences.saveRenderSurfaceBackend(host, backend)
    }

    private fun saveThemeModeSelection(host: Activity, themeMode: LauncherThemeMode) {
        LauncherPreferences.saveThemeMode(host, themeMode)
        LauncherThemeController.apply(host, themeMode)
    }

    private fun saveThemeColorSelection(host: Activity, themeColor: LauncherThemeColor) {
        LauncherPreferences.saveThemeColor(host, themeColor)
    }

    private fun saveLauncherIconModeSelection(host: Activity, iconMode: LauncherIconMode) {
        LauncherPreferences.saveLauncherIconMode(host, iconMode)
        LauncherIconController.apply(host, iconMode)
    }

    private fun saveChromeBackgroundOpacitySelection(host: Activity, opacity: Float) {
        LauncherPreferences.saveChromeBackgroundOpacity(host, opacity)
    }

    private fun saveBootOverlayAnimationSelection(
        host: Activity,
        animation: BootOverlayAnimation
    ) {
        LauncherPreferences.saveBootOverlayAnimation(host, animation)
    }

    private fun saveBootOverlayStyleSelection(
        host: Activity,
        style: BootOverlayStyle
    ) {
        LauncherPreferences.saveBootOverlayStyle(host, style)
    }
    private fun saveJvmHeapMaxSelection(host: Activity, heapMaxMb: Int) {
        LauncherPreferences.saveJvmHeapMaxMb(host, heapMaxMb)
    }

    private fun saveJvmCompressedPointersSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmCompressedPointersEnabled(host, enabled)
    }

    private fun saveJvmStringDeduplicationSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setJvmStringDeduplicationEnabled(host, enabled)
    }

    private fun saveGamePerformanceOverlaySelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setGamePerformanceOverlayEnabled(host, enabled)
    }

    private fun saveSustainedPerformanceModeSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSustainedPerformanceModeEnabled(host, enabled)
    }

    private fun saveTouchscreenInputModeSelection(host: Activity, mode: TouchscreenInputMode): Boolean {
        return try {
            GameplaySettingsService.saveTouchscreenInputMode(host, mode)
            true
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_touchscreen_mode_save_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_SHORT
            )
            false
        }
    }

    private fun saveCardPlayOptimizationModeSelection(
        host: Activity,
        mode: CardPlayOptimizationMode
    ): Boolean {
        return try {
            GameplaySettingsService.saveCardPlayOptimizationMode(host, mode)
            true
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_card_play_optimization_save_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_SHORT
            )
            false
        }
    }

    private fun saveGameplayFontScaleSelection(host: Activity, value: Float): Boolean {
        return try {
            GameplaySettingsService.saveFontScale(host, value)
            true
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_gameplay_font_scale_save_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_SHORT
            )
            false
        }
    }

    private fun saveGameplayLargerUiSelection(host: Activity, enabled: Boolean): Boolean {
        return try {
            GameplaySettingsService.saveLargerUiEnabled(host, enabled)
            true
        } catch (error: IOException) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_gameplay_larger_ui_save_failed,
                    error.message ?: host.getString(R.string.feedback_unknown_error)
                ),
                Toast.LENGTH_SHORT
            )
            false
        }
    }

    private fun backupRemoteSteamCloudProfile(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        timestamp: String,
    ): String {
        val stagingRoot = File(
            SteamCloudManifestStore.outputDir(host),
            "remote-backup-staging-$timestamp-${System.nanoTime()}"
        )
        return try {
            SteamCloudPullCoordinator.downloadAllToDirectory(
                host = host,
                authMaterial = authMaterial,
                outputRoot = stagingRoot,
            )
            SettingsSaveBackupService.backupSaveProfileToDownloads(
                host = host,
                sourceRoot = stagingRoot,
                backupFileName = "steam-cloud-saves-before-independent-overwrite-$timestamp.zip",
                relativeSubdirectory = STEAM_CLOUD_BACKUP_DOWNLOAD_SUBDIR,
            )
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun buildLogPathText(host: Activity): String {
        val latestLog = RuntimePaths.latestLog(host)
        val archivedDir = RuntimePaths.jvmLogsDir(host)
        val logcatDir = RuntimePaths.logcatDir(host)
        val logs = JvmLogRotationManager.listLogFiles(host)
        val lines = mutableListOf(
            host.getString(
                R.string.settings_log_slots,
                logs.size,
                JvmLogRotationManager.MAX_LOG_SLOTS
            ),
            host.getString(R.string.settings_log_latest, latestLog.absolutePath),
            host.getString(R.string.settings_log_archive_dir, archivedDir.absolutePath),
            host.getString(R.string.settings_log_logcat_dir, logcatDir.absolutePath)
        )
        if (logs.isEmpty()) {
            lines += host.getString(R.string.settings_log_none_yet)
        } else {
            lines += logs.map { log ->
                host.getString(R.string.settings_log_entry, log.name, log.length())
            }
        }
        return lines.joinToString("\n")
    }

    override fun onCleared() {
        cancelActiveSteamCloudLogin("Settings screen cleared.", clearBusy = false)
        executor.shutdownNow()
        super.onCleared()
    }

    private fun buildSteamCloudAuthPrompt(
        host: Activity,
        cancellationHandle: SteamCloudAuthCoordinator.CancellationHandle,
        loginGeneration: Long,
    ): SteamCloudClient.AuthPrompt {
        return object : SteamCloudClient.AuthPrompt {
            override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
                val future = CompletableFuture<String>()
                host.runOnUiThread {
                    if (!isActiveSteamCloudLogin(cancellationHandle, loginGeneration)) {
                        future.completeExceptionally(
                            CancellationException("Steam Cloud login cancelled by user.")
                        )
                        return@runOnUiThread
                    }
                    cancelPendingSteamCloudChallenge("Steam Cloud device code prompt replaced.", clearState = false)
                    pendingSteamCloudCodeFuture = future
                    uiState = uiState.copy(
                        steamCloudLoginChallenge = SteamCloudLoginChallenge(
                            kind = SteamCloudLoginChallengeKind.DEVICE_CODE,
                            previousCodeWasIncorrect = previousCodeWasIncorrect
                        )
                    )
                }
                return future
            }

            override fun getEmailCode(
                email: String?,
                previousCodeWasIncorrect: Boolean,
            ): CompletableFuture<String> {
                val future = CompletableFuture<String>()
                host.runOnUiThread {
                    if (!isActiveSteamCloudLogin(cancellationHandle, loginGeneration)) {
                        future.completeExceptionally(
                            CancellationException("Steam Cloud login cancelled by user.")
                        )
                        return@runOnUiThread
                    }
                    cancelPendingSteamCloudChallenge("Steam Cloud email code prompt replaced.", clearState = false)
                    pendingSteamCloudCodeFuture = future
                    uiState = uiState.copy(
                        steamCloudLoginChallenge = SteamCloudLoginChallenge(
                            kind = SteamCloudLoginChallengeKind.EMAIL_CODE,
                            emailHint = email?.trim().orEmpty(),
                            previousCodeWasIncorrect = previousCodeWasIncorrect
                        )
                    )
                }
                return future
            }

            override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                val future = CompletableFuture.completedFuture(true)
                host.runOnUiThread {
                    if (!isActiveSteamCloudLogin(cancellationHandle, loginGeneration)) {
                        return@runOnUiThread
                    }
                    cancelPendingSteamCloudChallenge("Steam Cloud device confirmation prompt replaced.", clearState = false)
                    pendingSteamCloudConfirmationFuture = null
                    uiState = uiState.copy(
                        steamCloudLoginChallenge = SteamCloudLoginChallenge(
                            kind = SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION
                        )
                    )
                }
                return future
            }
        }
    }

    private fun clearPendingSteamCloudChallengeState() {
        pendingSteamCloudCodeFuture = null
        pendingSteamCloudConfirmationFuture = null
        if (uiState.steamCloudLoginChallenge != null) {
            uiState = uiState.copy(steamCloudLoginChallenge = null)
        }
    }

    private fun cancelPendingSteamCloudChallenge(reason: String, clearState: Boolean = true) {
        pendingSteamCloudCodeFuture?.completeExceptionally(CancellationException(reason))
        pendingSteamCloudConfirmationFuture?.completeExceptionally(CancellationException(reason))
        pendingSteamCloudCodeFuture = null
        pendingSteamCloudConfirmationFuture = null
        if (clearState) {
            clearPendingSteamCloudChallengeState()
        }
    }

    private fun cancelActiveSteamCloudLogin(reason: String, clearBusy: Boolean) {
        steamCloudLoginGeneration += 1L
        cancelPendingSteamCloudChallenge(reason)
        pendingSteamCloudLoginCancellationHandle?.cancel()
        pendingSteamCloudLoginCancellationHandle = null
        pendingSteamCloudLoginTask?.cancel(true)
        pendingSteamCloudLoginTask = null
        if (clearBusy && uiState.busy) {
            setBusy(false, null)
        }
    }

    private fun isSteamCloudLoginCancellation(error: Throwable): Boolean {
        return generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .any { current -> current is CancellationException }
    }

    private fun isActiveSteamCloudLogin(
        cancellationHandle: SteamCloudAuthCoordinator.CancellationHandle,
        loginGeneration: Long,
    ): Boolean {
        return pendingSteamCloudLoginCancellationHandle === cancellationHandle &&
            steamCloudLoginGeneration == loginGeneration &&
            !cancellationHandle.isCancelled
    }

    private fun buildSteamCloudCredentialsSummary(
        host: Activity,
        snapshot: SteamCloudAuthStore.AuthSnapshot,
    ): String {
        if (snapshot.accountName.isBlank() && !snapshot.refreshTokenConfigured) {
            return host.getString(R.string.settings_steam_cloud_credentials_not_configured)
        }

        val accountSummary = snapshot.accountName.ifBlank {
            host.getString(R.string.settings_status_unknown)
        }
        val tokenSummary = if (snapshot.refreshTokenConfigured) {
            host.getString(R.string.settings_steam_cloud_credentials_present)
        } else {
            host.getString(R.string.settings_steam_cloud_credentials_missing_short)
        }
        val guardSummary = if (snapshot.guardDataConfigured) {
            host.getString(R.string.settings_steam_cloud_credentials_present)
        } else {
            host.getString(R.string.settings_steam_cloud_credentials_missing_short)
        }
        return host.getString(
            R.string.settings_steam_cloud_credentials_summary,
            accountSummary,
            tokenSummary,
            guardSummary
        )
    }

    private fun buildSteamCloudManifestSummary(
        host: Activity,
        snapshot: SteamCloudManifestSnapshot?,
    ): String {
        if (snapshot == null) {
            return host.getString(R.string.settings_steam_cloud_manifest_missing_summary)
        }
        return host.getString(
            R.string.settings_steam_cloud_manifest_summary,
            snapshot.fileCount,
            snapshot.preferencesCount,
            snapshot.savesCount
        )
    }

    private fun buildSteamCloudStatusText(
        host: Activity,
        authSnapshot: SteamCloudAuthStore.AuthSnapshot,
        manifestSnapshot: SteamCloudManifestSnapshot?,
        baselineSnapshot: SteamCloudSyncBaseline?,
    ): String {
        val lines = mutableListOf<String>()
        if (!authSnapshot.isComplete) {
            lines += host.getString(R.string.settings_steam_cloud_status_not_logged_in)
        } else {
            lines += host.getString(
                R.string.settings_steam_cloud_status_logged_in,
                authSnapshot.accountName.ifBlank { host.getString(R.string.settings_status_unknown) }
            )
        }
        authSnapshot.lastAuthAtMs?.let {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_login,
                formatSettingsTimestamp(it)
            )
        }
        manifestSnapshot?.let {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_manifest,
                formatSettingsTimestamp(it.fetchedAtMs),
                it.fileCount
            )
        }
        authSnapshot.lastPullAtMs?.let {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_pull,
                formatSettingsTimestamp(it)
            )
        }
        authSnapshot.lastPushAtMs?.let {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_push,
                formatSettingsTimestamp(it)
            )
        }
        if (baselineSnapshot == null && authSnapshot.isComplete) {
            lines += host.getString(R.string.settings_steam_cloud_status_baseline_missing)
        }
        baselineSnapshot?.let {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_baseline,
                formatSettingsTimestamp(it.syncedAtMs),
                it.remoteEntries.size
            )
        }
        val pullSummaryFile = SteamCloudManifestStore.pullSummaryFile(host)
        if (pullSummaryFile.isFile) {
            lines += host.getString(
                R.string.settings_steam_cloud_status_pull_summary,
                pullSummaryFile.absolutePath
            )
        }
        val pushSummaryFile = SteamCloudManifestStore.pushSummaryFile(host)
        if (pushSummaryFile.isFile) {
            lines += host.getString(
                R.string.settings_steam_cloud_status_push_summary,
                pushSummaryFile.absolutePath
            )
        }
        val diagnosticsSummaryFile = SteamCloudDiagnosticsStore.summaryFile(host)
        if (diagnosticsSummaryFile.isFile) {
            lines += host.getString(
                R.string.settings_steam_cloud_status_diagnostics_summary,
                diagnosticsSummaryFile.absolutePath
            )
        }
        if (authSnapshot.lastError.isNotBlank()) {
            lines += host.getString(
                R.string.settings_steam_cloud_status_last_error,
                authSnapshot.lastError
            )
        }
        lines += host.getString(
            R.string.settings_steam_cloud_status_app_id,
            STEAM_CLOUD_APP_ID
        )
        return lines.joinToString("\n")
    }

    private fun summarizeSteamCloudError(host: Activity, error: Throwable): String {
        val cause = SteamCloudErrorClassifier.meaningfulCause(error)
        val message = cause.message?.trim().orEmpty()
        when (SteamCloudErrorClassifier.classify(error)) {
            SteamCloudErrorKind.USER_CANCELLED ->
                return host.getString(R.string.settings_steam_cloud_login_cancelled_summary)

            SteamCloudErrorKind.AUTH_CONNECTION_CANCELLED ->
                return host.getString(R.string.settings_steam_cloud_login_guard_connection_cancelled_summary)

            SteamCloudErrorKind.AUTH_WATCHDOG_DISCONNECT ->
                return host.getString(R.string.settings_steam_cloud_login_guard_wait_timeout_summary)

            SteamCloudErrorKind.INVALID_CREDENTIALS ->
                return host.getString(R.string.settings_steam_cloud_login_invalid_credentials_summary)

            SteamCloudErrorKind.UPLOAD_DISCONNECT ->
                return host.getString(R.string.settings_steam_cloud_upload_disconnect_summary)

            SteamCloudErrorKind.OTHER -> Unit
        }
        return if (message.isNotEmpty()) {
            message
        } else {
            cause.javaClass.simpleName
        }
    }

    private fun formatSettingsTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    private fun buildSteamCloudPhase0CredentialsSummary(
        host: Activity,
        snapshot: SteamCloudPhase0Store.Snapshot,
    ): String {
        if (snapshot.accountName.isBlank() && !snapshot.hasRefreshToken) {
            return host.getString(R.string.settings_steam_cloud_phase0_credentials_not_configured)
        }
        val accountSummary = if (snapshot.accountName.isBlank()) {
            host.getString(R.string.settings_status_unknown)
        } else {
            snapshot.accountName
        }
        val tokenSummary = if (snapshot.hasRefreshToken) {
            host.getString(
                R.string.settings_steam_cloud_phase0_token_saved_summary,
                snapshot.refreshTokenLength
            )
        } else {
            host.getString(R.string.settings_steam_cloud_phase0_token_missing_summary)
        }
        val proxySummary = if (snapshot.proxyUrl.isBlank()) {
            host.getString(R.string.settings_steam_cloud_phase0_proxy_direct_summary)
        } else {
            host.getString(R.string.settings_steam_cloud_phase0_proxy_configured_summary, snapshot.proxyUrl)
        }
        return host.getString(
            R.string.settings_steam_cloud_phase0_credentials_configured_summary,
            accountSummary,
            tokenSummary,
            proxySummary
        )
    }

    private fun buildSteamCloudPhase0StatusText(
        host: Activity,
        snapshot: SteamCloudPhase0Store.Snapshot,
    ): String {
        val lines = mutableListOf<String>()
        if (snapshot.lastProbeAtMs == null) {
            lines += if (snapshot.hasRefreshToken && snapshot.accountName.isNotBlank()) {
                host.getString(R.string.settings_steam_cloud_phase0_status_ready)
            } else {
                host.getString(R.string.settings_steam_cloud_phase0_status_idle)
            }
        } else {
            val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date(snapshot.lastProbeAtMs))
            if (snapshot.lastProbeSuccess == true) {
                lines += host.getString(
                    R.string.settings_steam_cloud_phase0_status_last_success,
                    formattedTime,
                    snapshot.lastFileCount ?: 0,
                    snapshot.lastPreferencesCount ?: 0,
                    snapshot.lastSavesCount ?: 0
                )
            } else {
                lines += host.getString(
                    R.string.settings_steam_cloud_phase0_status_last_failure,
                    formattedTime,
                    snapshot.lastError.ifBlank {
                        host.getString(R.string.settings_status_unknown)
                    }
                )
            }
        }
        if (snapshot.lastOutputPath.isNotBlank()) {
            lines += host.getString(
                R.string.settings_steam_cloud_phase0_status_report,
                snapshot.lastOutputPath
            )
        }
        if (snapshot.lastListingPath.isNotBlank()) {
            lines += host.getString(
                R.string.settings_steam_cloud_phase0_status_listing,
                snapshot.lastListingPath
            )
        }
        return lines.joinToString("\n")
    }

    private fun BackBehavior.displayName(host: Activity): String {
        return when (this) {
            BackBehavior.EXIT_TO_LAUNCHER ->
                host.getString(R.string.settings_back_behavior_exit)
            BackBehavior.SEND_ESCAPE ->
                host.getString(R.string.settings_back_behavior_escape)
            BackBehavior.NONE ->
                host.getString(R.string.settings_back_behavior_none)
        }
    }

    private fun TouchscreenInputMode.displayName(host: Activity): String {
        return when (this) {
            TouchscreenInputMode.DESKTOP ->
                host.getString(R.string.settings_touchscreen_mode_desktop)
            TouchscreenInputMode.HYBRID ->
                host.getString(R.string.settings_touchscreen_mode_hybrid)
            TouchscreenInputMode.MOBILE ->
                host.getString(R.string.settings_touchscreen_mode_mobile)
        }
    }

    private fun CardPlayOptimizationMode.displayName(host: Activity): String {
        return host.getString(displayNameResId())
    }

    private fun SteamCloudSaveMode.displayName(host: Activity): String {
        return when (this) {
            SteamCloudSaveMode.INDEPENDENT ->
                host.getString(R.string.settings_steam_cloud_save_mode_independent_title)

            SteamCloudSaveMode.STEAM_CLOUD ->
                host.getString(R.string.settings_steam_cloud_save_mode_cloud_title)
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

    private fun RendererSelectionMode.displayName(host: Activity): String {
        return when (this) {
            RendererSelectionMode.AUTO ->
                host.getString(R.string.settings_renderer_selection_mode_auto)
            RendererSelectionMode.MANUAL ->
                host.getString(R.string.settings_renderer_selection_mode_manual)
        }
    }

    private fun GpuResourceGuardianMode.displayName(host: Activity): String {
        return when (this) {
            GpuResourceGuardianMode.OFF ->
                host.getString(R.string.settings_gpu_resource_guardian_mode_off)
            GpuResourceGuardianMode.SAFE ->
                host.getString(R.string.settings_gpu_resource_guardian_mode_safe)
            GpuResourceGuardianMode.AGGRESSIVE ->
                host.getString(R.string.settings_gpu_resource_guardian_mode_aggressive)
            GpuResourceGuardianMode.ULTRA_AGGRESSIVE ->
                host.getString(R.string.settings_gpu_resource_guardian_mode_ultra_aggressive)
            GpuResourceGuardianMode.LEGACY ->
                host.getString(R.string.settings_gpu_resource_guardian_mode_legacy)
        }
    }
}


