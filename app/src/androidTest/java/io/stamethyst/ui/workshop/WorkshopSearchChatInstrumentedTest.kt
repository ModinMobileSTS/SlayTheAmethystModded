package io.stamethyst.ui.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentToolExecutionEvent
import io.stamethyst.backend.workshop.*
import io.stamethyst.ui.FrostedGlassChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkshopSearchChatInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @SdkSuppress(minSdkVersion = 31)
    @Test fun composerIgnoresOrdinarySearchBlurDuringPaneOverlap() {
        val sessions = WorkshopSearchSessions(CoroutineScope(Dispatchers.Main))
        val browseColor = mutableStateOf(Color.Red)
        val motion = WorkshopModeMotion(mutableFloatStateOf(0.5f))
        compose.setContent {
            MaterialTheme {
                val haze = rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().workshopModePane(motion, chat = false, selected = false)
                        .hazeSource(haze).background(browseColor.value))
                    // Hide the outgoing pane directly, so its color can only show up through a blur sample.
                    Box(Modifier.fillMaxSize().background(Color.White))
                    WorkshopSearchChat(
                        sessions, rememberLazyListState(), {},
                        modifier = Modifier.workshopModePane(motion, chat = true, selected = true),
                        composerModifier = Modifier.testTag("composer"),
                        active = false, titleInset = 96.dp, haze = haze,
                    ) {}
                    FrostedGlassChrome(
                        modifier = Modifier.align(Alignment.TopCenter).size(120.dp, 56.dp).testTag("shared-glass"),
                        hazeState = haze, shape = RectangleShape,
                    ) {}
                }
            }
        }

        fun centerPixel(tag: String): Color {
            val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        val sharedBefore = centerPixel("shared-glass")
        val composerBefore = centerPixel("composer")
        compose.runOnIdle { browseColor.value = Color.Blue }
        assertNotEquals("Shared glass can still sample the outgoing pane", sharedBefore, centerPixel("shared-glass"))
        assertEquals("Composer must not sample the outgoing pane", composerBefore, centerPixel("composer"))
    }

    @Test fun searchAndToolRowsRemainExpandableAfterStopping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = WorkshopSearchSessions(CoroutineScope(Dispatchers.Main))
        sessions.current.turns += WorkshopTurn(1, "Map colours?", stopped = true)
            .withToolEvent(AgentToolExecutionEvent("search", "search_workshop", """{"query":"map colours"}""",
                result = """{"candidates":[{"id":"123","title":"Colored Map"}]}"""))
            .withToolEvent(AgentToolExecutionEvent("read", "read_workshop", """{"id":"123"}"""))
        compose.setContent {
            MaterialTheme {
                WorkshopSearchChat(sessions, rememberLazyListState(), {}, active = false) {}
            }
        }

        val search = compose.onNode(hasText(context.getString(R.string.market_ai_search_tool)) and hasClickAction())
        search.performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            context.getString(R.string.ai_mod_editor_tool_completed)))
        search.performClick()
        compose.onNodeWithText("Colored Map", substring = true).performScrollTo().assertIsDisplayed()
        search.performScrollTo().performClick()
        compose.onNode(hasText(context.getString(R.string.market_ai_read_tool)) and hasClickAction())
            .performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.ai_mod_editor_tool_stopped)))
        compose.onNodeWithTag("ai-assistant-loading").assertDoesNotExist()
    }

    @Test fun switchingTabsPreservesDraftAndCardOffersSourceAndExistingDetailsAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val item = WorkshopItemSummary(123uL, 646570u, "Example mod", "", "", authorName = "Example author")
        val quote = "Right click to highlight."
        val sessions = WorkshopSearchSessions(CoroutineScope(Dispatchers.Main))
        sessions.current.apply {
            turns += WorkshopTurn(1, "Can I use touch?", listOf(
                WorkshopAnswerBlock.Text("Touch has not been confirmed."),
                WorkshopAnswerBlock.Card(WorkshopCitation(item, quote, partial = true))))
        }
        var opened = false
        compose.setContent {
            var ai by remember { mutableStateOf(false) }
            val state = rememberLazyListState()
            MaterialTheme {
                Column {
                    WorkshopSearchTabs(ai) { ai = it }
                    if (ai) WorkshopSearchChat(sessions, state, {}, active = false) { mod ->
                        WorkshopItemCard(item = mod, downloadState = WorkshopModDownloadState.NotDownloaded,
                            onClick = { opened = true }, onDownload = {})
                    }
                }
            }
        }
        val aiTab = context.getString(R.string.market_ai_title)
        compose.onNodeWithContentDescription(aiTab).performClick()
        compose.onNodeWithText(item.title).performScrollTo().performClick()
        compose.runOnIdle { check(opened) }
        compose.onNodeWithText(context.getString(R.string.market_ai_partial)).performScrollTo().performClick()
        compose.onNodeWithText(quote, substring = true).performScrollTo().assertIsDisplayed()
        val field = context.getString(R.string.market_ai_hint)
        compose.onNodeWithContentDescription(field).performTextInput("Follow-up draft")
        compose.onNodeWithContentDescription(context.getString(R.string.market_ai_ordinary)).performClick()
        compose.onNodeWithContentDescription(field).assertDoesNotExist()
        compose.onNodeWithContentDescription(aiTab).performClick()
        compose.onNodeWithText("Follow-up draft").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.ai_mod_editor_new_chat)).performClick()
        compose.onNodeWithText("Follow-up draft").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.market_ai_welcome)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.ai_editor_sessions)).performClick()
        compose.onNodeWithText("Can I use touch?").performClick()
        compose.onNodeWithText("Follow-up draft").assertIsDisplayed()
    }
}
