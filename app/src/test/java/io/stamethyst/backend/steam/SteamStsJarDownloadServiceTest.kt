package io.stamethyst.backend.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SteamStsJarDownloadServiceTest {
    @Test
    fun excludesSteamworksCommonRedistributablesFromDesktopJarCandidates() {
        val gameCandidate = SteamStsDepotCandidate(
            appId = 646570u,
            depotId = 646571u,
            manifestId = 123uL,
            branch = "public",
        )
        val sharedRuntimeCandidate = SteamStsDepotCandidate(
            appId = 228980u,
            depotId = 228983u,
            manifestId = 8124929965194586177uL,
            branch = "public",
        )

        val filtered = listOf(gameCandidate, sharedRuntimeCandidate)
            .filterNot(::isSteamworksCommonRedistributableCandidate)

        assertEquals(listOf(gameCandidate), filtered)
        assertFalse(isSteamworksCommonRedistributableCandidate(gameCandidate))
    }
}
