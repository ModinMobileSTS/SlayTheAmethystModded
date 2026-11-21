package io.stamethyst.backend.steamcloud

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import top.apricityx.workshop.steam.protocol.SteamServerUnavailableException
import top.apricityx.workshop.steam.protocol.SteamProtocolException
import top.apricityx.workshop.steam.protocol.SteamServiceMethodException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SteamCloudFailurePolicyTest {
    @Test fun cloudListRateLimitIsABusinessFailure() {
        val error = SteamServiceMethodException("Cloud.GetAppFileChangelist#1", 84, null)
        val category = SteamCloudFailureClassifier.classify(error)
        assertEquals(SteamCloudFailureCategory.RATE_LIMITED, category)
        assertFalse(error.isCmLogonFailure)
    }

    @Test fun expiredRpcDeadlineIsNotAPlayerCancellation() {
        val error = runCatching { runBlocking { withTimeout(1) { awaitCancellation() } } }.exceptionOrNull()!!
        assertEquals(SteamCloudFailureCategory.TRANSIENT_NETWORK, SteamCloudFailureClassifier.classify(error))
    }

    @Test fun throttleStopsOnlyThisAccountsCloudRequestsForOneMinute() {
        var now = 3L
        val cooldown = SteamCloudRpcCooldown { now }
        val error = SteamServiceMethodException("Cloud.GetAppFileChangelist#1", 84, null)
        var calls = 0
        assertEquals(error, runCatching { cooldown.call(1) { calls++; throw error } }.exceptionOrNull())
        now += 59_999
        val cooling = runCatching { cooldown.call(1) { calls++ } }.exceptionOrNull()!!
        assertEquals(SteamCloudFailureCategory.RATE_LIMITED, SteamCloudFailureClassifier.classify(cooling))
        cooldown.call(2) { calls++ }
        assertEquals(2, calls)
        now++
        cooldown.call(1) { calls++ }
        assertEquals(3, calls)
    }
    @Test
    fun unavailableLogonServerIsTransientAndDoesNotInvalidateCredentials() {
        val unavailable = SteamServerUnavailableException(5514, -1, 3)
        listOf(unavailable, SteamProtocolException("CM attempts exhausted", unavailable),
            IllegalStateException("Steam server unavailable for request EMsg=5514 job=-1 serverType=3"),
            IllegalStateException("Steam 会话登录失败 EResult=20")).forEach { error ->
            val category = SteamCloudFailureClassifier.classify(error)
            assertEquals(SteamCloudFailureCategory.TRANSIENT_NETWORK, category)
        }
    }

    @Test
    fun classify_recognizesAuthenticationAndRateLimitFailures() {
        assertEquals(
            SteamCloudFailureCategory.UNKNOWN,
            SteamCloudFailureClassifier.classify(IllegalStateException("Steam logon failed: AccessDenied")),
        )
        assertEquals(
            SteamCloudFailureCategory.UNKNOWN,
            SteamCloudFailureClassifier.classify(IllegalStateException("Steam logon failed: InvalidPassword")),
        )
        assertEquals(
            SteamCloudFailureCategory.RATE_LIMITED,
            SteamCloudFailureClassifier.classify(IllegalStateException("Authentication failed EResult=84")),
        )
    }

    @Test
    fun classify_onlyMarksNetworkAndCancellationAsRetryOrCancelCategories() {
        assertEquals(
            SteamCloudFailureCategory.TRANSIENT_NETWORK,
            SteamCloudFailureClassifier.classify(SocketTimeoutException("timed out")),
        )
        assertEquals(
            SteamCloudFailureCategory.CANCELLED,
            SteamCloudFailureClassifier.classify(CancellationException("cancelled")),
        )
        assertEquals(
            SteamCloudFailureCategory.TRANSIENT_NETWORK,
            SteamCloudFailureClassifier.classify(
                IllegalStateException("Steam Cloud upload failed: BeginHTTPUpload failed: DuplicateRequest"),
            ),
        )
        assertEquals(
            SteamCloudFailureCategory.UNKNOWN,
            SteamCloudFailureClassifier.classify(IllegalStateException("local file failure")),
        )
    }

    @Test
    fun identityClassificationRequiresAnAuthenticationMethodNotResponseText() {
        val forged = "InvalidPassword Authentication.BeginAuthSessionViaCredentials#1 EResult=5"
        val classify = SteamCloudFailureClassifier::classify
        assertEquals(SteamCloudFailureCategory.UNKNOWN, classify(java.io.IOException("HTTP 403: $forged")))
        val cloud = "Cloud.CompleteAppUploadBatchBlocking#1"
        assertEquals(SteamCloudFailureCategory.PERMISSION_DENIED, classify(SteamServiceMethodException(cloud, 15, forged)))
        assertEquals(SteamCloudFailureCategory.REQUEST_REJECTED, classify(SteamServiceMethodException(cloud, 5, forged)))
        assertEquals(SteamCloudFailureCategory.AUTH_REJECTED, classify(SteamServiceMethodException(
            "Authentication.BeginAuthSessionViaCredentials#1", 5, null)))
    }

    @Test fun credentialNoticeRejectsAccountSwitchReloginAndRevocation() {
        val account = top.apricityx.workshop.steam.protocol.SteamAccountSession("A", 1, "old")
        val auth = SteamCloudAuthStore.SavedAuthMaterial("A", "old", "", "1", 3)
        val notices = io.stamethyst.backend.steam.SteamCmLoginFailureNotice
        fun notice(current: SteamCloudAuthStore.SavedAuthMaterial?, epoch: String = "E") =
            notices.noticeForRejectedLogon(account, 3, "E", current, epoch)
        assertEquals("1", notice(auth)?.steamId)
        assertNull(notice(null))
        assertNull(notice(auth.copy(steamId64 = "2")))
        assertNull(notice(auth.copy(refreshToken = "new")))
        assertNull(notice(auth.copy(credentialRevision = 4)))
        assertNull(notice(auth, "new epoch"))
    }
}
