package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus
import io.stamethyst.backend.workshop.WorkshopItemDetails
import io.stamethyst.backend.workshop.WorkshopItemSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkshopDownloadSheetInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val task = WorkshopDownloadTaskUi(17uL, "Example", WorkshopDownloadTaskStatus.Downloading, "", details = WorkshopItemDetails(
        summary = WorkshopItemSummary(appId = 646570u, publishedFileId = 17uL, title = "Example", previewUrl = "", description = ""),
    ))

    @Test fun outputRailAnimatesDuringASingleFileWithoutFollowingDownloadCompletion() {
        val state = mutableStateOf(task.copy(downloadedBytes = 100L, totalBytes = 100L,
            completedFiles = 0, totalFiles = 1, writeProgressBytes = 20L, writeTotalBytes = 100L))
        compose.setContent { MaterialTheme { Column { TransferProgressRails(state.value) } } }
        val bars = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        fun progress(index: Int) = bars[index].fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(.2f, progress(1), .001f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(writeProgressBytes = 80L) }
        compose.mainClock.advanceTimeBy(128L)
        assertEquals(1f, progress(0), .001f)
        assertTrue(progress(1) in .201f.. .7999f)
        compose.mainClock.advanceTimeBy(500L)
        assertEquals(.8f, progress(1), .001f)
    }

    @Test fun failedTaskOffersRetryAndRevealsLogActionsInPlace() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        var retries = 0
        var exports = 0
        compose.setContent {
            MaterialTheme { DownloadTaskSheet(task.copy(status = WorkshopDownloadTaskStatus.Failed, errorMessage = "Timed out"),
                null, {}, {}, {}, {}, { retries++ }, { exports++ }, {}) }
        }
        compose.onNodeWithText("Timed out").assertExists()
        val export = ctx.getString(R.string.workshop_action_export_log)
        compose.onNodeWithText(export).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.transfer_technical)).performScrollTo().performClick()
        compose.onNodeWithText(export).performScrollTo().performClick()
        compose.onNodeWithText(ctx.getString(R.string.workshop_action_retry)).performClick()
        compose.runOnIdle { assertEquals(1, exports); assertEquals(1, retries) }
    }
}
