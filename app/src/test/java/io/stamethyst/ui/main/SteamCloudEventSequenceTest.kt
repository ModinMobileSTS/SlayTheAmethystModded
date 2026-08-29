package io.stamethyst.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudEventSequenceTest {
    @Test
    fun shouldAcceptSteamCloudEventSequence_rejectsDuplicateAndOutOfOrderEvents() {
        assertTrue(shouldAcceptSteamCloudEventSequence(lastProcessedSequence = 7L, eventSequence = 8L))
        assertFalse(shouldAcceptSteamCloudEventSequence(lastProcessedSequence = 7L, eventSequence = 7L))
        assertFalse(shouldAcceptSteamCloudEventSequence(lastProcessedSequence = 7L, eventSequence = 6L))
    }

    @Test
    fun shouldAcceptSteamCloudEventSequence_acceptsLegacyEventsWithoutASequence() {
        assertTrue(shouldAcceptSteamCloudEventSequence(lastProcessedSequence = 7L, eventSequence = null))
    }
}
