package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceOrderingTest {
    @Test
    fun metadataCandidates_prioritizePreferredVipFirst() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_LLKK,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.GH_PROXY_VIP)
        )
    }

    @Test
    fun metadataCandidates_prioritizePreferredLlkkFirst() {
        assertEquals(
            listOf(
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.GH_LLKK)
        )
    }

    @Test
    fun metadataCandidates_excludeGhproxyNetAndKeepOfficialLast() {
        val candidates = UpdateSource.metadataCandidates(UpdateSource.GH_PROXY_VIP)

        assertFalse(candidates.contains(UpdateSource.GH_PROXY_NET))
        assertEquals(UpdateSource.OFFICIAL, candidates.last())
    }

    @Test
    fun downloadCandidates_includeGhproxyNetBeforeOfficial() {
        val candidates = UpdateSource.downloadCandidates(
            preferredUserSource = UpdateSource.GH_PROXY_VIP,
            metadataSource = UpdateSource.GH_LLKK
        )

        assertTrue(candidates.indexOf(UpdateSource.GH_PROXY_NET) >= 0)
        assertTrue(
            candidates.indexOf(UpdateSource.GH_PROXY_NET) <
                candidates.indexOf(UpdateSource.OFFICIAL)
        )
    }
}
