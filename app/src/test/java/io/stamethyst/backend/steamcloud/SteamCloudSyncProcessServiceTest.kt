package io.stamethyst.backend.steamcloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCloudSyncProcessServiceTest {
    @Test
    fun replacementStartIsRejected_whileCancelledWorkerIsUnwinding() {
        assertTrue(
            SteamCloudSyncProcessService.shouldRejectReplacementStart(
                isRunning = true,
                cancellationPending = true,
            )
        )
    }

    @Test
    fun replacementStartIsAllowed_whenNoCancellationIsPending() {
        assertFalse(
            SteamCloudSyncProcessService.shouldRejectReplacementStart(
                isRunning = true,
                cancellationPending = false,
            )
        )
    }

    @Test
    fun replacementStartReportsTheRunningOperationPhase() {
        assertTrue(
            SteamCloudSyncProcessService.replacementResultCodeFor(
                SteamCloudServiceOperationPhase.CHECKING,
            ) == SteamCloudSyncProcessService.RESULT_CHECKING,
        )
        assertTrue(
            SteamCloudSyncProcessService.replacementResultCodeFor(
                SteamCloudServiceOperationPhase.SYNCING,
            ) == SteamCloudSyncProcessService.RESULT_SYNC_STARTED,
        )
    }

    @Test
    fun liveSaveLeaseContention_isDeferredInsteadOfReportedAsSyncFailure() {
        assertTrue(
            SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(
                SteamCloudLiveSaveInUseException()
            )
        )
        assertTrue(
            SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(
                IllegalStateException("wrapper", SteamCloudLiveSaveInUseException())
            )
        )
        assertFalse(
            SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(
                IllegalStateException("unrelated")
            )
        )
    }
}
