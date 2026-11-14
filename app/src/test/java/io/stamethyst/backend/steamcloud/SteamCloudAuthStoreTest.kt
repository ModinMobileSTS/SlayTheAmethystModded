package io.stamethyst.backend.steamcloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudAuthStoreTest {
    @Test
    fun cmStatusDistinguishesReadFailureFromConfirmedAbsence() {
        val absent = SteamCloudAuthStore.readCmAuthMaterialWithStatus(null, { "epoch" }, { "epoch" to null })
        val unreadable = SteamCloudAuthStore.readCmAuthMaterialWithStatus(null, { "epoch" }, { throw IOException() })

        assertEquals(null, absent.material)
        assertFalse(absent.readFailed)
        assertEquals(null, unreadable.material)
        assertTrue(unreadable.readFailed)
        assertEquals("epoch", unreadable.accountEpoch)
    }

    @Test
    fun cmStatusRejectsStateFromRevokedEpoch() {
        val auth = SteamCloudAuthStore.SavedAuthMaterial("account", "token", "", "76561198883607238", 1)
        val read = SteamCloudAuthStore.readCmAuthMaterialWithStatus(null, { "new" }, { "old" to auth })

        assertFalse(read.readFailed)
        assertEquals(null, read.material)
    }

    @Test
    fun cmStatusRejectsLogoutRacingSuccessfulEncryptedRead() {
        val auth = SteamCloudAuthStore.SavedAuthMaterial("account", "token", "", "76561198883607238", 1)
        var epoch = "old"
        val read = SteamCloudAuthStore.readCmAuthMaterialWithStatus("old", { epoch }, {
            epoch = "new"
            "old" to auth
        })

        assertFalse(read.readFailed)
        assertEquals(null, read.material)
    }

    @Test
    fun cmStatusRejectsLogoutRacingUnreadableEncryptedState() {
        var epoch = "old"
        val read = SteamCloudAuthStore.readCmAuthMaterialWithStatus("old", { epoch }, {
            epoch = "new"
            throw IOException()
        })

        assertFalse(read.readFailed)
        assertEquals(null, read.material)
        assertEquals("new", read.accountEpoch)
    }

    @Test
    fun cmStatusUnreadableEpochNeverReturnsCredentials() {
        val read = SteamCloudAuthStore.readCmAuthMaterialWithStatus("old", { throw IOException() }, {
            error("Must not read credentials without an epoch fence")
        })

        assertTrue(read.readFailed)
        assertEquals(null, read.material)
    }

    @Test
    fun authSnapshot_isIncompleteWhenSteamId64Missing() {
        val snapshot = authSnapshot(steamId64 = "")

        assertFalse(snapshot.isComplete)
    }

    @Test
    fun authSnapshot_isCompleteWhenTokenAndSteamId64ArePresent() {
        val snapshot = authSnapshot(steamId64 = "76561198883607238")

        assertTrue(snapshot.isComplete)
    }

    @Test
    fun reusableGuardDataForCredentials_requiresMatchingAccountName() {
        val guardData = reusableGuardDataForCredentials(
            savedAccountName = "old-account",
            savedGuardData = "guard-token",
            requestedUsername = "new-account",
        )

        assertEquals("", guardData)
    }

    @Test
    fun reusableGuardDataForCredentials_matchesIgnoringCaseAndWhitespace() {
        val guardData = reusableGuardDataForCredentials(
            savedAccountName = "  TestUser  ",
            savedGuardData = "  guard-token  ",
            requestedUsername = "testuser",
        )

        assertEquals("guard-token", guardData)
    }

    @Test
    fun finishLoginAttempt_clearsOnlyTheMatchingActiveAttempt() {
        assertEquals(
            LoginAttemptFinishAction.CLEAR_ACTIVE,
            resolveLoginAttemptFinish(
                activeAttemptId = "active",
                finishingAttemptId = "active",
            ),
        )
        assertEquals(
            LoginAttemptFinishAction.IGNORE,
            resolveLoginAttemptFinish(
                activeAttemptId = "newer",
                finishingAttemptId = "unrelated",
            ),
        )
    }

    @Test
    fun finishLoginAttempt_preservesCommittedAttemptWhenNewAttemptIsActive() {
        assertEquals(
            LoginAttemptFinishAction.IGNORE,
            resolveLoginAttemptFinish(
                activeAttemptId = "newer",
                finishingAttemptId = "cancelled",
            ),
        )
    }

    private fun authSnapshot(steamId64: String): SteamCloudAuthStore.AuthSnapshot =
        SteamCloudAuthStore.AuthSnapshot(
            accountName = "account",
            refreshTokenConfigured = true,
            guardDataConfigured = false,
            steamId64 = steamId64,
            personaName = "",
            avatarUrl = "",
            lastAuthAtMs = null,
            lastManifestAtMs = null,
            lastPullAtMs = null,
            lastPushAtMs = null,
            lastError = "",
        )
}
