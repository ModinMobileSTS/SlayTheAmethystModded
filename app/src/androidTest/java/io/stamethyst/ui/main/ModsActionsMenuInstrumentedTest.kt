package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModsActionsMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun filtersAreInSubmenuAndCanBeCombined() {
        val enabledOnly = mutableStateOf(false)
        val updatesOnly = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = true,
                    dragLocked = true,
                    showEnabledModsOnly = enabledOnly.value,
                    showUpdateAvailableModsOnly = updatesOnly.value,
                    feedbackUnreadCount = 0,
                    checkingUpdates = false,
                    onToggleDragLocked = {},
                    onShowEnabledModsOnlyChange = { enabledOnly.value = it },
                    onShowUpdateAvailableModsOnlyChange = { updatesOnly.value = it },
                    onAddFolderClick = {},
                    onCheckWorkshopUpdates = {},
                    onOpenFeedbackUpdates = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_action_add_folder)).assertDoesNotExist()
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_action_add_folder)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.community_patches_view_all)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_mod_check_updates)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_enabled)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_enabled)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_update_available)).performClick()
        compose.runOnIdle {
            assertTrue(enabledOnly.value)
            assertTrue(updatesOnly.value)
        }
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_action_add_folder)).assertIsDisplayed()
    }

    @Test fun unavailableActionsAreDisabledButFiltersRemainAccessible() {
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = false,
                    dragLocked = true,
                    showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false,
                    feedbackUnreadCount = 3,
                    checkingUpdates = true,
                    onToggleDragLocked = { error("Disabled action") },
                    onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {},
                    onAddFolderClick = { error("Disabled action") },
                    onCheckWorkshopUpdates = { error("Disabled action") },
                    onOpenFeedbackUpdates = {},
                )
            }
        }
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_workshop_checking)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.community_patches_view_all)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_action_unlock_drag)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_action_add_folder)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_feedback_updates_content_description)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_filter_enabled)).assertIsDisplayed()
    }

    @Test fun actionsInvokeCallbacksAndDismissMenu() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = true,
                    dragLocked = false,
                    showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false,
                    feedbackUnreadCount = 2,
                    checkingUpdates = false,
                    onToggleDragLocked = { actions.add("lock") },
                    onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {},
                    onAddFolderClick = { actions.add("folder") },
                    onCheckWorkshopUpdates = { actions.add("updates") },
                    onOpenFeedbackUpdates = { actions.add("feedback") },
                    onOpenCommunityPatches = { actions.add("community") },
                )
            }
        }
        listOf(
            R.string.main_action_lock_drag,
            R.string.main_action_add_folder,
            R.string.main_mod_check_updates,
            R.string.main_feedback_updates_content_description,
            R.string.community_patches_view_all,
        ).forEach { label ->
            compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
            compose.onNodeWithText(context.getString(label)).performClick()
            compose.onNodeWithText(context.getString(R.string.main_mod_filter_menu)).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(listOf("lock", "folder", "updates", "feedback", "community"), actions) }
    }
}
