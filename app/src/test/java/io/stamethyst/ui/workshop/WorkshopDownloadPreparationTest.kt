package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class WorkshopDownloadPreparationTest {
    private val item = WorkshopItemSummary(123uL, 646570u, "Mod", "", "", updatedAtMillis = 100L)
    private val details = WorkshopItemDetails(summary = item)

    @Test
    fun completedDetailPipelineAvoidsDuplicateRequestsAndKeepsDependencies() = runBlocking {
        val complete = details.copy(dependencies = listOf(item.copy(publishedFileId = 456uL)))
        val prepared = prepareWorkshopDownloadDetails(item, { complete }, { error("Unexpected request") })

        assertSame(complete, prepared)
        assertEquals(listOf(456uL), prepared.dependencies.map { it.publishedFileId })
    }

    @Test
    fun unfinishedPipelineMustFinishBeforeDependencyCheck() = runBlocking {
        val pipeline = CompletableDeferred<WorkshopItemDetails>()
        val preparation = async {
            prepareWorkshopDownloadDetails(item, { pipeline.await() }, { error("Unexpected request") })
        }
        yield()
        assertFalse(preparation.isCompleted)

        val complete = details.copy(dependencies = listOf(item.copy(publishedFileId = 456uL)))
        pipeline.complete(complete)
        assertSame(complete, preparation.await())
    }

    @Test
    fun absentOrCancelledDetailPipelinePreparesIndependently() = runBlocking {
        var requests = 0
        val prepared = prepareWorkshopDownloadDetails(item, { null }, { requests++; details })

        assertSame(details, prepared)
        assertEquals(1, requests)
    }

    @Test
    fun staleDetailsCannotPrepareAnUpdate() = runBlocking {
        val stale = details.copy(summary = item.copy(updatedAtMillis = 99L))
        assertSame(details, prepareWorkshopDownloadDetails(item, { stale }, { details }))
    }

    @Test
    fun anotherModsCompletedDetailsAreNotReused() = runBlocking {
        val other = details.copy(summary = item.copy(publishedFileId = 456uL))
        assertSame(details, prepareWorkshopDownloadDetails(item, { other }, { details }))
    }

    @Test
    fun anotherAppsCompletedDetailsAreNotReused() = runBlocking {
        val other = details.copy(summary = item.copy(appId = 1u))
        assertSame(details, prepareWorkshopDownloadDetails(item, { other }, { details }))
    }

    @Test
    fun newerCompletedDetailsAreReusable() = runBlocking {
        val newer = details.copy(summary = item.copy(updatedAtMillis = 101L))
        assertSame(newer, prepareWorkshopDownloadDetails(item, { newer }, { error("Unexpected request") }))
    }

    @Test
    fun preparationFailureRemainsAnErrorInsteadOfUsingPartialDetails() = runBlocking {
        val failure = IllegalStateException("Metadata unavailable")
        try {
            prepareWorkshopDownloadDetails(item, { null }, { throw failure })
            error("Expected preparation to fail")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
    }
}
