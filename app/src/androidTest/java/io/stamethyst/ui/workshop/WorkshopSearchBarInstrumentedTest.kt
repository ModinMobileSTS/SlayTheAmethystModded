package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkshopSearchBarInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val historyTitle get() = context.getString(R.string.search_history_title)
    private val searchField get() = compose.onNode(hasSetTextAction())

    @Test
    fun eightHistoryEntriesOpenInPopupWithoutMovingHeaderOrResults() {
        val headerSizes = mutableListOf<IntSize>()
        val searches = setSearchBar(
            List(8) { "Previous search ${it + 1}" },
            onHeaderSizeChanged = { headerSizes += it },
        )
        val header = compose.onNodeWithTag("market-test-header")
        val result = compose.onNodeWithTag("market-test-result")
        val headerBefore = header.getUnclippedBoundsInRoot()
        val resultBefore = result.getUnclippedBoundsInRoot()
        val sizesBefore = compose.runOnIdle { headerSizes.toList() }

        searchField.performTouchInput { click() }

        compose.onNode(isPopup()).assertExists()
        compose.onNodeWithText(historyTitle).assertIsDisplayed()
        searchField.assertIsFocused()
        assertEquals(headerBefore, header.getUnclippedBoundsInRoot())
        assertEquals(resultBefore, result.getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertEquals(sizesBefore, headerSizes)
            assertEquals(emptyList<String>(), searches)
        }
    }

    @Test
    fun deletingHistoryKeepsPopupOpenAndSelectingHistorySubmitsIt() {
        val searches = setSearchBar(listOf("Remove this", "Search for this"))
        searchField.performTouchInput { click() }

        compose.onNodeWithContentDescription(
            context.getString(R.string.search_history_delete_content_description, "Remove this"),
        ).performClick()
        compose.onNodeWithText("Remove this").assertDoesNotExist()
        compose.onNodeWithText("Search for this").performClick()

        compose.onNodeWithText(historyTitle).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("Search for this"), searches) }
    }

    @Test
    fun emptyHistoryDoesNotTakeFocusFromTypingOrImeSearch() {
        val searches = setSearchBar(emptyList())
        searchField.performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.search_history_empty)).assertIsDisplayed()

        searchField.performTextInput("typed query")
        searchField.assertIsFocused().performImeAction()

        compose.onNodeWithText(historyTitle).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("typed query"), searches) }
    }

    @Test
    fun deactivatingBrowseHidesThePopupAndDoesNotReopenItOnReturn() {
        val active = mutableStateOf(true)
        setSearchBar(listOf("Old search")) { active.value }
        searchField.performTouchInput { click() }
        compose.onNodeWithText(historyTitle).assertIsDisplayed()

        compose.runOnIdle { active.value = false }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { active.value = true }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    private fun setSearchBar(
        entries: List<String>,
        onHeaderSizeChanged: (IntSize) -> Unit = {},
        historyEnabled: () -> Boolean = { true },
    ): List<String> {
        val searches = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var query by remember { mutableStateOf("") }
                var history by remember { mutableStateOf(entries) }
                Column {
                    Column(
                        Modifier.fillMaxWidth()
                            .testTag("market-test-header")
                            .onSizeChanged(onHeaderSizeChanged),
                    ) {
                        WorkshopSearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { searches += it },
                            history = history,
                            onHistorySelected = {
                                query = it
                                searches += it
                            },
                            onHistoryDeleted = { history = history - it },
                            historyEnabled = historyEnabled(),
                        )
                        Text("Filters")
                    }
                    Text("Market result", Modifier.testTag("market-test-result"))
                }
            }
        }
        return searches
    }
}
