package io.stamethyst.backend.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccelerationStrategyTest {
    @Test
    fun defaultPrefersTheBundledRmbgameHop() {
        assertEquals(AccelerationStrategy.RMBGAME_FIRST, AccelerationStrategy.DEFAULT)
    }

    @Test
    fun persistedValuesRoundTrip() {
        AccelerationStrategy.entries.forEach { strategy ->
            assertEquals(
                strategy,
                AccelerationStrategy.fromPersistedValue(strategy.persistedValue),
            )
        }
    }

    @Test
    fun unknownOrMissingPersistedValueFallsBackToNull() {
        assertNull(AccelerationStrategy.fromPersistedValue(null))
        assertNull(AccelerationStrategy.fromPersistedValue(""))
        assertNull(AccelerationStrategy.fromPersistedValue("  "))
        assertNull(AccelerationStrategy.fromPersistedValue("definitely-not-a-strategy"))
    }

    @Test
    fun persistedValuesAreStableForExistingInstalls() {
        assertEquals("rmbgame_first", AccelerationStrategy.RMBGAME_FIRST.persistedValue)
        assertEquals("best_path", AccelerationStrategy.BEST_PATH.persistedValue)
    }
}
