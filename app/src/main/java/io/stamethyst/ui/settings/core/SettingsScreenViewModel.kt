package io.stamethyst.ui.settings.core

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
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
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.BuildConfig
import io.stamethyst.clearSlingBreakWebViewData
import io.stamethyst.backend.minigame.SlingBreakService
import io.stamethyst.backend.minigame.SlingBreakInstallation
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.resources.ResourcePackDownloadMirrorSwitchController
import io.stamethyst.backend.resources.ResourcePackSlowDownloadMirrorSwitch
import io.stamethyst.backend.diag.LogcatCaptureProcessClient
import io.stamethyst.backend.diag.LauncherLogcatCaptureProcessClient
import io.stamethyst.backend.fs.LauncherJunkFileCleaner
import io.stamethyst.backend.easytier.EasyTierConfigRepository
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.steam.SteamAccountLogoutCoordinator
import io.stamethyst.backend.steamcloud.STEAM_CLOUD_APP_ID
import io.stamethyst.backend.steamcloud.SteamAuthenticationCircuitBreaker
import io.stamethyst.backend.steamcloud.SteamCloudAuthCoordinator
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudFailureCategory
import io.stamethyst.backend.steamcloud.SteamCloudFailureClassifier
import io.stamethyst.backend.steamcloud.SteamCloudAvatarCacheStore
import io.stamethyst.backend.steamcloud.SteamCloudBaselineStore
import io.stamethyst.backend.steamcloud.SteamCloudClient
import io.stamethyst.backend.steamcloud.SteamCloudCredentialsMissingException
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallenge
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallengeKind
import io.stamethyst.backend.steamcloud.SteamCloudDeviceConfirmationDecision
import io.stamethyst.backend.steamcloud.SteamCloudLiveSaveLease
import io.stamethyst.backend.steamcloud.SteamCloudManifestSnapshot
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamCloudNetworkEnvironment
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.backend.steamcloud.SteamCloudProfileService
import io.stamethyst.backend.steamcloud.SteamCloudSyncRepository
import io.stamethyst.backend.steamcloud.SteamCloudPullResult
import io.stamethyst.backend.steamcloud.SteamCloudSyncProgress
import io.stamethyst.backend.steamcloud.SteamCloudSyncDirection
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.backend.steamcloud.SteamCloudRootKind
import io.stamethyst.backend.steamcloud.SteamCloudSaveProfileManager
import io.stamethyst.backend.steamcloud.SteamCloudSyncBlacklist
import io.stamethyst.backend.steamcloud.SteamCloudSyncProcessService
import io.stamethyst.backend.steamcloud.SteamGamePresenceService
import io.stamethyst.backend.steamcloud.SteamCloudSyncBaseline
import io.stamethyst.backend.steamcloud.SteamCloudUploadPlan
import io.stamethyst.backend.steamcloud.reusableGuardDataForCredentials
import top.apricityx.workshop.steam.protocol.SteamGuardChallenge
import top.apricityx.workshop.steam.protocol.SteamGuardChallengeType
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
import io.stamethyst.backend.resources.ArthasResourcePackService
import io.stamethyst.backend.resources.ResourcePackStore
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
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.launch.MtsClasspathWarmupCoordinator
import io.stamethyst.backend.launch.MtsPatchCacheCoordinator
import io.stamethyst.backend.launch.MtsStartupCacheCoordinator
import io.stamethyst.backend.mods.ImportDownscaleMaterialPolicy
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.importing.patches.ImportPatchRegistry
import io.stamethyst.backend.mods.importing.patches.texture.AtlasOfflineDownscalePatchModule
import io.stamethyst.backend.mods.RuntimeDownscaleMaterialPolicy
import io.stamethyst.backend.mods.StsDesktopJarIntegrity
import io.stamethyst.config.RichPresenceDisplayPreferences
import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.LauncherUpdateUiReducer
import io.stamethyst.backend.update.UpdateCheckExecutionResult
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
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.R
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.FramePacingMode
import kotlin.math.roundToInt
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.backend.network.AccelerationStrategy
import io.stamethyst.config.CloudControlConfig
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherIconController
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherConfig
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
import java.lang.ref.WeakReference
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

private const val STEAM_CLOUD_PROFILE_BACKFILL_INTERVAL_MS = 6L * 60L * 60L * 1000L


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
        data class OpenExportSavesPicker(
            val fileName: String,
            val sourceMode: SteamCloudSaveMode,
        ) : Effect
        data class OpenExportLogsPicker(val fileName: String) : Effect
        data class OpenExportPerformanceLogsPicker(val fileName: String) : Effect
        data class OpenExportAgentWorkspacePicker(val fileName: String) : Effect
        data class OpenBootOverlayImagePicker(val slot: BootOverlayImageSlot) : Effect
        data class ShareJvmLogsBundle(val payload: JvmLogsSharePayload) : Effect
        data class SharePerformanceLogsBundle(val payload: JvmLogsSharePayload) : Effect
        data object OpenCompatibility : Effect
        data object OpenMobileGluesSettings : Effect
        data object OpenFeedback : Effect
        data class ShowDialog(val title: String, val message: String) : Effect
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

    data class EasyTierSettingsUiState(
        val enabled: Boolean = false,
        val canConnect: Boolean = false,
        val defaultMode: EasyTierNetworkMode = EasyTierNetworkMode.Room,
        val allowSharedCommunityNetwork: Boolean = false,
        val roomApiBaseUrl: String = "",
        val webConsoleApiBaseUrl: String = "",
        val configServerUrl: String = "",
        val entryNodeUrl: String = "",
        val connectTimeoutSeconds: Int = 0,
        val statusPollIntervalSeconds: Int = 0,
    )

    data class StsJarIntegrityDialogState(
        val displayName: String,
        val expectedSha1: String,
        val actualSha1: String,
        val forceConfirmationVisible: Boolean = false,
    )

    data class SaveSlotDialogState(
        val importUri: Uri? = null,
        val exportFileName: String? = null,
        val cloudAvailable: Boolean,
        val selectedMode: SteamCloudSaveMode,
    )

    data class SaveImportConfirmationState(
        val archive: java.io.File,
        val mode: SteamCloudSaveMode,
        val targetRoot: java.io.File,
        val candidates: List<io.stamethyst.ui.settings.files.SaveArchiveResolver.Candidate>,
        val candidateIndex: Int,
        val summary: io.stamethyst.ui.settings.files.SaveImportPreview.Summary,
    )

    data class RendererBackendOptionState(
        val backend: RendererBackend,
        val available: Boolean,
        val reasonText: String? = null
    )

    data class UiState(
        val loadStates: Map<SettingsLoadDomain, SettingsLoadState> = emptyMap(),
        val saveSlotDialogState: SaveSlotDialogState? = null,
        val saveImportConfirmation: SaveImportConfirmationState? = null,
        val busy: Boolean = false,
        val busyOperation: UiBusyOperation = UiBusyOperation.NONE,
        val busyMessage: UiText? = null,
        val busyProgressPercent: Int? = null,
        val steamCloudPullProgress: SteamCloudSyncProgress? = null,
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
        val selectedTargetFps: Float = LauncherPreferences.DEFAULT_TARGET_FPS.toFloat(),
        val nonRecommendedFpsEnabled: Boolean = false,
        val framePacingMode: FramePacingMode = LauncherPreferences.DEFAULT_FRAME_PACING_MODE,
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
        val accelerationStrategy: AccelerationStrategy =
            LauncherPreferences.DEFAULT_ACCELERATION_STRATEGY,
        val themeMode: LauncherThemeMode = LauncherPreferences.DEFAULT_THEME_MODE,
        val themeColor: LauncherThemeColor = LauncherPreferences.DEFAULT_THEME_COLOR,
        val launcherIconMode: LauncherIconMode = LauncherPreferences.DEFAULT_LAUNCHER_ICON_MODE,
        val chromeBackgroundOpacity: Float =
            LauncherPreferences.DEFAULT_CHROME_BACKGROUND_OPACITY,
        val bootOverlayStyle: BootOverlayStyle =
            LauncherPreferences.DEFAULT_BOOT_OVERLAY_STYLE,
        val slingBreakDependencyProgress: Int? = null,
        val slingBreakDependencyFailure: String? = null,
        val slingBreakDownloadMessage: String = "",
        val slingBreakSlowDownloadSwitch: ResourcePackSlowDownloadMirrorSwitch? = null,
        val slingBreakInstalledVersion: String = "",
        val slingBreakUpdateChecking: Boolean = false,
        val slingBreakUpdateStatus: String = "",
        val slingBreakUpdateRelease: io.stamethyst.backend.update.UpdateReleaseInfo? = null,
        val bootOverlayAnimation: BootOverlayAnimation =
            LauncherPreferences.DEFAULT_BOOT_OVERLAY_ANIMATION,
        val bootOverlayImageConfig: BootOverlayImageConfig = BootOverlayImageConfig(
            mode = LauncherPreferences.DEFAULT_BOOT_OVERLAY_IMAGE_MODE
        ),
        val selectedJvmHeapMaxMb: Int = LauncherPreferences.DEFAULT_JVM_HEAP_MAX_MB,
        val jvmTieredStopAtLevel: Int = LauncherPreferences.DEFAULT_JVM_TIERED_STOP_AT_LEVEL,
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
        val autoPopupKeyboardEnabled: Boolean =
            LauncherPreferences.DEFAULT_AUTO_POPUP_KEYBOARD_ENABLED,
        val floatingToolButtons: Set<String> = LauncherPreferences.DEFAULT_FLOATING_TOOL_BUTTONS,
        val hapticFeedbackEnabled: Boolean = LauncherPreferences.DEFAULT_HAPTIC_FEEDBACK_ENABLED,
        val autoSwitchLeftAfterRightClick: Boolean = LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
        val showModFileName: Boolean = LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME,
        val mobileHudEnabled: Boolean = LauncherPreferences.DEFAULT_MOBILE_HUD_ENABLED,
        val compendiumUpgradeTouchFixEnabled: Boolean =
            LauncherPreferences.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED,
        val togetherInSpireRouteLockEnabled: Boolean =
            LauncherPreferences.DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED,
        val togetherInSpireEasyTierAutofillEnabled: Boolean =
            LauncherPreferences.DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED,
        val localTestCloudControlEnabled: Boolean = false,
        val steamAchievementDebugModeEnabled: Boolean = false,
        val slingBreakAudioDebugModeEnabled: Boolean = false,
        val localTestOnlineServiceBaseUrl: String = "http://10.126.126.2:3001",
        val localTestConfigServerUrl: String = "udp://10.126.126.2:22020",
        val localTestEntryNodeUrl: String = "tcp://10.126.126.2:11010",
        val avoidDisplayCutout: Boolean = LauncherPreferences.DEFAULT_AVOID_DISPLAY_CUTOUT,
        val cropScreenBottom: Boolean = LauncherPreferences.DEFAULT_CROP_SCREEN_BOTTOM,
        val gameDisplayRegion: io.stamethyst.backend.render.GameDisplayRegion? = null,
        val ramSaverEnabled: Boolean = LauncherPreferences.DEFAULT_RAM_SAVER_ENABLED,
        val ramSaverOptimizations: Map<io.stamethyst.config.RamSaverOptimization, Boolean> =
            io.stamethyst.config.RamSaverOptimization.entries.associateWith { it.defaultEnabled },
        val mtsPatchCacheEnabled: Boolean = LauncherPreferences.DEFAULT_MTS_PATCH_CACHE_ENABLED,
        val mtsCacheStartupOptimizations: Map<io.stamethyst.config.MtsCacheStartupOptimization, Boolean> =
            io.stamethyst.config.MtsCacheStartupOptimization.entries.associateWith { false },
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
        val arthasAnalysisEnabled: Boolean = LauncherPreferences.DEFAULT_ARTHAS_ANALYSIS_ENABLED,
        val arthasResourceInstalled: Boolean = false,
        val arthasResourceVersion: String = "",
        val resourcePackReady: Boolean = false,
        val resourcePackVersion: String = "",
        val resourcePackId: String = "",
        val resourcePackIssues: String = "",
        val gdxPadCursorDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GDX_PAD_CURSOR_DEBUG,
        val glBridgeSwapHeartbeatDebugEnabled: Boolean = LauncherPreferences.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG,
        val touchscreenInputMode: TouchscreenInputMode =
            GameplaySettingsService.DEFAULT_TOUCHSCREEN_INPUT_MODE,
        val cardPlayOptimizationMode: CardPlayOptimizationMode =
            GameplaySettingsService.DEFAULT_CARD_PLAY_OPTIMIZATION_MODE,
        val touchIndicatorEnabled: Boolean = GameplaySettingsService.DEFAULT_TOUCH_INDICATOR_ENABLED,
        val hideEnemyPanelWhileTargeting: Boolean =
            GameplaySettingsService.DEFAULT_HIDE_ENEMY_PANEL_WHILE_TARGETING,
        val gameplayFontScale: Float = GameplaySettingsService.DEFAULT_FONT_SCALE,
        val gameplayLargerUiEnabled: Boolean = GameplaySettingsService.DEFAULT_LARGER_UI_ENABLED,
        val statusText: String = "",
        val logPathText: String = "",
        val targetFpsOptions: List<Float> = emptyList(),
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
        val steamCloudSyncDisabled: Boolean = LauncherPreferences.DEFAULT_STEAM_CLOUD_SYNC_DISABLED,
        val steamGamePresenceEnabled: Boolean =
            LauncherPreferences.DEFAULT_STEAM_GAME_PRESENCE_ENABLED,
        val richPresenceDisplayPreferences: RichPresenceDisplayPreferences =
            RichPresenceDisplayPreferences(),
        val steamAchievementSyncEnabled: Boolean = false,
        val achievementUnlockNotificationEnabled: Boolean =
            LauncherPreferences.DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED,
        val workshopMaxConcurrentDownloads: Int =
            LauncherPreferences.DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS,
        val workshopDownloadThreads: Int = LauncherPreferences.DEFAULT_WORKSHOP_DOWNLOAD_THREADS,
        val workshopWattAccelerationEnabled: Boolean =
            LauncherPreferences.DEFAULT_WORKSHOP_WATT_ACCELERATION_ENABLED,
        val workshopSteamLanguage: SteamLanguagePreference =
            LauncherPreferences.DEFAULT_WORKSHOP_STEAM_LANGUAGE,
        val workshopDefaultSort: WorkshopBrowseSort =
            LauncherPreferences.DEFAULT_WORKSHOP_DEFAULT_SORT,
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
        val easyTierSettings: EasyTierSettingsUiState = EasyTierSettingsUiState(),
    ) {
        fun loadState(domain: SettingsLoadDomain): SettingsLoadState =
            loadStates[domain] ?: SettingsLoadState.READY

        fun loadState(vararg domains: SettingsLoadDomain): SettingsLoadState =
            SettingsLoadState.combine(domains.map(::loadState))
    }

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

    private data class SteamAccountResult(
        val auth: SteamCloudAuthStore.AuthSnapshot,
        val steamCloudAccountName: String,
        val steamCloudRefreshTokenConfigured: Boolean,
        val steamCloudGuardDataConfigured: Boolean,
        val steamCloudPersonaName: String,
        val steamCloudAvatarUrl: String,
        val steamCloudCredentialsSummary: String,
    )

    private data class SteamSavesResult(
        val mode: SteamCloudSaveMode,
        val blacklist: Set<String>,
        val candidates: List<String>,
        val statusText: String,
        val manifestSummary: String,
        val manifestAvailable: Boolean,
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

    internal class StatusRefreshBusyTracker {
        private var clearBusyRequested = false

        @Synchronized
        fun begin(clearBusy: Boolean): Boolean {
            clearBusyRequested = clearBusyRequested || clearBusy
            return clearBusyRequested
        }

        @Synchronized
        fun complete() {
            clearBusyRequested = false
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val refreshExecutors = SettingsLoadDomain.entries.associateWith {
        Executors.newSingleThreadExecutor()
    }
    private val refreshTasks = refreshExecutors.mapValues { SettingsRefreshTask(it.value) }
    private val steamProfileExecutor = Executors.newSingleThreadExecutor()
    private val steamProfileTask = SettingsRefreshTask(steamProfileExecutor)
    private val steamCloudLoginCleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Control writes may wait for a save transaction in :steamcloud. Never do so on main,
    // or behind a network operation on the general settings executor. Preserve toggle order.
    private val steamCloudControlExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var steamCloudControlGeneration = 0L
    private var pendingSteamCloudSyncDisabled: Boolean? = null
    private var steamIdentityChangeInProgress = false
    private var busyGeneration = 0L
    private val statusRefreshBusyTracker = StatusRefreshBusyTracker()
    private var boundActivityReference: WeakReference<Activity>? = null
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 16)
    private var nativeLibraryMarketCatalog: List<NativeLibraryMarketCatalogEntry> = emptyList()
    private var pendingSteamCloudCodeFuture: CompletableFuture<String>? = null
    private var pendingSteamCloudConfirmationFuture: CompletableFuture<SteamCloudDeviceConfirmationDecision>? = null
    private var pendingSteamCloudSelectionFuture: CompletableFuture<SteamGuardChallengeType>? = null
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

    @Volatile
    private var lastSteamCloudProfileBackfillAtMs: Long = 0L

    private val automaticallyPromptedUpdateVersions = LinkedHashSet<String>()

    var uiState by mutableStateOf(
        UiState(loadStates = SettingsLoadDomain.entries.associateWith { SettingsLoadState.LOADING })
    )
        private set

    val effects = _effects.asSharedFlow()

    fun bind(
        activity: Activity,
        keepScreenOnTimeoutMinuteOptions: IntArray =
            LauncherPreferences.KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS
    ) {
        val keepScreenOnOptions = keepScreenOnTimeoutMinuteOptions.toList()
        if (uiState.keepScreenOnTimeoutMinuteOptions != keepScreenOnOptions) {
            uiState = uiState.copy(keepScreenOnTimeoutMinuteOptions = keepScreenOnOptions)
        }
        // Standalone play can install/update while this launcher's ViewModel stays alive.
        uiState = uiState.copy(slingBreakInstalledVersion = SlingBreakInstallation.read(activity)?.rawTagName.orEmpty())
        if (boundActivityReference?.get() === activity) {
            // Re-entering a page retries failed domains, without restarting healthy checks.
            SettingsLoadDomain.entries.filter { uiState.loadState(it) == SettingsLoadState.ERROR }
                .forEach { retrySettingsLoad(activity, it) }
            return
        }
        boundActivityReference = WeakReference(activity)

        // Each domain publishes independently; storage/network checks never hold back
        // the user's saved preferences or run on the UI thread.
        syncStoredUpdateState(activity)
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
            slingBreakInstalledVersion = SlingBreakInstallation.read(host)?.rawTagName.orEmpty(),
            bootOverlayAnimation = SettingsRepository.loadBootOverlayAnimation(host),
            bootOverlayImageConfig = SettingsRepository.loadBootOverlayImageConfig(host)
        )
    }

    fun onThemeModeChanged(host: Activity, themeMode: LauncherThemeMode) {
        saveThemeModeSelection(host, themeMode)
        syncThemeAppearance(host)
        refreshConfiguration(host)
    }

    fun onThemeColorChanged(host: Activity, themeColor: LauncherThemeColor) {
        saveThemeColorSelection(host, themeColor)
        syncThemeAppearance(host)
        refreshConfiguration(host)
    }

    fun onLauncherIconModeChanged(host: Activity, iconMode: LauncherIconMode) {
        if (uiState.busy || uiState.launcherIconMode == iconMode) {
            return
        }
        saveLauncherIconModeSelection(host, iconMode)
        syncThemeAppearance(host)
        refreshConfiguration(host)
    }

    fun onChromeBackgroundOpacityChanged(host: Activity, opacity: Float) {
        val normalized = LauncherPreferences.normalizeChromeBackgroundOpacity(opacity)
        if (uiState.busy || uiState.chromeBackgroundOpacity == normalized) {
            return
        }
        uiState = uiState.copy(chromeBackgroundOpacity = normalized)
        saveChromeBackgroundOpacitySelection(host, normalized)
        refreshConfiguration(host)
    }

    fun onBootOverlayAnimationChanged(host: Activity, animation: BootOverlayAnimation) {
        if (uiState.busy || uiState.bootOverlayAnimation == animation) {
            return
        }
        uiState = uiState.copy(bootOverlayAnimation = animation)
        saveBootOverlayAnimationSelection(host, animation)
        refreshConfiguration(host)
    }

    fun onBootOverlayStyleChanged(host: Activity, style: BootOverlayStyle) {
        if (uiState.busy || uiState.slingBreakDependencyProgress != null || uiState.bootOverlayStyle == style) {
            return
        }
        if (style == BootOverlayStyle.GODOT_SLING_BREAK && SlingBreakInstallation.read(host) == null) {
            onPrepareSlingBreak(host)
            return
        }
        uiState = uiState.copy(bootOverlayStyle = style)
        saveBootOverlayStyleSelection(host, style)
        refreshConfiguration(host)
    }

    private val slingBreakMirrorSwitchController = ResourcePackDownloadMirrorSwitchController()
    private var slingBreakSelectAfterInstall = false

    fun checkSlingBreakUpdates(host: Activity) {
        if (uiState.slingBreakUpdateChecking || uiState.slingBreakDependencyProgress != null) return
        val context = host.applicationContext
        uiState = uiState.copy(slingBreakUpdateChecking = true, slingBreakUpdateStatus = "", slingBreakUpdateRelease = null,
            slingBreakInstalledVersion = SlingBreakInstallation.read(context)?.rawTagName.orEmpty())
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val result = runCatching { SlingBreakService.check(context) }.getOrElse {
                io.stamethyst.backend.update.UpdateCheckExecutionResult.Failure(it.message.orEmpty())
            }
            main.post {
                when (result) {
                    is io.stamethyst.backend.update.UpdateCheckExecutionResult.Success -> uiState = uiState.copy(
                        slingBreakUpdateChecking = false,
                        slingBreakUpdateRelease = result.release.takeIf {
                            val current = SlingBreakInstallation.read(context)
                            current == null || io.stamethyst.backend.minigame.SlingBreakReleases.compare(it, current) >= 0
                        },
                        slingBreakUpdateStatus = if (result.hasUpdate) context.getString(R.string.godot_update_available, result.release.rawTagName)
                            else context.getString(R.string.update_status_result_latest),
                    )
                    is io.stamethyst.backend.update.UpdateCheckExecutionResult.Failure -> uiState = uiState.copy(
                        slingBreakUpdateChecking = false, slingBreakUpdateStatus = context.getString(R.string.update_check_result_failed, result.errorSummary),
                    )
                }
            }
        }, "slingbreak-update-check").start()
    }

    fun installSlingBreakUpdate(host: Activity) {
        if (uiState.busy || uiState.slingBreakDependencyProgress != null) return
        slingBreakSelectAfterInstall = false
        prepareSlingBreak(host, uiState.slingBreakUpdateRelease)
    }

    fun switchSlingBreakDownloadMirror() {
        if (uiState.slingBreakDependencyProgress != null) {
            slingBreakMirrorSwitchController.requestSwitchToNextMirror()
        }
    }

    fun onPrepareSlingBreak(host: Activity) {
        if (uiState.busy || uiState.slingBreakDependencyProgress != null) return
        if (uiState.slingBreakDependencyFailure == null) slingBreakSelectAfterInstall = true
        prepareSlingBreak(host, uiState.slingBreakUpdateRelease.takeUnless { slingBreakSelectAfterInstall })
    }

    private fun prepareSlingBreak(host: Activity, release: io.stamethyst.backend.update.UpdateReleaseInfo?) {
        if (uiState.busy || uiState.slingBreakDependencyProgress != null) return
        val context = host.applicationContext
        uiState = uiState.copy(
            slingBreakDependencyProgress = -1,
            slingBreakDependencyFailure = null,
            slingBreakDownloadMessage = context.getString(R.string.godot_fetching_version),
            slingBreakSlowDownloadSwitch = null,
        )
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener: (ResourcePackSlowDownloadMirrorSwitch?) -> Unit = { prompt ->
            main.post {
                if (uiState.slingBreakDependencyProgress != null) {
                    uiState = uiState.copy(slingBreakSlowDownloadSwitch = prompt)
                }
            }
        }
        slingBreakMirrorSwitchController.addSlowDownloadListener(listener)
        SlingBreakService.ensureAsync(context, StartupProgressCallback { progress, message ->
            uiState = uiState.copy(slingBreakDependencyProgress = progress, slingBreakDownloadMessage = message)
        }, slingBreakMirrorSwitchController, release, { failure ->
            slingBreakMirrorSwitchController.removeSlowDownloadListener(listener)
            if (failure == null && slingBreakSelectAfterInstall) LauncherPreferences.saveBootOverlayStyle(context, BootOverlayStyle.GODOT_SLING_BREAK)
            uiState = uiState.copy(
                slingBreakDependencyProgress = null,
                slingBreakDependencyFailure = failure,
                slingBreakSlowDownloadSwitch = null,
                bootOverlayStyle = if (failure == null && slingBreakSelectAfterInstall) BootOverlayStyle.GODOT_SLING_BREAK else uiState.bootOverlayStyle,
                slingBreakInstalledVersion = SlingBreakInstallation.read(context)?.rawTagName.orEmpty(),
                slingBreakUpdateStatus = if (failure == null) context.getString(R.string.godot_installed_offline,
                    SlingBreakInstallation.read(context)?.rawTagName.orEmpty()) else uiState.slingBreakUpdateStatus,
                slingBreakUpdateRelease = if (failure == null) null else uiState.slingBreakUpdateRelease,
            )
            refreshConfiguration(host)
        })
    }

    fun dismissSlingBreakDependencyFailure() {
        uiState = uiState.copy(slingBreakDependencyFailure = null)
    }

    fun retrySlingBreakInstallation(host: Activity) = prepareSlingBreak(host, uiState.slingBreakUpdateRelease)

    fun onBootOverlayImageModeChanged(host: Activity, mode: BootOverlayImageMode) {
        if (uiState.busy || uiState.bootOverlayImageConfig.mode == mode) {
            return
        }
        val nextConfig = BootOverlayImageService.saveMode(host, mode)
        uiState = uiState.copy(bootOverlayImageConfig = nextConfig)
        refreshConfiguration(host)
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
            refreshConfiguration(host)
        } catch (error: Throwable) {
            showToast(
                host,
                UiText.StringResource(
                    R.string.settings_boot_overlay_custom_image_failed,
                    error.message ?: error.javaClass.simpleName
                ),
                Toast.LENGTH_LONG
            )
            refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onResetBootOverlayImages(host: Activity) {
        if (uiState.busy) {
            return
        }
        val nextConfig = BootOverlayImageService.reset(host)
        uiState = uiState.copy(bootOverlayImageConfig = nextConfig)
        showToast(host, UiText.StringResource(R.string.settings_boot_overlay_custom_image_reset), Toast.LENGTH_SHORT)
        refreshConfiguration(host)
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
        val promptState = if (result.hasUpdate) {
            buildUpdatePromptState(host, result.release)
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
    ): UpdatePromptState {
        val defaultDownloadSource = resolveSelectedMirrorSource(host)
        val downloadOptions = UpdateSource.oneShotDownloadSelectionSources(
            primarySource = defaultDownloadSource,
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
            downloadSourceDisplayName = defaultDownloadSource.displayName,
            notesText = release.notesText.ifBlank {
                host.getString(R.string.update_dialog_notes_empty)
            },
            downloadOptions = downloadOptions,
            defaultDownloadSourceId = defaultDownloadSource.id
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

    /** Full refresh is reserved for binding and operations that change installed data. */
    fun refreshStatus(host: Activity, clearBusy: Boolean = true) {
        host.runOnUiThread {
            refreshConfiguration(host, clearBusy)
            SettingsLoadDomain.entries.filter { it != SettingsLoadDomain.CONFIGURATION }
                .forEach { retrySettingsLoad(host, it) }
        }
    }

    private fun setLoadState(domain: SettingsLoadDomain, state: SettingsLoadState) {
        uiState = uiState.copy(loadStates = uiState.loadStates + (domain to state))
    }

    private fun <T> loadDomain(
        host: Activity,
        domain: SettingsLoadDomain,
        load: (checkCurrent: () -> Unit) -> T,
        onComplete: () -> Unit = {},
        publish: (T) -> Unit,
    ) {
        if (domain != SettingsLoadDomain.CONFIGURATION ||
            uiState.loadState(domain) != SettingsLoadState.READY
        ) {
            setLoadState(domain, SettingsLoadState.LOADING)
        }
        refreshTasks.getValue(domain).submit(
            dispatch = { callback -> host.runOnUiThread { callback() } },
            load = load,
            onSuccess = { result ->
                try {
                    publish(result)
                    setLoadState(domain, SettingsLoadState.READY)
                } catch (error: Exception) {
                    Log.w("SettingsRefresh", "Unable to display $domain", error)
                    setLoadState(domain, SettingsLoadState.ERROR)
                } finally {
                    onComplete()
                }
                // Formatting diagnostics is not a prerequisite for displaying saved settings.
                runCatching { rebuildStatusText(host) }.onFailure { error ->
                    Log.w("SettingsRefresh", "Unable to compose diagnostics", error)
                    setLoadState(SettingsLoadDomain.DIAGNOSTICS, SettingsLoadState.ERROR)
                }
            },
            onFailure = { error ->
                Log.w("SettingsRefresh", "Unable to load $domain", error)
                setLoadState(domain, SettingsLoadState.ERROR)
                onComplete()
            },
        )
    }

    fun refreshConfiguration(host: Activity, clearBusy: Boolean = false) {
        host.runOnUiThread {
            val refreshClearBusy = statusRefreshBusyTracker.begin(clearBusy)
            val refreshBusyGeneration = busyGeneration
            loadDomain(
                host, SettingsLoadDomain.CONFIGURATION,
                load = { SettingsRepository.loadSettingsSnapshot(host) },
                onComplete = {
                    if (refreshClearBusy && refreshBusyGeneration == busyGeneration) setBusy(false, null)
                    statusRefreshBusyTracker.complete()
                },
            ) { snapshot ->
                latestSettingsSnapshot = snapshot
                applySnapshot(snapshot)
                uiState = uiState.copy(targetFpsOptions = resolveTargetFpsOptions())
            }
        }
    }

    fun retrySettingsLoad(host: Activity, domain: SettingsLoadDomain) {
        host.runOnUiThread {
            when (domain) {
                SettingsLoadDomain.CONFIGURATION -> refreshConfiguration(host)
                SettingsLoadDomain.RENDERER -> refreshRenderer(host)
                SettingsLoadDomain.RESOURCES -> refreshResources(host)
                SettingsLoadDomain.STEAM_ACCOUNT -> refreshSteamAccount(host)
                SettingsLoadDomain.STEAM_SAVES -> refreshSteamSaves(host)
                SettingsLoadDomain.DIAGNOSTICS -> refreshDiagnostics(host)
                SettingsLoadDomain.GAME_MODE -> loadDomain(host, domain, {
                    AndroidGameModeSupport.readCurrentMode(host)
                }) { mode ->
                    latestSystemGameMode = mode
                    uiState = uiState.copy(
                        systemGameModeDisplayName = host.getString(mode.displayNameResId),
                        systemGameModeDescription = host.getString(mode.descriptionResId),
                    )
                }
                SettingsLoadDomain.EASYTIER -> loadDomain(host, domain, {
                    buildEasyTierSettingsUiState()
                }) { uiState = uiState.copy(easyTierSettings = it) }
                SettingsLoadDomain.TRANSLATION -> loadDomain(host, domain, {
                    BaiduTranslationCredentialsRepository(host).hasConfiguredCredentials()
                }) { uiState = uiState.copy(baiduTranslationCredentialsConfigured = it) }
            }
        }
    }

    private fun refreshRenderer(host: Activity) {
        loadDomain(host, SettingsLoadDomain.RENDERER, {
            RendererBackendResolver.resolve(
                context = host,
                requestedSurfaceBackend = LauncherPreferences.readRenderSurfaceBackend(host),
                selectionMode = LauncherPreferences.readRendererSelectionMode(host),
                manualBackend = LauncherPreferences.readManualRendererBackend(host),
            )
        }) { decision ->
            latestRendererDecision = decision
            uiState = uiState.copy(
                autoSelectedRendererBackend = decision.automaticBackend,
                effectiveRendererBackend = decision.effectiveBackend,
                effectiveRenderSurfaceBackend = decision.effectiveSurfaceBackend,
                rendererBackendOptions = decision.availableBackends.map {
                    RendererBackendOptionState(it.backend, it.available, it.describeUnavailable(host))
                },
                rendererFallbackText = decision.fallbackSummary(host),
                surfaceBackendForcedByRenderer = decision.surfaceBackendForced,
            )
        }
    }

    private fun refreshResources(host: Activity) {
        loadDomain(host, SettingsLoadDomain.RESOURCES, { checkCurrent ->
            val pack = ResourcePackStore.inspectQuick(host)
            checkCurrent()
            val arthas = ArthasResourcePackService.stateQuick(host)
            checkCurrent()
            // Keep the resource-dependent safety correction, not a dependency of all settings.
            val corrected = !arthas.valid && LauncherPreferences.isArthasAnalysisEnabled(host)
            if (corrected) {
                saveArthasAnalysisSelection(host, false)
            }
            Triple(pack, arthas, corrected)
        }) { (pack, arthas, corrected) ->
            uiState = uiState.copy(
                arthasAnalysisEnabled = uiState.arthasAnalysisEnabled && arthas.valid,
                arthasResourceInstalled = arthas.valid,
                arthasResourceVersion = arthas.version,
                resourcePackReady = pack.ready,
                resourcePackVersion = pack.version.orEmpty(),
                resourcePackId = pack.packId.orEmpty(),
                resourcePackIssues = pack.issues.joinToString("; "),
            )
            if (corrected) refreshConfiguration(host)
        }
    }

    private fun refreshSteamAccount(host: Activity) {
        if (steamIdentityChangeInProgress) return
        steamProfileTask.cancel()
        loadDomain(host, SettingsLoadDomain.STEAM_ACCOUNT, { checkCurrent ->
            val read = SteamCloudAuthStore.readSnapshotWithStatus(host)
            check(!read.readFailed) { "Steam account state is temporarily unavailable" }
            val auth = read.snapshot
            checkCurrent()
            SteamAccountResult(
                auth = auth,
                steamCloudAccountName = auth.accountName,
                steamCloudRefreshTokenConfigured = auth.isComplete,
                steamCloudGuardDataConfigured = auth.guardDataConfigured,
                steamCloudPersonaName = auth.personaName,
                steamCloudAvatarUrl = auth.avatarUrl,
                steamCloudCredentialsSummary = buildSteamCloudCredentialsSummary(host, auth),
            )
        }) { result ->
            uiState = uiState.copy(
                steamCloudAccountName = result.steamCloudAccountName,
                steamCloudRefreshTokenConfigured = result.steamCloudRefreshTokenConfigured,
                steamCloudGuardDataConfigured = result.steamCloudGuardDataConfigured,
                steamCloudPersonaName = result.steamCloudPersonaName,
                steamCloudAvatarUrl = result.steamCloudAvatarUrl,
                steamCloudCredentialsSummary = result.steamCloudCredentialsSummary,
            )
            // Profile HTTP is cosmetic and cannot hold account/save controls in loading.
            steamProfileTask.submit(
                dispatch = { callback -> host.runOnUiThread { callback() } },
                load = { checkCurrent -> maybeBackfillSteamCloudProfile(host, result.auth, checkCurrent) },
                onSuccess = { profile ->
                    uiState = uiState.copy(
                        steamCloudPersonaName = profile.personaName,
                        steamCloudAvatarUrl = profile.avatarUrl,
                    )
                },
                onFailure = { Log.w("SettingsRefresh", "Unable to refresh Steam profile", it) },
            )
        }
    }

    private fun refreshSteamSaves(host: Activity) {
        if (steamIdentityChangeInProgress) return
        loadDomain(host, SettingsLoadDomain.STEAM_SAVES, { checkCurrent ->
            val read = SteamCloudAuthStore.readSnapshotWithStatus(host)
            check(!read.readFailed) { "Steam save account state is temporarily unavailable" }
            checkCurrent()
            var mode = LauncherPreferences.readSteamCloudSaveMode(host)
            if (mode == SteamCloudSaveMode.STEAM_CLOUD) {
                if (!read.snapshot.isComplete || LauncherConfig.isSteamCloudIndependentSwitchPending(host)) {
                    SteamCloudOperationMutex.runExclusive(host) {
                        checkCurrent()
                        val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
                        val pending = LauncherConfig.isSteamCloudIndependentSwitchPending(host)
                        val authoritative = SteamCloudAuthStore.readSnapshotWithStatus(host, attempts = 1)
                        check(!authoritative.readFailed) { "Steam save account state is temporarily unavailable" }
                        if (currentMode == SteamCloudSaveMode.STEAM_CLOUD &&
                            (pending || !authoritative.snapshot.isComplete)
                        ) {
                            if (pending) SteamCloudSaveProfileManager.completeDeferredIndependentSwitch(host)
                            else SteamCloudSaveProfileManager.switchMode(host, currentMode, SteamCloudSaveMode.INDEPENDENT)
                        }
                        mode = LauncherPreferences.readSteamCloudSaveMode(host)
                    }
                }
            }
            checkCurrent()
            val blacklist = LauncherPreferences.readSteamCloudSyncBlacklistPaths(host)
            // These readers use the save transaction lock. They must not hold up
            // the account card or account-dependent switches during a cloud sync.
            val manifest = SteamCloudManifestStore.readSnapshot(host, read.snapshot.steamId64)
            checkCurrent()
            val baseline = SteamCloudBaselineStore.readSnapshot(host, read.snapshot.steamId64)
            SteamSavesResult(
                mode = mode,
                blacklist = blacklist,
                candidates = SteamCloudSyncBlacklist.listSelectableLocalRelativePaths(RuntimePaths.stsRoot(host), blacklist),
                statusText = buildSteamCloudStatusText(host, read.snapshot, manifest, baseline),
                manifestSummary = buildSteamCloudManifestSummary(host, manifest),
                manifestAvailable = manifest != null,
            )
        }) { result ->
            uiState = uiState.copy(
                steamCloudSaveMode = result.mode,
                steamCloudSyncBlacklistPaths = result.blacklist,
                steamCloudSyncBlacklistCandidates = result.candidates,
                steamCloudStatusText = result.statusText,
                steamCloudManifestSummary = result.manifestSummary,
                steamCloudManifestAvailable = result.manifestAvailable,
            )
        }
    }

    private fun beginSteamIdentityChange() {
        steamIdentityChangeInProgress = true
        steamProfileTask.cancel()
        listOf(SettingsLoadDomain.STEAM_ACCOUNT, SettingsLoadDomain.STEAM_SAVES).forEach { domain ->
            refreshTasks.getValue(domain).cancel()
            setLoadState(domain, SettingsLoadState.LOADING)
        }
    }

    /** Save operations may also change gameplay preferences; unrelated inspections stay untouched. */
    private fun refreshSteamState(host: Activity, clearBusy: Boolean = true) {
        host.runOnUiThread {
            refreshConfiguration(host, clearBusy)
            refreshSteamAccount(host)
            refreshSteamSaves(host)
        }
    }

    private fun finishSteamIdentityChange(host: Activity, clearBusy: Boolean = true) {
        steamIdentityChangeInProgress = false
        refreshSteamState(host, clearBusy)
    }

    private data class DiagnosticsEnvironment(
        val hasJar: Boolean,
        val optionalEnabled: Int,
        val optionalTotal: Int,
        val dependencies: List<CoreDependencyStatus>,
        val device: DeviceRuntimeStatus,
        val logPathText: String,
    )

    private var latestSettingsSnapshot: SettingsRepository.SettingsSnapshot? = null
    private var latestRendererDecision: RendererDecision? = null
    private var latestSystemGameMode: AndroidGameModeSnapshot? = null
    private var latestDiagnostics: DiagnosticsEnvironment? = null

    private fun refreshDiagnostics(host: Activity) {
        loadDomain(host, SettingsLoadDomain.DIAGNOSTICS, { checkCurrent ->
            val hasJar = hasValidImportedStsJar(host)
            val mods = ModManager.listInstalledMods(host).filterNot { it.required }
            val dependencies = listOf(
                Triple("ModTheSpire", RuntimePaths.importedMtsJar(host), "ModTheSpire.jar"),
                Triple("BaseMod", RuntimePaths.importedBaseModJar(host), "BaseMod.jar"),
                Triple("StSLib", RuntimePaths.importedStsLibJar(host), "StSLib.jar"),
                Triple("Amethyst Runtime Compat", RuntimePaths.importedAmethystRuntimeCompatJar(host), "AmethystRuntimeCompat.jar"),
                Triple("Amethyst Floating Tools", RuntimePaths.importedAmethystFloatingToolsJar(host), "AmethystFloatingTools.jar"),
                Triple("Ram Saver", RuntimePaths.importedRamSaverJar(host), "RamSaver.jar"),
            ).map { (label, file, asset) ->
                checkCurrent()
                resolveCoreDependencyStatus(host, label, file, "components/mods/$asset")
            }
            DiagnosticsEnvironment(
                hasJar, mods.count { it.enabled }, mods.size, dependencies,
                collectDeviceRuntimeStatus(host),
                buildLogPathText(host, JvmLogRotationManager.listLogFiles(host)),
            )
        }) { result ->
            latestDiagnostics = result
            uiState = uiState.copy(logPathText = result.logPathText)
        }
    }

    private fun rebuildStatusText(host: Activity) {
        val snapshot = latestSettingsSnapshot ?: return
        val renderer = latestRendererDecision ?: return
        val gameMode = latestSystemGameMode ?: return
        val environment = latestDiagnostics ?: return
        uiState = uiState.copy(statusText = buildStatusText(
            host, snapshot, renderer, gameMode, environment.hasJar,
            environment.optionalEnabled, environment.optionalTotal,
            environment.dependencies[0], environment.dependencies[1], environment.dependencies[2],
            environment.dependencies[3], environment.dependencies[4], environment.dependencies[5],
            environment.device,
        ))
    }

    /**
     * Sessions logged in before persona/avatar capture (or whose profile fetch
     * failed at login) hold valid credentials without an avatar URL. Backfill
     * them at most once per interval so the avatar caches permanently instead
     * of staying blank; a failed lookup just waits for the next interval.
     */
    private fun maybeBackfillSteamCloudProfile(
        host: Activity,
        snapshot: SteamCloudAuthStore.AuthSnapshot,
        checkCurrent: () -> Unit,
    ): SteamCloudAuthStore.AuthSnapshot {
        if (!snapshot.isComplete || snapshot.avatarUrl.isNotBlank()) {
            return snapshot
        }
        val now = System.currentTimeMillis()
        if (now - lastSteamCloudProfileBackfillAtMs < STEAM_CLOUD_PROFILE_BACKFILL_INTERVAL_MS) {
            return snapshot
        }
        lastSteamCloudProfileBackfillAtMs = now
        val profile = runCatching {
            SteamCloudProfileService.fetchProfile(host, snapshot.steamId64)
        }.getOrNull() ?: return snapshot
        checkCurrent()
        runCatching {
            SteamCloudAuthStore.recordProfile(
                context = host,
                steamId64 = snapshot.steamId64,
                personaName = profile.personaName,
                avatarUrl = profile.avatarUrl,
            )
        }
        return snapshot.copy(
            personaName = profile.personaName.ifBlank { snapshot.personaName },
            avatarUrl = profile.avatarUrl,
        )
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
        beginSteamIdentityChange()
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_login))
        val cancellationHandle = SteamCloudAuthCoordinator.CancellationHandle()
        val loginGeneration = steamCloudLoginGeneration + 1L
        steamCloudLoginGeneration = loginGeneration
        pendingSteamCloudLoginCancellationHandle = cancellationHandle
        val loginTask = FutureTask<Unit> {
            var loginAttemptId = ""
            var savedAuthMaterial: SteamCloudAuthStore.SavedAuthMaterial? = null
            try {
                cancellationHandle.throwIfCancellationRequested()
                SteamCloudSaveProfileManager.completeDeferredIndependentSwitch(host)
                cancellationHandle.throwIfCancellationRequested()
                val loginStart = SteamCloudOperationMutex.runExclusive(host) {
                    val existingAuth = SteamCloudAuthStore.readAuthMaterial(host)
                    val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
                    if (currentMode == SteamCloudSaveMode.STEAM_CLOUD &&
                        (existingAuth == null ||
                            !existingAuth.accountName.equals(normalizedUsername, ignoreCase = true))
                    ) {
                        SteamCloudSaveProfileManager.switchMode(
                            context = host,
                            fromMode = currentMode,
                            toMode = SteamCloudSaveMode.INDEPENDENT,
                        )
                    }
                    existingAuth to SteamCloudAuthStore.beginLoginAttempt(host)
                }
                savedAuthMaterial = loginStart.first
                loginAttemptId = loginStart.second
                cancellationHandle.bindLoginAttempt(loginAttemptId) { cancelledAttemptId ->
                    runCatching {
                        steamCloudLoginCleanupExecutor.execute {
                            runCatching {
                                SteamCloudAuthStore.finishLoginAttempt(
                                    context = host.applicationContext,
                                    attemptId = cancelledAttemptId,
                                )
                            }
                        }
                    }
                }
                cancellationHandle.throwIfCancellationRequested()
                val existingGuardData = reusableGuardDataForCredentials(
                    savedAccountName = savedAuthMaterial?.accountName.orEmpty(),
                    savedGuardData = savedAuthMaterial?.guardData.orEmpty(),
                    requestedUsername = normalizedUsername,
                )
                val authenticateWithGuardData = { guardData: String ->
                    SteamCloudAuthCoordinator.authenticateWithCredentials(
                        context = host,
                        username = normalizedUsername,
                        password = password,
                        existingGuardData = guardData,
                        prompt = buildSteamCloudAuthPrompt(
                            host = host,
                            cancellationHandle = cancellationHandle,
                            loginGeneration = loginGeneration,
                        ),
                        cancellationHandle = cancellationHandle,
                    )
                }
                var retriedWithoutGuardData = false
                val authResult = try {
                    authenticateWithGuardData(existingGuardData)
                } catch (error: Throwable) {
                    val shouldRetryWithoutGuardData =
                        existingGuardData.isNotBlank() &&
                            !cancellationHandle.isCancelled &&
                            shouldRetrySteamCloudCredentialLoginWithoutGuardData(error)
                    if (!shouldRetryWithoutGuardData) {
                        throw error
                    }
                    retriedWithoutGuardData = true
                    runCatching { SteamCloudAuthStore.clearGuardData(host) }
                    authenticateWithGuardData("")
                }
                val loginDiagnosticsExtraLines = mutableListOf(
                    "Refresh token received: ${authResult.refreshToken.length} chars",
                    "Guard data returned: ${if (authResult.guardData.isBlank()) "no" else "yes"}",
                    "SteamID64 resolved: ${if (authResult.steamId64.isBlank()) "no" else "yes"}",
                    "Resolved SteamID64 value: ${authResult.steamId64.ifBlank { "<blank>" }}",
                )
                if (existingGuardData.isNotBlank()) {
                    loginDiagnosticsExtraLines +=
                        "Stored guard data reused for credential login: yes"
                }
                if (retriedWithoutGuardData) {
                    loginDiagnosticsExtraLines +=
                        "Stored guard data was cleared and the credential login was retried without it after Steam rejected the remembered device state."
                }
                // Identity is usable once authentication completes. Cosmetic HTTP requests
                // run through steamProfileTask after the account state is published.
                loginDiagnosticsExtraLines += "Profile fetch deferred to the independent profile refresh."
                cancellationHandle.throwIfCancellationRequested()
                SteamCloudOperationMutex.runExclusive(host) {
                    cancellationHandle.throwIfCancellationRequested()
                    SteamCloudAuthStore.recordAuthSuccess(
                        context = host,
                        loginAttemptId = loginAttemptId,
                        cancellationHandle = cancellationHandle,
                        accountName = authResult.accountName,
                        refreshToken = authResult.refreshToken,
                        guardData = authResult.guardData,
                        steamId64 = authResult.steamId64,
                        personaName = "",
                        avatarUrl = "",
                    )
                }
                SteamAuthenticationCircuitBreaker.reset()
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
                val shouldPromptSteamCloudSaveModeSwitch =
                    LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD
                host.runOnUiThread {
                    if (steamCloudLoginGeneration != loginGeneration) {
                        return@runOnUiThread
                    }
                    uiState = uiState.copy(
                        steamCloudAccountName = authResult.accountName,
                        steamCloudRefreshTokenConfigured = true,
                    )
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
                    finishSteamIdentityChange(host, clearBusy = false)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                val loginCancelled = cancellationHandle.isCancelled || isSteamCloudLoginCancellation(error)
                val failureCategory = if (loginCancelled) {
                    SteamCloudFailureCategory.CANCELLED
                } else {
                    SteamCloudFailureClassifier.classify(error)
                }
                val circuitTripped = SteamAuthenticationCircuitBreaker.trip(failureCategory)
                val circuitLogoutFailure = if (circuitTripped) {
                    runCatching {
                        SteamAccountLogoutCoordinator.logout(
                            context = host,
                            clearDiagnostics = false,
                        )
                    }.exceptionOrNull()
                } else {
                    null
                }
                if (!loginCancelled) {
                    savedAuthMaterial?.let { authMaterial ->
                        runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                    }
                }
                host.runOnUiThread {
                    if (steamCloudLoginGeneration != loginGeneration) {
                        return@runOnUiThread
                    }
                    clearPendingSteamCloudChallengeState()
                    pendingSteamCloudLoginCancellationHandle = null
                    pendingSteamCloudLoginTask = null
                    dismissSteamCloudManifestDialog()
                    if (circuitTripped) {
                        _effects.tryEmit(
                            Effect.ShowDialog(
                                title = host.getString(R.string.main_steam_auth_circuit_logout_title),
                                message = when {
                                    circuitLogoutFailure != null -> host.getString(
                                        R.string.main_steam_auth_circuit_logout_failed_message,
                                        circuitLogoutFailure.message ?: circuitLogoutFailure.javaClass.simpleName,
                                    )

                                    failureCategory == SteamCloudFailureCategory.RATE_LIMITED -> host.getString(
                                        R.string.main_steam_auth_circuit_rate_limited_message,
                                    )

                                    else -> host.getString(
                                        R.string.main_steam_auth_circuit_rejected_message,
                                        summary,
                                    )
                                },
                            )
                        )
                    } else if (loginCancelled) {
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
                    finishSteamIdentityChange(host)
                }
            } finally {
                runCatching {
                    SteamCloudAuthStore.finishLoginAttempt(
                        context = host,
                        attemptId = loginAttemptId,
                    )
                }
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

    internal fun readSavedSteamCloudLoginCredentials(host: Activity): SteamCloudAuthStore.SavedLoginCredentials =
        SteamCloudAuthStore.readSavedLoginCredentials(host)

    fun saveSteamCloudLoginCredentials(
        host: Activity,
        username: String,
        password: String,
    ) {
        val normalizedUsername = username.trim()
        executor.execute {
            runCatching {
                SteamCloudAuthStore.saveLoginCredentials(
                    context = host.applicationContext,
                    username = normalizedUsername,
                    password = password,
                )
            }
        }
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
                val snapshot = SteamCloudOperationMutex.runExclusive(host) {
                    SteamCloudSyncRepository.refreshManifest(
                        host,
                        requireCurrentSteamCloudAuthMaterial(host, authMaterial),
                    )
                }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_manifest_refresh_succeeded,
                            snapshot.fileCount
                        )
                    )
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_manifest_refresh_failed,
                            summary
                        )
                    )
                    refreshSteamState(host)
                }
            }
        }
    }

    fun onOpenSteamCloudManifestDialog(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, null)
        executor.execute {
            val steamId64 = SteamCloudAuthStore.readSnapshot(host).steamId64
            val snapshot = runCatching {
                SteamCloudManifestStore.readSnapshot(host, steamId64)
            }.getOrNull()
            host.runOnUiThread {
                setBusy(false, null)
                if (snapshot == null) {
                    showToast(host, UiText.StringResource(R.string.settings_steam_cloud_manifest_missing))
                } else {
                    uiState = uiState.copy(steamCloudManifestDialogSnapshot = snapshot)
                }
            }
        }
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
                val plan = SteamCloudOperationMutex.runExclusive(host) {
                    SteamCloudSyncRepository.buildUploadPlan(
                        host,
                        requireCurrentSteamCloudAuthMaterial(host, authMaterial),
                    )
                }
                host.runOnUiThread {
                    dismissSteamCloudPushConfirmDialog()
                    uiState = uiState.copy(steamCloudUploadPlanDialogSnapshot = plan)
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_upload_plan_failed,
                            summary
                        )
                    )
                    refreshSteamState(host)
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
                val plan = SteamCloudOperationMutex.runExclusive(host) {
                    SteamCloudSyncRepository.buildUploadPlan(
                        host,
                        requireCurrentSteamCloudAuthMaterial(host, authMaterial),
                    )
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
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_upload_plan_failed,
                            summary
                        )
                    )
                    refreshSteamState(host)
                }
            }
        }
    }

    fun onConfirmSteamCloudPush(host: Activity) {
        if (uiState.busy) {
            return
        }
        if (rejectDisabledSteamCloudSync(host)) return
        val plan = uiState.steamCloudUploadConfirmPlan ?: return
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_push))
        executor.execute {
            try {
                val result = SteamCloudOperationMutex.runExclusive(host) {
                    SteamCloudSyncRepository.pushLocalChanges(
                        host,
                        requireCurrentSteamCloudAuthMaterial(host, authMaterial),
                        plan,
                    )
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
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_push_failed,
                            summary
                        )
                    )
                    refreshSteamState(host)
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
        if (rejectDisabledSteamCloudSync(host)) return
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            return
        }
        beginSteamCloudPull()
        executor.execute {
            try {
                val result = SteamCloudOperationMutex.runExclusive(host) {
                    val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
                    if (currentMode != SteamCloudSaveMode.STEAM_CLOUD) {
                        SteamCloudSaveProfileManager.saveActiveProfile(host, currentMode)
                    }
                    SteamCloudSyncRepository.pullAll(
                        host = host,
                        authMaterial = requireCurrentSteamCloudAuthMaterial(host, authMaterial),
                        saveModeAfterPull = SteamCloudSaveMode.STEAM_CLOUD,
                        progressCallback = steamCloudPullProgressReporter(host),
                    )
                }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_pull_succeeded,
                            result.appliedFileCount
                        )
                    )
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_pull_failed,
                            summary
                        )
                    )
                    refreshSteamState(host)
                }
            }
        }
    }

    fun onSteamCloudWattAccelerationChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamCloudWattAccelerationEnabled(host, enabled)
        val effectiveEnabled = LauncherPreferences.isSteamCloudWattAccelerationEnabled(host)
        uiState = uiState.copy(
            steamCloudWattAccelerationEnabled = effectiveEnabled,
            workshopWattAccelerationEnabled = effectiveEnabled,
        )
        refreshConfiguration(host, clearBusy = false)
    }

    private fun rejectDisabledSteamCloudSync(host: Activity): Boolean {
        if (!LauncherPreferences.isSteamCloudSyncDisabled(host)) return false
        showToast(host, UiText.StringResource(R.string.settings_steam_cloud_sync_disabled_title))
        return true
    }

    fun onSteamCloudSyncDisabledChanged(host: Activity, disabled: Boolean) {
        refreshTasks.getValue(SettingsLoadDomain.CONFIGURATION).cancel()
        val generation = ++steamCloudControlGeneration
        pendingSteamCloudSyncDisabled = disabled
        val context = host.applicationContext
        uiState = uiState.copy(steamCloudSyncDisabled = disabled)
        steamCloudControlExecutor.execute {
            val result = runCatching {
                LauncherPreferences.setSteamCloudSyncDisabled(context, disabled)
                if (disabled) SteamCloudSyncProcessService.cancel(context)
            }
            host.runOnUiThread {
                if (generation != steamCloudControlGeneration) return@runOnUiThread
                // A different configuration read may have captured the pre-write control value.
                refreshTasks.getValue(SettingsLoadDomain.CONFIGURATION).cancel()
                pendingSteamCloudSyncDisabled = null
                result.exceptionOrNull()?.let { showToast(host, UiText.DynamicString(summarizeSteamCloudError(host, it))) }
                refreshConfiguration(host, clearBusy = false)
            }
        }
    }

    fun onSteamCloudAutoLaunchAfterSyncChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamCloudAutoLaunchAfterSyncEnabled(host, enabled)
        uiState = uiState.copy(steamCloudAutoLaunchAfterSyncEnabled = enabled)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onSteamGamePresenceChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamGamePresenceEnabled(host, enabled)
        uiState = uiState.copy(steamGamePresenceEnabled = enabled)
        if (enabled) {
            SteamGamePresenceService.startIfEnabled(host)
        } else {
            SteamGamePresenceService.stop(host)
        }
        refreshConfiguration(host, clearBusy = false)
    }

    fun onRichPresenceDisplayPreferencesChanged(
        host: Activity,
        settings: RichPresenceDisplayPreferences,
    ) {
        LauncherPreferences.saveRichPresenceDisplayPreferences(host, settings)
        uiState = uiState.copy(richPresenceDisplayPreferences = settings)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onSteamAchievementSyncChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setSteamAchievementSyncEnabled(host, enabled)
        uiState = uiState.copy(steamAchievementSyncEnabled = enabled)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onAchievementUnlockNotificationChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setAchievementUnlockNotificationEnabled(host, enabled)
        uiState = uiState.copy(achievementUnlockNotificationEnabled = enabled)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onClearSteamCloudNetworkCache(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(true, null)
        executor.execute {
            SteamCloudNetworkEnvironment.clearNetworkCache(host)
            SteamCloudAvatarCacheStore.clear(host)
            host.runOnUiThread {
                showToast(host, UiText.StringResource(R.string.settings_steam_cloud_network_cache_cleared))
                setBusy(false, null)
                refreshSteamAccount(host)
            }
        }
    }

    fun onWorkshopMaxConcurrentDownloadsChanged(host: Activity, value: Int) {
        LauncherPreferences.saveWorkshopMaxConcurrentDownloads(host, value)
        uiState = uiState.copy(
            workshopMaxConcurrentDownloads = LauncherConfig.normalizeWorkshopMaxConcurrentDownloads(value)
        )
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopDownloadThreadsChanged(host: Activity, value: Int) {
        LauncherPreferences.saveWorkshopDownloadThreads(host, value)
        uiState = uiState.copy(
            workshopDownloadThreads = LauncherConfig.normalizeWorkshopDownloadThreads(value)
        )
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopWattAccelerationChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setWorkshopWattAccelerationEnabled(host, enabled)
        val effectiveEnabled = LauncherPreferences.isWorkshopWattAccelerationEnabled(host)
        uiState = uiState.copy(
            steamCloudWattAccelerationEnabled = effectiveEnabled,
            workshopWattAccelerationEnabled = effectiveEnabled,
        )
        refreshConfiguration(host, clearBusy = false)
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
                refreshConfiguration(host)
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
    }

    fun onWorkshopSteamLanguageChanged(host: Activity, language: SteamLanguagePreference) {
        LauncherPreferences.saveWorkshopSteamLanguage(host, language)
        uiState = uiState.copy(workshopSteamLanguage = language)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopDefaultSortChanged(host: Activity, sort: WorkshopBrowseSort) {
        LauncherPreferences.saveWorkshopDefaultSort(host, sort)
        uiState = uiState.copy(workshopDefaultSort = sort)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopAutoImportChanged(host: Activity, enabled: Boolean) {
        LauncherPreferences.setWorkshopAutoImportEnabled(host, enabled)
        uiState = uiState.copy(workshopAutoImportEnabled = enabled)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopAutoImportAtlasDownscaleChanged(host: Activity, enabled: Boolean) {
        ImportPatchRegistry.setEnabled(host, AtlasOfflineDownscalePatchModule.id, enabled)
        uiState = uiState.copy(workshopAutoImportAtlasDownscaleEnabled = enabled)
        refreshConfiguration(host, clearBusy = false)
    }

    fun onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged(host: Activity, maxEdgePx: Int) {
        LauncherPreferences.saveWorkshopAutoImportAtlasDownscaleMaxEdgePx(host, maxEdgePx)
        uiState = uiState.copy(
            workshopAutoImportAtlasDownscaleMaxEdgePx =
                LauncherConfig.normalizeWorkshopAutoImportAtlasDownscaleMaxEdgePx(maxEdgePx)
        )
        refreshConfiguration(host, clearBusy = false)
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

    fun onClearSlingBreakData(host: Activity) {
        clearSlingBreakWebViewData(host) {
            showToast(host, UiText.StringResource(R.string.settings_developer_slingbreak_clear_done))
        }
    }

    internal fun readBaiduTranslationCredentials(host: Activity): BaiduTranslationCredentials =
        BaiduTranslationCredentialsRepository(host).getCredentials()

    fun onSaveBaiduTranslationCredentials(
        host: Activity,
        appId: String,
        apiKey: String,
    ) {
        refreshTasks.getValue(SettingsLoadDomain.TRANSLATION).cancel()
        val repository = BaiduTranslationCredentialsRepository(host)
        repository.setCredentials(BaiduTranslationCredentials(appId = appId, apiKey = apiKey))
        val savedCredentials = repository.getCredentials()
        uiState = uiState.copy(
            baiduTranslationCredentialsConfigured = savedCredentials.isConfigured(),
        )
        setLoadState(SettingsLoadDomain.TRANSLATION, SettingsLoadState.READY)
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
                refreshSteamState(host, clearBusy = false)
                return
            }
        }

        val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
        if (currentMode == targetMode) {
            refreshSteamState(host, clearBusy = false)
            return
        }
        if (targetMode == SteamCloudSaveMode.STEAM_CLOUD &&
            !SteamCloudSaveProfileManager.profileIsInitialized(host, targetMode)
        ) {
            onPullSteamCloudFromManifest(host)
            return
        }

        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_save_mode_switch))
        refreshTasks.getValue(SettingsLoadDomain.STEAM_SAVES).cancel()
        setLoadState(SettingsLoadDomain.STEAM_SAVES, SettingsLoadState.LOADING)
        executor.execute {
            try {
                SteamCloudOperationMutex.runExclusive(host) {
                    val authoritativeMode = LauncherPreferences.readSteamCloudSaveMode(host)
                    if (targetMode == SteamCloudSaveMode.STEAM_CLOUD &&
                        !SteamCloudAuthStore.readSnapshot(host).isComplete
                    ) {
                        throw SteamCloudCredentialsMissingException(
                            host.getString(R.string.settings_steam_cloud_credentials_missing)
                        )
                    }
                    if (authoritativeMode != targetMode) {
                        SteamCloudSaveProfileManager.switchMode(
                            context = host,
                            fromMode = authoritativeMode,
                            toMode = targetMode,
                        )
                    }
                }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_save_mode_switch_succeeded,
                            targetMode.displayName(host),
                        )
                    )
                    refreshSteamState(host)
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
                    refreshSteamState(host)
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
                retrySettingsLoad(host, SettingsLoadDomain.STEAM_SAVES)
                return
            }
        refreshTasks.getValue(SettingsLoadDomain.STEAM_SAVES).cancel()
        setLoadState(SettingsLoadDomain.STEAM_SAVES, SettingsLoadState.LOADING)
        executor.execute {
            runCatching {
                SteamCloudOperationMutex.runExclusive(host) {
                    val nextPaths = LauncherPreferences.readSteamCloudSyncBlacklistPaths(host)
                        .toMutableSet()
                    if (selected) {
                        nextPaths += normalizedPath
                    } else {
                        nextPaths -= normalizedPath
                    }
                    LauncherPreferences.saveSteamCloudSyncBlacklistPaths(host, nextPaths)
                }
            }.onFailure { error ->
                host.runOnUiThread { showToast(host, UiText.DynamicString(summarizeSteamCloudError(host, error))) }
            }
            retrySettingsLoad(host, SettingsLoadDomain.STEAM_SAVES)
        }
    }

    fun onForceIndependentSaveOverwriteCloud(host: Activity) {
        if (uiState.busy) {
            return
        }
        if (rejectDisabledSteamCloudSync(host)) return
        if (LauncherPreferences.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD) {
            refreshSteamState(host, clearBusy = false)
            return
        }
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
        if (authMaterial == null) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_missing))
            refreshSteamState(host, clearBusy = false)
            return
        }
        if (!SteamCloudSaveProfileManager.profileHasRegularFiles(host, SteamCloudSaveMode.INDEPENDENT)) {
            showToast(host, UiText.StringResource(R.string.settings_steam_cloud_independent_profile_missing))
            refreshSteamState(host, clearBusy = false)
            return
        }

        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.settings_busy_steam_cloud_force_independent_override),
            operation = UiBusyOperation.STEAM_CLOUD_SYNC,
        )
        executor.execute {
            try {
                val (remoteBackupLabel, result) = SteamCloudOperationMutex.runExclusive(host) {
                    val currentAuthMaterial = requireCurrentSteamCloudAuthMaterial(host, authMaterial)
                    check(
                        SteamCloudSaveProfileManager.profileHasRegularFiles(
                            host,
                            SteamCloudSaveMode.INDEPENDENT,
                        )
                    ) { host.getString(R.string.settings_steam_cloud_independent_profile_missing) }
                    SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.STEAM_CLOUD)
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val remoteBackupLabel = backupRemoteSteamCloudProfile(host, currentAuthMaterial, timestamp)
                    SettingsSaveBackupService.backupSaveProfileToDownloads(
                        host = host,
                        sourceRoot = SteamCloudSaveProfileManager.profileRoot(
                            host,
                            SteamCloudSaveMode.INDEPENDENT
                        ),
                        backupFileName = "independent-saves-before-steam-cloud-overwrite-$timestamp.zip",
                        relativeSubdirectory = STEAM_CLOUD_BACKUP_DOWNLOAD_SUBDIR,
                    )

                    val result = SteamCloudSyncRepository.overwriteRemoteWithLocal(
                        host = host,
                        authMaterial = currentAuthMaterial,
                        sourceRoot = SteamCloudSaveProfileManager.profileRoot(host, SteamCloudSaveMode.INDEPENDENT),
                    )
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
                    refreshSteamState(host)
                }
            } catch (error: Throwable) {
                val summary = summarizeSteamCloudError(host, error)
                runCatching { SteamCloudAuthStore.recordFailure(host, summary, authMaterial) }
                host.runOnUiThread {
                    showToast(
                        host,
                        UiText.StringResource(
                            R.string.settings_steam_cloud_force_independent_override_failed,
                            summary,
                        ),
                        Toast.LENGTH_LONG,
                    )
                    refreshSteamState(host)
                }
            }
        }
    }

    fun onClearSteamCloudCredentials(host: Activity) {
        if (uiState.busy) {
            return
        }
        cancelActiveSteamCloudLogin("Steam Cloud credentials cleared.", clearBusy = false)
        beginSteamIdentityChange()
        setBusy(true, UiText.StringResource(R.string.settings_busy_steam_cloud_save_mode_switch))
        executor.execute {
            try {
                SteamAccountLogoutCoordinator.logout(
                    context = host,
                    clearDiagnostics = true,
                )
                host.runOnUiThread {
                    dismissSteamCloudManifestDialog()
                    dismissSteamCloudUploadPlanDialog()
                    dismissSteamCloudPushConfirmDialog()
                    showToast(host, UiText.StringResource(R.string.settings_steam_cloud_credentials_cleared))
                    finishSteamIdentityChange(host)
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
                    finishSteamIdentityChange(host)
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
        future.complete(SteamCloudDeviceConfirmationDecision.APPROVE_ON_TRUSTED_DEVICE)
    }

    fun onSelectSteamCloudLoginMethod(kind: SteamCloudLoginChallengeKind) {
        val selectedType = when (kind) {
            SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION -> SteamGuardChallengeType.DeviceConfirmation
            SteamCloudLoginChallengeKind.DEVICE_CODE -> SteamGuardChallengeType.DeviceCode
            SteamCloudLoginChallengeKind.EMAIL_CODE -> SteamGuardChallengeType.EmailCode
            SteamCloudLoginChallengeKind.METHOD_SELECTION -> return
        }
        val future = pendingSteamCloudSelectionFuture ?: return
        pendingSteamCloudSelectionFuture = null
        uiState = uiState.copy(steamCloudLoginChallenge = null)
        future.complete(selectedType)
    }

    fun onCancelSteamCloudChallenge(host: Activity? = null) {
        cancelActiveSteamCloudLogin("Steam Cloud login cancelled by user.", clearBusy = true)
        (host ?: boundActivityReference?.get())?.let { finishSteamIdentityChange(it, clearBusy = false) }
        host?.let {
            showToast(it, UiText.StringResource(R.string.settings_steam_cloud_login_cancelled))
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
        refreshConfiguration(host)
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

    fun onExportSaves(host: Activity) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(
            saveSlotDialogState = SaveSlotDialogState(
                exportFileName = SettingsFileService.buildSaveExportFileName(),
                cloudAvailable = true,
                selectedMode = LauncherPreferences.readSteamCloudSaveMode(host),
            )
        )
    }

    fun dismissSaveSlotDialog() {
        uiState = uiState.copy(saveSlotDialogState = null)
    }

    fun onSaveSlotSelected(host: Activity, mode: SteamCloudSaveMode) {
        val dialogState = uiState.saveSlotDialogState ?: return
        if (uiState.busy || (mode == SteamCloudSaveMode.STEAM_CLOUD && !dialogState.cloudAvailable)) {
            return
        }
        dismissSaveSlotDialog()
        dialogState.importUri?.let { uri ->
            prepareSaveImport(host, uri, mode)
        }
        dialogState.exportFileName?.let { fileName ->
            _effects.tryEmit(Effect.OpenExportSavesPicker(fileName, mode))
        }
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
        setBusy(
            busy = true,
            message = UiText.StringResource(
                R.string.common_busy_preparing_jvm_log_bundle_progress,
                0
            ),
            operation = UiBusyOperation.EXPORT_ARCHIVE,
            progressPercent = 0
        )
        executor.execute {
            try {
                val payload = JvmLogShareService.prepareSharePayload(
                    host,
                    exportProgressReporter(
                        host,
                        R.string.common_busy_preparing_jvm_log_bundle_progress
                    )
                )
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
                    refreshDiagnostics(host)
                }
            }
        }
    }

    fun onExportPerformanceLogsToFile() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(
            Effect.OpenExportPerformanceLogsPicker(
                SettingsFileService.buildPerformanceLogExportFileName()
            )
        )
    }

    fun onExportAgentWorkspaceToFile() {
        if (uiState.busy) {
            return
        }
        _effects.tryEmit(
            Effect.OpenExportAgentWorkspacePicker(
                SettingsFileService.buildAgentWorkspaceExportFileName()
            )
        )
    }

    fun onSharePerformanceLogs(host: Activity) {
        if (uiState.busy) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(
                R.string.settings_busy_preparing_performance_logs_progress,
                0
            ),
            operation = UiBusyOperation.EXPORT_ARCHIVE,
            progressPercent = 0
        )
        executor.execute {
            try {
                val payload = JvmLogShareService.preparePerformanceSharePayload(
                    host,
                    exportProgressReporter(
                        host,
                        R.string.settings_busy_preparing_performance_logs_progress
                    )
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    _effects.tryEmit(Effect.SharePerformanceLogsBundle(payload))
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_performance_logs_failed_prefix),
                                error
                            )
                        )
                    )
                }
            }
        }
    }

    fun onPerformanceLogsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(
                R.string.settings_busy_exporting_performance_logs_progress,
                0
            ),
            operation = UiBusyOperation.EXPORT_ARCHIVE,
            progressPercent = 0
        )
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportPerformanceLogBundle(
                    host,
                    uri,
                    exportProgressReporter(host, R.string.settings_busy_exporting_performance_logs_progress)
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.StringResource(R.string.settings_performance_logs_exported, exportedCount)
                    )
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_performance_logs_failed_prefix),
                                error
                            )
                        )
                    )
                }
            }
        }
    }

    fun onAgentWorkspaceExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(
                R.string.settings_busy_exporting_agent_workspace_progress,
                0
            ),
            operation = UiBusyOperation.EXPORT_ARCHIVE,
            progressPercent = 0
        )
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportAgentWorkspaceBundle(
                    host,
                    uri,
                    exportProgressReporter(host, R.string.settings_busy_exporting_agent_workspace_progress)
                )
                host.runOnUiThread {
                    setBusy(false, null)
                    if (exportedCount > 0) {
                        showToast(
                            host,
                            UiText.StringResource(
                                R.string.settings_agent_workspace_exported,
                                exportedCount
                            )
                        )
                    } else {
                        showToast(
                            host,
                            UiText.StringResource(R.string.settings_agent_workspace_exported_empty)
                        )
                    }
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.DynamicString(
                            StsExternalStorageAccess.buildFailureMessage(
                                host,
                                host.getString(R.string.settings_agent_workspace_export_failed_prefix),
                                error
                            )
                        )
                    )
                }
            }
        }
    }

    fun onLogsExportPicked(host: Activity, uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.settings_busy_exporting_jvm_logs_progress, 0),
            operation = UiBusyOperation.EXPORT_ARCHIVE,
            progressPercent = 0
        )
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportJvmLogBundle(
                    host,
                    uri,
                    exportProgressReporter(host, R.string.settings_busy_exporting_jvm_logs_progress)
                )
                host.runOnUiThread {
                    if (exportedCount > 0) {
                        showToast(
                            host,
                            UiText.StringResource(R.string.settings_logs_exported, exportedCount)
                        )
                    } else {
                        showToast(host, UiText.StringResource(R.string.settings_logs_exported_empty))
                    }
                    setBusy(false, null)
                    refreshDiagnostics(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
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
                    refreshDiagnostics(host)
                }
            }
        }
    }

    /**
     * Progress arrives on the main looper from the diagnostics process; keep it resilient by
     * ignoring updates that race with a finished or unrelated operation.
     */
    private fun exportProgressReporter(host: Activity, messageRes: Int): (Int) -> Unit {
        return { percent ->
            val clamped = percent.coerceIn(0, 100)
            host.runOnUiThread {
                if (uiState.busy && uiState.busyOperation == UiBusyOperation.EXPORT_ARCHIVE) {
                    setBusy(
                        busy = true,
                        message = UiText.StringResource(messageRes, clamped),
                        operation = UiBusyOperation.EXPORT_ARCHIVE,
                        progressPercent = clamped
                    )
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
                    setBusy(false, null)
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
                    setBusy(false, null)
                }
            }
        }
    }

    fun onTargetFpsSelected(host: Activity, targetFps: Float) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(selectedTargetFps = targetFps)
        saveTargetFpsSelection(host, targetFps)
        refreshConfiguration(host)
    }

    fun onVirtualResolutionModeChanged(host: Activity, mode: VirtualResolutionMode) {
        if (uiState.busy || uiState.virtualResolutionMode == mode) {
            return
        }
        uiState = uiState.copy(virtualResolutionMode = mode)
        saveVirtualResolutionModeSelection(host, mode)
        refreshConfiguration(host)
    }

    fun onRenderSurfaceBackendChanged(host: Activity, backend: RenderSurfaceBackend) {
        if (uiState.busy || uiState.renderSurfaceBackend == backend) {
            return
        }
        uiState = uiState.copy(renderSurfaceBackend = backend)
        saveRenderSurfaceBackendSelection(host, backend)
        refreshConfiguration(host)
        refreshRenderer(host)
    }

    fun onRendererSelectionModeChanged(host: Activity, mode: RendererSelectionMode) {
        if (uiState.busy || uiState.rendererSelectionMode == mode) {
            return
        }
        uiState = uiState.copy(rendererSelectionMode = mode)
        saveRendererSelectionModeSelection(host, mode)
        refreshConfiguration(host)
        refreshRenderer(host)
    }

    fun onManualRendererBackendChanged(host: Activity, backend: RendererBackend) {
        if (uiState.busy || uiState.manualRendererBackend == backend) {
            return
        }
        uiState = uiState.copy(manualRendererBackend = backend)
        saveManualRendererBackendSelection(host, backend)
        refreshConfiguration(host)
        refreshRenderer(host)
    }

    fun onGpuResourceGuardianModeChanged(host: Activity, mode: GpuResourceGuardianMode) {
        if (uiState.busy || uiState.gpuResourceGuardianMode == mode) {
            return
        }
        uiState = uiState.copy(gpuResourceGuardianMode = mode)
        saveGpuResourceGuardianModeSelection(host, mode)
        refreshConfiguration(host)
    }

    fun onGpuResourceGuardianPressureDownscaleChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || uiState.gpuResourceGuardianPressureDownscaleEnabled == enabled) {
            return
        }
        uiState = uiState.copy(gpuResourceGuardianPressureDownscaleEnabled = enabled)
        LauncherPreferences.setGpuResourceGuardianPressureDownscaleEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onAccelerationStrategyChanged(host: Activity, strategy: AccelerationStrategy) {
        if (uiState.busy || uiState.accelerationStrategy == strategy) {
            return
        }
        uiState = uiState.copy(accelerationStrategy = strategy)
        saveAccelerationStrategySelection(host, strategy)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onJvmTieredStopAtLevelChanged(host: Activity, level: Int) {
        if (uiState.busy) {
            return
        }
        val normalized = LauncherConfig.normalizeJvmTieredStopAtLevel(level)
        if (normalized == uiState.jvmTieredStopAtLevel) {
            return
        }
        uiState = uiState.copy(jvmTieredStopAtLevel = normalized)
        LauncherPreferences.saveJvmTieredStopAtLevel(host, normalized)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
        return true
    }

    fun onBackBehaviorChanged(host: Activity, behavior: BackBehavior) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(backBehavior = behavior)
        saveBackBehaviorSelection(host, behavior)
        refreshConfiguration(host)
    }

    fun onManualDismissBootOverlayChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(manualDismissBootOverlay = enabled)
        saveManualDismissBootOverlaySelection(host, enabled)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onTouchMouseInteractionModeChanged(host: Activity, mode: TouchMouseInteractionMode) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchMouseInteractionMode = mode)
        saveTouchMouseInteractionModeSelection(host, mode)
        refreshConfiguration(host)
    }

    fun onTouchDoubleClickAsRightClickChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchDoubleClickAsRightClick = enabled)
        saveTouchDoubleClickAsRightClickSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onIgnoreLongPressRightClickWhilePlayingCardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(ignoreLongPressRightClickWhilePlayingCard = enabled)
        saveIgnoreLongPressRightClickWhilePlayingCardSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onBuiltInSoftKeyboardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(builtInSoftKeyboardEnabled = enabled)
        saveBuiltInSoftKeyboardSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onAutoPopupKeyboardChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(autoPopupKeyboardEnabled = enabled)
        saveAutoPopupKeyboardSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onFloatingToolButtonChanged(host: Activity, buttonId: String, enabled: Boolean) {
        if (uiState.busy || buttonId !in LauncherPreferences.FLOATING_TOOL_BUTTON_IDS) {
            return
        }
        val buttons = uiState.floatingToolButtons.toMutableSet().apply {
            if (enabled) add(buttonId) else remove(buttonId)
        }
        uiState = uiState.copy(floatingToolButtons = buttons)
        LauncherPreferences.saveFloatingToolButtons(host, buttons)
        refreshConfiguration(host)
    }

    fun onHapticFeedbackChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(hapticFeedbackEnabled = enabled)
        saveHapticFeedbackSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onAutoSwitchLeftAfterRightClickChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(autoSwitchLeftAfterRightClick = enabled)
        saveAutoSwitchLeftAfterRightClickSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onShowModFileNameChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showModFileName = enabled)
        saveShowModFileNameSelection(host, enabled)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onPreloadAllJreLibrariesChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(preloadAllJreLibrariesEnabled = enabled)
        savePreloadAllJreLibrariesSelection(host, enabled)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onJvmLogcatMirrorChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(jvmLogcatMirrorEnabled = enabled)
        saveJvmLogcatMirrorSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onGpuResourceDiagChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (enabled && !ArthasResourcePackService.isInstalledQuick(host)) {
            return
        }
        uiState = uiState.copy(gpuResourceDiagEnabled = enabled)
        saveGpuResourceDiagSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onArthasAnalysisChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy || !uiState.gpuResourceDiagEnabled) {
            return
        }
        if (enabled && !ArthasResourcePackService.isInstalledQuick(host)) {
            return
        }
        uiState = uiState.copy(arthasAnalysisEnabled = enabled)
        saveArthasAnalysisSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onInstallArthasResourceRequested(host: Activity) {
        if (uiState.busy) return
        refreshTasks.getValue(SettingsLoadDomain.RESOURCES).cancel()
        setLoadState(SettingsLoadDomain.RESOURCES, SettingsLoadState.LOADING)
        setBusy(true, UiText.StringResource(R.string.settings_busy_installing_arthas_resource))
        val preferredSource = UpdateMirrorManager.current(host)
        executor.execute {
            try {
                var lastProgressPercent: Int? = null
                val state = ArthasResourcePackService.downloadAndInstall(
                    context = host,
                    preferredSource = preferredSource,
                    onProgress = { progress ->
                        if (progress.percent == lastProgressPercent) return@downloadAndInstall
                        lastProgressPercent = progress.percent
                        host.runOnUiThread {
                            setBusy(
                                true,
                                UiText.StringResource(R.string.settings_busy_installing_arthas_resource),
                                progressPercent = progress.percent,
                            )
                        }
                    },
                )
                host.runOnUiThread {
                    uiState = uiState.copy(
                        arthasResourceInstalled = state.valid,
                        arthasResourceVersion = state.version,
                    )
                    setBusy(false, null)
                    showToast(host, UiText.StringResource(R.string.settings_arthas_resource_installed))
                    refreshResources(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread {
                    setBusy(false, null)
                    showToast(
                        host,
                        UiText.DynamicString(
                            host.getString(
                                R.string.settings_arthas_resource_install_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        ),
                        Toast.LENGTH_LONG
                    )
                    refreshResources(host)
                }
            }
        }
    }

    fun onGdxPadCursorDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(gdxPadCursorDebugEnabled = enabled)
        saveGdxPadCursorDebugSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onGlBridgeSwapHeartbeatDebugChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(glBridgeSwapHeartbeatDebugEnabled = enabled)
        saveGlBridgeSwapHeartbeatDebugSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onMobileHudEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(mobileHudEnabled = enabled)
        saveMobileHudEnabledSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onCompendiumUpgradeTouchFixEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(compendiumUpgradeTouchFixEnabled = enabled)
        saveCompendiumUpgradeTouchFixSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onTogetherInSpireRouteLockEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(togetherInSpireRouteLockEnabled = enabled)
        LauncherPreferences.setTogetherInSpireRouteLockEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onRamSaverOptimizationChanged(
        host: Activity,
        optimization: io.stamethyst.config.RamSaverOptimization,
        enabled: Boolean,
    ) {
        if (uiState.busy) return
        LauncherPreferences.setRamSaverOptimization(host, optimization, enabled)
        uiState = uiState.copy(ramSaverOptimizations = uiState.ramSaverOptimizations + (optimization to enabled))
        refreshConfiguration(host)
    }

    fun onTogetherInSpireEasyTierAutofillEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(togetherInSpireEasyTierAutofillEnabled = enabled)
        LauncherPreferences.setTogetherInSpireEasyTierAutofillEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onLocalTestCloudControlEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(localTestCloudControlEnabled = enabled)
        CloudControlConfig.setLocalTestChannelEnabled(host, enabled)
        refreshConfiguration(host)
        retrySettingsLoad(host, SettingsLoadDomain.EASYTIER)
    }

    fun onSteamAchievementDebugModeEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(steamAchievementDebugModeEnabled = enabled)
        LauncherPreferences.setSteamAchievementDebugModeEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onSlingBreakAudioDebugModeChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(slingBreakAudioDebugModeEnabled = enabled)
        LauncherPreferences.setSlingBreakAudioDebugModeEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onLocalTestEndpointsChanged(
        host: Activity,
        onlineServiceBaseUrl: String,
        configServerUrl: String,
        entryNodeUrl: String,
    ): Boolean {
        if (uiState.busy) {
            return false
        }
        val saved = CloudControlConfig.updateLocalTestEndpoints(
            context = host,
            onlineServiceBaseUrl = onlineServiceBaseUrl,
            configServerUrl = configServerUrl,
            entryNodeUrl = entryNodeUrl,
        )
        if (saved) {
            uiState = uiState.copy(
                localTestOnlineServiceBaseUrl = LauncherPreferences.readLocalTestOnlineServiceBaseUrl(host),
                localTestConfigServerUrl = LauncherPreferences.readLocalTestConfigServerUrl(host),
                localTestEntryNodeUrl = LauncherPreferences.readLocalTestEntryNodeUrl(host),
            )
            refreshConfiguration(host)
            retrySettingsLoad(host, SettingsLoadDomain.EASYTIER)
        }
        return saved
    }

    fun onGameDisplayRegionChanged(host: Activity, region: io.stamethyst.backend.render.GameDisplayRegion) {
        if (uiState.busy) return
        val normalized = region.normalized()
        uiState = uiState.copy(
            gameDisplayRegion = normalized,
            avoidDisplayCutout = false,
            cropScreenBottom = false,
        )
        LauncherPreferences.saveGameDisplayRegion(host, normalized)
        refreshConfiguration(host)
    }

    fun onRamSaverEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(ramSaverEnabled = enabled)
        saveRamSaverEnabledSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onMtsPatchCacheEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(mtsPatchCacheEnabled = enabled)
        saveMtsPatchCacheEnabledSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onMtsCacheStartupOptimizationChanged(
        host: Activity,
        optimization: io.stamethyst.config.MtsCacheStartupOptimization,
        enabled: Boolean,
    ) {
        if (uiState.busy) return
        LauncherConfig.setMtsCacheStartupOptimization(host, optimization, enabled)
        uiState = uiState.copy(
            mtsCacheStartupOptimizations = uiState.mtsCacheStartupOptimizations + (optimization to enabled)
        )
        refreshConfiguration(host)
    }

    fun onFramePacingModeChanged(host: Activity, mode: FramePacingMode) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(framePacingMode = mode)
        LauncherPreferences.saveFramePacingMode(host, mode)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onGamePerformanceOverlayChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(showGamePerformanceOverlay = enabled)
        saveGamePerformanceOverlaySelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onSustainedPerformanceModeChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(sustainedPerformanceModeEnabled = enabled)
        saveSustainedPerformanceModeSelection(host, enabled)
        refreshConfiguration(host)
    }

    fun onTouchscreenInputModeChanged(host: Activity, mode: TouchscreenInputMode) {
        if (uiState.busy) {
            return
        }
        if (!saveTouchscreenInputModeSelection(host, mode)) {
            return
        }
        uiState = uiState.copy(touchscreenInputMode = mode)
        refreshConfiguration(host)
    }

    fun onCardPlayOptimizationModeChanged(host: Activity, mode: CardPlayOptimizationMode) {
        if (uiState.busy) {
            return
        }
        if (!saveCardPlayOptimizationModeSelection(host, mode)) {
            return
        }
        uiState = uiState.copy(cardPlayOptimizationMode = mode)
        refreshConfiguration(host)
    }

    fun onTouchIndicatorEnabledChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(touchIndicatorEnabled = enabled)
        GameplaySettingsService.setTouchIndicatorEnabled(host, enabled)
        refreshConfiguration(host)
    }

    fun onHideEnemyPanelWhileTargetingChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) return
        GameplaySettingsService.setHideEnemyPanelWhileTargeting(host, enabled)
        uiState = uiState.copy(hideEnemyPanelWhileTargeting = enabled)
        refreshConfiguration(host)
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
        refreshConfiguration(host)
    }

    fun onGameplayLargerUiChanged(host: Activity, enabled: Boolean) {
        if (uiState.busy) {
            return
        }
        if (!saveGameplayLargerUiSelection(host, enabled)) {
            return
        }
        uiState = uiState.copy(gameplayLargerUiEnabled = enabled)
        refreshConfiguration(host)
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
            val importLog = runCatching { StsJarImportLogStore.create(host) }.getOrNull()
            StsJarImportLogStore.append(importLog, "start type=picker uri=$uri thread=${Thread.currentThread().name}")
            try {
                val displayName = SettingsFileService.resolveDisplayName(host, uri)
                val preparedFile = createStsJarImportScratchFile(host)
                stagedFile = preparedFile
                StsJarImportLogStore.append(importLog, "scratch ${describeImportFile(preparedFile)}")
                SettingsFileService.copyUriToFile(host, uri, preparedFile)
                StsJarImportLogStore.append(importLog, "after_copy ${describeImportFile(preparedFile)}")
                StsJarValidator.validate(preparedFile)
                StsJarImportLogStore.append(importLog, "validator=success")
                val integrity = StsDesktopJarIntegrity.inspect(preparedFile)
                StsJarImportLogStore.append(importLog, "integrity matches=${integrity.matchesExpected} expected=${integrity.expectedSha1} actual=${integrity.actualSha1}")
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
                StsJarImportLogStore.append(importLog, "failure ${error.javaClass.name}: ${error.message} ${stagedFile?.let(::describeImportFile).orEmpty()}")
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
                StsJarImportLogStore.append(importLog, "cleanup ${stagedFile?.let(::describeImportFile).orEmpty()}")
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
        val importLog = runCatching { StsJarImportLogStore.create(host) }.getOrNull()
        StsJarImportLogStore.append(importLog, "start type=quick_start mode=$mode generation=$generation thread=${Thread.currentThread().name}")
        StsJarImportLogStore.append(importLog, "transientRoot=${RuntimePaths.transientFilesRoot(host).absolutePath} state=${describeImportFile(RuntimePaths.transientFilesRoot(host))}")
        var downloadedJarForDiagnostics: File? = null
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
            downloadedJarForDiagnostics = downloadedJar
            StsJarImportLogStore.append(importLog, "download_complete ${describeImportFile(downloadedJar)} generation=$generation")
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
                StsJarImportLogStore.append(importLog, "cancelled_before_import generation=$generation ${describeImportFile(downloadedJar)}")
                downloadedJar.delete()
                throw CancellationException("Quick start Steam import was cancelled")
            }
            val sourceCleaned = QuickStartSteamImport.importDownloadedJar(
                context = host.applicationContext,
                source = downloadedJar,
            )
            StsJarImportLogStore.append(importLog, "import=success source=${downloadedJar.absolutePath} target=${RuntimePaths.importedStsJar(host).absolutePath}")
            StsJarImportLogStore.append(importLog, "quick_start_source_cleanup=$sourceCleaned")
            if (!sourceCleaned) {
                android.util.Log.w("StsJarImport", "Imported game successfully; source cleanup will be retried by Clear junk files")
            }
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
            StsJarImportLogStore.append(importLog, "failure ${error.javaClass.name}: ${error.message} file=${downloadedJarForDiagnostics?.let(::describeImportFile).orEmpty()} generation=$generation")
            StsJarImportLogStore.append(importLog, error.stackTraceToString())
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
        if (LauncherPreferences.isSteamCloudSyncDisabled(host)) return null
        val authMaterial = runCatching { SteamCloudAuthStore.readAuthMaterial(host) }.getOrNull()
            ?: return null
        host.runOnUiThread {
            beginSteamCloudPull()
        }
        return SteamCloudOperationMutex.runExclusive(host) {
            val currentAuthMaterial = requireCurrentSteamCloudAuthMaterial(host, authMaterial)
            val currentMode = LauncherPreferences.readSteamCloudSaveMode(host)
            if (currentMode != SteamCloudSaveMode.STEAM_CLOUD) {
                SteamCloudSaveProfileManager.saveActiveProfile(host, currentMode)
            }
            SteamCloudSyncRepository.pullAll(
                host = host,
                authMaterial = currentAuthMaterial,
                saveModeAfterPull = SteamCloudSaveMode.STEAM_CLOUD,
                progressCallback = steamCloudPullProgressReporter(host),
            )
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

    private fun applySnapshot(
        snapshot: SettingsRepository.SettingsSnapshot
    ) {
        val rendering = snapshot.rendering
        val jvm = snapshot.jvm
        val input = snapshot.input
        val diagnostics = snapshot.diagnostics
        val market = snapshot.market
        val compatibility = snapshot.compatibility
        val mobileGluesSettings = rendering.mobileGluesSettings
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
            nonRecommendedFpsEnabled = rendering.nonRecommendedFpsEnabled,
            framePacingMode = rendering.framePacingMode,
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
            gpuResourceGuardianMode = rendering.gpuResourceGuardianMode,
            gpuResourceGuardianPressureDownscaleEnabled =
                rendering.gpuResourceGuardianPressureDownscaleEnabled,
            accelerationStrategy = snapshot.accelerationStrategy,
            selectedJvmHeapMaxMb = jvm.heapMaxMb,
            jvmTieredStopAtLevel = jvm.tieredStopAtLevel,
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
            autoPopupKeyboardEnabled = input.autoPopupKeyboardEnabled,
            floatingToolButtons = input.floatingToolButtons,
            hapticFeedbackEnabled = input.hapticFeedbackEnabled,
            autoSwitchLeftAfterRightClick = input.autoSwitchLeftAfterRightClick,
            showModFileName = input.showModFileName,
            mobileHudEnabled = input.mobileHudEnabled,
            compendiumUpgradeTouchFixEnabled = input.compendiumUpgradeTouchFixEnabled,
            togetherInSpireRouteLockEnabled = input.togetherInSpireRouteLockEnabled,
            togetherInSpireEasyTierAutofillEnabled =
                input.togetherInSpireEasyTierAutofillEnabled,
            localTestCloudControlEnabled = snapshot.localTestCloudControlEnabled,
            steamAchievementDebugModeEnabled = snapshot.steamAchievementDebugModeEnabled,
            slingBreakAudioDebugModeEnabled = snapshot.slingBreakAudioDebugModeEnabled,
            localTestOnlineServiceBaseUrl = snapshot.localTestOnlineServiceBaseUrl,
            localTestConfigServerUrl = snapshot.localTestConfigServerUrl,
            localTestEntryNodeUrl = snapshot.localTestEntryNodeUrl,
            avoidDisplayCutout = input.avoidDisplayCutout,
            cropScreenBottom = input.cropScreenBottom,
            gameDisplayRegion = input.gameDisplayRegion,
            ramSaverEnabled = input.ramSaverEnabled,
            ramSaverOptimizations = input.ramSaverOptimizations,
            mtsPatchCacheEnabled = input.mtsPatchCacheEnabled,
            mtsCacheStartupOptimizations = input.mtsCacheStartupOptimizations,
            keepScreenOnTimeoutMinutes = input.keepScreenOnTimeoutMinutes,
            showGamePerformanceOverlay = diagnostics.showGamePerformanceOverlay,
            sustainedPerformanceModeEnabled = diagnostics.sustainedPerformanceModeEnabled,
            lwjglDebugEnabled = diagnostics.lwjglDebugEnabled,
            preloadAllJreLibrariesEnabled = diagnostics.preloadAllJreLibrariesEnabled,
            logcatCaptureEnabled = diagnostics.logcatCaptureEnabled,
            launcherLogcatCaptureEnabled = diagnostics.launcherLogcatCaptureEnabled,
            jvmLogcatMirrorEnabled = diagnostics.jvmLogcatMirrorEnabled,
            gpuResourceDiagEnabled = diagnostics.gpuResourceDiagEnabled,
            arthasAnalysisEnabled = diagnostics.arthasAnalysisEnabled &&
                (uiState.loadState(SettingsLoadDomain.RESOURCES) != SettingsLoadState.READY || uiState.arthasResourceInstalled),
            gdxPadCursorDebugEnabled = diagnostics.gdxPadCursorDebugEnabled,
            glBridgeSwapHeartbeatDebugEnabled = diagnostics.glBridgeSwapHeartbeatDebugEnabled,
            touchscreenInputMode = TouchscreenInputMode.fromSettings(
                touchscreenEnabled = input.touchscreenEnabled,
                nativeTouchscreenAllowlistEnabled =
                    compatibility.nativeTouchscreenAllowlistCompatEnabled
            ),
            cardPlayOptimizationMode = input.cardPlayOptimizationMode,
            touchIndicatorEnabled = input.touchIndicatorEnabled,
            hideEnemyPanelWhileTargeting = input.hideEnemyPanelWhileTargeting,
            gameplayFontScale = input.fontScale,
            gameplayLargerUiEnabled = input.largerUiEnabled,
            workshopMaxConcurrentDownloads = market.workshopMaxConcurrentDownloads,
            workshopDownloadThreads = market.workshopDownloadThreads,
            workshopWattAccelerationEnabled = market.workshopWattAccelerationEnabled,
            workshopSteamLanguage = market.workshopSteamLanguage,
            workshopDefaultSort = market.workshopDefaultSort,
            workshopAutoImportEnabled = market.workshopAutoImportEnabled,
            workshopAutoImportAtlasDownscaleEnabled = market.workshopAutoImportAtlasDownscaleEnabled,
            workshopAutoImportAtlasDownscaleMaxEdgePx = market.workshopAutoImportAtlasDownscaleMaxEdgePx,
            steamCloudWattAccelerationEnabled = snapshot.steam.wattAccelerationEnabled,
            steamCloudAutoLaunchAfterSyncEnabled = snapshot.steam.autoLaunchAfterSyncEnabled,
            steamCloudSyncDisabled = pendingSteamCloudSyncDisabled ?: snapshot.steam.syncDisabled,
            steamGamePresenceEnabled = snapshot.steam.gamePresenceEnabled,
            richPresenceDisplayPreferences = snapshot.steam.richPresenceDisplayPreferences,
            steamAchievementSyncEnabled = snapshot.steam.achievementSyncEnabled,
            achievementUnlockNotificationEnabled = snapshot.steam.achievementUnlockNotificationEnabled,
        )
    }

    private fun buildEasyTierSettingsUiState(): EasyTierSettingsUiState {
        val config = EasyTierConfigRepository.current()
        return EasyTierSettingsUiState(
            enabled = config.enabled,
            canConnect = config.canConnect,
            defaultMode = config.defaultMode,
            allowSharedCommunityNetwork = config.allowSharedCommunityNetwork,
            roomApiBaseUrl = config.roomApiBaseUrl,
            webConsoleApiBaseUrl = config.webConsoleApiBaseUrl,
            configServerUrl = config.configServerUrl,
            entryNodeUrl = config.entryNodeUrl,
            connectTimeoutSeconds = config.connectTimeoutSeconds,
            statusPollIntervalSeconds = config.statusPollIntervalSeconds,
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
        showSaveImportTargetDialog(host, uri)
    }

    private fun showSaveImportTargetDialog(host: Activity, uri: Uri) {
        if (host.isFinishing || host.isDestroyed) {
            return
        }
        val cloudAvailable = runCatching {
            SteamCloudAuthStore.readSnapshot(host).isComplete
        }.getOrDefault(false)
        val selectedMode = if (
            cloudAvailable &&
            LauncherPreferences.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD
        ) {
            SteamCloudSaveMode.STEAM_CLOUD
        } else {
            SteamCloudSaveMode.INDEPENDENT
        }
        uiState = uiState.copy(
            saveSlotDialogState = SaveSlotDialogState(
                importUri = uri,
                cloudAvailable = cloudAvailable,
                selectedMode = selectedMode,
            )
        )
    }

    private fun importSavesArchive(
        host: Activity,
        uri: Uri,
        targetMode: SteamCloudSaveMode,
        targetLabel: String,
        candidatePrefix: String? = null,
        selectedPaths: Set<String>? = null,
        preparedArchive: java.io.File? = null,
        expectedTargetRoot: java.io.File? = null,
        expectedSummary: io.stamethyst.ui.settings.files.SaveImportPreview.Summary? = null,
    ) {
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.settings_busy_importing_save_archive),
            operation = UiBusyOperation.EXPORT_ARCHIVE
        )
        executor.execute {
            try {
                val result = SteamCloudOperationMutex.runExclusive(host) {
                    if (
                        targetMode == SteamCloudSaveMode.STEAM_CLOUD &&
                        !SteamCloudAuthStore.readSnapshot(host).isComplete
                    ) {
                        throw SteamCloudCredentialsMissingException(
                            host.getString(R.string.settings_steam_cloud_credentials_missing)
                        )
                    }
                    val targetRoot = if (
                        LauncherPreferences.readSteamCloudSaveMode(host) == targetMode
                    ) {
                        RuntimePaths.stsRoot(host)
                    } else {
                        SteamCloudSaveProfileManager.profileRoot(host, targetMode)
                    }
                    if (expectedTargetRoot != null && targetRoot.canonicalFile != expectedTargetRoot.canonicalFile) {
                        throw java.io.IOException("Save slot or account changed; preview the import again")
                    }
                    val applyConfirmed = {
                        if (expectedSummary != null && preparedArchive != null) {
                            val candidate = io.stamethyst.ui.settings.files.SaveArchiveResolver.inspect(preparedArchive)
                                .single { it.prefix == candidatePrefix }
                            val current = io.stamethyst.ui.settings.files.SaveImportPreview.build(candidate, targetRoot, preparedArchive)
                            if (current != expectedSummary) throw java.io.IOException("Save files changed; preview the import again")
                        }
                        SettingsFileService.importSaveArchive(host, uri, targetRoot, candidatePrefix, selectedPaths)
                    }
                    if (targetRoot.canonicalFile == RuntimePaths.stsRoot(host).canonicalFile) {
                        SteamCloudLiveSaveLease.runMutation(host, applyConfirmed)
                    } else applyConfirmed()
                }
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
            } finally {
                preparedArchive?.delete()
            }
        }
    }

    private fun prepareSaveImport(host: Activity, uri: Uri, mode: SteamCloudSaveMode) {
        setBusy(true, UiText.StringResource(R.string.settings_busy_importing_save_archive))
        executor.execute {
            val archive = java.io.File(host.cacheDir, "save-preview-${java.util.UUID.randomUUID()}.zip")
            try {
                SettingsFileService.copyUriToFile(host, uri, archive)
                val state = SteamCloudOperationMutex.runExclusive(host) {
                    val root = if (LauncherPreferences.readSteamCloudSaveMode(host) == mode) RuntimePaths.stsRoot(host)
                        else SteamCloudSaveProfileManager.profileRoot(host, mode)
                    val candidates = io.stamethyst.ui.settings.files.SaveArchiveResolver.inspect(archive)
                    if (candidates.isEmpty()) throw java.io.IOException("No importable saves, preferences or runs")
                    SaveImportConfirmationState(archive, mode, root, candidates, 0,
                        io.stamethyst.ui.settings.files.SaveImportPreview.build(candidates.first(), root, archive))
                }
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) {
                        archive.delete()
                        refreshStatus(host)
                        return@runOnUiThread
                    }
                    uiState = uiState.copy(saveImportConfirmation = state)
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                archive.delete()
                host.runOnUiThread { showSaveImportFailedDialog(host, error); refreshStatus(host) }
            }
        }
    }

    fun selectSaveImportCandidate(host: Activity, index: Int) {
        val state = uiState.saveImportConfirmation ?: return
        if (uiState.busy || index !in state.candidates.indices) return
        setBusy(true, UiText.StringResource(R.string.settings_busy_importing_save_archive))
        executor.execute {
            try {
                val summary = io.stamethyst.ui.settings.files.SaveImportPreview.build(state.candidates[index], state.targetRoot, state.archive)
                host.runOnUiThread {
                    uiState = uiState.copy(saveImportConfirmation = state.copy(candidateIndex = index, summary = summary))
                    refreshStatus(host)
                }
            } catch (error: Throwable) {
                host.runOnUiThread { showSaveImportFailedDialog(host, error); refreshStatus(host) }
            }
        }
    }

    fun dismissSaveImportConfirmation() {
        if (uiState.busy) return
        uiState.saveImportConfirmation?.archive?.delete()
        uiState = uiState.copy(saveImportConfirmation = null)
    }

    fun confirmSaveImport(host: Activity, paths: Set<String>) {
        val state = uiState.saveImportConfirmation ?: return
        if (uiState.busy || paths.isEmpty()) return
        uiState = uiState.copy(saveImportConfirmation = null)
        importSavesArchive(host, Uri.fromFile(state.archive), state.mode, state.mode.displayName(host),
            state.candidates[state.candidateIndex].prefix, paths, state.archive, state.targetRoot, state.summary)
    }

    fun onSavesExportPicked(
        host: Activity,
        uri: Uri?,
        sourceMode: SteamCloudSaveMode,
    ) {
        if (uri == null) {
            return
        }
        setBusy(
            busy = true,
            message = UiText.StringResource(R.string.settings_busy_exporting_save_archive),
            operation = UiBusyOperation.EXPORT_ARCHIVE
        )
        executor.execute {
            try {
                val exportedCount = SettingsFileService.exportSaveBundle(host, uri, sourceMode)
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

    private fun beginSteamCloudPull() {
        setBusy(
            true,
            UiText.StringResource(R.string.settings_busy_steam_cloud_pull),
            UiBusyOperation.STEAM_CLOUD_SYNC,
        )
        uiState = uiState.copy(
            steamCloudPullProgress = SteamCloudSyncProgress(
                SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                SteamCloudSyncPhase.CONNECTING,
            ),
        )
    }

    private fun steamCloudPullProgressReporter(host: Activity): (SteamCloudSyncProgress) -> Unit = { progress ->
        host.runOnUiThread {
            if (uiState.busy && uiState.steamCloudPullProgress != null) {
                uiState = uiState.copy(steamCloudPullProgress = progress)
            }
        }
    }

    private fun setBusy(
        busy: Boolean,
        message: UiText?,
        operation: UiBusyOperation = UiBusyOperation.OTHER_BUSY,
        progressPercent: Int? = null
    ) {
        busyGeneration++
        // A refresh finishing the previous operation must never unlock a new operation.
        if (busy) statusRefreshBusyTracker.complete()
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyOperation = operation,
                steamCloudPullProgress = null,
                busyMessage = message,
                busyProgressPercent = progressPercent?.coerceIn(0, 100)
            )
        } else {
            uiState.copy(
                busy = false,
                busyOperation = UiBusyOperation.NONE,
                steamCloudPullProgress = null,
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
        val scratchDir = RuntimePaths.workshopStsJarImportRoot(host).apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Failed to create import scratch directory: $absolutePath")
            }
        }
        return File(scratchDir, "desktop-1.0-${System.nanoTime()}.jar")
    }

    private fun describeImportFile(file: File): String {
        val parent = file.parentFile
        return "path=${file.absolutePath} exists=${file.exists()} isFile=${file.isFile} length=${file.length()} canRead=${file.canRead()} canWrite=${file.canWrite()} usableSpace=${file.usableSpace} freeSpace=${file.freeSpace} totalSpace=${file.totalSpace} parentExists=${parent?.isDirectory == true}"
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
        io.stamethyst.ui.LauncherLegacyAlertDialog.Builder(host)
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
        val dialog = io.stamethyst.ui.LauncherLegacyAlertDialog.Builder(host)
            .setTitle(R.string.settings_steam_cloud_switch_prompt_title)
            .setMessage(
                host.getString(
                    R.string.settings_steam_cloud_switch_prompt_message,
                    accountName,
                )
            )
            .setPositiveButton(R.string.settings_steam_cloud_switch_prompt_confirm) { _, _ ->
                onPullSteamCloudFromManifest(host)
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
        io.stamethyst.ui.LauncherLegacyAlertDialog.Builder(host)
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
        rendererDecision: RendererDecision,
        systemGameMode: AndroidGameModeSnapshot,
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
        lines += host.getString(
            R.string.settings_status_target_fps,
            formatTargetFps(host, rendering.targetFps)
        )
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
        lines += host.getString(R.string.settings_status_jvm_tiered_stop_at_level, jvm.tieredStopAtLevel)
        lines += host.getString(
            R.string.settings_status_compressed_pointers,
            toggleStateText(host, jvm.compressedPointersEnabled)
        )
        lines += host.getString(
            R.string.settings_status_string_dedup,
            toggleStateText(host, jvm.stringDeduplicationEnabled)
        )
        lines += host.getString(
            R.string.settings_status_acceleration_strategy,
            snapshot.accelerationStrategy.displayName(host)
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
        lines += host.getString(R.string.settings_game_display_size_title) + ": " +
            (input.gameDisplayRegion?.persistedValue()
                ?: host.getString(R.string.settings_game_display_legacy))
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
            host.getString(systemGameMode.displayNameResId)
        )
        lines += host.getString(
            R.string.settings_status_system_game_mode_detail,
            host.getString(systemGameMode.descriptionResId)
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
            R.string.status_auto_popup_keyboard_format,
            toggleStateText(host, input.autoPopupKeyboardEnabled)
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

    private fun saveAutoPopupKeyboardSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setAutoPopupKeyboardEnabled(host, enabled)
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

    private fun saveArthasAnalysisSelection(host: Activity, enabled: Boolean) {
        LauncherPreferences.setArthasAnalysisEnabled(host, enabled)
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

    private fun saveAccelerationStrategySelection(host: Activity, strategy: AccelerationStrategy) {
        LauncherPreferences.saveAccelerationStrategy(host, strategy)
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

    private fun saveTargetFpsSelection(host: Activity, targetFps: Float) {
        LauncherPreferences.saveTargetFps(host, targetFps)
    }

    private fun resolveTargetFpsOptions(): List<Float> {
        return LauncherPreferences.TARGET_FPS_OPTIONS.map(Int::toFloat)
    }

    private fun formatTargetFps(host: Activity, targetFps: Float): String {
        if (targetFps <= 0f) return host.getString(R.string.settings_target_fps_unlimited)
        if (targetFps == targetFps.roundToInt().toFloat()) {
            return targetFps.roundToInt().toString()
        }
        return String.format(Locale.US, "%.3f", targetFps)
            .trimEnd('0')
            .trimEnd('.')
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
            SteamCloudSyncRepository.downloadAllToDirectory(
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

    private fun buildLogPathText(host: Activity, logs: List<File>): String {
        val latestLog = RuntimePaths.latestLog(host)
        val archivedDir = RuntimePaths.jvmLogsDir(host)
        val logcatDir = RuntimePaths.logcatDir(host)
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
        uiState.saveImportConfirmation?.archive?.delete()
        cancelActiveSteamCloudLogin("Settings screen cleared.", clearBusy = false)
        refreshTasks.values.forEach { it.cancel() }
        refreshExecutors.values.forEach { it.shutdownNow() }
        steamProfileTask.cancel()
        steamProfileExecutor.shutdownNow()
        executor.shutdownNow()
        steamCloudLoginCleanupExecutor.shutdown()
        steamCloudControlExecutor.shutdown()
        super.onCleared()
    }

    private fun buildSteamCloudAuthPrompt(
        host: Activity,
        cancellationHandle: SteamCloudAuthCoordinator.CancellationHandle,
        loginGeneration: Long,
    ): SteamCloudAuthCoordinator.AuthPrompt {
        return object : SteamCloudAuthCoordinator.AuthPrompt {
            override fun getChallengeSelection(
                challenges: List<SteamGuardChallenge>,
            ): CompletableFuture<SteamGuardChallengeType> {
                val future = CompletableFuture<SteamGuardChallengeType>()
                host.runOnUiThread {
                    if (!isActiveSteamCloudLogin(cancellationHandle, loginGeneration)) {
                        future.completeExceptionally(
                            CancellationException("Steam Cloud login cancelled by user.")
                        )
                        return@runOnUiThread
                    }
                    cancelPendingSteamCloudChallenge("Steam Cloud method selection replaced.", clearState = false)
                    pendingSteamCloudSelectionFuture = future
                    uiState = uiState.copy(
                        steamCloudLoginChallenge = SteamCloudLoginChallenge(
                            kind = SteamCloudLoginChallengeKind.METHOD_SELECTION,
                            availableKinds = challenges.mapNotNull(::toSteamCloudLoginChallengeKind).toSet(),
                        )
                    )
                }
                return future
            }

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

            override fun getDeviceConfirmationDecision(
                deviceCodeAvailable: Boolean,
            ): CompletableFuture<SteamCloudDeviceConfirmationDecision> {
                val future = CompletableFuture<SteamCloudDeviceConfirmationDecision>()
                host.runOnUiThread {
                    if (!isActiveSteamCloudLogin(cancellationHandle, loginGeneration)) {
                        future.completeExceptionally(
                            CancellationException("Steam Cloud login cancelled by user.")
                        )
                        return@runOnUiThread
                    }
                    cancelPendingSteamCloudChallenge("Steam Cloud device confirmation prompt replaced.", clearState = false)
                    pendingSteamCloudConfirmationFuture = future
                    uiState = uiState.copy(
                        steamCloudLoginChallenge = SteamCloudLoginChallenge(
                            kind = SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION,
                            deviceCodeAvailable = deviceCodeAvailable,
                        )
                    )
                }
                return future
            }
        }
    }

    private fun toSteamCloudLoginChallengeKind(
        challenge: SteamGuardChallenge,
    ): SteamCloudLoginChallengeKind? = when (challenge.type) {
        SteamGuardChallengeType.DeviceConfirmation -> SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION
        SteamGuardChallengeType.DeviceCode -> SteamCloudLoginChallengeKind.DEVICE_CODE
        SteamGuardChallengeType.EmailCode -> SteamCloudLoginChallengeKind.EMAIL_CODE
        else -> null
    }

    private fun clearPendingSteamCloudChallengeState() {
        pendingSteamCloudCodeFuture = null
        pendingSteamCloudConfirmationFuture = null
        pendingSteamCloudSelectionFuture = null
        if (uiState.steamCloudLoginChallenge != null) {
            uiState = uiState.copy(steamCloudLoginChallenge = null)
        }
    }

    private fun cancelPendingSteamCloudChallenge(reason: String, clearState: Boolean = true) {
        pendingSteamCloudCodeFuture?.completeExceptionally(CancellationException(reason))
        pendingSteamCloudConfirmationFuture?.completeExceptionally(CancellationException(reason))
        pendingSteamCloudSelectionFuture?.completeExceptionally(CancellationException(reason))
        pendingSteamCloudCodeFuture = null
        pendingSteamCloudConfirmationFuture = null
        pendingSteamCloudSelectionFuture = null
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

    private fun requireCurrentSteamCloudAuthMaterial(
        host: Activity,
        expected: SteamCloudAuthStore.SavedAuthMaterial,
    ): SteamCloudAuthStore.SavedAuthMaterial {
        val current = SteamCloudAuthStore.readAuthMaterial(host)
        if (current == null || current != expected) {
            throw SteamCloudCredentialsMissingException(
                host.getString(R.string.settings_steam_cloud_credentials_missing)
            )
        }
        return current
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

    private fun AccelerationStrategy.displayName(host: Activity): String {
        return when (this) {
            AccelerationStrategy.RMBGAME_FIRST ->
                host.getString(R.string.settings_developer_acceleration_strategy_rmbgame_first)
            AccelerationStrategy.BEST_PATH ->
                host.getString(R.string.settings_developer_acceleration_strategy_best_path)
        }
    }
}
