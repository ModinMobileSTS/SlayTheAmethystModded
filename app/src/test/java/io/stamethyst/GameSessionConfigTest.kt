package io.stamethyst

import io.stamethyst.backend.render.AndroidGameModeSnapshot
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.RenderSurfaceBackend
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
            launchMode = "vanilla",
            backBehavior = BackBehavior.EXIT_TO_LAUNCHER,
            manualDismissBootOverlay = false,
            forceJvmCrash = false,
            showFloatingMouseWindow = false,
            touchMouseDoubleTapLockEnabled = false,
            showGamePerformanceOverlay = false,
            mirrorJvmLogsToLogcat = false,
            longPressMouseShowsKeyboard = false,
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
            avoidDisplayCutout = false,
            cropScreenBottom = false,
            sustainedPerformanceModeEnabled = false,
            systemGameMode = AndroidGameModeSnapshot(
                rawMode = 0,
                displayNameResId = R.string.settings_game_mode_name_unsupported,
                descriptionResId = R.string.settings_game_mode_desc_unsupported,
                supported = false
            )
        )
    }
}
