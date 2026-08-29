package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSurfaceStateTest {
    @Test
    fun buildApplyPlan_appliesBufferAndDispatchesWindowSize_onFirstSurface() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)

        val plan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )

        assertEquals(960, plan.bufferWidth)
        assertEquals(540, plan.bufferHeight)
        assertTrue(plan.shouldApplyBufferSize)
        assertTrue(plan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_skipsDuplicateBufferAndWindowUpdates_forSameSizeAndGeneration() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)
        val firstPlan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )
        state.recordBufferApply(firstPlan, applied = true, incrementsHolderResize = true)
        state.recordWindowSizeDispatch(firstPlan, dispatched = true)

        val secondPlan = state.buildApplyPlan(
            viewWidth = 1280, viewHeight = 720, virtualWidth = 960, virtualHeight = 540
        )

        assertEquals(1280, secondPlan.physicalWidth)
        assertEquals(720, secondPlan.physicalHeight)
        assertEquals(960, secondPlan.windowWidth)
        assertEquals(540, secondPlan.windowHeight)
        assertFalse(secondPlan.shouldApplyBufferSize)
        assertFalse(secondPlan.shouldDispatchWindowSize)
    }

    @Test
    fun buildForcedApplyPlan_reappliesBufferForSameSizeAndGeneration() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)
        val firstPlan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )
        state.recordBufferApply(firstPlan, applied = true, incrementsHolderResize = true)
        state.recordWindowSizeDispatch(firstPlan, dispatched = true)

        val forcedPlan = state.buildForcedApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )

        assertTrue(forcedPlan.shouldApplyBufferSize)
        assertFalse(forcedPlan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_reappliesBufferOnNewSurfaceGeneration_withoutRedispatchingWindowSize() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)
        val firstPlan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )
        state.recordBufferApply(firstPlan, applied = true, incrementsHolderResize = true)
        state.recordWindowSizeDispatch(firstPlan, dispatched = true)

        state.markSurfaceAvailable(generation = 2, width = 1920, height = 1080)
        val secondPlan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )

        assertTrue(secondPlan.shouldApplyBufferSize)
        assertFalse(secondPlan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_keepsPhysicalSurfaceSeparateFromFixedVirtualGameBuffer() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)

        val plan = state.buildApplyPlan(
            viewWidth = 1920, viewHeight = 1080, virtualWidth = 960, virtualHeight = 540
        )

        assertEquals(1920, plan.physicalWidth)
        assertEquals(1080, plan.physicalHeight)
        assertEquals(960, plan.bufferWidth)
        assertEquals(540, plan.bufferHeight)
        assertEquals(960, plan.windowWidth)
        assertEquals(540, plan.windowHeight)
    }
}
