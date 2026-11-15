package io.stamethyst.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.stamethyst.config.ModPageLayout
import io.stamethyst.model.ModItemUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPageLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val mod = ModItemUi(
        modId = "layout-test", manifestModId = "layout-test", storagePath = "/layout-test.jar",
        name = "Layout Test", version = "1.0", description = "Classic card description",
        dependencies = emptyList(), required = false, installed = true, enabled = true,
        explicitPriority = null, effectivePriority = 10,
    )

    @Test fun switchingLayoutChangesOnlyCardPresentationAndUsesTheSameDetailsDialog() {
        val layout = mutableStateOf(ModPageLayout.COMPACT)
        var toggles = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalModPageLayout provides layout.value) {
                    ModCard(
                        mod = mod, isDraggedInOverlay = false, showModFileName = false,
                        selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false,
                        showDragHandle = false,
                        callbacks = ModCardCallbacks(onToggleMod = { _, _ -> toggles++ }),
                    )
                }
            }
        }
        compose.onNodeWithTag("mod_card_compact").assertIsDisplayed()
        compose.onNodeWithText(mod.description).assertDoesNotExist()
        compose.runOnIdle { layout.value = ModPageLayout.CLASSIC }
        compose.onNodeWithTag("mod_card_classic").assertIsDisplayed()
        compose.onNodeWithText(mod.description).assertIsDisplayed()
        compose.onNodeWithTag("mod_card_enable").assertIsOn().assertIsEnabled()
        compose.onNodeWithText(mod.name).performClick()
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        compose.onNodeWithTag("mod_details_technical_toggle").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { layout.value = ModPageLayout.COMPACT }
        compose.onNodeWithTag("mod_details_dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, toggles) }
    }

    @Test fun classicEnableCheckboxCallsTheExistingCallback() {
        var toggled: Boolean? = null
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalModPageLayout provides ModPageLayout.CLASSIC) {
                    ModCard(
                        mod = mod, isDraggedInOverlay = false, showModFileName = false,
                        selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false,
                        showDragHandle = false,
                        callbacks = ModCardCallbacks(onToggleMod = { _, enabled -> toggled = enabled }),
                    )
                }
            }
        }
        compose.onNodeWithTag("mod_card_enable").performClick()
        compose.runOnIdle { assertEquals(false, toggled) }
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
    }
}
