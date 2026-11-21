package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoEnabledDependenciesDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cancelReturnsToNoticeAndOnlyConfirmationRequestsUndo() {
        val showing = mutableStateOf(true)
        val outcomes = mutableListOf<Boolean>()
        val dialog = MainScreenViewModel.Effect.ShowAutoEnabledDependenciesDialog(
            rootModName = "Sample character",
            dependencyNames = listOf("Sample library A", "Sample library B"),
            missingDependenciesDialog = null,
            onUndo = {},
        )
        compose.setContent {
            MaterialTheme {
                if (showing.value) {
                    AutoEnabledDependenciesDialog(dialog) { undo ->
                        outcomes.add(undo)
                        showing.value = false
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.main_mod_auto_enabled_dependencies_title)).assertIsDisplayed()
        compose.onNodeWithText("Sample library A").assertIsDisplayed()
        compose.onNodeWithText("Sample library B").assertIsDisplayed()
        val undo = compose.onNodeWithText(context.getString(R.string.common_action_undo))
        undo.performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_undo_dependencies_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_mod_undo_dependencies_single_warning, "Sample character"))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_mod_undo_dependencies_cancel)).performClick()
        undo.assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), outcomes) }

        undo.performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_undo_dependencies_confirm)).performClick()
        compose.runOnIdle { assertEquals(listOf(true), outcomes) }

        compose.runOnIdle { showing.value = true }
        compose.onNodeWithText(context.getString(R.string.common_action_acknowledge)).performClick()
        compose.runOnIdle { assertEquals(listOf(true, false), outcomes) }
    }
}
