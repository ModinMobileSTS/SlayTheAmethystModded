package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkshopModUpdateDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun updateCanBeConfirmedBeforeOrDuringChangeNotesLoading() {
        val state = mutableStateOf<WorkshopUpdateChangeNotesState>(WorkshopUpdateChangeNotesState.Idle)
        var updates = 0
        compose.setContent {
            MaterialTheme {
                WorkshopModUpdateDialog(
                    modName = "Test mod",
                    changeNotesState = state.value,
                    onRetryChangeNotes = { error("No retry requested") },
                    onUpdate = { updates++ },
                    onDismiss = {},
                )
            }
        }
        val updateButton = compose.onNodeWithText(context.getString(R.string.workshop_action_update))
        updateButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, updates)
            state.value = WorkshopUpdateChangeNotesState.Loading
        }
        compose.onNodeWithText(context.getString(R.string.workshop_change_notes_loading)).assertIsDisplayed()
        updateButton.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, updates) }
    }

    @Test
    fun failureRetryAndSuccessfulChangeNotesNeverGateOrTriggerAnUpdate() {
        val state = mutableStateOf<WorkshopUpdateChangeNotesState>(WorkshopUpdateChangeNotesState.Failed("offline"))
        var updates = 0
        var retries = 0
        compose.setContent {
            MaterialTheme {
                WorkshopModUpdateDialog(
                    modName = "Test mod",
                    changeNotesState = state.value,
                    onRetryChangeNotes = {
                        retries++
                        state.value = WorkshopUpdateChangeNotesState.Loading
                    },
                    onUpdate = { updates++ },
                    onDismiss = {},
                )
            }
        }
        val updateButton = compose.onNodeWithText(context.getString(R.string.workshop_action_update))
        compose.onNodeWithText(context.getString(R.string.workshop_change_notes_load_failed, "offline"))
            .performScrollTo().assertIsDisplayed()
        updateButton.assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.workshop_action_retry)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, updates)
            assertEquals(1, retries)
        }
        updateButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, updates)
            state.value = WorkshopUpdateChangeNotesState.Loaded("")
        }
        compose.onNodeWithText(context.getString(R.string.workshop_change_notes_empty)).assertIsDisplayed()
        updateButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(3, updates)
            state.value = WorkshopUpdateChangeNotesState.Loaded("Latest changes")
        }
        compose.onNodeWithText(context.getString(R.string.workshop_change_notes_latest_title))
            .performScrollTo().assertIsDisplayed()
        updateButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(4, updates)
            assertEquals(1, retries)
        }
    }

    @Test
    fun cancelDoesNotUpdateOrRetryWhileChangeNotesAreLoading() {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                WorkshopModUpdateDialog(
                    modName = "Test mod",
                    changeNotesState = WorkshopUpdateChangeNotesState.Loading,
                    onRetryChangeNotes = { error("Cancellation must not retry change notes") },
                    onUpdate = { error("Cancellation must not update") },
                    onDismiss = { dismissals++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
