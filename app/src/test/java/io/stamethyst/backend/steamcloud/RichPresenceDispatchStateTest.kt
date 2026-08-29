package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichPresenceDispatchStateTest {
    @Test
    fun uploadsFirstValidSnapshotAndSuppressesOnlySuccessfulDuplicates() {
        val mainMenu = mapOf("status" to "Main menu", "steam_display" to "#Status")
        val state = RichPresenceDispatchState(mainMenu)

        assertTrue(state.shouldUpload)
        state.markUploaded()
        assertFalse(state.shouldUpload)

        state.update(mainMenu)
        assertFalse(state.shouldUpload)
    }

    @Test
    fun keepsLatestSnapshotPendingUntilUploadSucceeds() {
        val mainMenu = mapOf("status" to "Main menu", "steam_display" to "#Status")
        val floorOne = mapOf("status" to "Silent - Floor 1", "steam_display" to "#Status")
        val state = RichPresenceDispatchState(mainMenu)

        state.update(floorOne)
        assertTrue(state.shouldUpload)
        assertTrue(state.pending() === floorOne)

        state.markUploaded()
        assertFalse(state.shouldUpload)
    }
}
