package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
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
import io.stamethyst.config.ModPageLayout
import io.stamethyst.model.AgentPatchModUi
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModCommunityBadgeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mod = ModItemUi(
        modId = "community-navigation-test", manifestModId = "community-navigation-test",
        storagePath = "/missing-community-navigation-test.jar",
        name = "Community Navigation", version = "1.0", description = "", dependencies = emptyList(),
        required = false, installed = true, enabled = false,
        explicitPriority = null, effectivePriority = null, hasAgentPatchCatalogueEntries = true,
    )

    private fun setCard(
        layout: ModPageLayout = ModPageLayout.COMPACT,
        item: ModItemUi = mod,
        callbacks: ModCardCallbacks = ModCardCallbacks(),
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalModPageLayout provides layout) {
                    ModCard(
                        mod = item, isDraggedInOverlay = false, showModFileName = false,
                        selectionEnabled = true, fileActionsEnabled = true,
                        dragEnabled = false, showDragHandle = false, callbacks = callbacks,
                    )
                }
            }
        }
    }

    private fun assertCommunitySelected() {
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        compose.onNodeWithTag("mod_manager_tab_2").assertIsSelected()
        compose.onNode(hasText(context.getString(R.string.mod_details_ai_community), substring = true)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.mod_details_ai_installed, 0)).assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.main_mod_agent_patch_catalogue_title, mod.name)).assertDoesNotExist()
    }

    @Test fun compactBadgeOpensCommunityInDetails() {
        setCard()
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_agent_patch_github_tab)).performClick()
        assertCommunitySelected()
        compose.onNodeWithText(context.getString(R.string.mod_details_done)).performClick()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_manager_tab_0").assertIsSelected()
        compose.onNodeWithTag("mod_manager_tab_2").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_ai_installed, 0)).assertIsSelected()
    }

    @Test fun classicBadgeOpensCommunityInDetails() {
        setCard(layout = ModPageLayout.CLASSIC)
        compose.onNodeWithContentDescription(context.getString(R.string.main_mod_agent_patch_github_tab)).performClick()
        assertCommunitySelected()
    }

    @Test fun detailsRelationSwitchesFromInstalledToCommunity() {
        setCard()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_manager_tab_2").performClick()
        compose.onNodeWithText(context.getString(R.string.mod_details_ai_installed, 0)).assertIsSelected()
        compose.onNodeWithTag("mod_manager_tab_1").performClick()
        compose.onNode(
            hasText(context.getString(R.string.main_mod_agent_patch_badge)) and
                hasAnyAncestor(hasTestTag("mod_details_section_badges")),
        ).performScrollTo().performClick()
        assertCommunitySelected()
    }

    @Test fun disabledParentCanStillTogglePatchWithoutTheRemovedHint() {
        val patch = AgentPatchModUi("patch-id", "patch-mod-id", "Test AI Mod", "1.0", "", false)
        var toggled = false
        setCard(item = mod.copy(agentPatchMods = listOf(patch)),
            callbacks = ModCardCallbacks(onSetAgentPatchEnabled = { _, _, enabled -> toggled = enabled }))
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_manager_tab_2").performClick()
        listOf(
            "The parent mod is disabled. You can still toggle patches; enabled patches will load once the parent mod is enabled.",
            "主模组未启用，仍可调整补丁开关；已开启的补丁将在主模组启用后加载。",
            "主模組未啟用，仍可調整修補程式開關；已開啟的修補程式將在主模組啟用後載入。",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription(context.getString(R.string.mod_details_toggle, patch.name))
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(toggled) }
    }
}
