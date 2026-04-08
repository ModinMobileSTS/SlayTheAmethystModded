package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSurfaceManagerPolicyTest {
    @Test
    fun resolveForegroundResyncDelayMs_debouncesSurfaceViewLayoutAndForegroundReasons() {
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "layout"
            )
        )
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "resume"
            )
        )
        assertEquals(
            16L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "surface_available"
            )
        )
    }

    @Test
    fun resolveForegroundResyncDelayMs_keepsTextureViewImmediate() {
        assertEquals(
            0L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = true,
                reason = "layout"
            )
        )
    }

    @Test
    fun shouldSkipSurfaceViewSteadyStateResync_onlySkipsStableForegroundReasons() {
        assertTrue(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "resume"
            )
        )
        assertTrue(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "focus"
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = true,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "resume"
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "layout"
            )
        )
    }

    @Test
    fun resolvePostBootSurfaceSoftRefreshBlocker_prioritizesImeActivity() {
        assertEquals(
            "ime_active",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = true
            )
        )
    }

    @Test
    fun resolvePostBootSurfaceSoftRefreshBlocker_reportsForegroundAndSurfaceReadiness() {
        assertEquals(
            "not_ready_foreground",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = false,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = false
            )
        )
        assertEquals(
            "surface_unavailable",
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = false,
                softKeyboardSessionActive = false
            )
        )
        assertEquals(
            null,
            RenderSurfaceManager.resolvePostBootSurfaceSoftRefreshBlocker(
                inForeground = true,
                hasWindowFocus = true,
                hasCurrentSurface = true,
                softKeyboardSessionActive = false
            )
        )
    }
}
