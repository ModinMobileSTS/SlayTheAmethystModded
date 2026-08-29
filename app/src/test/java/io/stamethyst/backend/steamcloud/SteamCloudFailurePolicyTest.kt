package io.stamethyst.backend.steamcloud

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudFailurePolicyTest {
    @After
    fun tearDown() {
        SteamAuthenticationCircuitBreaker.reset()
    }

    @Test
    fun classify_recognizesAuthenticationAndRateLimitFailures() {
        assertEquals(
            SteamCloudFailureCategory.UNKNOWN,
            SteamCloudFailureClassifier.classify(IllegalStateException("Steam logon failed: AccessDenied")),
        )
        assertEquals(
            SteamCloudFailureCategory.AUTH_REJECTED,
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
    fun circuitBreaker_tripsOnceForAuthenticationFailuresOnly() {
        assertFalse(SteamAuthenticationCircuitBreaker.trip(SteamCloudFailureCategory.TRANSIENT_NETWORK))
        assertTrue(SteamAuthenticationCircuitBreaker.trip(SteamCloudFailureCategory.AUTH_REJECTED))
        assertFalse(SteamAuthenticationCircuitBreaker.trip(SteamCloudFailureCategory.RATE_LIMITED))
        assertTrue(SteamAuthenticationCircuitBreaker.isOpen())
    }
}
