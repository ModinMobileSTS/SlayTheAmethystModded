package io.stamethyst.ui.main

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherDialogHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Exercises the real local actions -> rename path without network or patch-file writes. */
class AgentPatchRenameInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nameAndDescriptionRemainSelectableAfterKeyboardHideAndReopen() {
        var renamed: Pair<String, String>? = null
        openRename { name, description -> renamed = name to description }

        listOf("Patch title" to "New title", "Patch description" to "New description").forEach { (old, new) ->
            val field = compose.onNode(hasSetTextAction() and hasText(old))
            field.performScrollTo().performClick()
            waitForKeyboard(field, visible = true)
            field.performTextInputSelection(TextRange(0, 5))
            field.assertIsFocused().assert(selectionIs(TextRange(0, 5)))
            hideKeyboard(field)
            field.assertIsFocused().assert(selectionIs(TextRange(0, 5)))
            // Tapping the same focused field reopens the IME; selecting must still work.
            field.performClick()
            waitForKeyboard(field, visible = true)
            field.performTextInputSelection(TextRange(0, 5))
            field.assertIsFocused().assert(selectionIs(TextRange(0, 5)))
            field.performTextInput("New")
            compose.onNode(hasSetTextAction() and hasText(new)).assertIsFocused()
        }
        hideKeyboard(compose.onNode(hasSetTextAction() and hasText("New description")))
        compose.onNodeWithText(text(R.string.main_folder_dialog_confirm)).performClick()
        compose.runOnIdle { assertEquals("New title" to "New description", renamed) }
    }

    @Test fun blankNameCannotBeSavedAndCancelDoesNotRename() {
        var renamed: Pair<String, String>? = null
        openRename { name, description -> renamed = name to description }
        val name = compose.onNode(
            hasSetTextAction() and hasText(text(R.string.main_mod_agent_patch_rename_name)),
        )
        name.performScrollTo().performClick().performTextClearance()
        name.performTextReplacement("  ")
        hideKeyboard(compose.onNode(hasSetTextAction() and hasText("  ")))
        compose.onNodeWithText(text(R.string.main_folder_dialog_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.main_folder_dialog_cancel)).performClick()
        compose.runOnIdle { assertNull(renamed) }
    }

    private fun openRename(onRename: (String, String) -> Unit) {
        val patch = AgentPatchModUi(
            patchId = "rename-test", patchModId = "test.patch", name = "Patch title",
            version = "1.0", description = "Patch description", enabled = true,
        )
        compose.setContent {
            MaterialTheme {
                LauncherDialogHost {
                    AgentPatchCatalogueDialog(
                        mod = ModItemUi(
                            modId = "test", manifestModId = "test", storagePath = "",
                            name = "Test mod", version = "1.0", description = "",
                            dependencies = emptyList(), required = false, installed = true,
                            enabled = true, explicitPriority = null, effectivePriority = null,
                        ),
                        localPatches = listOf(patch),
                        workshopId = null,
                        initialLocalActionPatchId = patch.patchId,
                        actionsOnly = true,
                        onDismiss = {},
                        onInstalled = { error("Rename must not install patches") },
                        onSetEnabled = { _, _ -> error("Rename must not toggle patches") },
                        onExport = AgentPatchFileAction { error("Rename must not export patches") },
                        onShare = AgentPatchFileAction { error("Rename must not share patches") },
                        onRename = { _, name, description -> onRename(name, description) },
                    )
                }
            }
        }
        compose.onNodeWithText(text(R.string.main_mod_agent_patch_rename)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.main_mod_agent_patch_rename_title)).assertExists()
    }

    private fun selectionIs(range: TextRange) =
        SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range)

    private fun hideKeyboard(field: SemanticsNodeInteraction) {
        val view = (field.fetchSemanticsNode().root as ViewRootForTest).view
        compose.runOnIdle {
            val input = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.hideSoftInputFromWindow(view.windowToken, 0)
        }
        waitForKeyboard(field, visible = false)
    }

    private fun waitForKeyboard(field: SemanticsNodeInteraction, visible: Boolean) {
        val view = (field.fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
