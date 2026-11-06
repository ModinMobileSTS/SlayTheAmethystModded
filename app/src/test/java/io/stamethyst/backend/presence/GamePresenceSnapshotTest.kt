package io.stamethyst.backend.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GamePresenceSnapshotTest {
    @Test
    fun visibleAiEditorOverridesLauncherPresence() {
        val snapshot = GamePresenceSnapshot(GamePresenceState.Launcher, "")

        assertEquals(GamePresenceState.AiEditor, snapshot.forReporting(aiEditorVisible = true).state)
        assertEquals(GamePresenceState.Launcher, snapshot.state)
    }

    @Test
    fun visibleAiEditorOverridesBackgroundGameWithoutMutatingItsMarker() {
        val snapshot = GamePresenceSnapshot(GamePresenceState.Game, "mts")
        val reported = snapshot.forReporting(aiEditorVisible = true)

        assertEquals(GamePresenceState.AiEditor, reported.state)
        assertEquals("mts", reported.launchMode)
        assertEquals(GamePresenceState.Game, snapshot.state)
    }

    @Test
    fun leavingOrPausingEditorRestoresCurrentUnderlyingPresence() {
        for (state in listOf(GamePresenceState.Launcher, GamePresenceState.Game)) {
            val snapshot = GamePresenceSnapshot(state, "normal")

            assertEquals(GamePresenceState.AiEditor, snapshot.forReporting(aiEditorVisible = true).state)
            assertSame(snapshot, snapshot.forReporting(aiEditorVisible = false))
        }
    }
}
