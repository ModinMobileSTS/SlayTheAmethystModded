package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameResolutionDraftTest {
    @Test fun hdAndPerformanceUseTheSameDisplayRegion() {
        val hd = GameResolutionPreset.HD.resolve(2400, 1080)!!
        val fast = GameResolutionPreset.PERFORMANCE.resolve(2400, 1080)!!
        assertEquals(hd.region, fast.region)
        val area = hd.region.toPixels(2400, 1080)
        assertEquals(DisplayPixelRect(240, 0, 2160, 1080), area)
        assertEquals(VirtualResolution(1280, 720, 720f / 1080),
            VirtualResolutionPolicy.resolve(area.width, area.height, fast.scale))
    }

    @Test fun presetsFitSmallerScreensAndRejectUnsupportedCrops() {
        val hd = GameResolutionPreset.HD.resolve(1600, 900)!!
        assertEquals(DisplayPixelRect(0, 0, 1600, 900), hd.region.toPixels(1600, 900))
        assertNull(GameResolutionPreset.CLASSIC.resolve(3200, 1080))
    }

    @Test fun hdAndPerformanceFillTheHeightOnQhdPhonesButCapInternalRendering() {
        val hd = GameResolutionPreset.HD.resolve(3200, 1440)!!
        val fast = GameResolutionPreset.PERFORMANCE.resolve(3200, 1440)!!
        val area = hd.region.toPixels(3200, 1440)
        assertEquals(DisplayPixelRect(320, 0, 2880, 1440), area)
        assertEquals(area, fast.region.toPixels(3200, 1440))
        assertEquals(VirtualResolution(1920, 1080, .75f),
            VirtualResolutionPolicy.resolve(area.width, area.height, hd.scale))
        assertEquals(VirtualResolution(1280, 720, .5f),
            VirtualResolutionPolicy.resolve(area.width, area.height, fast.scale))
    }

    @Test fun sixteenByNinePresetsFitInsideNarrowerScreens() {
        val hd = GameResolutionPreset.HD.resolve(1600, 1080)!!
        val fast = GameResolutionPreset.PERFORMANCE.resolve(1600, 1080)!!
        val area = hd.region.toPixels(1600, 1080)
        assertEquals(DisplayPixelRect(0, 90, 1600, 990), area)
        assertEquals(area, fast.region.toPixels(1600, 1080))
        assertEquals(1f, hd.scale)
        assertEquals(VirtualResolution(1280, 720, .8f),
            VirtualResolutionPolicy.resolve(area.width, area.height, fast.scale))
    }

    @Test fun roundedPanelWidthDoesNotLeaveAOnePixelVerticalGap() {
        val hd = GameResolutionPreset.HD.resolve(1920, 1081)!!
        assertEquals(1080, hd.region.toPixels(1920, 1081).height)
        val fast = GameResolutionPreset.PERFORMANCE.resolve(1921, 1081)!!
        // At 1921 px wide the nearest integer height is 1081, not 1080.
        assertEquals(1081, fast.region.toPixels(1921, 1081).height)
    }

    @Test fun openingLegacy720pKeepsItsVisibleAreaAndRenderDimensions() {
        val draft = GameResolutionDraft.fromSaved(
            GameDisplayRegion.FULLSCREEN, 1f, VirtualResolutionMode.RESOLUTION_720P, 2400, 1080,
        )
        val area = draft.region.toPixels(2400, 1080)
        assertEquals(DisplayPixelRect(240, 0, 2160, 1080), area)
        assertEquals(VirtualResolution(1280, 720, 720f / 1080),
            VirtualResolutionPolicy.resolve(area.width, area.height, draft.scale))
    }

    @Test fun resizingMaintainsUniformScaleAndHonorsTheMinimum() {
        val draft = GameResolutionDraft(GameDisplayRegion.FULLSCREEN, .5f)
        val smaller = GameDisplayRegion.FULLSCREEN.withPixelSize(2400, 1080, 1200, 540)!!
        assertEquals(450f / 540, draft.withRegion(smaller, 2400, 1080).scale, .0001f)
    }

    @Test fun uniformScaleRoundingDoesNotAddBlackBarsButRealAspectChangeDoes() {
        val area = DisplayPixelRect(11, 30, 1934, 1111)
        val render = VirtualResolutionPolicy.resolve(area.width, area.height, .73f)
        assertEquals(area, VirtualResolutionPolicy.fitViewport(area, render.width, render.height, ignorePixelRounding = true))
        assertEquals(DisplayPixelRect(11, 90, 1934, 1051),
            VirtualResolutionPolicy.fitViewport(area, 1000, 500, ignorePixelRounding = true))
    }
}
