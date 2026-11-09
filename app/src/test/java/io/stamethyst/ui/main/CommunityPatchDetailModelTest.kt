package io.stamethyst.ui.main

import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.mods.RemoteAgentPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityPatchDetailModelTest {
    @Test fun fallbackUsesWorkshopIdAndCatalogueNameWithoutInventingMarketMetadata() {
        val summary = mod(workshopId = 1609846039uL).workshopFallback()!!
        assertEquals(646570u, summary.appId)
        assertEquals(1609846039uL, summary.publishedFileId)
        assertEquals("The Disciple", summary.title)
        assertEquals("", summary.authorName)
        assertEquals("", summary.previewUrl)
        assertEquals(0L, summary.downloadCount)
        assertNull(summary.rating)
    }

    @Test fun unlinkedAndZeroWorkshopIdsDoNotCreateFakeMarketItems() {
        assertNull(mod(null).workshopFallback())
        assertNull(mod(0uL).workshopFallback())
    }

    @Test fun emptyCatalogueNameFallsBackToModId() {
        assertEquals("chronomuncher", mod(10uL).copy(name = "").workshopFallback()!!.title)
    }

    @Test fun differentCatalogueEntriesWithTheSamePatchIdKeepIndependentIdentity() {
        val first = patch("a".repeat(64), "3.7.0")
        val second = patch("b".repeat(64), "3.7.1")
        assertEquals(first.patchModId, second.patchModId)
        assertNotEquals(first.communityItemKey(), second.communityItemKey())
        assertEquals("${first.patchModId}:${first.sha256}", first.communityItemKey())
    }

    private fun mod(workshopId: ULong?) = CommunityPatchMod("chronomuncher", "The Disciple", workshopId, emptyList())

    private fun patch(hash: String, version: String) = RemoteAgentPatch(
        name = "Translation", description = "Description", authorName = "24", version = version,
        patchModId = "amethyst.ai.patch.chronomuncher", workshopId = "1609846039", targetVersions = listOf("2.0"),
        jarName = "patch.jar", downloadUrl = "https://raw.githubusercontent.com/example/patch.jar", size = 1, sha256 = hash,
    )
}
