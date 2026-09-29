package io.stamethyst.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiBusyOperationTest {
    @Test
    fun locksInteractionRequiresBusyAndBlockingOperation() {
        assertTrue(UiBusyOperation.STEAM_CLOUD_SYNC.locksInteraction(busy = true))
        assertFalse(UiBusyOperation.STEAM_CLOUD_SYNC.locksInteraction(busy = false))
        assertFalse(UiBusyOperation.OTHER_BUSY.locksInteraction(busy = true))
        assertFalse(UiBusyOperation.NONE.locksInteraction(busy = true))
    }
}
