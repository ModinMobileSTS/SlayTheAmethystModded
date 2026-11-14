package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchSelectAllActionInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun clickingLabelCompletesPartialSelectionThenClearsOnlyScope() {
        var observed = emptySet<String>()
        compose.setContent {
            var selected by remember { mutableStateOf(setOf("outside", "a")) }
            val scope = setOf("a", "b")
            MaterialTheme {
                BatchSelectAllAction(state(batchSelectionState(selected, scope), enabled = true).copy(
                    selectedCount = selected.size,
                    totalCount = 3,
                    onToggleSelectAll = {
                        selected = toggleFolderBatchSelection(selected, scope)
                        observed = selected
                    },
                ))
            }
        }
        compose.onNodeWithText("2/3").assertExists()
        val action = compose.onNodeWithContentDescription(context.getString(R.string.mod_select_all_results))
        action.performClick().assertIsOn()
        compose.onNodeWithText("3/3").assertExists()
        compose.runOnIdle { assertEquals(setOf("outside", "a", "b"), observed) }
        action.performClick().assertIsOff()
        compose.onNodeWithText("1/3").assertExists()
        compose.runOnIdle { assertEquals(setOf("outside"), observed) }
    }

    @Test fun emptyScopeDisablesAction() {
        compose.setContent {
            MaterialTheme { BatchSelectAllAction(state(ToggleableState.Off, enabled = false)) }
        }
        compose.onNodeWithText("0/0")
            .assertIsOff().assertIsNotEnabled()
    }

    private fun state(toggle: ToggleableState, enabled: Boolean) = BatchEditBarState(
        selectAllState = toggle, selectAllEnabled = enabled, selectionFiltered = true,
        selectedCount = 0, controlsEnabled = enabled,
        onMove = {}, onDelete = {}, onEnable = {}, onDisable = {}, onCancel = {},
    )
}
