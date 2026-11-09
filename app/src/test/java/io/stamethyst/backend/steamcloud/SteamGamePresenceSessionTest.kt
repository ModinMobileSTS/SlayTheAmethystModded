package io.stamethyst.backend.steamcloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamGamePresenceSessionTest {
    private val mainMenu = mapOf("status" to "Main menu", "steam_display" to "#Status")
    private val floorOne = mapOf("status" to "Silent - Floor 1", "steam_display" to "#Status")

    @Test
    fun firstSessionSendsLogonPersonaAppIdAndDetailsInOrder() {
        val client = FakeClient()
        assertTrue(SteamGamePresenceSession(client).synchronize(mainMenu))

        assertEquals(listOf("logon", "persona", "game:646570", "details:Main menu"), client.events)
    }

    @Test
    fun healthyResumeDoesNotRepeatLogonOrUnchangedDetails() {
        val client = FakeClient()
        val session = SteamGamePresenceSession(client)
        session.synchronize(mainMenu)
        client.events.clear()

        repeat(3) { assertFalse(session.synchronize(mainMenu)) }

        assertTrue(client.events.isEmpty())
    }

    @Test
    fun disconnectRestoresUnchangedDetailsWithoutWaitingForGameStateChange() {
        val client = FakeClient()
        val session = SteamGamePresenceSession(client)
        session.synchronize(mainMenu)
        client.events.clear()
        client.sessionIdentity = null

        assertTrue(session.synchronize(mainMenu))

        assertEquals(listOf("logon", "persona", "game:646570", "details:Main menu"), client.events)
    }

    @Test
    fun sharedSessionReconnectedElsewhereStillReplaysAppIdAndLatestDetails() {
        val client = FakeClient()
        val session = SteamGamePresenceSession(client)
        session.synchronize(mainMenu)
        client.events.clear()
        client.sessionIdentity = Any()

        assertTrue(session.synchronize(floorOne))

        assertEquals(listOf("persona", "game:646570", "details:Silent - Floor 1"), client.events)
    }

    @Test
    fun newlyBorrowedHandleMustLogOnEvenWhenSharedTransportIsAlreadyActive() {
        val client = FakeClient().apply { sessionIdentity = Any() }

        SteamGamePresenceSession(client).synchronize(mainMenu)

        assertEquals("logon", client.events.first())
    }

    @Test
    fun failedLogonCanRecoverAndUseLatestSnapshot() {
        val client = FakeClient().apply { failLogon = true }
        val session = SteamGamePresenceSession(client)
        assertThrows(IOException::class.java) { session.synchronize(mainMenu) }
        client.events.clear()
        client.failLogon = false

        assertTrue(session.synchronize(floorOne))

        assertEquals(listOf("logon", "persona", "game:646570", "details:Silent - Floor 1"), client.events)
    }

    @Test
    fun changedDetailsDoNotRepeatPersonaOrAppId() {
        val client = FakeClient()
        val session = SteamGamePresenceSession(client)
        session.synchronize(mainMenu)
        client.events.clear()

        assertFalse(session.synchronize(floorOne))

        assertEquals(listOf("details:Silent - Floor 1"), client.events)
    }

    @Test
    fun failedDetailsRemainPendingWithoutRepeatingPersonaOrAppId() {
        val client = FakeClient().apply { failDetails = true }
        val session = SteamGamePresenceSession(client)
        assertThrows(IOException::class.java) { session.synchronize(mainMenu) }
        client.events.clear()
        client.failDetails = false

        session.synchronize(mainMenu)

        assertEquals(listOf("details:Main menu"), client.events)
    }

    @Test
    fun failedAppIdMustBeReplayedBeforeUploadingAnyDetails() {
        val client = FakeClient().apply { failAppId = true }
        val session = SteamGamePresenceSession(client)
        assertThrows(IOException::class.java) { session.synchronize(mainMenu) }
        assertEquals(listOf("logon", "persona", "game:646570"), client.events)
        client.events.clear()
        client.failAppId = false

        assertTrue(session.synchronize(floorOne))

        assertEquals(listOf("persona", "game:646570", "details:Silent - Floor 1"), client.events)
    }

    @Test
    fun disconnectDuringUploadIsNotReportedAsSuccessfulRecovery() {
        val client = FakeClient().apply { disconnectDuringUpload = true }

        assertThrows(IOException::class.java) { SteamGamePresenceSession(client).synchronize(mainMenu) }
    }

    private class FakeClient : SteamGamePresenceSession.Client {
        override var sessionIdentity: Any? = null
        val events = mutableListOf<String>()
        var failLogon = false
        var failAppId = false
        var failDetails = false
        var disconnectDuringUpload = false

        override fun logOn() {
            events += "logon"
            if (failLogon) throw IOException("Network unavailable")
            if (sessionIdentity == null) sessionIdentity = Any()
        }

        override fun setPersonaOnline() { events += "persona" }
        override fun setGamePlayedAppId(appId: Long) {
            events += "game:$appId"
            if (failAppId) throw IOException("AppID upload failed")
        }
        override fun setRichPresence(presence: Map<String, String>) {
            events += "details:${presence["status"]}"
            if (failDetails) throw IOException("Upload failed")
            if (disconnectDuringUpload) sessionIdentity = null
        }
    }
}
