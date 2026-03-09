package io.stamethyst

import android.content.Context
import android.content.Intent
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RenderSurfaceBackend

internal data class GameSessionConfig(
    val renderScale: Float,
    val targetFps: Int,
    val launchMode: String,
    val backBehavior: BackBehavior,
    val manualDismissBootOverlay: Boolean,
    val forceJvmCrash: Boolean,
    val showFloatingMouseWindow: Boolean,
    val showGamePerformanceOverlay: Boolean,
    val mirrorJvmLogsToLogcat: Boolean,
    val longPressMouseShowsKeyboard: Boolean,
    val autoSwitchLeftAfterRightClick: Boolean,
    val renderSurfaceBackend: RenderSurfaceBackend
) {
    val useTextureViewSurface: Boolean
        get() = renderSurfaceBackend.usesTextureViewSurface

    companion object {
        fun fromActivityIntent(context: Context, intent: Intent): GameSessionConfig {
            val requestedMode = intent.getStringExtra(StsGameActivity.EXTRA_LAUNCH_MODE)
            val launchMode = if (requestedMode == StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD) {
                StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
            } else {
                StsLaunchSpec.LAUNCH_MODE_VANILLA
            }

            return GameSessionConfig(
                renderScale = LauncherConfig.readRenderScale(context),
                targetFps = LauncherConfig.normalizeTargetFps(
                    intent.getIntExtra(
                        StsGameActivity.EXTRA_TARGET_FPS,
                        LauncherConfig.DEFAULT_TARGET_FPS
                    )
                ),
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
                renderSurfaceBackend = LauncherConfig.readRenderSurfaceBackend(context)
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
