package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentQuestion
import io.stamethyst.backend.llm.AgentQuestionOption
import io.stamethyst.backend.llm.AgentQuestionRequest
import io.stamethyst.backend.llm.LlmReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiQuestionDockInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun questionDockPreservesDraftAndSubmitsAllPagesOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val draft = "An unsent draft"
        val question = mutableStateOf<AgentQuestionRequest?>(AgentQuestionRequest("request-1", listOf(
            AgentQuestion("Direction", "Which direction?", listOf(AgentQuestionOption("North", "First"))),
            AgentQuestion("Extras", "Which extras?", listOf(AgentQuestionOption("Map", "Second")), multiple = true),
        )))
        val responses = mutableListOf<Pair<String, List<List<String>>>>()
        compose.setContent {
            MaterialTheme {
                AiChatComposer(
                    draft = draft, onDraftChange = { error("Answer must not change the chat draft") },
                    attachments = emptyList(), onRemoveAttachment = {}, onAttach = null,
                    configured = true, busy = question.value != null, modelName = "test", models = listOf("test"),
                    effort = LlmReasoningEffort.LOW, onEffortChange = {}, onModelSelect = {}, onStop = {},
                    onSend = { error("Answer must not send a chat message") }, hazeState = rememberHazeState(),
                    onComposerHeightChanged = {}, pendingQuestion = question.value,
                    onQuestionReply = { id, answers -> responses.add(id to answers); question.value = null; true },
                )
            }
        }
        val next = compose.onNodeWithText(context.getString(R.string.ai_question_next))
        next.assertIsNotEnabled()
        compose.onNodeWithText("North").performScrollTo().performClick()
        next.performClick()
        compose.onNodeWithText("Map").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ai_question_custom)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ai_question_custom_hint)).performScrollTo().performTextInput("Compass")
        compose.onNodeWithText(context.getString(R.string.ai_question_previous)).performClick()
        next.performClick()
        compose.onNodeWithText(context.getString(R.string.ai_question_submit)).performClick()
        compose.onNodeWithText(draft).assertExists()
        compose.runOnIdle {
            assertEquals(listOf("request-1" to listOf(listOf("North"), listOf("Map", "Compass"))), responses)
        }
    }
}
