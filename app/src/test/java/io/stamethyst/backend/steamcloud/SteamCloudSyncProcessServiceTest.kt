package io.stamethyst.backend.steamcloud

import org.junit.Assert.*
import org.junit.Test

class SteamCloudSyncProcessServiceTest {
    @Test fun timeoutThrottleAndCancellationProduceDistinctFinalEvents() {
        val timeout = runCatching { kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
        } }.exceptionOrNull()!!
        val throttle = top.apricityx.workshop.steam.protocol.SteamServiceMethodException("Cloud.CommitHTTPUpload#1", 84, "")
        val denied = top.apricityx.workshop.steam.protocol.SteamServiceMethodException("Cloud.CommitHTTPUpload#1", 5, "")
        fun event(error: Exception, stopped: Boolean = false) =
            SteamCloudSyncProcessService.failureEvent(error, SteamCloudSyncMode.MERGE, stopped)
        val failed = SteamCloudSyncProcessService.RESULT_FAILURE
        val cancelled = SteamCloudSyncProcessService.RESULT_CANCELLED to SteamCloudFailureCategory.CANCELLED
        assertEquals(failed to SteamCloudFailureCategory.TRANSIENT_NETWORK, event(IllegalStateException("rpc", timeout)))
        assertEquals(failed to SteamCloudFailureCategory.RATE_LIMITED, event(throttle))
        assertEquals(cancelled, event(IllegalStateException("rpc", InterruptedException())))
        assertEquals(cancelled, event(denied, stopped = true))
    }
    @Test fun liveSaveLeaseContentionIsDeferredIncludingWrappedErrors() {
        assertTrue(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(SteamCloudLiveSaveInUseException()))
        assertTrue(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(
            IllegalStateException("wrapper", SteamCloudLiveSaveInUseException())))
        assertFalse(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(IllegalStateException("unrelated")))
    }
    @Test fun pendingReturnDoesNotRetryFailureOrUndoCancellation() {
        val batch = SteamCloudReturnBatch("1", null).also { it.attach(99) }
        for (code in listOf(SteamCloudSyncProcessService.RESULT_CANCELLED, SteamCloudSyncProcessService.RESULT_FAILURE)) {
            batch.finish(code, null, false, mergeAgain = { fail("retried $code") }, publish = { assertEquals(code, it) })
        }
        batch.finish(SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED, null, true,
            mergeAgain = { fail("restarted after cancellation") }, publish = { assertEquals(SteamCloudSyncProcessService.RESULT_CANCELLED, it) })
    }
    @Test fun onlyANewReturnCanRetryADeferredSnapshot() {
        val code = SteamCloudSyncProcessService.RESULT_DEFERRED
        val batch = SteamCloudReturnBatch("1", 1)
        batch.attach(null)
        batch.attach(1)
        batch.finish(code, null, false, mergeAgain = { fail("no new game progress") }, publish = { assertEquals(code, it) })
        batch.attach(2)
        batch.attach(3)
        var count = 0
        batch.finish(code, null, false, mergeAgain = { assertEquals(3L, it.returnedLaunch); ++count }, publish = { fail("new return wasn't checked") })
        batch.attach(2) // delayed duplicate, already covered by generation 3
        batch.finish(code, null, false, mergeAgain = { fail("infinite retry") }, publish = { assertEquals(code, it) })
        assertEquals(1, count)
    }
}
