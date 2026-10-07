package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState as Status
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorUi as State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure rendering fixtures: never access Steam, launcher preferences or real saves. */
@RunWith(AndroidJUnit4::class)
class SteamCloudDesignInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sheetBackgroundActionRequestsGameLaunchAndDismissesOnlyWhenAccepted() {
        var requests = 0
        var refreshes = 0
        var dismissed = 0
        var networkPrompts = 0
        var accept = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalNavigator provides rememberAppNavigator(Route.Main)) {
                SteamCloudStatusSheet(State(state = Status.HIDDEN), MainScreenActions(
                    isHostAvailable = true,
                    onBackgroundSteamCloudSyncAndLaunch = { requests++; accept },
                    onRefreshSteamCloudStatus = { refreshes++ },
                    shouldPromptSteamCloudDirectMode = { networkPrompts++; true },
                ), true, { dismissed++ })
            }
        } }
        compose.onNodeWithTag("steam-cloud-background").performClick()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(0, refreshes)
            assertEquals(0, dismissed)
            assertEquals(0, networkPrompts)
            accept = true
        }
        compose.onNodeWithTag("steam-cloud-background").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, requests)
            assertEquals(0, refreshes)
            assertEquals(1, dismissed)
        }
    }

    @Test fun sheetKeepsConnectionWithoutLowerStageSummaryInAnyState() {
        val state = mutableStateOf(State(state = Status.SYNCING, phase = SteamCloudSyncPhase.DOWNLOADING,
            completedFiles = 3, totalFiles = 8))
        compose.setContent { MaterialTheme {
            SteamCloudStatusDetails(state.value, true, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("steam-cloud-connection").assertIsDisplayed()
        compose.onNodeWithTag("steam-cloud-stage-progress").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        Status.entries.forEach { status ->
            compose.runOnIdle { state.value = State(state = status) }
            compose.onNodeWithTag("steam-cloud-stage-progress").assertDoesNotExist()
        }
    }

    @Test fun homepageHasOnlyTheWholeCardActionAndAnimatesHeight() {
        val state = mutableStateOf(State(state = Status.UP_TO_DATE))
        var opened = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            SteamCloudStatusCard(state.value, { opened++ })
        } } }
        compose.onNodeWithTag("steam-cloud-status-card").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("steam-cloud-background").assertDoesNotExist()
        compose.onNodeWithTag("steam-cloud-refresh").assertDoesNotExist()
        val collapsed = compose.onNodeWithTag("steam-cloud-status-card").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state.value = State(state = Status.SYNCING,
            phase = SteamCloudSyncPhase.DOWNLOADING, completedFiles = 3, totalFiles = 8) }
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        val middle = compose.onNodeWithTag("steam-cloud-status-card").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag("steam-cloud-status-card").fetchSemanticsNode().boundsInRoot.height
        assertTrue("$collapsed < $middle < $expanded", middle > collapsed && middle < expanded)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnUiThread { state.value = State(state = Status.UP_TO_DATE) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(collapsed, compose.onNodeWithTag("steam-cloud-status-card").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun sheetBackgroundButtonMatchesWebRulesForAllStates() {
        val state = mutableStateOf(State())
        var background = 0
        var launch = 0
        compose.setContent { MaterialTheme {
            SteamCloudStatusDetails(state.value, true, {}, {}, {}, {}, { launch++ }, { background++ })
        } }
        Status.entries.forEach { status ->
            compose.runOnIdle { state.value = State(state = status) }
            compose.waitForIdle()
            if (shouldShowSteamCloudBackgroundSyncAction(state.value)) {
                compose.onNodeWithTag("steam-cloud-background").assertIsDisplayed()
                if (status == Status.CANCELLING) compose.onNodeWithTag("steam-cloud-background").assertIsNotEnabled()
                else compose.onNodeWithTag("steam-cloud-background").performClick()
            } else compose.onNodeWithTag("steam-cloud-background").assertDoesNotExist()
        }
        assertEquals(4, background)
        assertEquals(0, launch)
    }

    @Test fun warningsAndRecoveryEvidenceStayBehindTechnicalDetails() {
        val state = mutableStateOf(State(state = Status.UP_TO_DATE, warnings = listOf("fixture warning")))
        compose.setContent { MaterialTheme {
            SteamCloudStatusDetails(state.value, true, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("fixture warning").assertDoesNotExist()
        compose.onNodeWithTag("steam-cloud-details").performClick()
        compose.onNodeWithText("fixture warning").assertExists()
        compose.runOnIdle { state.value = State(state = Status.RECOVERY_REQUIRED, errorSummary = "fixture recovery") }
        compose.onNodeWithText("fixture recovery").assertExists()
        compose.onNodeWithTag("steam-cloud-local").assertIsDisplayed()
        compose.onNodeWithTag("steam-cloud-remote").assertIsDisplayed()
        compose.onNodeWithTag("steam-cloud-background").assertDoesNotExist()
        compose.onNodeWithTag("steam-cloud-close").assertDoesNotExist()
    }

    @Test fun longDetailsScrollWithoutMovingTheBottomActionsAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(480.dp).testTag("fixture-screen"), contentAlignment = Alignment.BottomCenter) {
                        SteamCloudStatusDetails(State(state = Status.UP_TO_DATE,
                            warnings = (1..40).map { "fixture warning $it" }), true, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("steam-cloud-actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("steam-cloud-details").performScrollTo().performClick()
        compose.onNodeWithText("fixture warning 40").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("steam-cloud-launch").assertIsDisplayed()
        compose.onNodeWithTag("steam-cloud-refresh").assertIsDisplayed()
        val after = compose.onNodeWithTag("steam-cloud-actions").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("fixture-screen").fetchSemanticsNode().boundsInRoot
        assertEquals(before.bottom, after.bottom, 1f)
        assertTrue(after.bottom <= screen.bottom && after.left >= screen.left && after.right <= screen.right)
    }

    @Test fun technicalDetailsUseRealIntermediateHeightsAndCanReverse() {
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                SteamCloudStatusDetails(State(state = Status.UP_TO_DATE, warnings = listOf("fixture warning")),
                    true, {}, {}, {}, {}, {}, {}, modifier = Modifier.testTag("fixture-sheet"))
            }
        } }
        val collapsed = compose.onNodeWithTag("fixture-sheet").fetchSemanticsNode().boundsInRoot.height
        val bottom = compose.onNodeWithTag("fixture-sheet").fetchSemanticsNode().boundsInRoot.bottom
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("steam-cloud-details").performClick()
        compose.mainClock.advanceTimeBy(128)
        compose.waitForIdle()
        val middle = compose.onNodeWithTag("fixture-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.height > collapsed)
        assertEquals(bottom, middle.bottom, 1f)
        compose.onNodeWithTag("steam-cloud-details").performClick()
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        assertEquals(collapsed, compose.onNodeWithTag("fixture-sheet").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithText("fixture warning").assertDoesNotExist()
    }
}
