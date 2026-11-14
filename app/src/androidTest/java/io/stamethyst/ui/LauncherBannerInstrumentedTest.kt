package io.stamethyst.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated fixtures: never load game data, network services or launcher preferences. */
@RunWith(AndroidJUnit4::class)
class LauncherBannerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope
    private lateinit var banner: LauncherBannerState
    private val activeKey = mutableStateOf<Any>(DockBannerKey)
    private var clicks = 0

    @Test
    fun horizontalDragInterpolatesOneSurfaceAndKeepsCorrectActions() {
        setFixture()
        compose.onAllNodesWithTag(LAUNCHER_BANNER_TAG).assertCountEquals(1)
        compose.onNodeWithText("Page 0").assertIsDisplayed()
        val firstHeight = height()
        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.waitForIdle()
        val secondHeight = height()
        assertTrue("Expanded pages must have different heights", secondHeight > firstHeight)

        compose.runOnIdle { scope.launch { pager.scrollToPage(0, 0.5f) } }
        compose.waitForIdle()
        val halfwayHeight = height()
        assertTrue("Height must follow the finger, not snap at the half-page threshold",
            halfwayHeight > firstHeight && halfwayHeight < secondHeight)
        assertEquals((firstHeight + secondHeight) / 2f, halfwayHeight, 2f)
        val outgoingTitle = compose.onNodeWithText("Page 0", useUnmergedTree = true).fetchSemanticsNode()
        val incomingTitle = compose.onNodeWithText("Page 1", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("Outgoing text must slide left, not just fade", outgoingTitle.positionInRoot.x < 0f)
        assertTrue("Incoming text must slide in from the right", incomingTitle.positionInRoot.x > 0f)
        compose.onAllNodesWithTag(LAUNCHER_BANNER_TAG).assertCountEquals(1)

        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.onNodeWithText("Action 1").performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun verticalScrollAnimatesWithoutChangingListInset() {
        setFixture()
        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.waitForIdle()
        val expandedHeight = height()
        val insetHeight = compose.onNodeWithTag("inset-1").fetchSemanticsNode().size.height
        val page = compose.runOnIdle { requireNotNull(banner.pages[1]) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { page.animateCollapsed(collapsed = true) } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        val halfwayHeight = height()
        compose.mainClock.advanceTimeBy(300)
        val collapsedHeight = height()
        assertTrue("Height must interpolate over animation frames",
            halfwayHeight > collapsedHeight && halfwayHeight < expandedHeight)
        assertEquals("Scroll inset must not feed back from an animated measurement", insetHeight,
            compose.onNodeWithTag("inset-1").fetchSemanticsNode().size.height)

        compose.mainClock.autoAdvance = true
        compose.runOnIdle { scope.launch { page.expand() } }
        compose.waitForIdle()
        compose.onNodeWithTag("scroll-1").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("A real nested scroll must collapse the banner", height() < expandedHeight)
        compose.onNodeWithText("Action 1").assertIsDisplayed()
    }

    @Test
    fun navigationUsesSameBannerAndRestoresPageState() {
        setFixture()
        compose.runOnIdle { scope.launch { pager.scrollToPage(1) } }
        compose.waitForIdle()
        val page = compose.runOnIdle { requireNotNull(banner.pages[1]) }
        compose.runOnIdle { scope.launch { page.animateCollapsed(collapsed = true) } }
        val collapsedHeight = height()
        compose.runOnIdle { activeKey.value = "detail" }
        compose.onNodeWithText("Details").assertIsDisplayed()
        compose.onAllNodesWithTag(LAUNCHER_BANNER_TAG).assertCountEquals(1)
        compose.runOnIdle { activeKey.value = DockBannerKey }
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        assertEquals(collapsedHeight, height(), 1f)
        compose.onNodeWithText("Details").assertDoesNotExist()
    }

    private fun height(): Float =
        compose.onNodeWithTag(LAUNCHER_BANNER_TAG).fetchSemanticsNode().size.height.toFloat()

    private fun setFixture() {
        compose.setContent {
            MaterialTheme {
                banner = remember { LauncherBannerState() }
                pager = rememberPagerState { 2 }
                scope = rememberCoroutineScope()
                val haze = rememberHazeState()
                CompositionLocalProvider(LocalLauncherBannerState provides banner) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().hazeSource(haze)) {
                            HorizontalPager(pager, beyondViewportPageCount = 1) { index ->
                                LauncherBannerPage(index) { FixturePage(index) }
                            }
                            if (activeKey.value == "detail") {
                                LauncherBannerPage("detail") {
                                    LauncherTopAppBar(title = { Text("Details") })
                                }
                            }
                        }
                        LauncherBannerHost(
                            state = banner,
                            hazeState = haze,
                            activeKey = activeKey.value,
                            dockKeys = listOf(0, 1),
                            pagerState = pager,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FixturePage(index: Int) {
        val haze = rememberHazeState()
        val inset = launcherBannerExpandedHeight(100.dp)
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().testTag("scroll-$index").verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(inset).fillMaxWidth().testTag("inset-$index"))
                repeat(40) { Text("Item $it", Modifier.height(64.dp)) }
            }
            CollapsibleFloatingGlassHeader(
                hazeState = haze,
                contentPadding = PaddingValues(0.dp),
                pinnedContent = {
                    LauncherBannerTitleRow(
                        iconResId = R.drawable.ic_dock_game,
                        title = "Page $index",
                        subtitle = "Subtitle $index",
                    ) { Button(onClick = { clicks += index + 1 }) { Text("Action $index") } }
                },
                expandedContent = if (index == 1) {
                    { Spacer(Modifier.fillMaxWidth().height(120.dp)) }
                } else null,
            )
        }
    }
}
