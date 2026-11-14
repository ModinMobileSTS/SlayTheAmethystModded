package io.stamethyst.ui.workshop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkshopDetailDownloadActionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun actionIsImmediatelyAvailableAndOnlyClickStartsPreparation() {
        val preparing = mutableStateOf(false)
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                WorkshopDetailDownloadActionButton(
                    state = WorkshopModDownloadState.NotDownloaded,
                    preparing = preparing.value,
                    onClick = { clicks++; preparing.value = true },
                )
            }
        }
        composeRule.onNodeWithText(text(R.string.workshop_action_download))
            .assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithText(text(R.string.workshop_status_checking_dependencies))
            .assertIsDisplayed().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, clicks) }

        // A failed preparation clears the flag and allows the user to retry.
        composeRule.runOnIdle { preparing.value = false }
        composeRule.onNodeWithText(text(R.string.workshop_action_download)).assertIsEnabled()
    }

    @Test
    fun existingDownloadStateIsPreservedWithoutPreparation() {
        composeRule.setContent {
            MaterialTheme {
                WorkshopDetailDownloadActionButton(
                    state = WorkshopModDownloadState.Downloaded,
                    preparing = false,
                    onClick = { error("Downloaded action must not be clickable") },
                )
            }
        }
        composeRule.onNodeWithText(text(R.string.workshop_download_state_downloaded))
            .assertIsDisplayed().assertIsNotEnabled()
    }
}
