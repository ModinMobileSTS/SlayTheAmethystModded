package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopComment
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopDetailCommentsMergeTest {
    private val details = WorkshopItemDetails(
        WorkshopItemSummary(123uL, 646570u, "Mod", "", "Description"),
    )

    @Test
    fun laterDetailSnapshotKeepsCommentsAndCurrentPage() {
        val current = details.copy(
            comments = listOf(WorkshopComment("1", "Author", "", "Already loaded")),
            commentCount = 12,
            commentPage = 2,
            commentTotalPages = 3,
            hasPreviousCommentPage = true,
            hasNextCommentPage = true,
        )

        val merged = details.retainLoadedCommentsFrom(current)

        assertEquals(current.comments, merged.comments)
        assertEquals(2, merged.commentPage)
        assertEquals(3, merged.commentTotalPages)
        assertEquals(true, merged.hasPreviousCommentPage)
        assertEquals(true, merged.hasNextCommentPage)
    }

    @Test
    fun confirmedEmptyCommentResponseIsNotReplacedByUnknownCount() {
        val merged = details.retainLoadedCommentsFrom(details.copy(commentCount = 0), hasNewCommentResponse = true)
        assertEquals(0L, merged.commentCount)
    }

    @Test
    fun staleCachedEmptyCountCannotHideNewlyAvailableComments() {
        val fresh = details.copy(commentCount = 7)
        val merged = fresh.retainLoadedCommentsFrom(details.copy(commentCount = 0))
        assertEquals(7L, merged.commentCount)
    }

    @Test
    fun anotherModsCommentsCannotLeakIntoTheNewDetailPage() {
        val other = details.copy(
            summary = details.summary.copy(publishedFileId = 999uL),
            comments = listOf(WorkshopComment("1", "Author", "", "Other mod")),
        )
        assertEquals(emptyList<WorkshopComment>(), details.retainLoadedCommentsFrom(other).comments)
    }
}
