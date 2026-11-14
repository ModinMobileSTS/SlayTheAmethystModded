package io.stamethyst.backend.audio

import org.junit.Assert.*
import org.junit.Test

class AudioOutputRouteBaselineTest {
    @Test fun cancelledDebounceDoesNotLoseChangeWhenResumeReportsIdenticalInventory() {
        val baseline = AudioOutputRouteBaseline()
        baseline.update(setOf(1, 2))
        assertFalse(baseline.hasPendingChange)
        baseline.update(setOf(1))
        assertTrue(baseline.hasPendingChange)
        // Pause cancels delivery; resume/register reports the same already-recorded IDs.
        assertFalse(baseline.update(setOf(1)))
        assertTrue(baseline.consumePendingChange())
        assertFalse(baseline.hasPendingChange)
        assertFalse(baseline.consumePendingChange())
    }

    @Test fun noisyAndMultipleDeviceEventsCoalesceUntilDelivered() {
        val baseline = AudioOutputRouteBaseline()
        baseline.update(setOf(1))
        baseline.markChanged()
        baseline.update(setOf(1, 2))
        baseline.update(setOf(1))
        assertTrue(baseline.consumePendingChange())
        assertFalse(baseline.consumePendingChange())
    }

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
