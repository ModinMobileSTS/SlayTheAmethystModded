package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPlayOptimizationModeTest {
    @Test
    fun tapCardThenTarget_persistedValue_roundTrips() {
        assertEquals(
            CardPlayOptimizationMode.TAP_CARD_THEN_TARGET,
            CardPlayOptimizationMode.fromPersistedValue("tap_card_then_target")
        )
    }

    @Test
    fun tapCardThenTarget_enablesInspectAndTapPlay() {
        assertTrue(CardPlayOptimizationMode.TAP_CARD_THEN_TARGET.optimizationEnabled)
        assertTrue(CardPlayOptimizationMode.TAP_CARD_THEN_TARGET.tapInspectEnabled)
        assertTrue(CardPlayOptimizationMode.TAP_CARD_THEN_TARGET.tapPlayEnabled)
        assertFalse(CardPlayOptimizationMode.RELEASE_KEEP_OPEN.tapPlayEnabled)
    }
}
