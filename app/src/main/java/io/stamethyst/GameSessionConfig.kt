package io.stamethyst

import android.content.Context
import android.content.Intent
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RenderSurfaceBackend

internal data class GameSessionConfig(
    val renderScale: Float,
    val requestedRenderScale: Float,
    val requestedTargetFps: Int,
    val effectiveTargetFps: Int,
    val launchMode: String,
    val backBehavior: BackBehavior,
    val manualDismissBootOverlay: Boolean,
    val forceJvmCrash: Boolean,
    val showFloatingMouseWindow: Boolean,
    val showGamePerformanceOverlay: Boolean,
    val mirrorJvmLogsToLogcat: Boolean,
    val longPressMouseShowsKeyboard: Boolean,
    val autoSwitchLeftAfterRightClick: Boolean,
    val requestedRenderSurfaceBackend: RenderSurfaceBackend,
    val rendererDecision: RendererDecision,
    val avoidDisplayCutout: Boolean,
    val cropScreenBottom: Boolean,
    val sustainedPerformanceModeEnabled: Boolean,
    val systemGameMode: AndroidGameModeSnapshot
) {
    val renderSurfaceBackend: RenderSurfaceBackend
        get() = rendererDecision.effectiveSurfaceBackend

    val useTextureViewSurface: Boolean
        get() = renderSurfaceBackend.usesTextureViewSurface

    companion object {
        fun fromActivityIntent(context: Context, intent: Intent): GameSessionConfig {
            val requestedMode = intent.getStringExtra(StsGameActivity.EXTRA_LAUNCH_MODE)
            val launchMode = if (StsLaunchSpec.isMtsLaunchMode(requestedMode)) {
                StsLaunchSpec.LAUNCH_MODE_MTS
            } else {
                StsLaunchSpec.LAUNCH_MODE_VANILLA
            }
            val requestedRenderSurfaceBackend = LauncherConfig.readRenderSurfaceBackend(context)
            val rendererDecision = RendererBackendResolver.resolve(
                context = context,
                requestedSurfaceBackend = requestedRenderSurfaceBackend,
                selectionMode = LauncherConfig.readRendererSelectionMode(context),
                manualBackend = LauncherConfig.readManualRendererBackend(context)
            )
            val systemGameMode = AndroidGameModeSupport.readCurrentMode(context)
            val requestedRenderScale = LauncherConfig.readRenderScale(context)
            val requestedTargetFps = LauncherConfig.readTargetFps(context)
            val effectiveRenderScale =
                AndroidGameModeSupport.resolveRenderScale(requestedRenderScale, systemGameMode)
            val effectiveTargetFps =
                AndroidGameModeSupport.resolveTargetFps(requestedTargetFps, systemGameMode)

            return GameSessionConfig(
                renderScale = effectiveRenderScale,
                requestedRenderScale = requestedRenderScale,
                requestedTargetFps = requestedTargetFps,
                effectiveTargetFps = effectiveTargetFps,
                launchMode = launchMode,
                backBehavior = parseBackBehavior(intent),
                manualDismissBootOverlay = intent.getBooleanExtra(
                    StsGameActivity.EXTRA_MANUAL_DISMISS_BOOT_OVERLAY,
                    LauncherConfig.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
                ),
                forceJvmCrash = intent.getBooleanExtra(StsGameActivity.EXTRA_FORCE_JVM_CRASH, false),
                showFloatingMouseWindow = LauncherConfig.readShowFloatingMouseWindow(context),
                showGamePerformanceOverlay = LauncherConfig.isGamePerformanceOverlayEnabled(context),
                mirrorJvmLogsToLogcat = LauncherConfig.isJvmLogcatMirrorEnabled(context),
                longPressMouseShowsKeyboard = LauncherConfig.readLongPressMouseShowsKeyboard(context),
                autoSwitchLeftAfterRightClick = LauncherConfig.readAutoSwitchLeftAfterRightClick(context),
                requestedRenderSurfaceBackend = requestedRenderSurfaceBackend,
                rendererDecision = rendererDecision,
                avoidDisplayCutout = LauncherConfig.isDisplayCutoutAvoidanceEnabled(context),
                cropScreenBottom = LauncherConfig.isScreenBottomCropEnabled(context),
                sustainedPerformanceModeEnabled =
                    LauncherConfig.isSustainedPerformanceModeEnabled(context),
                systemGameMode = systemGameMode
            )
        }

        private fun parseBackBehavior(intent: Intent): BackBehavior {
            val parsedBehavior = BackBehavior.fromPersistedValue(
                intent.getStringExtra(StsGameActivity.EXTRA_BACK_BEHAVIOR)
            )
            if (parsedBehavior != null) {
                return parsedBehavior
            }
            if (intent.hasExtra(StsGameActivity.EXTRA_BACK_IMMEDIATE_EXIT)) {
                val immediateExit = intent.getBooleanExtra(
                    StsGameActivity.EXTRA_BACK_IMMEDIATE_EXIT,
                    LauncherConfig.DEFAULT_BACK_IMMEDIATE_EXIT
                )
                return if (immediateExit) {
                    BackBehavior.EXIT_TO_LAUNCHER
                } else {
                    BackBehavior.NONE
                }
            }
            return LauncherConfig.DEFAULT_BACK_BEHAVIOR
        }
    }
}
