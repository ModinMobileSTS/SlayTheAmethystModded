package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFrameRateVoteStateTest {
    @Test
    fun recreatedSurfaceWithSameJavaObjectAndRateMustReceiveNewVote() {
        val state = SurfaceFrameRateVoteState()
        val holderSurface = Any()
        val appliedGenerations = mutableListOf<Int>()
        fun sync(generation: Int) {
            if (state.shouldApply(holderSurface, generation, 90f)) {
                appliedGenerations.add(generation)
                state.recordApplied(holderSurface, generation, 90f)
            }
        }
        sync(1)
        repeat(10) { sync(1) }
        // SurfaceView reuses holder.surface during the post-boot hide/show refresh.
        sync(2)
        repeat(10) { sync(2) }
        assertEquals(listOf(1, 2), appliedGenerations)
    }

    @Test
    fun destructionClearsVoteEvenIfWrapperAndGenerationAreReused() {
        val state = SurfaceFrameRateVoteState()
        val surface = Any()
        state.recordApplied(surface, 1, 90f)
        state.clear()
        assertTrue(state.shouldApply(surface, 1, 90f))
    }

    @Test
    fun differentSurfaceWithEqualValuesStillNeedsVote() {
        data class EqualSurface(val value: Int)
        val state = SurfaceFrameRateVoteState()
        state.recordApplied(EqualSurface(1), 1, 90f)
        assertTrue(state.shouldApply(EqualSurface(1), 1, 90f))
    }

    @Test
    fun pauseClearsRateAndResumeRestoresItOnce() {
        val state = SurfaceFrameRateVoteState()
        val surface = Any()
        state.recordApplied(surface, 1, 90f)
        assertTrue(state.shouldApply(surface, 1, 0f))
        state.recordApplied(surface, 1, 0f)
        assertFalse(state.shouldApply(surface, 1, 0f))
        assertTrue(state.shouldApply(surface, 1, 90f))
        state.recordApplied(surface, 1, 90f)
        assertFalse(state.shouldApply(surface, 1, 90f))
    }

    @Test
    fun failedRequestDoesNotBecomeCachedSuccess() {
        val state = SurfaceFrameRateVoteState()
        val surface = Any()
        state.recordApplied(surface, 1, 90f)
        assertTrue(state.shouldApply(surface, 2, 90f))
        // The platform call failed, so recordApplied was not called.
        assertTrue(state.shouldApply(surface, 2, 90f))
        state.recordApplied(surface, 2, 90f)
        assertFalse(state.shouldApply(surface, 2, 90f))
    }
}
