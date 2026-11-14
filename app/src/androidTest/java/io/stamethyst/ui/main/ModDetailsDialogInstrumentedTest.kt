package io.stamethyst.ui.main

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.stamethyst.R
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import io.stamethyst.ui.LauncherDialogHost
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModDetailsDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mod = ModItemUi(
        modId = "details-test", manifestModId = "details-test", storagePath = "/missing-details-test.jar",
        name = "Details Test Mod", version = "1.4.2", fileSizeBytes = 1572864,
        description = "A test description", dependencies = listOf("basemod"),
        required = false, installed = true, enabled = true, explicitPriority = null, effectivePriority = 10,
    )

    private fun setCard(
        item: ModItemUi = mod,
        batchSelectionMode: Boolean = false,
        controlsEnabled: Boolean = true,
        onBatchChange: (Boolean) -> Unit = {},
        callbacks: ModCardCallbacks = ModCardCallbacks(),
    ) {
        compose.setContent {
            MaterialTheme {
                ModCard(
                    mod = item, suggestionText = "Usage tip", isDraggedInOverlay = false,
                    showModFileName = false, selectionEnabled = controlsEnabled, fileActionsEnabled = controlsEnabled,
                    dragEnabled = false, showDragHandle = false, batchSelectionMode = batchSelectionMode,
                    batchSelectionEnabled = batchSelectionMode, onBatchSelectionChange = onBatchChange,
                    callbacks = callbacks,
                )
            }
        }
    }

    @Test fun singleTapOpensDetailsWithMetadataAndCollapsedTechnicalInformation() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        val author = context.getString(R.string.mod_details_author, context.getString(R.string.mod_details_unknown_author))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(author)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(author).assertIsDisplayed()
        compose.onNode(hasText(mod.description) and hasAnyAncestor(hasTestTag("mod_details_dialog"))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_details_badge_suggestion)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mod_details_technical_content").assertDoesNotExist()
        compose.onNodeWithTag("mod_details_technical_toggle").performScrollTo().performClick()
        compose.onNodeWithText("#10").performScrollTo().assertIsDisplayed()
    }

    @Test fun toolsUseFullWidthSingleColumnRows() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_manager_tab_0").performClick()
        val priority = compose.onNodeWithText(context.getString(R.string.main_mod_priority_adjust))
        val binding = compose.onNodeWithText(context.getString(R.string.main_mod_associate))
        binding.performScrollTo().assertIsDisplayed()

        val priorityBounds = priority.getUnclippedBoundsInRoot()
        val bindingBounds = binding.getUnclippedBoundsInRoot()
        assertEquals(priorityBounds.left, bindingBounds.left)
        assertEquals(priorityBounds.right, bindingBounds.right)
        assertTrue(bindingBounds.top >= priorityBounds.bottom)
        compose.onNodeWithText(context.getString(R.string.main_mod_favorite_add)).assertDoesNotExist()
        compose.onNodeWithTag("mod_details_favorite").assertIsDisplayed()
    }

    @Test fun detailsWithoutArtworkKeepsPlainBackground() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        compose.onNodeWithTag("mod_details_frosted_background").assertDoesNotExist()
    }

    @Test fun deleteIsLastToolAndHeaderHasNoCloseOrDeleteMenu() {
        var deletedMod: ModItemUi? = null
        setCard(callbacks = ModCardCallbacks(onDeleteMod = { deletedMod = it }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_close)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu)).assertDoesNotExist()
        compose.onNodeWithTag("mod_details_favorite").assertIsDisplayed()

        compose.onNodeWithTag("mod_manager_tab_0").performClick()
        val delete = compose.onNode(
            hasText(context.getString(R.string.main_mod_delete)) and
                hasAnyAncestor(hasTestTag("mod_details_scroll_0")),
        )
        delete.performScrollTo().assertIsDisplayed().assertIsEnabled()
        val rename = compose.onNodeWithText(context.getString(R.string.main_mod_rename))
        assertTrue(delete.getUnclippedBoundsInRoot().top >= rename.getUnclippedBoundsInRoot().bottom)
        delete.performClick()
        compose.runOnIdle { assertEquals(mod, deletedMod) }
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
    }

    @Test fun disabledToolsCannotDelete() {
        var deleted = false
        setCard(controlsEnabled = false, callbacks = ModCardCallbacks(onDeleteMod = { deleted = true }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_manager_tab_0").performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_delete))
            .performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(!deleted) }
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
    }

    @Test fun detailsUsesCardArtworkAsFrostedBackground() {
        val previewFile = File.createTempFile("mod-details-preview-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            previewFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            setCard(item = mod.copy(workshop = WorkshopModUi(
                appId = 646570u, publishedFileId = ULong.MAX_VALUE,
                state = WorkshopModState.ImportedPatched,
                localPreviewImagePath = previewFile.absolutePath,
            )))
            compose.onNodeWithText(mod.name).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasTestTag("mod_details_frosted_background"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mod_details_frosted_background").assertIsDisplayed()
            compose.onNodeWithTag("mod_details_tab_info").assertIsDisplayed().assertIsSelected()
        } finally {
            previewFile.delete()
        }
    }

    @Test fun actionsShortcutScrollsToOriginalListAndMoveOpensFolderPicker() {
        var movedTo: String? = null
        compose.setContent {
            MaterialTheme {
                var pendingMove by remember { mutableStateOf(false) }
                ModCard(
                    mod = mod, isDraggedInOverlay = false, showModFileName = false,
                    selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false, showDragHandle = false,
                    callbacks = ModCardCallbacks(onMoveFolderPickerRequest = { pendingMove = true }),
                )
                MoveModToFolderDialog(
                    visible = pendingMove, modName = mod.name,
                    targets = listOf(ModFolderMoveTarget("current", "Current folder", true),
                        ModFolderMoveTarget("other", "Other folder", false)),
                    controlsEnabled = true, onDismiss = { pendingMove = false },
                    onSelectTarget = { movedTo = it; pendingMove = false },
                )
            }
        }
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_tab_actions").performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_actions_group_management)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_details_move)).performScrollTo().performClick()
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
        compose.onNodeWithText("Other folder").performClick()
        compose.runOnIdle { assertEquals("other", movedTo) }
    }

    @Test fun aiSwitchUsesExistingCallbackAndManagementRemainsAvailable() {
        val patch = AgentPatchModUi("patch-id", "patch-mod-id", "Test AI Mod", "1.0", "AI description", false)
        var toggled = false
        setCard(item = mod.copy(agentPatchMods = listOf(patch)),
            callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { parent, selected, enabled ->
                assertEquals(mod.modId, parent.modId)
                assertEquals(patch.patchId, selected.patchId)
                toggled = enabled
            }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, patch.name))
            .performScrollTo().performClick()
        compose.runOnIdle { assertTrue(toggled) }
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_ai_manage_description, patch.name))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_agent_patch_action_title)).assertIsDisplayed()
    }

    @Test fun disabledParentStillAllowsAiPatchToggle() {
        val patch = AgentPatchModUi("patch-id", "patch-mod-id", "Test AI Mod", "1.0", "AI description", false)
        var toggled = false
        setCard(item = mod.copy(enabled = false, agentPatchMods = listOf(patch)),
            callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { parent, selected, enabled ->
                assertEquals(mod.modId, parent.modId)
                assertTrue(!parent.enabled)
                assertEquals(patch.patchId, selected.patchId)
                toggled = enabled
            }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, patch.name))
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(toggled) }
    }

    @Test fun disabledControlsStillBlockAiPatchToggle() {
        val patch = AgentPatchModUi("patch-id", "patch-mod-id", "Test AI Mod", "1.0", "AI description", false)
        var toggled = false
        setCard(item = mod.copy(enabled = false, agentPatchMods = listOf(patch)), controlsEnabled = false,
            callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { _, _, _ -> toggled = true }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, patch.name))
            .performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(!toggled) }
    }

    @Test fun disabledActionsCannotMoveOrToggle() {
        setCard(controlsEnabled = false)
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, mod.name)).assertIsNotEnabled()
        compose.onNodeWithTag("mod_details_tab_actions").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_move)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun patchStateUpdatesKeepHostedDialogOpenInBothDirections() {
        val patch = AgentPatchModUi("patch-id", "patch-mod-id", "Toggle regression", "1.0", "Description", false)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                LauncherDialogHost {
                    var current by remember { mutableStateOf(mod.copy(agentPatchMods = listOf(patch))) }
                    ModCard(
                        mod = current, isDraggedInOverlay = false, showModFileName = false,
                        selectionEnabled = true, fileActionsEnabled = true,
                        dragEnabled = false, showDragHandle = false,
                        callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { _, selected, enabled ->
                            current = current.copy(agentPatchMods = current.agentPatchMods.map {
                                if (it.patchId == selected.patchId) it.copy(enabled = enabled) else it
                            })
                            changes++
                        }),
                    )
                }
            }
        }
        compose.onNodeWithText(mod.name).performClick()
        // The hosted window has a clickable input shield; inspect its children without
        // merging the dialog's pane semantics into that shield.
        val control = compose.onNodeWithContentDescription(
            context.getString(R.string.mod_details_toggle, patch.name), useUnmergedTree = true,
        )
        repeat(3) {
            control.performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("mod_details_dialog", useUnmergedTree = true).assertIsDisplayed()
            control.performClick().assertIsOff()
            compose.onNodeWithTag("mod_details_dialog", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(6, changes) }
    }

    @Test fun batchSelectionTapDoesNotOpenDetails() {
        var selected = false
        setCard(batchSelectionMode = true, onBatchChange = { selected = it })
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(selected) }
    }

    @Test fun backDismissesDetails() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
    }

    @Test fun secondaryTabsShowEverySectionAndNavigateWithoutHidingContent() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_tab_info").assertIsSelected()
        ModDetailsSection.entries.forEach { section ->
            compose.onNodeWithTag("mod_details_tab_${section.key}").assertIsDisplayed()
            compose.onNodeWithTag("mod_details_section_${section.key}").assertExists()
            if (section != ModDetailsSection.INFO) {
                compose.onNodeWithTag("mod_details_divider_${section.key}").assertExists()
            }
        }
        listOf(ModDetailsSection.DESCRIPTION, ModDetailsSection.BADGES, ModDetailsSection.AI,
            ModDetailsSection.ACTIONS, ModDetailsSection.INFO).forEach { section ->
            compose.onNodeWithTag("mod_details_tab_${section.key}").performClick().assertIsSelected()
            ModDetailsSection.entries.forEach {
                compose.onNodeWithTag("mod_details_section_${it.key}").assertExists()
            }
        }
    }

    @Test fun manualScrollingUpdatesSelectedTab() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 100_000f)
        }
        compose.onNodeWithTag("mod_details_tab_actions").assertIsSelected()
        compose.onNodeWithTag("mod_details_scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, -100_000f)
        }
        compose.onNodeWithTag("mod_details_tab_info").assertIsSelected()
    }

    @Test fun installSummaryBelowDependenciesIsHidden() {
        val summary = "Installed ${mod.name}"
        setCard(item = mod.copy(workshop = WorkshopModUi(
            appId = 646570u, publishedFileId = 1uL, state = WorkshopModState.ImportedPatched,
            statusText = summary,
        )))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_tab_description").performClick()
        compose.onNodeWithText(summary).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.main_mod_dependencies_format, "basemod")).assertIsDisplayed()
    }

    @Test fun cardActionsShortcutStartsAtActionsTab() {
        setCard()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions)).performClick()
        compose.onNodeWithTag("mod_details_tab_actions").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.main_mod_actions_group_management)).assertIsDisplayed()
    }

    @Test fun tabsUseUpdatedPositionsAfterExpandingDescription() {
        setCard(item = mod.copy(description = List(40) { "A long mod description." }.joinToString(" ")))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_tab_description").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_expand)).performScrollTo().performClick()
        compose.onNodeWithTag("mod_details_tab_actions").performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.main_mod_actions_group_management)).assertIsDisplayed()
        compose.onNodeWithTag("mod_details_tab_badges").performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.mod_details_badge_suggestion)).assertIsDisplayed()
    }
}
