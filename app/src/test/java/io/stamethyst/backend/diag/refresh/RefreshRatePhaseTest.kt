package io.stamethyst.backend.diag.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRatePhaseTest {
    @Test fun originalFivePhaseKeysAndSeamlessComparisonArePreserved() {
        assertEquals(listOf("baseline", "highest_mode", "window_120", "surface_view_120", "texture_view_window_120"),
            RefreshRatePhase.entries.take(5).map { it.key })
        assertEquals(RefreshRateRequestKind.SURFACE_SEAMLESS, RefreshRatePhase.SURFACE.requestKind)
        assertEquals(RefreshRateRequestKind.DEFAULT, RefreshRatePhase.BASELINE.requestKind)
    }

    @Test fun composeBaselineAndHighUseTheSameRendererButDifferentVotePlans() {
        assertEquals(RefreshRateProbeKind.COMPOSE, RefreshRatePhase.COMPOSE_BASELINE.probeKind)
        assertEquals(RefreshRateProbeKind.COMPOSE, RefreshRatePhase.COMPOSE_HIGH.probeKind)
        assertEquals(RefreshRateRequestKind.DEFAULT, RefreshRatePhase.COMPOSE_BASELINE.requestKind)
        assertEquals(RefreshRateRequestKind.COMPOSE_HIGH, RefreshRatePhase.COMPOSE_HIGH.requestKind)
        assertTrue(RefreshRatePhase.COMPOSE_BASELINE.usesHighestRateReference)
        assertTrue(RefreshRatePhase.COMPOSE_HIGH.usesHighestRateReference)
    }

    @Test fun viewOnlyPhaseDoesNotUseAWindowOrSurfaceRequestPlan() {
        assertEquals(RefreshRateProbeKind.VIEW, RefreshRatePhase.VIEW_HIGH.probeKind)
        assertEquals(RefreshRateRequestKind.VIEW_HIGH, RefreshRatePhase.VIEW_HIGH.requestKind)
        assertTrue(RefreshRatePhase.VIEW_HIGH.usesHighestRateReference)
    }

    @Test fun bothProductionBackendsHaveTheirOwnProductionRequestStage() {
        assertEquals(RefreshRateRequestKind.PRODUCTION, RefreshRatePhase.SURFACE_PRODUCTION.requestKind)
        assertEquals(RefreshRateRequestKind.PRODUCTION, RefreshRatePhase.TEXTURE_PRODUCTION.requestKind)
        assertEquals(RefreshRateProbeKind.SURFACE, RefreshRatePhase.SURFACE_PRODUCTION.probeKind)
        assertEquals(RefreshRateProbeKind.TEXTURE, RefreshRatePhase.TEXTURE_PRODUCTION.probeKind)
        assertFalse(RefreshRatePhase.WINDOW.usesHighestRateReference)
        assertEquals(10, RefreshRatePhase.entries.size)
        assertEquals(10, RefreshRatePhase.entries.map { it.key }.distinct().size)
    }
}
