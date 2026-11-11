package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModToolsMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun toolsHideChecksMoveAndDeleteButKeepManagementActions() {
        compose.setContent {
            MaterialTheme {
                ModActionsList(
                    controlsEnabled = true, favorite = false, deleteEnabled = true,
                    onFavoriteChange = {}, onEditPriority = {}, showOpenWorkshopDetails = false,
                    onOpenWorkshopDetails = {}, showAiEditorNewBadge = false, onOpenAiEditor = {},
                    onAssociate = {}, onExport = {}, onShare = {}, onRename = {}, onDelete = {},
                    showMove = true, onCheckUpdates = {}, onCheckCommunity = {},
                )
            }
        }
        listOf(R.string.mod_check_updates_single, R.string.mod_check_community_single,
            R.string.mod_details_move, R.string.main_mod_delete).forEach {
            compose.onNodeWithText(context.getString(it)).assertDoesNotExist()
        }
        listOf(R.string.main_mod_favorite_add, R.string.main_mod_priority_adjust,
            R.string.main_mod_associate, R.string.main_mod_export, R.string.main_mod_share).forEach {
            compose.onNodeWithText(context.getString(it)).assertExists()
        }
    }

    @Test fun deleteRequiresOpeningOverflowBeforeDispatch() {
        var deletes = 0
        compose.setContent { MaterialTheme { ModDeleteOverflow(true) { deletes++ } } }
        compose.onNodeWithText(context.getString(R.string.main_mod_delete)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText(context.getString(R.string.main_mod_delete)).performClick()
        assertEquals(1, deletes)
    }

    @Test fun normalMenuHidesSelectionActionsAndBothChecks() {
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = true, dragLocked = false, showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false, feedbackUnreadCount = 0, checkingUpdates = false,
                    onToggleDragLocked = {}, onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {}, onAddFolderClick = {},
                    onCheckWorkshopUpdates = {}, onOpenFeedbackUpdates = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_action_add_folder)).assertExists()
        listOf(R.string.mod_check_updates_all, R.string.mod_check_community_all,
            R.string.main_mod_export, R.string.main_mod_delete).forEach {
            compose.onNodeWithText(context.getString(it)).assertDoesNotExist()
        }
    }

    @Test fun disabledDeleteCannotDispatch() {
        var deletes = 0
        compose.setContent { MaterialTheme { ModDeleteOverflow(false) { deletes++ } } }
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_delete)).assertIsNotEnabled()
        assertEquals(0, deletes)
    }

    @Test fun singleSelectionDisablesGroupAssociationButDispatchesExport() {
        var exports = 0
        val mod = ModItemUi(
            modId = "menu-test", manifestModId = "menu-test", storagePath = "/menu-test.jar",
            name = "Menu Test", version = "1", fileSizeBytes = 0,
            description = "", dependencies = emptyList(), required = false,
            installed = true, enabled = false, explicitPriority = null, effectivePriority = null,
        )
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = true, dragLocked = false, showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false, feedbackUnreadCount = 0, checkingUpdates = false,
                    onToggleDragLocked = {}, onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {}, onAddFolderClick = {},
                    onCheckWorkshopUpdates = {}, onOpenFeedbackUpdates = {}, selectedMods = listOf(mod),
                    batchActions = BatchEditBarState(
                        selectedMods = listOf(mod), selectedCount = 1, controlsEnabled = true,
                        onMove = {}, onDelete = {}, onEnable = {}, onDisable = {}, onCancel = {},
                        onExport = { exports++ },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_associate)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_favorite_remove)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.main_mod_export)).performClick()
        assertEquals(1, exports)
    }

    @Test fun bottomToolbarKeepsMoveButNotDelete() {
        compose.setContent {
            MaterialTheme {
                BatchEditToolbar(selectedCount = 2, controlsEnabled = true,
                    onMove = {}, onDelete = {}, onEnable = {}, onDisable = {}, onCancel = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_batch_action_move)).assertExists()
        compose.onNodeWithText(context.getString(R.string.main_batch_action_delete)).assertDoesNotExist()
    }

    @Test fun unassociatedModCannotRemoveAssociation() {
        compose.setContent {
            MaterialTheme {
                ModActionsList(
                    controlsEnabled = true, favorite = false, deleteEnabled = true,
                    onFavoriteChange = {}, onEditPriority = {}, showOpenWorkshopDetails = false,
                    onOpenWorkshopDetails = {}, showAiEditorNewBadge = false, onOpenAiEditor = {},
                    onAssociate = {}, onExport = {}, onShare = {}, onRename = {}, onDelete = {},
                    associationPresent = false,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.main_mod_associate)).assertExists()
        compose.onNodeWithText(context.getString(R.string.mod_remove_association)).assertIsNotEnabled()
    }

    @Test fun selectionContainingAssociatedModDisablesGroupingButAllowsRemoval() {
        var associations = 0
        var removals = 0
        val first = ModItemUi(
            modId = "first", manifestModId = "first", storagePath = "/first.jar",
            name = "First", version = "1", fileSizeBytes = 0, description = "",
            dependencies = emptyList(), required = false, installed = true, enabled = false,
            explicitPriority = null, effectivePriority = null,
        )
        val selected = listOf(first, first.copy(modId = "second", storagePath = "/second.jar"))
        compose.setContent {
            MaterialTheme {
                ModsActionsMenu(
                    canEditFolders = true, dragLocked = false, showEnabledModsOnly = false,
                    showUpdateAvailableModsOnly = false, feedbackUnreadCount = 0, checkingUpdates = false,
                    onToggleDragLocked = {}, onShowEnabledModsOnlyChange = {},
                    onShowUpdateAvailableModsOnlyChange = {}, onAddFolderClick = {},
                    onCheckWorkshopUpdates = {}, onOpenFeedbackUpdates = {}, selectedMods = selected,
                    batchActions = BatchEditBarState(
                        selectedMods = selected, selectedCount = 2, controlsEnabled = true,
                        onMove = {}, onDelete = {}, onEnable = {}, onDisable = {}, onCancel = {},
                        associationPresent = true, onAssociate = { associations++ },
                        onRemoveAssociation = { removals++ },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_associate)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.mod_remove_association)).performClick()
        assertEquals(0, associations)
        assertEquals(1, removals)
    }

    @Test fun associatedModDispatchesRemoveSeparatelyFromAssociate() {
        var removals = 0
        var associations = 0
        compose.setContent {
            MaterialTheme {
                ModActionsList(
                    controlsEnabled = true, favorite = false, deleteEnabled = true,
                    onFavoriteChange = {}, onEditPriority = {}, showOpenWorkshopDetails = false,
                    onOpenWorkshopDetails = {}, showAiEditorNewBadge = false, onOpenAiEditor = {},
                    onAssociate = { associations++ }, onExport = {}, onShare = {}, onRename = {}, onDelete = {},
                    associationPresent = true, onRemoveAssociation = { removals++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.mod_remove_association)).performClick()
        assertEquals(1, removals)
        assertEquals(0, associations)
    }
}
