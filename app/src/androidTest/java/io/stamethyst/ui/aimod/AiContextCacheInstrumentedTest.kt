package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.llm.AgentCacheStats
import io.stamethyst.backend.llm.AgentCacheUsage
import io.stamethyst.backend.llm.AgentContextState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

/** Isolated dialog fixture. Does not access AI credentials, Mod data or network. */
@RunWith(AndroidJUnit4::class)
class AiContextCacheInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun budgetDialogOnlyAllowsInputAndOutputEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme { AiContextCounter(tokens = null, modelKey = "budget-two-fields-fixture", busy = false) }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        val unknown = context.getString(R.string.ai_mod_editor_context_unknown)
        compose.onNodeWithContentDescription("$label: $unknown").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.ai_agent_budget_input_target))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_agent_budget_output_target))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun dialogShowsMeasuredCacheRatesAndUpdatesUnreportedToUnknown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "cache-ui-fixture"
        val stats = AgentCacheStats().observe(AgentCacheUsage(1000, 800, null))
        val state = mutableStateOf(AgentContextState(modelKey = key, cacheStats = stats))
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = 1000, modelKey = key, busy = false, contextState = state.value)
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        compose.onNodeWithContentDescription("$label: ${NumberFormat.getIntegerInstance().format(1000)}").performClick()
        compose.onNodeWithTag("cache-last", useUnmergedTree = true).assertTextEquals(formatAiCacheRate(0.8))
        compose.onNodeWithTag("cache-total", useUnmergedTree = true).assertTextEquals(formatAiCacheRate(0.8))
        compose.onNodeWithTag("cache-tokens", useUnmergedTree = true)
            .assertTextEquals("${formatAiTokenCount(800)} / ${formatAiTokenCount(1000)}")
        compose.onNodeWithTag("cache-coverage", useUnmergedTree = true)
            .assertTextEquals("${formatAiTokenCount(1)} / ${formatAiTokenCount(1)}")
        compose.runOnIdle { state.value = state.value.copy(cacheStats = stats.observe(AgentCacheUsage(1500, null, null))) }
        compose.onNodeWithTag("cache-last", useUnmergedTree = true).assertTextEquals("--")
        compose.onNodeWithTag("cache-total", useUnmergedTree = true).assertTextEquals(formatAiCacheRate(0.8))
    }

    @Test fun modelMismatchDoesNotShowPreviousModelsCacheRate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = null, modelKey = "new-model", busy = false,
                    contextState = AgentContextState(modelKey = "old-model",
                        cacheStats = AgentCacheStats().observe(AgentCacheUsage(1000, 1000, null))))
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        val unknown = context.getString(R.string.ai_mod_editor_context_unknown)
        compose.onNodeWithContentDescription("$label: $unknown").performClick()
        compose.onNodeWithTag("cache-last", useUnmergedTree = true).assertTextEquals("--")
        compose.onNodeWithTag("cache-total", useUnmergedTree = true).assertTextEquals("--")
    }

    @Test fun changingEndpointDoesNotShowAnOldGatewaysAccountingConvention() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = null, modelKey = "same-model", busy = false, endpoint = "RESPONSES",
                    contextState = AgentContextState(modelKey = "same-model",
                        cacheStats = AgentCacheStats(endpoint = "CHAT_COMPLETIONS")
                            .observe(AgentCacheUsage(1000, 1000, null))))
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        val unknown = context.getString(R.string.ai_mod_editor_context_unknown)
        compose.onNodeWithContentDescription("$label: $unknown").performClick()
        compose.onNodeWithTag("cache-last", useUnmergedTree = true).assertTextEquals("--")
        compose.onNodeWithTag("cache-total", useUnmergedTree = true).assertTextEquals("--")
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_normalized_badge)).assertDoesNotExist()
    }

    @Test fun explanationsAreCollapsedByDefaultAndCanBeOpenedAndClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme { AiContextCounter(tokens = 1000, modelKey = "guide-fixture", busy = false) }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        compose.onNodeWithContentDescription("$label: ${formatAiTokenCount(1000)}").performClick()
        val hint = context.getString(R.string.ai_mod_editor_cache_hint)
        compose.onNodeWithText(hint).assertDoesNotExist()
        compose.onNodeWithTag("context-explanation-toggle").performScrollTo().performClick()
        compose.onNodeWithText(hint).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-explanation-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test fun gatewayNormalizationHasShortBadgeInsteadOfAnAlwaysVisibleParagraph() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                AiContextCounter(tokens = 1000, modelKey = "normalized-fixture", busy = false,
                    contextState = AgentContextState(modelKey = "normalized-fixture",
                        lastInputTokens = 1000, lastOutputTokens = 200, compactionCount = 2,
                        cacheStats = AgentCacheStats(inputExcludesCached = true)))
            }
        }
        val label = context.getString(R.string.ai_mod_editor_context)
        compose.onNodeWithContentDescription("$label: ${formatAiTokenCount(1000)}").performClick()
        compose.onNodeWithTag("context-input", useUnmergedTree = true).assertTextEquals(formatAiTokenCount(1000))
        compose.onNodeWithTag("context-output", useUnmergedTree = true).assertTextEquals(formatAiTokenCount(200))
        compose.onNodeWithTag("context-compactions", useUnmergedTree = true).assertTextEquals(formatAiTokenCount(2))
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_normalized_badge))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ai_mod_editor_cache_normalized)).assertDoesNotExist()
    }
}
