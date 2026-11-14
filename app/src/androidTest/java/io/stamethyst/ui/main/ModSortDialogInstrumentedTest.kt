package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ModSortDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalOrder = emptyList<String>()

    @Before fun saveOrder() { originalOrder = ModDisplayOrder(context).read() }
    @After fun restoreOrder() { ModDisplayOrder(context).save(originalOrder) }

    @Test fun menuOpensDropdownAndRequiresSecondConfirmation() {
        var applied: ModSortOrder? = null
        compose.setContent {
            MaterialTheme {
                var showing by remember { mutableStateOf(false) }
                ModsActionsMenu(
                    canEditFolders = true, dragLocked = false, showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false, feedbackUnreadCount = 0, checkingUpdates = false,
                    onToggleDragLocked = {}, onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {}, onAddFolderClick = {},
                    onCheckWorkshopUpdates = {}, onOpenFeedbackUpdates = {},
                    onSortModsClick = { showing = true },
                )
                if (showing) ModSortDialog(true, { showing = false }) { applied = it; showing = false }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_sort)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_sort_overwrite_confirmation)).assertDoesNotExist()
        compose.onNodeWithTag("mod_sort_order_dropdown").performClick()
        listOf(R.string.main_mod_sort_name_descending, R.string.main_mod_sort_modified_newest,
            R.string.main_mod_sort_modified_oldest).forEach {
            compose.onNodeWithText(context.getString(it)).assertExists()
        }
        compose.onNodeWithText(context.getString(R.string.main_mod_sort_modified_oldest)).performClick()
        confirm()
        compose.runOnIdle { assertEquals(null, applied) }
        compose.onNodeWithText(context.getString(R.string.main_mod_sort_overwrite_confirmation)).assertExists()
        confirm()
        compose.runOnIdle { assertEquals(ModSortOrder.MODIFIED_OLDEST_FIRST, applied) }
    }

    @Test fun cancellingEitherStepDoesNotApplySort() {
        var applies = 0
        compose.setContent {
            MaterialTheme {
                var showing by remember { mutableStateOf(true) }
                if (showing) ModSortDialog(true, { showing = false }) { applies++ }
                androidx.compose.material3.TextButton(onClick = { showing = true }) { androidx.compose.material3.Text("Reopen") }
            }
        }
        cancel()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithText("Reopen").performClick()
        confirm()
        cancel()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithText(context.getString(R.string.main_mod_sort)).assertDoesNotExist()
    }

    @Test fun disabledControlsCannotConfirm() {
        compose.setContent { MaterialTheme { ModSortDialog(false, {}, { error("Must not apply") }) } }
        compose.onNodeWithTag("mod_sort_order_dropdown").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.common_action_confirm)).assertIsNotEnabled()
    }

    @Test fun hostOnlyOverwritesPersistedOrderAfterSecondConfirmation() {
        val store = ModDisplayOrder(context)
        store.save(listOf("z", "a", "obsolete"))
        compose.setContent {
            MaterialTheme {
                var showing by remember { mutableStateOf(true) }
                if (showing) ModSortDialogHost(listOf(mod("z", "Zulu"), mod("a", "Alpha")), true) { showing = false }
            }
        }
        confirm()
        compose.runOnIdle { assertEquals(listOf("z", "a", "obsolete"), store.read()) }
        confirm()
        compose.waitUntil(5_000) { store.read() == listOf("a", "z") }
        compose.onNodeWithText(context.getString(R.string.main_mod_sort)).assertDoesNotExist()
    }

    @Test fun displayOrderObserversReceiveExternalWritesAndCanUnsubscribe() {
        val store = ModDisplayOrder(context)
        val observed = mutableListOf<List<String>>()
        lateinit var stop: () -> Unit
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            stop = store.observe { observed.add(it) }
            ModDisplayOrder(context).save(listOf("sorted", "paths"))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(listOf("sorted", "paths"), observed.last())
        val count = observed.size
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            stop()
            ModDisplayOrder(context).save(listOf("after-unsubscribe"))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(count, observed.size)
    }

    private fun confirm() = compose.onNodeWithText(context.getString(R.string.common_action_confirm)).performClick()
    private fun cancel() = compose.onNodeWithText(context.getString(R.string.main_folder_dialog_cancel)).performClick()
    private fun mod(path: String, name: String) = ModItemUi(
        modId = path, manifestModId = path, storagePath = path, name = name, version = "1",
        description = "", dependencies = emptyList(), required = false, installed = true,
        enabled = false, explicitPriority = null, effectivePriority = null,
    )
}
