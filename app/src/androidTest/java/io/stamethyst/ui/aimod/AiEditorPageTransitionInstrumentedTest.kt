package io.stamethyst.ui.aimod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated transition: no credentials, models, devices or conversation writes. */
@RunWith(AndroidJUnit4::class)
class AiEditorPageTransitionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingSlidesMenuInFromRightAndReturningReversesIt() {
        val menuPage = mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                AiEditorPageTransition(menuPage.value, Modifier.testTag("transition-host")) { page ->
                    Box(Modifier.fillMaxSize().testTag(if (page == null) "chat-page" else "menu-page"))
                }
            }
        }
        compose.onNodeWithTag("chat-page").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { menuPage.value = 2 }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        val host = compose.onNodeWithTag("transition-host").fetchSemanticsNode().boundsInRoot
        val entering = compose.onNodeWithTag("menu-page").fetchSemanticsNode().boundsInRoot
        assertTrue(entering.left > host.left && entering.left < host.right)
        compose.onNodeWithTag("chat-page").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("chat-page").assertDoesNotExist()
        assertEquals(host.left, compose.onNodeWithTag("menu-page").fetchSemanticsNode().boundsInRoot.left, 1f)

        compose.runOnIdle { menuPage.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        val returning = compose.onNodeWithTag("chat-page").fetchSemanticsNode().boundsInRoot
        val leaving = compose.onNodeWithTag("menu-page").fetchSemanticsNode().boundsInRoot
        assertTrue(returning.right > host.left && returning.right < host.right)
        assertTrue(leaving.left > host.left && leaving.left < host.right)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("menu-page").assertDoesNotExist()
        compose.onNodeWithTag("chat-page").assertIsDisplayed()
    }

    @Test fun shortcutDestinationAndRapidReturnResolveToLatestState() {
        val menuPage = mutableStateOf<Int?>(null)
        compose.setContent {
            MaterialTheme {
                AiEditorPageTransition(menuPage.value) { page ->
                    Text(page?.let { "Menu $it" } ?: "Chat", Modifier.testTag("destination"))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { menuPage.value = 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { menuPage.value = null }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("destination").assertTextEquals("Chat")
        compose.runOnIdle { menuPage.value = 2 }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("destination").assertTextEquals("Menu 2")
    }
}
