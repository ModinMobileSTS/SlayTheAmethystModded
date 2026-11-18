package io.stamethyst.ui.aimod

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated list and callbacks, never deletes production conversation files. */
@RunWith(AndroidJUnit4::class)
class AiEditorSessionListInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listHasNoSearchAndOverflowOpensDeleteForCorrectSessionWithoutSwitching() {
        var selected: String? = null
        var deleted: String? = null
        compose.setContent {
            MaterialTheme {
                AiEditorSessionList(listOf(AiEditorSession("older", title = "Older"),
                    AiEditorSession("newer", title = "Newer")), "newer", true,
                    {}, { selected = it }, { deleted = it })
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
        val newer = compose.onNodeWithText("Newer").fetchSemanticsNode().boundsInRoot
        val older = compose.onNodeWithText("Older").fetchSemanticsNode().boundsInRoot
        assertTrue(newer.top < older.top)
        val more = compose.onNodeWithTag("ai-session-actions-newer").fetchSemanticsNode().boundsInRoot
        assertTrue(more.left >= newer.right)
        compose.onNodeWithTag("ai-session-actions-older").performClick()
        compose.onNodeWithTag("ai-session-delete-older").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(selected); assertNull(deleted) }
        compose.onNodeWithTag("ai-session-delete-confirm").performClick()
        compose.runOnIdle { assertNull(selected); assertEquals("older", deleted) }
    }

    @Test fun runningOrSwitchingListCannotDeleteOrSelect() {
        compose.setContent {
            MaterialTheme {
                AiEditorSessionList(listOf(AiEditorSession("active", title = "Active")), "active", false,
                    { fail("new") }, { fail("select") }, { fail("delete") })
            }
        }
        compose.onNodeWithTag("ai-session-actions-active").assertIsNotEnabled()
        compose.onNodeWithTag("ai-session-delete-active").assertDoesNotExist()
    }
}
