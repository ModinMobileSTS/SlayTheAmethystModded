package io.stamethyst.ui.main

import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.backend.workshop.WorkshopItemRating
import io.stamethyst.backend.workshop.WorkshopAuthor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun pendingCommunityMetadataOnlyMarksMissingFieldsAsLoading() {
        val summary = mod(10uL).workshopFallback()!!
        val pending = CommunityPatchMarketState(summary, loading = true)
        assertTrue(pending.authorLoading)
        assertTrue(pending.ratingLoading)
        assertFalse(pending.copy(summary = summary.copy(authorName = "Author")).authorLoading)
        assertFalse(pending.copy(summary = summary.copy(author = WorkshopAuthor("Author", "https://steamcommunity.com/id/author"))).authorLoading)
        assertFalse(pending.copy(summary = summary.copy(rating = WorkshopItemRating(4))).ratingLoading)
        assertFalse(pending.copy(loading = false).authorLoading)
        assertFalse(pending.copy(loading = false).ratingLoading)
    }

    @Test fun firstDetailFrameAlreadyUsesTheListSummary() = runBlocking {
        val store = CommunityPatchSummaryStore(this)
        val item = mod(10uL)
        val summary = item.workshopFallback()!!.copy(previewUrl = "https://example.com/cover.jpg", downloadCount = 123)
        store.load(summary.appId, summary.publishedFileId) { summary }

        val state = CommunityPatchMarketViewModel(store).stateFor(item)

        assertEquals(summary, state.summary)
        assertTrue(state.authorLoading)
        assertTrue(state.ratingLoading)
        assertNull(CommunityPatchMarketViewModel(store).stateFor(mod(11uL)).summary)
        assertFalse(CommunityPatchMarketViewModel(store).stateFor(mod(null)).loading)
    }

    private fun mod(workshopId: ULong?) = CommunityPatchMod("chronomuncher", "The Disciple", workshopId, emptyList())

    private fun patch(hash: String, version: String) = RemoteAgentPatch(
        name = "Translation", description = "Description", authorName = "24", version = version,
        patchModId = "amethyst.ai.patch.chronomuncher", workshopId = "1609846039", targetVersions = listOf("2.0"),
        jarName = "patch.jar", downloadUrl = "https://raw.githubusercontent.com/example/patch.jar", size = 1, sha256 = hash,
    )
}
