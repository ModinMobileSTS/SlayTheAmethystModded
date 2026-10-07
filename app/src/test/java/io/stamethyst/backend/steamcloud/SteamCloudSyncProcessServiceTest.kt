package io.stamethyst.backend.steamcloud

import org.junit.Assert.*
import org.junit.Test

class SteamCloudSyncProcessServiceTest {
    @Test fun liveSaveLeaseContentionIsDeferredIncludingWrappedErrors() {
        assertTrue(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(SteamCloudLiveSaveInUseException()))
        assertTrue(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(
            IllegalStateException("wrapper", SteamCloudLiveSaveInUseException())))
        assertFalse(SteamCloudSyncProcessService.shouldDeferForLiveSaveLease(IllegalStateException("unrelated")))
    }
}
