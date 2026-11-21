package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun eightHistoryEntriesExpandAndCollapseInsideBannerWithoutMovingResults() {
        val searches = setSearchBar(List(8) { "Previous search ${it + 1}" })
        val header = compose.onNodeWithTag("market-test-header")
        val result = compose.onNodeWithTag("market-test-result")
        val headerBefore = header.getUnclippedBoundsInRoot()
        val resultBefore = result.getUnclippedBoundsInRoot()

        searchField.performTouchInput { click() }

        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(hasText(historyTitle) and hasAnyAncestor(hasTestTag("market-test-header")))
            .assertIsDisplayed()
        searchField.assertIsFocused()
        assertTrue(header.getUnclippedBoundsInRoot().height > headerBefore.height)
        assertEquals(resultBefore, result.getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(emptyList<String>(), searches) }

        searchField.performImeAction()

        compose.onNodeWithText(historyTitle).assertDoesNotExist()
        assertEquals(headerBefore, header.getUnclippedBoundsInRoot())
        assertEquals(resultBefore, result.getUnclippedBoundsInRoot())
    }

    @Test
    fun deletingHistoryKeepsBannerExpandedAndSelectingHistorySubmitsIt() {
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

    private fun setSearchBar(entries: List<String>): List<String> {
        val searches = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                var query by remember { mutableStateOf("") }
                var history by remember { mutableStateOf(entries) }
                val controlsHeight = remember { WorkshopSearchControlsHeight() }
                val density = LocalDensity.current
                Box(Modifier.fillMaxSize()) {
                    LazyColumn {
                        item {
                            Spacer(Modifier.height(40.dp + controlsHeight.collapsedHeight(density) + 16.dp))
                        }
                        item {
                            Text("Market result", Modifier.testTag("market-test-result"))
                        }
                    }
                    CollapsibleFloatingGlassHeader(
                        modifier = Modifier.testTag("market-test-header"),
                        hazeState = rememberHazeState(),
                        expandedContentTopPadding = WorkshopSearchControlsTop,
                        pinnedContent = { Text("Market", Modifier.height(40.dp)) },
                        expandedContent = {
                            Column(
                                Modifier.fillMaxWidth().padding(bottom = WorkshopSearchControlsBottom),
                                verticalArrangement = Arrangement.spacedBy(WorkshopSearchControlsSpacing),
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
                                    inputFieldModifier = controlsHeight.inputModifier,
                                )
                                Text("Filters", controlsHeight.filtersModifier.height(48.dp))
                            }
                        },
                    )
                }
            }
        }
        return searches
    }
}
