package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import io.stamethyst.ui.LauncherBannerHost
import io.stamethyst.ui.LauncherBannerPage
import io.stamethyst.ui.LauncherBannerState
import io.stamethyst.ui.LocalLauncherBannerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkshopHeaderInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchModesRespondToTouchBeforeAndAfterSubscriptionsButtonAppears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.workshop_market_title)
        val aiSelected = mutableStateOf(false)
        val showSubscriptions = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val bannerState = remember { LauncherBannerState() }
                val haze = rememberHazeState()
                CompositionLocalProvider(LocalLauncherBannerState provides bannerState) {
                    Box(Modifier.requiredWidth(376.dp)) {
                        LauncherBannerPage("market") {
                            val selected = aiSelected.value
                            val motion = rememberWorkshopModeMotion(selected)
                            CollapsibleFloatingGlassHeader(
                                hazeState = haze,
                                pinnedContent = {
                                    WorkshopHeaderPinnedContent(
                                        showBackButton = false,
                                        activeDownloadTaskCount = 0,
                                        showSubscriptionsButton = showSubscriptions.value,
                                        title = title,
                                        onBack = {},
                                        onOpenSubscriptions = {},
                                        onOpenDownloadCenter = {},
                                        modeTabs = {
                                            WorkshopSearchTabs(selected, motion) { aiSelected.value = it }
                                        },
                                    )
                                },
                            )
                        }
                        LauncherBannerHost(
                            state = bannerState,
                            hazeState = haze,
                            activeKey = "market",
                            dockKeys = emptyList(),
                            pagerState = rememberPagerState { 1 },
                        )
                    }
                }
            }
        }

        val aiTab = compose.onNodeWithContentDescription(context.getString(R.string.market_ai_title))
        val browseTab = compose.onNodeWithContentDescription(context.getString(R.string.market_ai_ordinary))
        val subscriptions = compose.onNodeWithContentDescription(context.getString(R.string.workshop_subscriptions_title))

        subscriptions.assertDoesNotExist()
        browseTab.assertIsSelected()
        aiTab.assertIsNotSelected().performTouchInput { click() }
        aiTab.assertIsSelected()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.market_ai_title)).assertDoesNotExist()
        browseTab.assertIsNotSelected().performTouchInput { click() }
        browseTab.assertIsSelected()

        aiTab.performTouchInput { click() }
        aiTab.assertIsSelected()
        compose.runOnIdle { showSubscriptions.value = true }
        subscriptions.assertIsDisplayed()
        aiTab.assertIsDisplayed().assertIsSelected()
        browseTab.assertIsDisplayed().performTouchInput { click() }
        browseTab.assertIsSelected()
        aiTab.assertIsNotSelected().performTouchInput { click() }
        aiTab.assertIsSelected()
    }

    @Test
    fun narrowBannerKeepsStandardTitleSizeAndMovesActionsBelow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aiSelected = mutableStateOf(false)
        var subscriptionsOpened = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    WorkshopHeaderPinnedContent(
                        showBackButton = false,
                        activeDownloadTaskCount = 0,
                        showSubscriptionsButton = true,
                        title = "Market",
                        onBack = {},
                        onOpenSubscriptions = { subscriptionsOpened = true },
                        onOpenDownloadCenter = {},
                        modeTabs = {
                            WorkshopSearchTabs(selected = aiSelected.value) { aiSelected.value = it }
                        },
                    )
                }
            }
        }

        val title = compose.onNodeWithText("Market").assertIsDisplayed()
        val market = title.getUnclippedBoundsInRoot()
        val titleLayout = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayout) }
        assertEquals(24.sp, titleLayout.single().layoutInput.style.fontSize)
        assertTrue(!titleLayout.single().isLineEllipsized(0))
        assertEquals(80f, market.left.value, 1f)
        val download = compose.onNodeWithContentDescription(context.getString(R.string.workshop_download_center_title))
        val subscriptions = compose.onNodeWithContentDescription(context.getString(R.string.workshop_subscriptions_title))
        val aiTab = compose.onNodeWithContentDescription(context.getString(R.string.market_ai_title))
        val browseTab = compose.onNodeWithContentDescription(context.getString(R.string.market_ai_ordinary))
        val downloadBounds = download.getUnclippedBoundsInRoot()
        val subscriptionsBounds = subscriptions.getUnclippedBoundsInRoot()
        val aiBounds = aiTab.assertIsDisplayed().getUnclippedBoundsInRoot()
        val browseBounds = browseTab.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(market.bottom <= browseBounds.top)
        assertTrue(browseBounds.right <= aiBounds.left)
        assertTrue(subscriptionsBounds.left >= downloadBounds.right)
        assertTrue(aiBounds.right <= downloadBounds.left)
        assertEquals(downloadBounds.top.value, aiBounds.top.value, 0.5f)
        aiTab.performTouchInput { click() }.assertIsSelected()
        browseTab.performTouchInput { click() }.assertIsSelected()
        subscriptions.performClick()
        compose.runOnIdle { assertTrue(subscriptionsOpened) }
    }
}
