package io.stamethyst.backend.steamcloud

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SteamSessionRevokedException

class SteamGamePresenceLoopTest {
    @Test
    fun runningPresenceReadFailureRecoversWithFreshReadAndNewClient() {
        val harness = Harness()
        val recovered = harness.material!!.copy()
        harness.onChange = {
            if (harness.restorations == 1) harness.unreadable = true else harness.active = false
        }
        harness.onWaitRetry = {
            assertTrue(harness.clients.all { it.closed == false })
            if (harness.delays.size == 2) {
                harness.unreadable = false
                harness.material = recovered
            }
        }

        harness.run()

        assertEquals(listOf(30_000L, 60_000L), harness.delays)
        assertEquals(2, harness.restorations)
        assertEquals(2, harness.clients.size)
        assertSame(recovered, harness.clients.last().logons.single())
        assertEquals(listOf("logon", "persona", "game:646570", "details", "close:false"), harness.clients.first().events)
        assertEquals(listOf("logon", "persona", "game:646570", "details", "game:0", "close:true"), harness.clients.last().events)
    }

    @Test
    fun startupReadFailureStartsRecoveryButConfirmedAbsenceDoesNot() {
        val harness = Harness().apply { unreadable = true }
        assertTrue(SteamGamePresenceLoop.shouldStart(harness.readAuth(null)))
        harness.onWaitRetry = { harness.unreadable = false }

        harness.run()

        assertEquals(listOf(30_000L), harness.delays)
        assertEquals(1, harness.restorations)
        harness.material = null
        assertFalse(SteamGamePresenceLoop.shouldStart(harness.readAuth(null)))
    }

    @Test
    fun explicitLogoutStopsWithoutRetryOrAnyFurtherRpc() {
        val harness = Harness()
        harness.onChange = { harness.material = null }

        harness.run()

        assertTrue(harness.delays.isEmpty())
        assertEquals(1, harness.clients.size)
        assertEquals(listOf("logon", "persona", "game:646570", "details", "close:false"), harness.clients.single().events)
    }

    @Test
    fun epochRevocationWinsOverUnreadableEncryptedCredentials() {
        val harness = Harness()
        var readsBeforeRevocation = 0
        harness.onChange = {
            readsBeforeRevocation = harness.encryptedReads
            harness.epoch = "revoked"
            harness.unreadable = true
        }

        harness.run()

        assertTrue(harness.delays.isEmpty())
        assertEquals(readsBeforeRevocation, harness.encryptedReads)
        assertEquals(false, harness.clients.single().closed)
    }

    @Test
    fun logoutAndNewLoginDuringBackoffCannotResurrectOldWorker() {
        val harness = Harness()
        harness.onChange = { harness.unreadable = true }
        harness.onWaitRetry = {
            harness.epoch = "new-account-epoch"
            harness.stateEpoch = harness.epoch
            harness.material = harness.material!!.copy(accountName = "new", steamId64 = "76561198883607239")
            harness.unreadable = false
        }

        harness.run()

        assertEquals(listOf(30_000L), harness.delays)
        assertEquals(1, harness.clients.size)
        assertEquals(1, harness.clients.single().logons.size)
    }

    @Test
    fun accountSwitchWithoutEpochChangeDoesNotReuseOldSession() {
        val harness = Harness()
        harness.onChange = {
            harness.material = harness.material!!.copy(accountName = "new", steamId64 = "76561198883607239", credentialRevision = 2)
        }

        harness.run()

        assertTrue(harness.delays.isEmpty())
        assertEquals(1, harness.clients.size)
        assertEquals(1, harness.clients.single().logons.size)
        assertEquals(false, harness.clients.single().closed)
    }

    @Test
    fun readFailureBetweenClientCreationAndLogonRetriesWithoutUsingEarlierToken() {
        val harness = Harness()
        harness.onCreate = { if (harness.clients.size == 1) harness.unreadable = true }
        harness.onWaitRetry = { harness.unreadable = false }

        harness.run()

        assertEquals(listOf(30_000L), harness.delays)
        assertEquals(listOf("close:false"), harness.clients.first().events)
        assertEquals(1, harness.clients.last().logons.size)
        assertEquals(1, harness.restorations)
    }

    @Test
    fun readFailureBetweenPresenceMessagesStopsSendingAndReplaysOnRecovery() {
        val harness = Harness()
        harness.onPersona = { if (harness.clients.size == 1) harness.unreadable = true }
        harness.onWaitRetry = { harness.unreadable = false }

        harness.run()

        assertEquals(listOf("logon", "persona", "close:false"), harness.clients.first().events)
        assertEquals(listOf("logon", "persona", "game:646570", "details", "game:0", "close:true"), harness.clients.last().events)
        assertEquals(listOf(30_000L), harness.delays)
    }

    @Test
    fun nullableSharedValidatorReadFailureInsideLogonAlsoRecovers() {
        val harness = Harness()
        harness.onLogon = {
            if (harness.clients.size == 1) {
                harness.unreadable = true
                throw SteamSessionRevokedException()
            }
        }
        harness.onWaitRetry = { harness.unreadable = false }

        harness.run()

        assertEquals(listOf(30_000L), harness.delays)
        assertEquals(listOf("logon", "close:false"), harness.clients.first().events)
        assertEquals(1, harness.restorations)
    }

    @Test
    fun sharedValidatorRevocationWithReadableCurrentAccountUsesFreshClient() {
        val harness = Harness()
        harness.onLogon = { if (harness.clients.size == 1) throw SteamSessionRevokedException() }

        harness.run()

        assertEquals(listOf(30_000L), harness.delays)
        assertEquals(2, harness.clients.size)
        assertEquals(1, harness.restorations)
    }

    @Test
    fun sharedValidatorRealRevocationDuringLogonIsTerminalEvenWhenStateIsUnreadable() {
        val harness = Harness()
        harness.onLogon = {
            harness.epoch = "revoked"
            harness.unreadable = true
            throw SteamSessionRevokedException()
        }

        harness.run()

        assertTrue(harness.delays.isEmpty())
        assertEquals(listOf("logon", "close:false"), harness.clients.single().events)
        assertEquals(0, harness.restorations)
    }

    @Test
    fun stoppingDuringSynchronizationClearsAlreadyReportedGameExactlyOnce() {
        val harness = Harness()
        harness.onChange = {
            harness.presence = mapOf("status" to "Floor 2")
            harness.onDetails = {
                harness.active = false
                throw IllegalStateException("Game is no longer active.")
            }
        }

        assertThrows(IllegalStateException::class.java) { harness.run() }

        assertEquals(1, harness.restorations)
        assertClosedOnce(harness, clear = true)
    }

    @Test
    fun interruptionDuringSynchronizationClearsOnceBeforeRestoringInterruptFlag() {
        val harness = Harness()
        harness.onChange = {
            harness.presence = mapOf("status" to "Floor 2")
            harness.onDetails = {
                harness.active = false
                Thread.currentThread().interrupt()
                throw InterruptedException("Service is stopping.")
            }
        }

        try {
            assertThrows(InterruptedException::class.java) { harness.run() }

            assertClosedOnce(harness, clear = true)
            assertEquals(false, harness.clients.single().interruptedAtClose)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun interruptionWhileIdleAlsoClearsExactlyOnce() {
        val harness = Harness()
        harness.onChange = {
            harness.active = false
            Thread.currentThread().interrupt()
            throw InterruptedException("Service is stopping while awaiting a change.")
        }

        try {
            assertThrows(InterruptedException::class.java) { harness.run() }

            assertClosedOnce(harness, clear = true)
            assertEquals(false, harness.clients.single().interruptedAtClose)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun terminalInterruptionDoesNotNeedASecondStopSignalToClear() {
        val harness = Harness()
        harness.onChange = {
            harness.presence = mapOf("status" to "Floor 2")
            harness.onDetails = { throw InterruptedException("Worker interrupted.") }
        }

        assertThrows(InterruptedException::class.java) { harness.run() }

        assertClosedOnce(harness, clear = true)
    }

    @Test
    fun transientAuthReadFailureRacingStopCannotBeUpgradedToCleanupByARecoveredRead() {
        val harness = Harness()
        harness.onChange = {
            harness.onReadMaterial = {
                harness.onReadMaterial = {} // A subsequent read would succeed again.
                harness.active = false
                throw IOException("Transient KeyStore error coincides with stop.")
            }
        }

        assertThrows(IOException::class.java) { harness.run() }

        assertClosedOnce(harness, clear = false)
    }

    @Test
    fun epochRevocationRacingStopDuringAuthReadNeverClears() {
        val harness = Harness()
        harness.onChange = {
            harness.onReadMaterial = {
                harness.epoch = "revoked"
                harness.active = false
            }
        }

        assertThrows(CancellationException::class.java) { harness.run() }

        assertClosedOnce(harness, clear = false)
    }

    @Test
    fun sharedValidatorRevocationRacingStopNeverClearsEvenWithReadableCredentials() {
        val harness = Harness()
        harness.onChange = {
            harness.presence = mapOf("status" to "Floor 2")
            harness.onDetails = {
                harness.active = false
                throw SteamSessionRevokedException()
            }
        }

        assertThrows(SteamSessionRevokedException::class.java) { harness.run() }

        assertClosedOnce(harness, clear = false)
    }

    @Test
    fun stopBetweenIterationsRechecksUnreadableCredentialsBeforeCleanup() {
        val harness = Harness()
        harness.onChange = {
            harness.unreadable = true
            harness.active = false
        }

        harness.run()

        assertClosedOnce(harness, clear = false)
    }

    @Test
    fun stopBetweenIterationsRechecksEpochBeforeCleanup() {
        val harness = Harness()
        harness.onChange = {
            harness.epoch = "revoked"
            harness.active = false
        }

        harness.run()

        assertClosedOnce(harness, clear = false)
    }

    private fun assertClosedOnce(harness: Harness, clear: Boolean) {
        val client = harness.clients.single()
        assertTrue(harness.delays.isEmpty())
        assertTrue("The game must already have been reported", "game:646570" in client.events)
        assertEquals(if (clear) 1 else 0, client.events.count { it == "game:0" })
        assertEquals(1, client.events.count { it.startsWith("close:") })
        assertEquals(clear, client.closed)
    }

    private class Harness {
        private val owner = SteamGamePresenceOwnership().claim()
        var active = true
        var epoch = "epoch"
        var stateEpoch = epoch
        var material: SteamCloudAuthStore.SavedAuthMaterial? = SteamCloudAuthStore.SavedAuthMaterial(
            "account", "token", "", "76561198883607238", 1,
        )
        var unreadable = false
        var encryptedReads = 0
        var restorations = 0
        var nowMs = 0L
        var presence = mapOf("status" to "Main menu")
        val clients = mutableListOf<FakeClient>()
        val delays = mutableListOf<Long>()
        var onChange: () -> Unit = { active = false }
        var onWaitRetry: () -> Unit = {}
        var onCreate: () -> Unit = {}
        var onLogon: () -> Unit = {}
        var onPersona: () -> Unit = {}
        var onDetails: () -> Unit = {}
        var onReadMaterial: () -> Unit = {}

        fun readAuth(expectedEpoch: String?) = SteamCloudAuthStore.readCmAuthMaterialWithStatus(
            expectedEpoch = expectedEpoch,
            readEpoch = { epoch },
            readMaterial = {
                encryptedReads++
                onReadMaterial()
                if (unreadable) throw IOException("KeyStore/file temporarily unavailable")
                stateEpoch to material
            },
        )

        fun run() {
            var checks = 0
            SteamGamePresenceLoop(
                owner = owner,
                readAuth = ::readAuth,
                readPresence = { presence },
                createClient = { FakeClient(this).also { clients += it; onCreate() } },
                shouldContinue = { check(++checks < 50) { "Presence loop did not terminate" }; active },
                nowMs = { nowMs },
                awaitChange = { onChange() },
                awaitRetry = { retry ->
                    val delay = retry.remainingDelayMs(nowMs, recoveryRequested = false)
                    delays += delay
                    onWaitRetry()
                    nowMs += delay
                },
                onRestored = { restorations++ },
                onRetry = { assertTrue(clients.all { it.closed != null }) },
            ).run()
        }
    }

    private class FakeClient(private val harness: Harness) : SteamGamePresenceLoop.Client {
        override var sessionIdentity: Any? = null
        val events = mutableListOf<String>()
        val logons = mutableListOf<SteamCloudAuthStore.SavedAuthMaterial>()
        var closed: Boolean? = null
        var interruptedAtClose: Boolean? = null

        override fun logOn(auth: SteamCloudAuthStore.SavedAuthMaterial) {
            events += "logon"
            logons += auth
            harness.onLogon()
            sessionIdentity = Any()
        }

        override fun setPersonaOnline() { events += "persona"; harness.onPersona() }
        override fun setGamePlayedAppId(appId: Long) { events += "game:$appId" }
        override fun setRichPresence(presence: Map<String, String>) { events += "details"; harness.onDetails() }
        override fun close(clearState: Boolean) {
            interruptedAtClose = Thread.currentThread().isInterrupted
            // Like the synchronous CM facade, a pending interrupt prevents a clearing send.
            if (clearState && sessionIdentity != null && interruptedAtClose == false) setGamePlayedAppId(0L)
            closed = clearState
            events += "close:$clearState"
        }
    }
}
