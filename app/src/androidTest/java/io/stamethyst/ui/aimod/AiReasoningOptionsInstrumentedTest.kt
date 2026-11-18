package io.stamethyst.ui.aimod

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.stamethyst.R
import io.stamethyst.backend.llm.LlmReasoningEffort
import io.stamethyst.ui.settings.llm.LlmReasoningOptions
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated controls: no credentials, network or production settings writes. */
@RunWith(AndroidJUnit4::class)
class AiReasoningOptionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuKeepsBackArrowWithoutDuplicateReturnToChatAction() {
        var returned = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiEditorMenuLayout(0, "ExampleMod", { returned = true }) { }
            }
        }
        listOf("返回对话", "返回對話", "Back to chat").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.common_content_desc_back))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(returned) }
    }

    @Test fun menuTabsAndHorizontalSwipeStayInSyncWithoutExtraHintText() {
        compose.setContent {
            MaterialTheme {
                AiEditorMenuLayout(0, "ExampleMod", {}) { page ->
                    androidx.compose.material3.Text("Page $page")
                }
            }
        }
        compose.onNodeWithTag("ai-editor-tab-0").assertIsSelected()
        compose.onNodeWithTag("ai-editor-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("ai-editor-tab-1").assertIsSelected()
        compose.onNodeWithTag("ai-editor-tab-2").performScrollTo().performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithTag("ai-editor-tab-2").assertIsSelected()
        compose.onNodeWithText("左右滑动切换页面").assertDoesNotExist()
    }

    @Test fun multiSelectionControlsWhichSingleChoiceOptionsAreShown() {
        compose.setContent {
            MaterialTheme {
                var options by remember { mutableStateOf(listOf(LlmReasoningEffort.LOW)) }
                var effort by remember { mutableStateOf(LlmReasoningEffort.LOW) }
                Column {
                    LlmReasoningOptions(options) {
                        options = it
                        effort = resolveAiReasoningEffort(effort, it)
                    }
                    AiReasoningEffortPicker(effort, options, { effort = it }, {}, true, remember { HazeState() })
                }
            }
        }
        compose.onNodeWithTag("ai-reasoning-option-xhigh").performClick().assertIsSelected()
        compose.onNodeWithTag("ai-reasoning-option-low").assertIsSelected()
        compose.onNodeWithTag("ai-reasoning-picker").performClick()
        compose.onNodeWithTag("ai-reasoning-medium").assertDoesNotExist()
        compose.onNodeWithTag("ai-reasoning-xhigh").performClick()
        compose.onNodeWithTag("ai-reasoning-xhigh").assertIsSelected()
        compose.onNodeWithTag("ai-reasoning-picker").assertTextContains("xhigh")
        compose.onNodeWithTag("ai-reasoning-configure").performClick()
        compose.onNodeWithTag("ai-reasoning-option-xhigh").performClick().assertIsNotSelected()
        compose.onNodeWithTag("ai-reasoning-picker").assertTextContains("low")
        compose.onNodeWithTag("ai-reasoning-option-low").performClick().assertIsSelected()
        compose.onNodeWithText("已选5项·可多选").assertDoesNotExist()
    }

    @Test fun singleTierHasDisabledSliderAndSelectableLabel() {
        compose.setContent {
            MaterialTheme {
                AiReasoningEffortPicker(LlmReasoningEffort.MEDIUM, listOf(LlmReasoningEffort.MEDIUM),
                    {}, {}, true, remember { HazeState() })
            }
        }
        compose.onNodeWithTag("ai-reasoning-picker").performClick()
        compose.onNodeWithTag("ai-reasoning-slider").assertIsNotEnabled()
        compose.onNodeWithTag("ai-reasoning-medium").assertIsSelected().performClick()
        compose.onNodeWithTag("ai-reasoning-low").assertDoesNotExist()
    }

    @Test fun sliderAndLabelsSupportAllSevenPresets() {
        compose.setContent {
            MaterialTheme {
                var effort by remember { mutableStateOf(LlmReasoningEffort.LOW) }
                AiReasoningEffortPicker(effort, LlmReasoningEffort.selectable, { effort = it },
                    {}, true, remember { HazeState() })
            }
        }
        compose.onNodeWithTag("ai-reasoning-picker").performClick()
        LlmReasoningEffort.selectable.forEach {
            compose.onNodeWithTag("ai-reasoning-${it.name.lowercase()}").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("ai-reasoning-slider").performScrollTo()
            .performTouchInput { swipeRight() }
        compose.onNodeWithTag("ai-reasoning-max").assertIsSelected()
        compose.onNodeWithTag("ai-reasoning-minimal").performClick().assertIsSelected()
        compose.onNodeWithTag("ai-reasoning-picker").assertTextContains("minimal")
    }

    @Test fun composerKeepsOriginalRowsAndContextImmediatelyLeftOfSpeed() {
        compose.setContent {
            MaterialTheme {
                AiChatComposer(
                    draft = "", onDraftChange = {}, attachments = emptyList(), onRemoveAttachment = {},
                    onAttach = {}, configured = true, busy = false, modelName = "Example Model",
                    models = listOf("Example Model"), effort = LlmReasoningEffort.LOW, onEffortChange = {},
                    onModelSelect = {}, onStop = {}, onSend = {}, hazeState = remember { HazeState() },
                    onComposerHeightChanged = {},
                    contextCounter = { androidx.compose.material3.Text("Context", Modifier.testTag("test-context")) },
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        val picker = compose.onNodeWithTag("ai-reasoning-picker").fetchSemanticsNode().boundsInRoot
        val context = compose.onNodeWithTag("test-context").fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithTag("ai-history-speed").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithText("Example Model").fetchSemanticsNode().boundsInRoot
        assertTrue("Reasoning stays on the upper action row", picker.bottom <= model.top)
        assertTrue("Model and speed stay on the lower row", model.center.y in speed.top..speed.bottom)
        assertTrue("Context precedes speed", context.right <= speed.left)
        assertTrue("Context shares speed row", context.center.y in speed.top..speed.bottom)
    }
}
