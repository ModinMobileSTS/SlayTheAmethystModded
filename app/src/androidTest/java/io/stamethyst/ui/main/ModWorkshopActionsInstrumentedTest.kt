package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.model.WorkshopModUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModWorkshopActionsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mod = ModItemUi(
        modId = "workshop:1", manifestModId = "workshop:1", storagePath = "workshop:646570:1",
        name = "Pending market mod", version = "1", description = "", dependencies = emptyList(),
        required = false, installed = false, enabled = false, explicitPriority = null, effectivePriority = null,
        workshop = WorkshopModUi(646570u, 1uL, WorkshopModState.ImportedUnpatched),
    )

    @Test fun installReplacesTheCardSwitchAndRemainsAvailableInToolsWithFileActionsDisabled() {
        var installs = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ModCard(
                        mod = mod, isDraggedInOverlay = false, showModFileName = false,
                        selectionEnabled = false, fileActionsEnabled = false, dragEnabled = false, showDragHandle = false,
                        callbacks = ModCardCallbacks(onPatchWorkshopMod = { assertEquals(mod, it); installs++ }),
                    )
                }
            }
        }
        val installLabel = context.getString(R.string.main_mod_workshop_action_install)
        compose.onAllNodesWithText(installLabel).assertCountEquals(1)
        compose.onNode(hasText(installLabel) and hasAnyAncestor(hasTestTag("mod_card_primary_control")))
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("mod_card_enable").assertDoesNotExist()
        compose.onNodeWithTag("mod_details_dialog").assertDoesNotExist()
        assertEquals(1, installs)
        val slotBounds = compose.onNodeWithTag("mod_card_primary_control").fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag("mod_card_install").fetchSemanticsNode().boundsInRoot
        assertTrue("Install must not be clipped by the former fixed-width switch slot",
            buttonBounds.left >= slotBounds.left && buttonBounds.right <= slotBounds.right)
        compose.onNodeWithText(mod.name).performClick()
        compose.onNode(hasText(installLabel) and hasAnyAncestor(hasTestTag("mod_details_dialog")))
            .performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, installs)
        compose.onNode(hasTestTag("mod_details_dialog")).assertDoesNotExist()
    }

    @Test fun completingInstallationRestoresTheSwitchInTheSameHeaderSlot() {
        val state = mutableStateOf(mod)
        compose.setContent { MaterialTheme {
            ModCard(
                mod = state.value, isDraggedInOverlay = false, showModFileName = false,
                selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false, showDragHandle = false,
                callbacks = ModCardCallbacks(),
            )
        } }
        compose.onNodeWithTag("mod_card_install").assertIsDisplayed()
        compose.onNodeWithTag("mod_card_enable").assertDoesNotExist()
        compose.runOnIdle { state.value = mod.copy(installed = true,
            workshop = mod.workshop!!.copy(state = WorkshopModState.ImportedPatched)) }
        compose.onNodeWithText(context.getString(R.string.main_mod_workshop_action_install)).assertDoesNotExist()
        compose.onNode(hasTestTag("mod_card_enable") and hasAnyAncestor(hasTestTag("mod_card_primary_control")))
            .assertIsDisplayed().assertIsEnabled()
    }

    @Test fun batchSelectionDisablesTheHeaderInstallButton() {
        var installs = 0
        compose.setContent { MaterialTheme {
            ModCard(
                mod = mod, isDraggedInOverlay = false, showModFileName = false,
                selectionEnabled = false, fileActionsEnabled = false, dragEnabled = false, showDragHandle = false,
                batchSelectionMode = true,
                callbacks = ModCardCallbacks(onPatchWorkshopMod = { installs++ }),
            )
        } }
        compose.onNodeWithTag("mod_card_install").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("mod_card_enable").assertDoesNotExist()
        assertEquals(0, installs)
    }

    @Test fun pendingUpdateReplacesOldInstalledVersionsSwitchWithInstall() {
        var installs = 0
        val update = mod.copy(installed = true, workshop = mod.workshop!!.copy(
            state = WorkshopModState.UpdatePendingInstall, hasPendingInstall = true,
            statusText = "更新已下载，上次修补中断", localJarPath = "/old-installed.jar"))
        compose.setContent { MaterialTheme {
            ModCard(
                mod = update, isDraggedInOverlay = false, showModFileName = false,
                selectionEnabled = true, fileActionsEnabled = true, dragEnabled = false, showDragHandle = false,
                callbacks = ModCardCallbacks(onPatchWorkshopMod = { assertEquals(update, it); installs++ }),
            )
        } }
        compose.onNodeWithTag("mod_card_enable").assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.main_mod_workshop_action_install)) and
            hasAnyAncestor(hasTestTag("mod_card_primary_control"))).assertIsDisplayed().performClick()
        assertEquals(1, installs)
    }

    @Test fun actionsFollowDownloadPauseAndReadyToInstallTransitions() {
        val state = mutableStateOf(mod.copy(workshop = mod.workshop!!.copy(state = WorkshopModState.Downloading)))
        var installs = 0
        var resumes = 0
        compose.setContent { MaterialTheme {
            ModWorkshopActions(state.value, true, onInstall = { installs++ }, onRetryDownload = { resumes++ })
        } }
        val installLabel = context.getString(R.string.main_mod_workshop_action_install)
        compose.onNodeWithText(installLabel).assertDoesNotExist()
        compose.runOnIdle { state.value = mod.copy(workshop = mod.workshop!!.copy(state = WorkshopModState.DownloadPaused)) }
        compose.onNodeWithText(context.getString(R.string.main_mod_workshop_action_continue_download)).performClick()
        assertEquals(1, resumes)
        compose.runOnIdle { state.value = mod }
        compose.onNodeWithText(installLabel).assertIsEnabled().performClick()
        assertEquals(1, installs)
    }

    @Test fun disabledInstallationAndBlockedRetryCannotDispatch() {
        val state = mutableStateOf(mod)
        var actions = 0
        compose.setContent { MaterialTheme {
            ModWorkshopActions(state.value, false, onInstall = { actions++ }, onRetryDownload = { actions++ })
        } }
        compose.onNodeWithText(context.getString(R.string.main_mod_workshop_action_install)).assertIsNotEnabled()
        compose.runOnIdle { state.value = mod.copy(workshop = mod.workshop!!.copy(
            state = WorkshopModState.DownloadFailed, downloadBlocked = true)) }
        compose.onNodeWithText(context.getString(R.string.workshop_download_state_unavailable)).assertIsNotEnabled()
        assertEquals(0, actions)
    }
}
