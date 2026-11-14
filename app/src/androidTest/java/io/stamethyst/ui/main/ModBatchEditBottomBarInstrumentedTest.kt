package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.LauncherBannerPageState
import io.stamethyst.ui.LocalLauncherBannerPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModBatchEditBottomBarInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun closingKeepsOutgoingContentUntilAnimationCompletes() {
        val state = mutableStateOf<BatchEditBarState?>(selection())
        var height = 0
        compose.setContent {
            MaterialTheme {
                ModBatchEditBottomBar(batchEditBarState = state.value, hazeState = rememberHazeState(),
                    onHeightChanged = { height = it })
            }
        }
        compose.onNodeWithText("1/100").assertExists()
        compose.runOnIdle { assertTrue(height > 0) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("1/100").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("1/100").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, height) }
    }

    @Test fun selectionUpdatesAndQuickReopeningUseLatestCount() {
        val state = mutableStateOf<BatchEditBarState?>(selection())
        compose.setContent {
            MaterialTheme {
                ModBatchEditBottomBar(batchEditBarState = state.value, hazeState = rememberHazeState(),
                    onHeightChanged = {})
            }
        }
        compose.runOnIdle { state.value = selection().copy(selectedCount = 2) }
        compose.onNodeWithText("2/100").assertExists()
        compose.onNodeWithText("1/100").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { state.value = selection().copy(selectedCount = 3) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("3/100").assertExists()
        compose.onNodeWithText("2/100").assertDoesNotExist()
    }

    @Test fun menuIsAtBottomRightAndStillDispatchesBatchActions() {
        var exports = 0
        val mod = ModItemUi(
            modId = "batch-menu", manifestModId = "batch-menu", storagePath = "/batch-menu.jar",
            name = "Batch Menu", version = "1", fileSizeBytes = 0, installed = true,
            description = "", dependencies = emptyList(), required = false, enabled = false,
            explicitPriority = null, effectivePriority = null,
        )
        val state = selection().copy(selectedMods = listOf(mod), onExport = { exports++ })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ModBatchEditBottomBar(
                        batchEditBarState = state,
                        hazeState = rememberHazeState(),
                        onHeightChanged = {},
                        actionsMenu = { actions ->
                            ModsActionsMenu(
                                canEditFolders = true, dragLocked = true,
                                showEnabledModsOnly = false, showUpdateAvailableModsOnly = false,
                                feedbackUnreadCount = 0, checkingUpdates = false,
                                onToggleDragLocked = {}, onShowEnabledModsOnlyChange = {},
                                onShowUpdateAvailableModsOnlyChange = {}, onAddFolderClick = {},
                                onCheckWorkshopUpdates = {}, onOpenFeedbackUpdates = {},
                                selectedMods = actions.selectedMods, batchActions = actions,
                            )
                        },
                    )
                }
            }
        }
        val menu = compose.onNodeWithContentDescription(context.getString(R.string.main_mod_actions_menu))
        val menuBounds = menu.fetchSemanticsNode().boundsInRoot
        val countBounds = compose.onNodeWithText("1/100").fetchSemanticsNode().boundsInRoot
        val disableBounds = compose.onNodeWithText(context.getString(R.string.main_batch_action_disable))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(menuBounds.left >= disableBounds.right)
        assertTrue(menuBounds.top >= countBounds.bottom)
        menu.performClick()
        compose.onNodeWithText(context.getString(R.string.main_mod_export)).performScrollTo().performClick()
        assertEquals(1, exports)
    }

    @Test fun glassSpansBottomEdgeWithoutRegisteringAsTopBanner() {
        val page = LauncherBannerPageState().apply { content = {} }
        val topContent = page.content
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLauncherBannerPage provides page) {
                    Box(Modifier.width(360.dp).height(500.dp).testTag("batch_glass_host")) {
                        ModBatchEditBottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            batchEditBarState = selection(),
                            hazeState = rememberHazeState(),
                            onHeightChanged = {},
                        )
                    }
                }
            }
        }
        val host = compose.onNodeWithTag("batch_glass_host").fetchSemanticsNode().boundsInRoot
        val glass = compose.onNodeWithTag(MOD_BATCH_EDIT_TOOLBAR_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(host.left, glass.left, 0.5f)
        assertEquals(host.right, glass.right, 0.5f)
        assertEquals(host.bottom, glass.bottom, 0.5f)
        compose.runOnIdle { assertSame(topContent, page.content) }
    }

    private fun selection() = BatchEditBarState(
        selectedCount = 1, totalCount = 100, controlsEnabled = true, selectAllEnabled = true,
        onMove = {}, onDelete = {}, onEnable = {}, onDisable = {}, onCancel = {},
    )
}
