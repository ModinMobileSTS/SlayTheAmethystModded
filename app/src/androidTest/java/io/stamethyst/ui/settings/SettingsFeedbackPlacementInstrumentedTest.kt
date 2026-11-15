package io.stamethyst.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.navigation.Route
import io.stamethyst.ui.settings.common.SettingsHomeDestinations
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.sections.LauncherSettingsFeedbackScreenContent
import io.stamethyst.ui.settings.sections.LauncherSettingsScreenContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only callbacks: never submit reports or perform file operations. */
@RunWith(AndroidJUnit4::class)
class SettingsFeedbackPlacementInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun feedbackCardIsTheLastHomeDestination() {
        assertEquals(Route.Feedback, SettingsHomeDestinations.last().route)
        assertEquals(1, SettingsHomeDestinations.count { it.route == Route.Feedback })
    }

    @Test
    fun homeFeedbackCardOpensAllThreeDestinationsDirectly() {
        val navigations = mutableListOf<Route>()
        compose.setContent {
            MaterialTheme {
                LauncherSettingsScreenContent(
                    uiState = SettingsScreenViewModel.UiState(),
                    onOpenSettingsRoute = navigations::add,
                )
            }
        }

        feedbackDestinations.forEach { (title, _) ->
            val text = context.getString(title)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals(feedbackDestinations.map { it.second }, navigations)
        }
    }

    @Test
    fun busyHomeDisablesAllFeedbackActions() {
        compose.setContent {
            MaterialTheme {
                LauncherSettingsScreenContent(
                    uiState = SettingsScreenViewModel.UiState(busy = true),
                )
            }
        }

        feedbackDestinations.forEach { (title, _) ->
            val text = context.getString(title)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsNotEnabled()
        }
    }

    @Test
    fun searchGroupsFeedbackSeparatelyAndOpensTheFeatureDirectly() {
        val navigations = mutableListOf<Route>()
        compose.setContent {
            MaterialTheme {
                LauncherSettingsScreenContent(
                    uiState = SettingsScreenViewModel.UiState(),
                    onOpenSettingsRoute = navigations::add,
                )
            }
        }

        compose.onNode(hasSetTextAction())
            .performTextInput(context.getString(R.string.settings_feedback_entry_subscriptions))
        val text = context.getString(R.string.settings_feedback_entry_subscriptions)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(context.getString(R.string.settings_feedback_entry_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_feedback_logs_title)).assertDoesNotExist()
        compose.onNodeWithText(text).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Route.FeedbackSubscriptions), navigations)
        }
    }

    @Test
    fun filesPageKeepsFileOperationsWithoutFeedbackCard() {
        compose.setContent {
            MaterialTheme {
                LauncherSettingsFeedbackScreenContent(uiState = SettingsScreenViewModel.UiState())
            }
        }

        val text = context.getString(R.string.settings_mod_operations)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_feedback_entry_title)).assertDoesNotExist()
        feedbackDestinations.forEach { (title, _) ->
            compose.onNodeWithText(context.getString(title)).assertDoesNotExist()
        }
    }

    private val feedbackDestinations = listOf(
        R.string.settings_feedback_entry_new to Route.Feedback,
        R.string.settings_feedback_entry_subscriptions to Route.FeedbackSubscriptions,
        R.string.settings_feedback_entry_issue_browser to Route.FeedbackIssueBrowser,
    )
}
