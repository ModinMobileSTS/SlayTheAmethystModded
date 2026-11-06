package io.stamethyst.backend.workshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDetailUpdateTest {
    private val emptyDetails = WorkshopItemDetails(
        WorkshopItemSummary(publishedFileId = 123uL, appId = 646570u, title = "Mod", previewUrl = "", description = ""),
    )

    @Test
    fun missingSectionsStaySkeletonsWhileEitherSourceCanStillSupplyThem() {
        val update = WorkshopDetailUpdate(emptyDetails, metadataLoading = false, communityLoading = true, dependenciesLoading = true)
        assertTrue(update.descriptionLoading)
        assertTrue(update.galleryLoading)
        assertTrue(update.commentContextLoading)
        assertTrue(update.copy(metadataLoading = true, communityLoading = false).galleryLoading)
    }

    @Test
    fun loadedSectionsDisplayEvenWhenOtherRequestsArePending() {
        val details = emptyDetails.copy(
            summary = emptyDetails.summary.copy(description = "Cached description"),
            previewImageUrls = listOf("https://example.com/image.jpg"),
            commentThreadContext = WorkshopCommentThreadContext("owner", "feature"),
        )
        val update = WorkshopDetailUpdate(details, metadataLoading = true, communityLoading = true, dependenciesLoading = true)
        assertFalse(update.descriptionLoading)
        assertFalse(update.galleryLoading)
        assertFalse(update.commentContextLoading)
    }

    @Test
    fun confirmedEmptyCommentsDoNotShowASkeleton() {
        val update = WorkshopDetailUpdate(emptyDetails.copy(commentCount = 0L), true, false, true)
        assertFalse(update.commentContextLoading)
    }

    @Test
    fun completedEmptySectionsStopShowingSkeletons() {
        val update = WorkshopDetailUpdate(emptyDetails, false, false, false)
        assertFalse(update.descriptionLoading)
        assertFalse(update.galleryLoading)
        assertFalse(update.commentContextLoading)
    }

    @Test
    fun revalidationKeepsVisibleMetadataAndGallery() {
        val cached = emptyDetails.copy(
            jsonMetadata = "cached metadata",
            fileUrl = "https://example.com/mod.jar",
            previewMedia = listOf(WorkshopPreviewMedia("image", WorkshopPreviewMediaKind.Image, imageUrl = "https://example.com/image.jpg")),
            previewImageUrls = listOf("https://example.com/image.jpg"),
        )
        val update = WorkshopDetailUpdate(emptyDetails, true, true, true).retainVisibleCache(cached)
        assertFalse(update.metadataLoading)
        assertFalse(update.galleryLoading)
        assertEquals(cached.jsonMetadata, update.details.jsonMetadata)
        assertEquals(cached.fileUrl, update.details.fileUrl)
        assertEquals(cached.previewMedia, update.details.previewMedia)
    }
}
