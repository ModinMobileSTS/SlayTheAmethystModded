package io.stamethyst.ui.aimod

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentExecutionStep
import io.stamethyst.backend.llm.AgentStepKind
import io.stamethyst.backend.llm.AgentStepStatus
import io.stamethyst.backend.llm.agentErrorStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated UI: no credentials, API calls, installed mods or production conversation writes. */
@RunWith(AndroidJUnit4::class)
class AiExecutionHistoryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun speedEntryOpensHistoryAndCloseDismissesIt() {
        val snapshot = fixture()
        compose.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                Box {
                    AiHistorySpeedButton(42.5) { open = true }
                    if (open) AiExecutionHistoryPanel(snapshot) { open = false }
                }
            }
        }
        compose.onNodeWithText("42.5 token/s").assertIsDisplayed()
        compose.onNodeWithTag("ai-history-speed").performClick()
        compose.onNodeWithTag("ai-history-chart").assertIsDisplayed()
        compose.onNodeWithTag("ai-history-total-tokens", useUnmergedTree = true).assertTextEquals(formatAiTokenCount(150))
        compose.onNodeWithTag("ai-history-close").performClick()
        compose.onNodeWithTag("ai-history-list").assertDoesNotExist()
    }

    @Test fun turnsAndStepsExpandWithAccurateMetrics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { AiExecutionHistoryPanel(fixture()) {} } }
        val step = "ai-history-step-2:model"
        compose.onNodeWithTag("ai-history-list").performScrollToNode(hasTestTag(step))
        compose.onNodeWithTag(step).performClick()
        compose.onNodeWithText(context.getString(R.string.ai_history_input)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatAiTokenCount(120)).assertIsDisplayed()
        compose.onNodeWithText(formatAiTokenCount(30)).assertIsDisplayed()
        compose.onNodeWithTag("ai-history-turn-1").performScrollTo().performClick()
        compose.onNodeWithTag(step).assertDoesNotExist()
    }

    @Test fun aLongTurnKeepsStepsLazyAndLastStepReachable() {
        val snapshot = fixture(2_000)
        compose.setContent { MaterialTheme { AiExecutionHistoryPanel(snapshot) {} } }
        compose.onNodeWithTag("ai-history-step-2:tool-1999").assertDoesNotExist()
        compose.onNodeWithTag("ai-history-list").performScrollToNode(hasTestTag("ai-history-step-2:tool-1999"))
        compose.onNodeWithTag("ai-history-step-2:tool-1999").assertIsDisplayed()
    }

    @Test fun emptyHistoryShowsUsefulEmptyState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { AiExecutionHistoryPanel(AiHistorySnapshot()) {} } }
        compose.onNodeWithText(context.getString(R.string.ai_history_empty)).assertIsDisplayed()
        compose.onNodeWithTag("ai-history-list").assertDoesNotExist()
    }

    @Test fun errorNodeShowsItsCauseAndAssociatedAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = AiHistoryBuilder().build(listOf(
            AiEditorMessage(1, true, "Read source"),
            AiEditorMessage(2, false, "", executionSteps = listOf(
                AgentExecutionStep("request", AgentStepKind.MODEL, startedAtMs = 1_000,
                    elapsedMs = 50, status = AgentStepStatus.FAILED),
                agentErrorStep("MODEL", "IOException: connection reset", "request"),
            )),
        ))
        compose.setContent { MaterialTheme { AiExecutionHistoryPanel(snapshot) {} } }
        val tag = "ai-history-step-2:request:error"
        compose.onNodeWithTag("ai-history-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithText("IOException: connection reset").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_history_error_source, "MODEL")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_history_related_step, 1)).performScrollTo().assertIsDisplayed()
    }

    private fun fixture(tools: Int = 1): AiHistorySnapshot = AiHistoryBuilder().build(listOf(
        AiEditorMessage(1, true, "Inspect and update the card"),
        AiEditorMessage(2, false, "Done", modelName = "test-model", executionSteps = listOf(
            AgentExecutionStep("model", AgentStepKind.MODEL, detail = "Inspect the card implementation",
                startedAtMs = 1_000, elapsedMs = 1_500, status = AgentStepStatus.COMPLETED, inputTokens = 120, outputTokens = 30),
        ) + List(tools) { index ->
            AgentExecutionStep("tool-$index", AgentStepKind.TOOL, "read_workspace_file", "src/Card$index.java",
                2_500 + index * 100L, 100, AgentStepStatus.COMPLETED, 0, 0)
        }),
    ))
}
