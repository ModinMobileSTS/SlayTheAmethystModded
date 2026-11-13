package io.stamethyst.backend.audio

import org.junit.Assert.*
import org.junit.Test

class AudioOutputRouteBaselineTest {
    @Test fun initialInventoryAndIdenticalCallbacksDoNotCountAsRouteChanges() {
        val baseline = AudioOutputRouteBaseline()
        assertFalse(baseline.update(setOf(1, 2)))
        assertFalse(baseline.update(setOf(2, 1)))
        assertFalse(baseline.update(setOf(1, 2)))
        assertTrue(baseline.update(setOf(1)))
        assertFalse(baseline.update(setOf(1)))
        assertTrue(baseline.update(emptySet()))
        assertTrue(baseline.update(setOf(3)))
    }
}
