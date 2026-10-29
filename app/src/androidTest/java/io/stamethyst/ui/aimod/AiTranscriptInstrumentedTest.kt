package io.stamethyst.ui.aimod

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiTranscriptInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTurnDoesNotComposeItsOffscreenTail() {
        val rows = buildAiTranscriptRows(AiEditorMessage(2, false,
            "start\n" + "long paragraph\n".repeat(10_000) + "\nunique-tail"))
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.testTag("transcript")) {
                    items(rows, key = { it.key }) { row ->
                        AiMessageCard(row.message, {}, {}, {}, {}, true,
                            showFooter = row.footer, footerOnly = row.footer)
                    }
                }
            }
        }
        compose.onNodeWithText("unique-tail", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("transcript").performScrollToIndex(rows.lastIndex - 1)
        compose.onNodeWithText("unique-tail", substring = true).assertExists()
    }

    @Test fun emptyStreamingAssistantKeepsLoadingIndicatorInTheContentRow() {
        val rows = buildAiTranscriptRows(AiEditorMessage(2, false, "", streaming = true))
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.testTag("transcript")) {
                    items(rows, key = { it.key }) { row ->
                        AiMessageCard(row.message, {}, {}, {}, {}, true,
                            showFooter = row.footer, footerOnly = row.footer)
                    }
                }
            }
        }
        compose.onNodeWithTag("ai-assistant-loading").assertIsDisplayed()
    }
}
