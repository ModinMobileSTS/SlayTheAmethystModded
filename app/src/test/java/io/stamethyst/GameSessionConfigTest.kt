package io.stamethyst

import io.stamethyst.backend.launch.AutoplaySaveMode
import io.stamethyst.backend.launch.AutoplayMode
import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchMouseInteractionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GameSessionConfigTest {
    @Test
    fun renderSurfaceBackend_matchesResolvedRendererBackendWhenRenderScaleIsReduced() {
        val config = createConfig(
            renderScale = 0.25f,
            effectiveSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW
        )

        assertEquals(RenderSurfaceBackend.SURFACE_VIEW, config.renderSurfaceBackend)
    }

    @Test
    fun useTextureViewSurface_matchesRendererBackendChoiceAtFullScale() {
        val config = createConfig(
            renderScale = 1.0f,
            effectiveSurfaceBackend = RenderSurfaceBackend.TEXTURE_VIEW
        )

        assertEquals(RenderSurfaceBackend.TEXTURE_VIEW, config.renderSurfaceBackend)
        assertEquals(config.renderSurfaceBackend.usesTextureViewSurface, config.useTextureViewSurface)
    }

    private fun createConfig(
        renderScale: Float,
        effectiveSurfaceBackend: RenderSurfaceBackend
    ): GameSessionConfig {
        return GameSessionConfig(
            renderScale = renderScale,
            requestedRenderScale = renderScale,
            requestedTargetFps = 60,
            effectiveTargetFps = 60,
            launchMode = "vanilla",
            debugMode = false,
            backBehavior = BackBehavior.EXIT_TO_LAUNCHER,
            manualDismissBootOverlay = false,
            forceJvmCrash = false,
            forceRuntimeCrash = false,
            autoplay = false,
            autoplaySaveMode = AutoplaySaveMode.DEFAULT,
            autoplayMode = AutoplayMode.DEFAULT,
            autoplaySingleRoomBenchMode = false,
            autoplaySingleRoomSpecPath = "",
            autoplayChoiceDelayMs = 0L,
            cardObtainEffectOwnershipCompatEnabled = true,
            specialKeyInputMode = SpecialKeyInputMode.DISABLED,
            showFloatingMouseWindow = false,
            showGamePerformanceOverlay = false,
            performanceDeepDiagnostics = false,
            mirrorJvmLogsToLogcat = false,
            touchMouseInteractionMode = TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP,
            touchDoubleClickAsRightClick = false,
            ignoreLongPressRightClickWhilePlayingCard = true,
            builtInSoftKeyboardEnabled = true,
            autoSwitchLeftAfterRightClick = false,
            requestedRenderSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            rendererDecision = RendererDecision(
                selectionMode = RendererSelectionMode.AUTO,
                manualBackend = null,
                automaticBackend = RendererBackend.OPENGL_ES_MOBILEGLUES,
                effectiveBackend = RendererBackend.OPENGL_ES_MOBILEGLUES,
                requestedSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
                effectiveSurfaceBackend = effectiveSurfaceBackend,
                availableBackends = emptyList()
            ),
            virtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL,
            avoidDisplayCutout = false,
            cropScreenBottom = false,
            sustainedPerformanceModeEnabled = false,
            keepScreenOnTimeoutMs = null,
            systemGameMode = AndroidGameModeSnapshot(
                rawMode = 0,
                displayNameResId = R.string.settings_game_mode_name_unsupported,
                descriptionResId = R.string.settings_game_mode_desc_unsupported,
                supported = false
            )
        )
    }
}
