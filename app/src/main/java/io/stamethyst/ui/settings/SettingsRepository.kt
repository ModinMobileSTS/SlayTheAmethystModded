package io.stamethyst.ui.settings

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.AndroidGameModeSupport
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
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
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
        val rendererDecision: RendererDecision
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
        val touchMouseNewInteraction: Boolean,
        val longPressMouseShowsKeyboard: Boolean,
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
                rendererDecision = rendererDecision
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
                touchMouseNewInteraction = LauncherPreferences.readTouchMouseNewInteraction(context),
                longPressMouseShowsKeyboard = LauncherPreferences.readLongPressMouseShowsKeyboard(context),
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

    private fun resolveUpdateSourceDisplayName(sourceId: String?): String? {
        return UpdateMirrorManager.displayNameOf(sourceId)
    }

    private fun formatUpdateCheckTime(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestampMs))
    }
}
