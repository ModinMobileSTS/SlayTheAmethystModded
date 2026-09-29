package io.stamethyst.ui.main

import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierFailureCategory
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenEasyTierKickDialogTest {
    @Test
    fun eventKey_onlyAcceptsKickedSessionsAndDeduplicatesStableSnapshots() {
        val disconnected = EasyTierConnectionSnapshot(
            enabled = true,
            canConnect = true,
            status = EasyTierConnectionStatus.DISCONNECTED,
            mode = EasyTierNetworkMode.Room,
            roomId = "room-a",
            lastUpdatedAtMs = 1_000L,
        )
        assertNull(easyTierKickDialogEventKey(disconnected))

        val kicked = disconnected.copy(
            failureCategory = EasyTierFailureCategory.SessionKicked,
            lastSessionState = "kicked",
            lastErrorSummary = "Removed by owner: update the mod.",
        )
        assertEquals(
            easyTierKickDialogEventKey(kicked),
            easyTierKickDialogEventKey(kicked.copy()),
        )
        assertNotEquals(
            easyTierKickDialogEventKey(kicked),
            easyTierKickDialogEventKey(kicked.copy(lastUpdatedAtMs = 2_000L)),
        )
    }

    @Test
    fun kickedSession_usesDialogInsteadOfTroubleshootingCard() {
        assertNull(
            easyTierTroubleshootingMessageResId(
                state = MainScreenViewModel.EasyTierIndicatorState.DISCONNECTED,
                failureCategory = EasyTierFailureCategory.SessionKicked,
                errorSummary = "Removed by owner: update the mod.",
            )
        )
    }

    @Test
    fun shouldQueue_suppressesAcknowledgedEventAcrossRestart() {
        val key = "room-a|1000|Removed by owner."
        // Fresh process: nothing queued in memory yet, but the kick was already dismissed on a
        // previous launch, so the persisted acknowledgement must keep the dialog closed.
        assertFalse(
            shouldQueueEasyTierKickDialog(
                eventKey = key,
                lastQueuedEventKey = "",
                acknowledgedEventKey = key,
            )
        )
        // Same process, already queued.
        assertFalse(
            shouldQueueEasyTierKickDialog(
                eventKey = key,
                lastQueuedEventKey = key,
                acknowledgedEventKey = "",
            )
        )
        // A new kick produces a different key and must still be shown.
        assertTrue(
            shouldQueueEasyTierKickDialog(
                eventKey = "room-a|2000|Removed by owner.",
                lastQueuedEventKey = "",
                acknowledgedEventKey = key,
            )
        )
        assertFalse(
            shouldQueueEasyTierKickDialog(
                eventKey = null,
                lastQueuedEventKey = "",
                acknowledgedEventKey = "",
            )
        )
    }
}
