package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compile offline; execution requires a separately authorized, non-destructive UI test setup. */
@RunWith(AndroidJUnit4::class)
class ModDetailsInfoInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mod = ModItemUi(
        modId = "info-layout-test", manifestModId = "manifest-info-test", storagePath = "/missing-info-test.jar",
        name = "Info Layout Test", version = "1.2", description = "A short description", fileSizeBytes = 1024,
        dependencies = listOf(" basemod ", "stslib", "basemod"), required = false,
        installed = true, enabled = true, explicitPriority = null, effectivePriority = 10,
    )

    private fun openCard(
        item: ModItemUi = mod,
        controlsEnabled: Boolean = true,
        suggestion: String? = null,
        onSuggestionRead: () -> Unit = {},
        callbacks: ModCardCallbacks = ModCardCallbacks(),
    ) {
        compose.setContent {
            MaterialTheme {
                ModCard(
                    mod = item, suggestionText = suggestion, suggestionRead = false,
                    isDraggedInOverlay = false, showModFileName = false,
                    selectionEnabled = controlsEnabled, fileActionsEnabled = controlsEnabled,
                    dragEnabled = false, showDragHandle = false, onSuggestionRead = onSuggestionRead,
                    callbacks = callbacks,
                )
            }
        }
        compose.onNodeWithText(item.name).performClick()
    }

    @Test fun infoOpensWithConciseStatusAndNoDemonstrationEntries() {
        openCard()
        compose.onNodeWithTag("mod_manager_tab_1").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.mod_details_enabled)).assertIsDisplayed()
        compose.onNodeWithTag("mod_details_section_description").assertIsDisplayed()
        compose.onNodeWithTag("mod_details_section_badges").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_details_market)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.mod_details_badge_patch)).assertDoesNotExist()
        compose.onNodeWithTag("mod_details_suggestion").assertDoesNotExist()
        compose.onNodeWithText("将随游戏一同加载").assertDoesNotExist()
        compose.onNodeWithText("按实际情况显示").assertDoesNotExist()
    }

    @Test fun technicalInformationIsCollapsedAndCanExpandAndCollapse() {
        openCard()
        compose.onNodeWithTag("mod_details_technical_content").assertDoesNotExist()
        compose.onNodeWithText(mod.storagePath).assertDoesNotExist()
        compose.onNodeWithTag("mod_details_technical_toggle").performScrollTo().performClick()
        compose.onNodeWithText(mod.manifestModId).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("#10").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("basemod, stslib").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(mod.storagePath).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mod_details_copy_path").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mod_details_technical_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("mod_details_technical_content").assertDoesNotExist()
    }

    @Test fun shortDescriptionDoesNotOfferExpansion() {
        openCard()
        compose.onNodeWithTag("mod_details_description_expand").assertDoesNotExist()
    }

    @Test fun disabledModShowsDisabledStatus() {
        openCard(item = mod.copy(enabled = false))
        compose.onNodeWithText(context.getString(R.string.mod_details_disabled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_details_enabled)).assertDoesNotExist()
    }

    @Test fun missingWorkshopFileKeepsItsStateEvenWithoutStatusDetails() {
        openCard(item = mod.copy(installed = false, enabled = false,
            workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.FileMissing)))
        compose.onNode(hasText(context.getString(R.string.main_mod_workshop_state_file_missing)) and
            hasAnyAncestor(hasTestTag("mod_details_dialog"))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mod_details_enabled)).assertDoesNotExist()
    }

    @Test fun longDescriptionCanExpandAndCollapseWithoutChangingTabs() {
        openCard(item = mod.copy(description = List(40) { "A long mod description." }.joinToString(" ")))
        compose.onNodeWithTag("mod_details_description_expand").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_collapse)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_expand)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mod_manager_tab_1").assertIsSelected()
    }

    @Test fun suggestionUsesExistingDialogAndReadCallback() {
        var read = false
        openCard(suggestion = "Read this compatibility suggestion", onSuggestionRead = { read = true })
        compose.onNodeWithTag("mod_details_suggestion").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(read) }
        compose.onNodeWithText(context.getString(R.string.main_mod_suggestion_dialog_title_format, mod.name)).assertIsDisplayed()
    }

    @Test fun aiRelationSwitchesToExistingAiTab() {
        openCard()
        compose.onNode(hasText(context.getString(R.string.mod_details_ai_title)) and
            hasAnyAncestor(hasTestTag("mod_details_section_badges"))).performScrollTo().performClick()
        compose.onNodeWithTag("mod_manager_tab_2").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.mod_details_ai_installed, 0)).assertIsDisplayed()
    }

    @Test fun marketRelationNavigatesThroughExistingCallback() {
        var selected: ModItemUi? = null
        val item = mod.copy(workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.ImportedPatched))
        openCard(item = item, callbacks = ModCardCallbacks(onOpenWorkshopDetails = { selected = it }))
        compose.onNodeWithText(context.getString(R.string.mod_details_market)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(item, selected) }
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
    }

    @Test fun compatibilityDetailsStayBehindExistingPatchEntry() {
        val patchDetails = "A detailed compatibility fix explanation"
        openCard(item = mod.copy(importPatchDetails = patchDetails))
        compose.onNodeWithText(patchDetails).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.mod_details_badge_patch)).performScrollTo().performClick()
        compose.onNodeWithText(patchDetails).assertIsDisplayed()
    }

    @Test fun favoriteUsesCurrentStateAndExistingCallback() {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(mod) }
                ModCard(
                    mod = current, isDraggedInOverlay = false, showModFileName = false,
                    selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false, showDragHandle = false,
                    callbacks = ModCardCallbacks(onSetFavorite = { _, favorite ->
                        current = current.copy(favorite = favorite)
                        changes++
                    }),
                )
            }
        }
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_favorite").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("mod_details_favorite").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, changes) }
    }

    @Test fun favoriteRemainsDisabledWhileFileActionsAreDisabled() {
        var calls = 0
        openCard(controlsEnabled = false, callbacks = ModCardCallbacks(onSetFavorite = { _, _ -> calls++ }))
        compose.onNodeWithTag("mod_details_favorite").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun closeButtonDismissesDetails() {
        openCard()
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_close)).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
    }
}
