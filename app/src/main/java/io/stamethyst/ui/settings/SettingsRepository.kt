package io.stamethyst.ui.settings

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.MobileGluesConfigFile
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.LauncherUpdateVersioning
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.GpuResourceGuardianMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.ui.preferences.LauncherPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SettingsRepository {
    data class SettingsSnapshot(
        val themeMode: LauncherThemeMode,
        val themeColor: LauncherThemeColor,
        val playerName: String,
        val rendering: RenderingSnapshot,
        val jvm: JvmSnapshot,
        val input: InputSnapshot,
        val diagnostics: DiagnosticsSnapshot,
        val compatibility: CompatibilitySnapshot
    )

    data class RenderingSnapshot(
        val renderScale: Float,
        val targetFps: Int,
        val virtualResolutionMode: VirtualResolutionMode,
        val renderSurfaceBackend: RenderSurfaceBackend,
        val rendererSelectionMode: RendererSelectionMode,
        val manualRendererBackend: RendererBackend,
        val mobileGluesSettings: MobileGluesSettings,
        val rendererDecision: RendererDecision,
        val gpuResourceGuardianMode: GpuResourceGuardianMode
    )

    data class JvmSnapshot(
        val heapMaxMb: Int,
        val heapStartMb: Int,
        val compressedPointersEnabled: Boolean,
        val stringDeduplicationEnabled: Boolean
    )

    data class InputSnapshot(
        val backBehavior: BackBehavior,
        val manualDismissBootOverlay: Boolean,
        val showFloatingMouseWindow: Boolean,
        val touchMouseInteractionMode: TouchMouseInteractionMode,
        val touchDoubleClickAsRightClick: Boolean,
        val builtInSoftKeyboardEnabled: Boolean,
        val hapticFeedbackEnabled: Boolean,
        val autoSwitchLeftAfterRightClick: Boolean,
        val showModFileName: Boolean,
        val mobileHudEnabled: Boolean,
        val compendiumUpgradeTouchFixEnabled: Boolean,
        val avoidDisplayCutout: Boolean,
        val cropScreenBottom: Boolean,
        val touchscreenEnabled: Boolean,
        val fontScale: Float,
        val largerUiEnabled: Boolean
    )

    data class DiagnosticsSnapshot(
        val showGamePerformanceOverlay: Boolean,
        val sustainedPerformanceModeEnabled: Boolean,
        val systemGameMode: AndroidGameModeSnapshot,
        val lwjglDebugEnabled: Boolean,
        val preloadAllJreLibrariesEnabled: Boolean,
        val logcatCaptureEnabled: Boolean,
        val launcherLogcatCaptureEnabled: Boolean,
        val jvmLogcatMirrorEnabled: Boolean,
        val gpuResourceDiagEnabled: Boolean,
        val gdxPadCursorDebugEnabled: Boolean,
        val glBridgeSwapHeartbeatDebugEnabled: Boolean
    )

    data class CompatibilitySnapshot(
        val globalAtlasFilterCompatEnabled: Boolean,
        val modManifestRootCompatEnabled: Boolean,
        val runtimeTextureCompatEnabled: Boolean,
        val nativeTouchscreenAllowlistCompatEnabled: Boolean,
        val texturePressureDownscaleDivisor: Int,
        val forceLinearMipmapFilterEnabled: Boolean
    )

    data class UpdateStateSnapshot(
        val autoCheckUpdatesEnabled: Boolean,
        val preferredUpdateMirror: UpdateSource,
        val availableUpdateMirrors: List<UpdateSource>,
        val currentVersionText: String,
        val statusSummary: String
    )

    fun loadThemeMode(context: Context): LauncherThemeMode {
        return LauncherPreferences.readThemeMode(context)
    }

    fun loadThemeColor(context: Context): LauncherThemeColor {
        return LauncherPreferences.readThemeColor(context)
    }

    fun loadSettingsSnapshot(context: Context): SettingsSnapshot {
        val renderSurfaceBackend = LauncherPreferences.readRenderSurfaceBackend(context)
        val rendererSelectionMode = LauncherPreferences.readRendererSelectionMode(context)
        val manualRendererBackend = LauncherPreferences.readManualRendererBackend(context)
        val mobileGluesSettings = LauncherPreferences.readMobileGluesSettings(context)
        val rendererDecision = RendererBackendResolver.resolve(
            context = context,
            requestedSurfaceBackend = renderSurfaceBackend,
            selectionMode = rendererSelectionMode,
            manualBackend = manualRendererBackend
        )
        val heapMaxMb = LauncherPreferences.readJvmHeapMaxMb(context)
        return SettingsSnapshot(
            themeMode = LauncherPreferences.readThemeMode(context),
            themeColor = LauncherPreferences.readThemeColor(context),
            playerName = LauncherPreferences.readPlayerName(context),
            rendering = RenderingSnapshot(
                renderScale = RenderScaleService.readValue(context),
                targetFps = LauncherPreferences.readTargetFps(context),
                virtualResolutionMode = LauncherPreferences.readVirtualResolutionMode(context),
                renderSurfaceBackend = renderSurfaceBackend,
                rendererSelectionMode = rendererSelectionMode,
                manualRendererBackend = manualRendererBackend,
                mobileGluesSettings = mobileGluesSettings,
                rendererDecision = rendererDecision,
                gpuResourceGuardianMode = LauncherPreferences.readGpuResourceGuardianMode(context)
            ),
            jvm = JvmSnapshot(
                heapMaxMb = heapMaxMb,
                heapStartMb = LauncherPreferences.resolveJvmHeapStartMb(heapMaxMb),
                compressedPointersEnabled = LauncherPreferences.isJvmCompressedPointersEnabled(context),
                stringDeduplicationEnabled = LauncherPreferences.isJvmStringDeduplicationEnabled(context)
            ),
            input = InputSnapshot(
                backBehavior = LauncherPreferences.readBackBehavior(context),
                manualDismissBootOverlay = LauncherPreferences.readManualDismissBootOverlay(context),
                showFloatingMouseWindow = LauncherPreferences.readShowFloatingMouseWindow(context),
                touchMouseInteractionMode = LauncherPreferences.readTouchMouseInteractionMode(context),
                touchDoubleClickAsRightClick = LauncherPreferences.readTouchDoubleClickAsRightClick(context),
                builtInSoftKeyboardEnabled =
                    LauncherPreferences.isBuiltInSoftKeyboardEnabled(context),
                hapticFeedbackEnabled = LauncherPreferences.isHapticFeedbackEnabled(context),
                autoSwitchLeftAfterRightClick = LauncherPreferences.readAutoSwitchLeftAfterRightClick(context),
                showModFileName = LauncherPreferences.readShowModFileName(context),
                mobileHudEnabled = LauncherPreferences.readMobileHudEnabled(context),
                compendiumUpgradeTouchFixEnabled =
                    LauncherPreferences.readCompendiumUpgradeTouchFixEnabled(context),
                avoidDisplayCutout = LauncherPreferences.isDisplayCutoutAvoidanceEnabled(context),
                cropScreenBottom = LauncherPreferences.isScreenBottomCropEnabled(context),
                touchscreenEnabled = GameplaySettingsService.readTouchscreenEnabled(context),
                fontScale = GameplaySettingsService.readFontScale(context),
                largerUiEnabled = GameplaySettingsService.readLargerUiEnabled(context)
            ),
            diagnostics = DiagnosticsSnapshot(
                showGamePerformanceOverlay = LauncherPreferences.isGamePerformanceOverlayEnabled(context),
                sustainedPerformanceModeEnabled =
                    LauncherPreferences.isSustainedPerformanceModeEnabled(context),
                systemGameMode = AndroidGameModeSupport.readCurrentMode(context),
                lwjglDebugEnabled = LauncherPreferences.isLwjglDebugEnabled(context),
                preloadAllJreLibrariesEnabled =
                    LauncherPreferences.isPreloadAllJreLibrariesEnabled(context),
                logcatCaptureEnabled = LauncherPreferences.isLogcatCaptureEnabled(context),
                launcherLogcatCaptureEnabled =
                    LauncherPreferences.isLauncherLogcatCaptureEnabled(context),
                jvmLogcatMirrorEnabled = LauncherPreferences.isJvmLogcatMirrorEnabled(context),
                gpuResourceDiagEnabled = LauncherPreferences.isGpuResourceDiagEnabled(context),
                gdxPadCursorDebugEnabled = LauncherPreferences.isGdxPadCursorDebugEnabled(context),
                glBridgeSwapHeartbeatDebugEnabled =
                    LauncherPreferences.isGlBridgeSwapHeartbeatDebugEnabled(context)
            ),
            compatibility = CompatibilitySnapshot(
                globalAtlasFilterCompatEnabled =
                    CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context),
                modManifestRootCompatEnabled =
                    CompatibilitySettings.isModManifestRootCompatEnabled(context),
                runtimeTextureCompatEnabled = CompatibilitySettings.isRuntimeTextureCompatEnabled(context),
                nativeTouchscreenAllowlistCompatEnabled =
                    CompatibilitySettings.isNativeTouchscreenAllowlistCompatEnabled(context),
                texturePressureDownscaleDivisor =
                    CompatibilitySettings.readTexturePressureDownscaleDivisor(context),
                forceLinearMipmapFilterEnabled =
                    CompatibilitySettings.isForceLinearMipmapFilterEnabled(context)
            )
        )
    }

    fun loadUpdateStateSnapshot(context: Context): UpdateStateSnapshot {
        return UpdateStateSnapshot(
            autoCheckUpdatesEnabled = LauncherPreferences.isAutoCheckUpdatesEnabled(context),
            preferredUpdateMirror = UpdateMirrorManager.current(context),
            availableUpdateMirrors = UpdateMirrorManager.selectableSources(),
            currentVersionText = BuildConfig.VERSION_NAME,
            statusSummary = buildUpdateStatusSummary(context)
        )
    }

    fun resetLauncherSettingsToDefaults(context: Context) {
        LauncherPreferences.saveThemeMode(context, LauncherPreferences.DEFAULT_THEME_MODE)
        LauncherPreferences.saveThemeColor(context, LauncherPreferences.DEFAULT_THEME_COLOR)
        LauncherPreferences.savePlayerName(context, LauncherPreferences.DEFAULT_PLAYER_NAME)
        RenderScaleService.reset(context)
        LauncherPreferences.saveTargetFps(context, LauncherPreferences.DEFAULT_TARGET_FPS)
        LauncherPreferences.saveVirtualResolutionMode(
            context,
            LauncherPreferences.DEFAULT_VIRTUAL_RESOLUTION_MODE
        )
        LauncherPreferences.saveRenderSurfaceBackend(
            context,
            LauncherPreferences.DEFAULT_RENDER_SURFACE_BACKEND
        )
        LauncherPreferences.saveRendererSelectionMode(
            context,
            LauncherPreferences.DEFAULT_RENDERER_SELECTION_MODE
        )
        LauncherPreferences.saveManualRendererBackend(
            context,
            LauncherPreferences.DEFAULT_MANUAL_RENDERER_BACKEND
        )
        LauncherPreferences.saveMobileGluesSettings(context, defaultMobileGluesSettings())
        MobileGluesConfigFile.syncFromLauncherPreferences(context)
        LauncherPreferences.saveBackBehavior(context, LauncherPreferences.DEFAULT_BACK_BEHAVIOR)
        LauncherPreferences.saveManualDismissBootOverlay(
            context,
            LauncherPreferences.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
        )
        LauncherPreferences.saveShowFloatingMouseWindow(
            context,
            LauncherPreferences.DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
        )
        LauncherPreferences.saveTouchMouseInteractionMode(
            context,
            LauncherPreferences.DEFAULT_TOUCH_MOUSE_INTERACTION_MODE
        )
        LauncherPreferences.saveTouchDoubleClickAsRightClick(
            context,
            LauncherPreferences.DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK
        )
        LauncherPreferences.setBuiltInSoftKeyboardEnabled(
            context,
            LauncherPreferences.DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED
        )
        LauncherPreferences.setHapticFeedbackEnabled(
            context,
            LauncherPreferences.DEFAULT_HAPTIC_FEEDBACK_ENABLED
        )
        LauncherPreferences.saveAutoSwitchLeftAfterRightClick(
            context,
            LauncherPreferences.DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
        )
        LauncherPreferences.saveShowModFileName(
            context,
            LauncherPreferences.DEFAULT_SHOW_MOD_FILE_NAME
        )
        LauncherPreferences.saveMobileHudEnabled(
            context,
            LauncherPreferences.DEFAULT_MOBILE_HUD_ENABLED
        )
        LauncherPreferences.saveCompendiumUpgradeTouchFixEnabled(
            context,
            LauncherPreferences.DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED
        )
        LauncherPreferences.setDisplayCutoutAvoidanceEnabled(
            context,
            LauncherPreferences.DEFAULT_AVOID_DISPLAY_CUTOUT
        )
        LauncherPreferences.setScreenBottomCropEnabled(
            context,
            LauncherPreferences.DEFAULT_CROP_SCREEN_BOTTOM
        )
        LauncherPreferences.setGamePerformanceOverlayEnabled(
            context,
            LauncherPreferences.DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY
        )
        LauncherPreferences.setSustainedPerformanceModeEnabled(
            context,
            LauncherPreferences.DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED
        )
        LauncherPreferences.setLwjglDebugEnabled(context, LauncherPreferences.DEFAULT_LWJGL_DEBUG)
        LauncherPreferences.setPreloadAllJreLibrariesEnabled(
            context,
            LauncherPreferences.DEFAULT_PRELOAD_ALL_JRE_LIBRARIES
        )
        LauncherPreferences.setLogcatCaptureEnabled(
            context,
            LauncherPreferences.DEFAULT_LOGCAT_CAPTURE_ENABLED
        )
        LauncherPreferences.setLauncherLogcatCaptureEnabled(
            context,
            LauncherPreferences.DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED
        )
        LauncherPreferences.setJvmLogcatMirrorEnabled(
            context,
            LauncherPreferences.DEFAULT_JVM_LOGCAT_MIRROR_ENABLED
        )
        LauncherPreferences.setGpuResourceDiagEnabled(
            context,
            LauncherPreferences.DEFAULT_GPU_RESOURCE_DIAG_ENABLED
        )
        LauncherPreferences.resetGpuResourceGuardianMode(context)
        LauncherPreferences.setGdxPadCursorDebugEnabled(
            context,
            LauncherPreferences.DEFAULT_GDX_PAD_CURSOR_DEBUG
        )
        LauncherPreferences.setGlBridgeSwapHeartbeatDebugEnabled(
            context,
            LauncherPreferences.DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG
        )
        LauncherPreferences.saveJvmHeapMaxMb(context, LauncherPreferences.DEFAULT_JVM_HEAP_MAX_MB)
        LauncherPreferences.setJvmCompressedPointersEnabled(
            context,
            LauncherPreferences.DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED
        )
        LauncherPreferences.setJvmStringDeduplicationEnabled(
            context,
            LauncherPreferences.DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED
        )
        GameplaySettingsService.saveTouchscreenInputMode(
            context,
            GameplaySettingsService.DEFAULT_TOUCHSCREEN_INPUT_MODE
        )
        GameplaySettingsService.saveFontScale(context, GameplaySettingsService.DEFAULT_FONT_SCALE)
        GameplaySettingsService.saveLargerUiEnabled(
            context,
            GameplaySettingsService.DEFAULT_LARGER_UI_ENABLED
        )
        LauncherPreferences.setAutoCheckUpdatesEnabled(
            context,
            LauncherPreferences.DEFAULT_AUTO_CHECK_UPDATES_ENABLED
        )
        LauncherPreferences.savePreferredUpdateMirrorId(
            context,
            LauncherPreferences.DEFAULT_PREFERRED_UPDATE_MIRROR_ID
        )
        LauncherPreferences.setSteamCloudWattAccelerationEnabled(
            context,
            LauncherPreferences.DEFAULT_STEAM_CLOUD_WATT_ACCELERATION_ENABLED
        )
        CompatibilitySettings.resetToDefaults(context)
    }

    private fun buildUpdateStatusSummary(context: Context): String {
        val lastCheckedAtMs = LauncherPreferences.readLastUpdateCheckAtMs(context)
        if (lastCheckedAtMs <= 0L) {
            return context.getString(R.string.update_status_not_checked)
        }

        val lines = mutableListOf<String>()
        lines += context.getString(
            R.string.update_status_last_checked,
            formatUpdateCheckTime(lastCheckedAtMs)
        )

        val remoteTag = LauncherPreferences.readLastKnownRemoteTag(context)
        if (!remoteTag.isNullOrBlank()) {
            lines += context.getString(R.string.update_status_remote_version, remoteTag)
        }

        val metadataSource = resolveUpdateSourceDisplayName(
            LauncherPreferences.readLastSuccessfulMetadataSourceId(context)
        )
        if (metadataSource != null) {
            lines += context.getString(R.string.update_status_metadata_source, metadataSource)
        }

        val errorSummary = LauncherPreferences.readLastUpdateErrorSummary(context)
        if (!errorSummary.isNullOrBlank()) {
            lines += context.getString(
                R.string.update_status_result,
                context.getString(R.string.update_status_result_failed)
            )
            lines += errorSummary
            return lines.joinToString("\n")
        }

        val hasUpdate = !remoteTag.isNullOrBlank() &&
            LauncherUpdateVersioning.isRemoteNewer(BuildConfig.VERSION_NAME, remoteTag)
        lines += context.getString(
            R.string.update_status_result,
            if (hasUpdate) {
                context.getString(R.string.update_status_result_available)
            } else {
                context.getString(R.string.update_status_result_latest)
            }
        )

        if (hasUpdate) {
            val downloadSource = resolveUpdateSourceDisplayName(
                LauncherPreferences.readLastSuccessfulDownloadSourceId(context)
            )
            if (downloadSource != null) {
                lines += context.getString(R.string.update_status_download_source, downloadSource)
            }
        }

        return lines.joinToString("\n")
    }

    private fun defaultMobileGluesSettings(): MobileGluesSettings {
        return MobileGluesSettings(
            anglePolicy = LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_POLICY,
            noErrorPolicy = LauncherPreferences.DEFAULT_MOBILEGLUES_NO_ERROR_POLICY,
            multidrawMode = LauncherPreferences.DEFAULT_MOBILEGLUES_MULTIDRAW_MODE,
            extComputeShaderEnabled = LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
            extTimerQueryEnabled = LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
            extDirectStateAccessEnabled =
                LauncherPreferences.DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
            glslCacheSizePreset = LauncherPreferences.DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET,
            angleDepthClearFixMode = LauncherPreferences.DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
            customGlVersion = LauncherPreferences.DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION,
            fsr1QualityPreset = LauncherPreferences.DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET
        )
    }

    private fun resolveUpdateSourceDisplayName(sourceId: String?): String? {
        return UpdateMirrorManager.displayNameOf(sourceId)
    }

    private fun formatUpdateCheckTime(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestampMs))
    }
}
