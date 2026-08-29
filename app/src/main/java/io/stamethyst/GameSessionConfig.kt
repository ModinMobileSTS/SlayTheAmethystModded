package io.stamethyst

import android.content.Context
import android.content.Intent
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.StsLaunchSpec
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.AndroidGameModeSupport
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.resources.ArthasResourcePackService
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchMouseInteractionMode

internal data class GameSessionConfig(
    val renderScale: Float,
    val requestedRenderScale: Float,
    val requestedTargetFps: Int,
    val effectiveTargetFps: Int,
    val launchMode: String,
    val debugMode: Boolean,
    val backBehavior: BackBehavior,
    val manualDismissBootOverlay: Boolean,
    val forceJvmCrash: Boolean,
    val forceRuntimeCrash: Boolean,
    val autoplay: Boolean,
    val autoplaySaveMode: AutoplaySaveMode,
    val autoplayMode: AutoplayMode,
    val autoplaySingleRoomBenchMode: Boolean,
    val autoplaySingleRoomSpecPath: String,
    val autoplayChoiceDelayMs: Long,
    val cardObtainEffectOwnershipCompatEnabled: Boolean,
    val specialKeyInputMode: SpecialKeyInputMode,
    val showFloatingMouseWindow: Boolean,
    val showGamePerformanceOverlay: Boolean,
    val performanceDeepDiagnostics: Boolean,
    val mirrorJvmLogsToLogcat: Boolean,
    val touchMouseInteractionMode: TouchMouseInteractionMode,
    val touchDoubleClickAsRightClick: Boolean,
    val ignoreLongPressRightClickWhilePlayingCard: Boolean,
    val builtInSoftKeyboardEnabled: Boolean,
    val autoSwitchLeftAfterRightClick: Boolean,
    val requestedRenderSurfaceBackend: RenderSurfaceBackend,
    val rendererDecision: RendererDecision,
    val virtualResolutionMode: VirtualResolutionMode,
    val avoidDisplayCutout: Boolean,
    val cropScreenBottom: Boolean,
    val sustainedPerformanceModeEnabled: Boolean,
    val keepScreenOnTimeoutMs: Long?,
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

            val specialKeyInputMode = LauncherConfig.readSpecialKeyInputMode(context)

            return GameSessionConfig(
                renderScale = effectiveRenderScale,
                requestedRenderScale = requestedRenderScale,
                requestedTargetFps = requestedTargetFps,
                effectiveTargetFps = effectiveTargetFps,
                launchMode = launchMode,
                debugMode = intent.getBooleanExtra(StsGameActivity.EXTRA_DEBUG_MODE, false),
                backBehavior = parseBackBehavior(intent),
                manualDismissBootOverlay = intent.getBooleanExtra(
                    StsGameActivity.EXTRA_MANUAL_DISMISS_BOOT_OVERLAY,
                    LauncherConfig.DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
                ),
                forceJvmCrash = intent.getBooleanExtra(StsGameActivity.EXTRA_FORCE_JVM_CRASH, false),
                forceRuntimeCrash = intent.getBooleanExtra(StsGameActivity.EXTRA_FORCE_RUNTIME_CRASH, false),
                autoplay = intent.getBooleanExtra(StsGameActivity.EXTRA_AUTOPLAY, false),
                autoplaySaveMode = AutoplaySaveMode.fromPersistedValue(
                    intent.getStringExtra(StsGameActivity.EXTRA_AUTOPLAY_SAVE_MODE)
                ),
                autoplayMode = AutoplayMode.fromPersistedValue(
                    intent.getStringExtra(StsGameActivity.EXTRA_AUTOPLAY_MODE)
                ),
                autoplaySingleRoomBenchMode = intent.getBooleanExtra(
                    StsGameActivity.EXTRA_AUTOPLAY_SINGLE_ROOM_BENCH_MODE, false
                ),
                autoplaySingleRoomSpecPath =
                    intent.getStringExtra(StsGameActivity.EXTRA_AUTOPLAY_SINGLE_ROOM_SPEC)
                        .orEmpty(),
                autoplayChoiceDelayMs = intent.getLongExtra(
                    StsGameActivity.EXTRA_AUTOPLAY_CHOICE_DELAY_MS,
                    0L
                ).coerceAtLeast(0L),
                cardObtainEffectOwnershipCompatEnabled = intent.getBooleanExtra(
                    StsGameActivity.EXTRA_CARD_OBTAIN_EFFECT_OWNERSHIP_COMPAT_ENABLED,
                    true
                ),
                specialKeyInputMode = specialKeyInputMode,
                showFloatingMouseWindow =
                    specialKeyInputMode == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW,
                showGamePerformanceOverlay = LauncherConfig.isGamePerformanceOverlayEnabled(context),
                performanceDeepDiagnostics = (if (intent.hasExtra(StsGameActivity.EXTRA_PERFORMANCE_DEEP_DIAGNOSTICS)) {
                    intent.getBooleanExtra(StsGameActivity.EXTRA_PERFORMANCE_DEEP_DIAGNOSTICS, false)
                } else {
                    LauncherConfig.isGamePerformanceDeepDiagnosticsEnabled(context)
                }) && ArthasResourcePackService.isInstalled(context),
                mirrorJvmLogsToLogcat = LauncherConfig.isJvmLogcatMirrorEnabled(context),
                touchMouseInteractionMode = LauncherConfig.readTouchMouseInteractionMode(context),
                touchDoubleClickAsRightClick = LauncherConfig.readTouchDoubleClickAsRightClick(context),
                ignoreLongPressRightClickWhilePlayingCard =
                    LauncherConfig.readIgnoreLongPressRightClickWhilePlayingCard(context),
                builtInSoftKeyboardEnabled = LauncherConfig.isBuiltInSoftKeyboardEnabled(context),
                autoSwitchLeftAfterRightClick = LauncherConfig.readAutoSwitchLeftAfterRightClick(context),
                requestedRenderSurfaceBackend = requestedRenderSurfaceBackend,
                rendererDecision = rendererDecision,
                virtualResolutionMode = LauncherConfig.readVirtualResolutionMode(context),
                avoidDisplayCutout = LauncherConfig.isDisplayCutoutAvoidanceEnabled(context),
                cropScreenBottom = LauncherConfig.isScreenBottomCropEnabled(context),
                sustainedPerformanceModeEnabled =
                    LauncherConfig.isSustainedPerformanceModeEnabled(context),
                keepScreenOnTimeoutMs = LauncherConfig.keepScreenOnTimeoutMs(
                    LauncherConfig.readKeepScreenOnTimeoutMinutes(context)
                ),
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
