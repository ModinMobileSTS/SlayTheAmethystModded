package io.stamethyst

import android.content.pm.ActivityInfo
import android.view.WindowManager
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.render.VirtualResolutionPolicy
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
        assertEquals(
            48L,
            RenderSurfaceManager.resolveForegroundResyncDelayMs(
                useTextureViewSurface = false,
                reason = "window_configuration"
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
        assertFalse(
            RenderSurfaceManager.shouldSkipSurfaceViewSteadyStateResync(
                useTextureViewSurface = false,
                pendingSurfaceReadyCallback = false,
                bridgeSurfaceReady = true,
                hasCurrentSurface = true,
                reason = "window_configuration"
            )
        )
    }

    @Test
    fun surfaceViewFixedSizePolicy_doesNotSuppressWhenSurfaceFrameIsStale() {
        assertFalse(
            SurfaceViewHost.shouldSuppressFixedSize(
                requestedWidth = 2400,
                requestedHeight = 1080,
                frameWidth = 1200,
                frameHeight = 540
            )
        )
        assertTrue(
            SurfaceViewHost.shouldSuppressFixedSize(
                requestedWidth = 2400,
                requestedHeight = 1080,
                frameWidth = 2400,
                frameHeight = 1080
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_placesCropOppositeDisplayCutoutSide() {
        assertEquals(
            RenderViewportInsets(right = 96),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(left = 96),
                fallbackInset = 24
            )
        )
        assertEquals(
            RenderViewportInsets(left = 96),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(right = 96),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_usesGestureSideWhenAvailable() {
        assertEquals(
            RenderViewportInsets(left = 80),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(left = 80),
                cameraInsets = RenderViewportInsets(top = 40),
                fallbackInset = 24
            )
        )
        assertEquals(
            RenderViewportInsets(right = 24),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(),
                cameraInsets = RenderViewportInsets(),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_keepsRightCutoutCropOnLeftEvenWithRightGestureInset() {
        assertEquals(
            RenderViewportInsets(left = 80),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(right = 48),
                cameraInsets = RenderViewportInsets(right = 80),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveScreenBottomCropInsets_usesReliableInsetsOnly() {
        assertEquals(
            RenderViewportInsets(right = 48),
            RenderSurfaceManager.resolveScreenBottomCropInsets(
                cropScreenBottom = true,
                gestureInsets = RenderViewportInsets(right = 48),
                cameraInsets = RenderViewportInsets(),
                fallbackInset = 24
            )
        )
    }

    @Test
    fun resolveViewportLayout_keepsHalfScreenWindowUsable() {
        assertEquals(
            RenderViewportLayout(
                width = 1200,
                height = 675,
                leftMargin = 0,
                topMargin = 202,
                rightMargin = 0,
                bottomMargin = 203
            ),
            RenderSurfaceManager.resolveViewportLayout(
                rootWidth = 1200,
                rootHeight = 1080,
                cropInsets = RenderViewportInsets(),
                virtualResolutionMode = VirtualResolutionMode.RATIO_16_9
            )
        )
    }

    @Test
    fun resolveFixedVirtualViewportLayout_letterboxesFreeformWindow() {
        assertEquals(
            RenderViewportLayout(
                width = 1200,
                height = 675,
                leftMargin = 0,
                topMargin = 202,
                rightMargin = 0,
                bottomMargin = 203
            ),
            RenderSurfaceManager.resolveFixedVirtualViewportLayout(
                rootWidth = 1200,
                rootHeight = 1080,
                cropInsets = RenderViewportInsets(),
                virtualWidth = 960,
                virtualHeight = 540
            )
        )
    }

    @Test
    fun resolveViewportCanvasSize_usesSingleSideCroppedAreaAsFullscreenCanvas() {
        val canvas = RenderSurfaceManager.resolveViewportCanvasSize(
            rootWidth = 2400,
            rootHeight = 1080,
            cropInsets = RenderViewportInsets(left = 96)
        )

        assertEquals(2304, canvas.width)
        assertEquals(1080, canvas.height)

        val virtualResolution = VirtualResolutionPolicy.resolve(
            physicalWidth = canvas.width,
            physicalHeight = canvas.height,
            renderScale = 1.0f,
            mode = VirtualResolutionMode.FULLSCREEN_FILL
        )
        assertEquals(
            RenderViewportLayout(
                width = 2304,
                height = 1080,
                leftMargin = 96,
                topMargin = 0,
                rightMargin = 0,
                bottomMargin = 0
            ),
            RenderSurfaceManager.resolveFixedVirtualViewportLayout(
                rootWidth = 2400,
                rootHeight = 1080,
                cropInsets = RenderViewportInsets(left = 96),
                virtualWidth = virtualResolution.width,
                virtualHeight = virtualResolution.height
            )
        )
    }

    @Test
    fun resolveViewportCanvasSize_usesBothSideCroppedAreaAsFullscreenCanvas() {
        val cropInsets = RenderViewportInsets(left = 100, right = 120)
        val canvas = RenderSurfaceManager.resolveViewportCanvasSize(
            rootWidth = 2400,
            rootHeight = 1080,
            cropInsets = cropInsets
        )

        assertEquals(2180, canvas.width)
        assertEquals(1080, canvas.height)

        val virtualResolution = VirtualResolutionPolicy.resolve(
            physicalWidth = canvas.width,
            physicalHeight = canvas.height,
            renderScale = 1.0f,
            mode = VirtualResolutionMode.FULLSCREEN_FILL
        )
        assertEquals(
            RenderViewportLayout(
                width = 2180,
                height = 1080,
                leftMargin = 100,
                topMargin = 0,
                rightMargin = 120,
                bottomMargin = 0
            ),
            RenderSurfaceManager.resolveFixedVirtualViewportLayout(
                rootWidth = 2400,
                rootHeight = 1080,
                cropInsets = cropInsets,
                virtualWidth = virtualResolution.width,
                virtualHeight = virtualResolution.height
            )
        )
    }

    @Test
    fun shouldDeferPortraitLandscapeTransition_onlyDefersLockedLandscapeWindow() {
        assertTrue(
            RenderSurfaceManager.shouldDeferPortraitLandscapeTransition(
                rootWidth = 1080,
                rootHeight = 2400,
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                multiWindow = false
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldDeferPortraitLandscapeTransition(
                rootWidth = 1080,
                rootHeight = 2400,
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                multiWindow = true
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldDeferPortraitLandscapeTransition(
                rootWidth = 2400,
                rootHeight = 1080,
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                multiWindow = false
            )
        )
    }

    @Test
    fun resolveViewportLayout_keepsLeftAndRightCropsSeparate() {
        assertEquals(
            RenderViewportLayout(
                width = 2180,
                height = 1080,
                leftMargin = 100,
                topMargin = 0,
                rightMargin = 120,
                bottomMargin = 0
            ),
            RenderSurfaceManager.resolveViewportLayout(
                rootWidth = 2400,
                rootHeight = 1080,
                cropInsets = RenderViewportInsets(left = 100, right = 120),
                virtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL
            )
        )
    }

    @Test
    fun resolveDisplayCutoutMode_keepsBootOverlayFullScreen() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = true,
                bootOverlayActive = true
            )
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = false,
                bootOverlayActive = true
            )
        )
    }

    @Test
    fun resolveDisplayCutoutMode_restoresGameCutoutAvoidanceAfterBootOverlay() {
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = true,
                bootOverlayActive = false
            )
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            RenderSurfaceManager.resolveDisplayCutoutMode(
                avoidDisplayCutout = false,
                bootOverlayActive = false
            )
        )
    }

    @Test
    fun shouldApplyManualDisplayCutoutAvoidance_followsPreference() {
        assertTrue(
            RenderSurfaceManager.shouldApplyManualDisplayCutoutAvoidance(
                avoidDisplayCutout = true
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldApplyManualDisplayCutoutAvoidance(
                avoidDisplayCutout = false
            )
        )
    }

    @Test
    fun shouldUseCachedWindowInsets_rejectsInsetsFromPreviousRotation() {
        assertTrue(
            RenderSurfaceManager.shouldUseCachedWindowInsets(
                cachedRotation = 0,
                currentRotation = 0
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldUseCachedWindowInsets(
                cachedRotation = 0,
                currentRotation = 1
            )
        )
        assertFalse(
            RenderSurfaceManager.shouldUseCachedWindowInsets(
                cachedRotation = null,
                currentRotation = 1
            )
        )
    }

    @Test
    fun mergeViewportInsets_preservesIndependentGameCrops() {
        assertEquals(
            RenderViewportInsets(left = 72, top = 12, right = 96, bottom = 0),
            RenderSurfaceManager.mergeViewportInsets(
                RenderViewportInsets(right = 96),
                RenderViewportInsets(left = 72, top = 12)
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
