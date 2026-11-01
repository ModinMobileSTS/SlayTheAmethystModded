package io.stamethyst.ui.workshop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopInstalledModRecord
import io.stamethyst.backend.workshop.WorkshopModCardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkshopUpdatesDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rendersLocalCardsAndSupportsIndividualAndAllUpdates() {
        val updates = listOf(record(42UL, "Alpha"), record(43UL, "Beta"))
        val clicked = mutableListOf<ULong>()
        var allClicks = 0
        compose.setContent {
            MaterialTheme {
                WorkshopUpdatesDialog(
                    updates = updates,
                    downloadState = { WorkshopModDownloadState.UpdateAvailable },
                    actionsEnabled = true,
                    status = "",
                    onUpdate = { clicked.add(it.publishedFileId) },
                    onUpdateAll = { allClicks++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Local Alpha description").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("Local Beta description").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.workshop_action_update))
            .assertCountEquals(2)[0].performClick()
        compose.onNodeWithText(context.getString(R.string.workshop_updates_all)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(42UL), clicked)
            assertEquals(1, allClicks)
        }
    }

    @Test
    fun queuedAndCompletedCardsRemainVisibleAndCannotBeUpdatedAgain() {
        val downloadState = mutableStateOf(WorkshopModDownloadState.Queued)
        compose.setContent {
            MaterialTheme {
                WorkshopUpdatesDialog(
                    updates = listOf(record(42UL, "Alpha")),
                    downloadState = { downloadState.value },
                    actionsEnabled = true,
                    status = "",
                    onUpdate = { error("Queued or completed update must not restart") },
                    onUpdateAll = { error("No pending update") },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workshop_updates_all)).assertIsNotEnabled()
        compose.runOnIdle { downloadState.value = WorkshopModDownloadState.Downloaded }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workshop_updates_all)).assertIsNotEnabled()
    }

    private fun record(id: ULong, title: String) = WorkshopInstalledModRecord(
        appId = 646570u,
        publishedFileId = id,
        title = title,
        description = "Local $title description",
        previewUrl = "",
        versionText = "1.0",
        updatedAtMillis = 1_000L,
        installedAtMillis = 2_000L,
        localJarPath = "/local.jar",
        cardState = WorkshopModCardState.UpdateAvailable,
    )
}
