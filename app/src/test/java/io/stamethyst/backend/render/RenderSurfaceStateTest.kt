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

        val plan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)

        assertEquals(1920, plan.bufferWidth)
        assertEquals(1080, plan.bufferHeight)
        assertTrue(plan.shouldApplyBufferSize)
        assertTrue(plan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_skipsDuplicateBufferAndWindowUpdates_forSameSizeAndGeneration() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)
        val firstPlan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)
        state.recordBufferApply(firstPlan, applied = true, incrementsHolderResize = true)
        state.recordWindowSizeDispatch(firstPlan, dispatched = true)

        val secondPlan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)

        assertFalse(secondPlan.shouldApplyBufferSize)
        assertFalse(secondPlan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_reappliesBufferOnNewSurfaceGeneration_withoutRedispatchingWindowSize() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)
        val firstPlan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)
        state.recordBufferApply(firstPlan, applied = true, incrementsHolderResize = true)
        state.recordWindowSizeDispatch(firstPlan, dispatched = true)

        state.markSurfaceAvailable(generation = 2, width = 1920, height = 1080)
        val secondPlan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)

        assertTrue(secondPlan.shouldApplyBufferSize)
        assertFalse(secondPlan.shouldDispatchWindowSize)
    }

    @Test
    fun buildApplyPlan_keepsSurfaceAndWindowAtPhysicalSize() {
        val state = RenderSurfaceState()
        state.markSurfaceAvailable(generation = 1, width = 1920, height = 1080)

        val plan = state.buildApplyPlan(viewWidth = 1920, viewHeight = 1080)

        assertEquals(1920, plan.bufferWidth)
        assertEquals(1080, plan.bufferHeight)
        assertEquals(1920, plan.windowWidth)
        assertEquals(1080, plan.windowHeight)
    }
}
