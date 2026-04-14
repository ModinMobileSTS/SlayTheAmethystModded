package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourceOrderingTest {
    @Test
    fun defaultPreferredSource_isAcceleratedDirect() {
        assertEquals(UpdateSource.ACCELERATED_DIRECT, UpdateSource.DEFAULT_PREFERRED_USER_SOURCE)
    }

    @Test
    fun metadataCandidates_keepPreferredSourceFirstEvenWhenItWasDownloadOnly() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_NET,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.GH_PROXY_COM)
        )
    }

    @Test
    fun metadataCandidates_acceleratedDirectUsesWattFirstThenMirrorsThenOfficial() {
        assertEquals(
            listOf(
                UpdateSource.ACCELERATED_DIRECT,
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_NET,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.ACCELERATED_DIRECT)
        )
    }

    @Test
    fun metadataCandidates_prioritizePreferredVipFirstThenOtherMirrors() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_NET,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.GH_PROXY_VIP)
        )
    }

    @Test
    fun metadataCandidates_prioritizePreferredLlkkFirstThenOtherMirrors() {
        assertEquals(
            listOf(
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_PROXY_NET,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.metadataCandidates(UpdateSource.GH_LLKK)
        )
    }

    @Test
    fun metadataCandidates_includeAllMirrorsAndKeepOfficialLast() {
        val candidates = UpdateSource.metadataCandidates(UpdateSource.GH_PROXY_VIP)

        assertTrue(candidates.contains(UpdateSource.GH_PROXY_NET))
        assertEquals(UpdateSource.OFFICIAL, candidates.last())
    }

    @Test
    fun downloadCandidates_includeGhproxyNetBeforeOfficial() {
        val candidates = UpdateSource.downloadCandidates(
            preferredUserSource = UpdateSource.GH_PROXY_COM,
            metadataSource = UpdateSource.GH_LLKK
        )

        assertTrue(candidates.indexOf(UpdateSource.GH_PROXY_NET) >= 0)
        assertTrue(
            candidates.indexOf(UpdateSource.GH_PROXY_NET) <
                candidates.indexOf(UpdateSource.OFFICIAL)
        )
    }

    @Test
    fun downloadCandidates_followPreferredThenOtherMirrorsThenOfficial() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_LLKK,
                UpdateSource.GH_PROXY_NET,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.downloadCandidates(
                preferredUserSource = UpdateSource.GH_PROXY_COM,
                metadataSource = UpdateSource.GH_LLKK
            )
        )
    }

    @Test
    fun oneShotDownloadSelectionSources_keepResolvedSourceFirst() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_LLKK,
                UpdateSource.ACCELERATED_DIRECT,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.oneShotDownloadSelectionSources(UpdateSource.GH_PROXY_VIP)
        )
    }

    @Test
    fun oneShotDownloadSelectionSources_includeResolvedFallbackSource() {
        assertEquals(
            listOf(
                UpdateSource.GH_PROXY_NET,
                UpdateSource.GH_PROXY_COM,
                UpdateSource.GH_PROXY_VIP,
                UpdateSource.GH_LLKK,
                UpdateSource.ACCELERATED_DIRECT,
                UpdateSource.OFFICIAL
            ),
            UpdateSource.oneShotDownloadSelectionSources(UpdateSource.GH_PROXY_NET)
        )
    }
}
