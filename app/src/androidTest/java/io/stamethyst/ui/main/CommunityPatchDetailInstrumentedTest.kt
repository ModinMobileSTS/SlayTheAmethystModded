package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.stamethyst.R
import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.mods.RemoteAgentPatch
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopItemRating
import io.stamethyst.ui.workshop.WorkshopModDownloadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only: no live Steam requests, downloads, installed mods or settings changes. */
@RunWith(AndroidJUnit4::class)
class CommunityPatchDetailInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shortPatch = patch("a".repeat(64), "1.0", "A short patch description.")
    private val longPatch = patch("b".repeat(64), "2.0", "Long patch description with complete details. ".repeat(60))
    private val mod = CommunityPatchMod("example", "Example Mod", 1234uL, listOf(shortPatch))
    private val summary = WorkshopItemSummary(1234uL, 646570u, "Market Mod Title", "", "", authorName = "Original Mod Author")

    @Test fun marketCardNavigatesAndItsDownloadActionDoesNotAlsoNavigate() {
        var navigations = 0
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    mod, CommunityPatchMarketState(summary), WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = { assertEquals(summary, it); navigations++ },
                    onDownload = { assertEquals(summary, it); downloads++ }, onRetryMarket = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_action_download)).performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(0, navigations) }
        compose.onNodeWithTag("community_market_card").performClick()
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(1, downloads) }
    }

    @Test fun marketCardNeverShowsDescriptionWhileAuthorAndRatingArrive() {
        val description = "API mod introduction must never appear in this card."
        var marketState by mutableStateOf(CommunityPatchMarketState(
            summary.copy(authorName = "", description = description), loading = true,
        ))
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    mod, marketState, WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = {}, onDownload = {}, onRetryMarket = {},
                )
            }
        }
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.workshop_unknown_author)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_rating_unrated_content_description)).assertDoesNotExist()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle {
            marketState = CommunityPatchMarketState(summary.copy(description = description, rating = WorkshopItemRating(4)))
        }

        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText(summary.authorName).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.workshop_unknown_author)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_rating_content_description, 4, 5)).assertExists()
    }

    @Test fun missingAuthorNeverFallsBackToDescriptionAfterLoadingFinishes() {
        val description = "Mod introduction must not replace an unavailable author."
        showContent(CommunityPatchMarketState(summary.copy(authorName = "", description = description)))
        compose.onNodeWithText(description).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.workshop_unknown_author)).assertIsDisplayed()
    }

    @Test fun longDescriptionsExpandCollapseAndSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { CommunityPatchCard(longPatch) } }
        }
        val toggle = "community_patch_expand_${longPatch.communityItemKey()}"
        compose.onNodeWithTag(toggle).assertIsDisplayed().performClick()
        compose.onNodeWithTag(toggle).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.community_patch_collapse)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(toggle).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.community_patch_description_expanded)))
            .performClick()
        compose.onNodeWithTag(toggle).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.community_patch_expand)).assertIsDisplayed()
    }

    @Test fun shortDescriptionNeedsNoExpansionAndIconKeepsItsFullSize() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(320.dp)) { CommunityPatchCard(shortPatch) } }
        }
        compose.onNodeWithTag("community_patch_expand_${shortPatch.communityItemKey()}").assertDoesNotExist()
        compose.onNodeWithTag("community_patch_icon_${shortPatch.communityItemKey()}").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText(context.getString(R.string.community_patch_contributor, shortPatch.authorName)).assertIsDisplayed()
    }

    @Test fun samePatchIdEntriesDoNotShareExpandedState() {
        val other = longPatch.copy(sha256 = "c".repeat(64), version = "3.0")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { CommunityPatchCard(longPatch); CommunityPatchCard(other) }
            }
        }
        compose.onNodeWithTag("community_patch_expand_${longPatch.communityItemKey()}").performScrollTo().performClick()
        // The second entry remains independently collapsed, despite sharing patchModId.
        compose.onNodeWithTag("community_patch_expand_${other.communityItemKey()}")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.community_patch_description_collapsed)))
    }

    @Test fun marketLoadingDoesNotHideAlreadyAvailablePatches() {
        showContent(CommunityPatchMarketState(loading = true))
        compose.onNodeWithTag("community_market_loading").assertDoesNotExist()
        compose.onNodeWithTag("community_market_card").assertIsDisplayed()
        compose.onNodeWithText(mod.name).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("community_patch_details")
            .performScrollToNode(hasTestTag("community_patch_${shortPatch.communityItemKey()}"))
        compose.onNodeWithText(shortPatch.description).assertIsDisplayed()
    }

    @Test fun lazyCommunityFieldsDoNotBlockKnownMetadataOrDownload() {
        var downloads = 0
        val base = summary.copy(authorName = "", downloadCount = 1234)
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    mod, CommunityPatchMarketState(base, loading = true), WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = {}, onDownload = { downloads++ }, onRetryMarket = {},
                )
            }
        }
        compose.onNodeWithText(base.title).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_count_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("1234").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_action_download)).performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun refreshDoesNotReplaceKnownAuthorOrRatingWithSkeletons() {
        showContent(CommunityPatchMarketState(summary.copy(rating = WorkshopItemRating(4)), loading = true))
        compose.onNodeWithText(summary.authorName).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_rating_content_description, 4, 5)).assertExists()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun loadedAuthorRemainsVisibleWhileOnlyRatingIsMissing() {
        showContent(CommunityPatchMarketState(summary, loading = true))
        compose.onNodeWithText(summary.authorName).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun loadedRatingRemainsVisibleWhileOnlyAuthorIsMissing() {
        showContent(CommunityPatchMarketState(summary.copy(authorName = "", rating = WorkshopItemRating(4)), loading = true))
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_rating_content_description, 4, 5)).assertExists()
    }

    @Test fun largeTextKeepsPartialLoadingSkeletonsInsideTheCard() {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                    Box(Modifier.width(360.dp)) {
                        CommunityPatchDetailContent(
                            mod, CommunityPatchMarketState(summary.copy(authorName = "", downloadCount = 1234), loading = true),
                            WorkshopModDownloadState.NotDownloaded, onOpenDetails = {}, onDownload = {}, onRetryMarket = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(summary.title).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun failedCommunityLoadStopsSkeletonsButRetainsBaseCard() {
        var state by mutableStateOf(CommunityPatchMarketState(summary.copy(authorName = ""), loading = true))
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    mod, state, WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = {}, onDownload = {}, onRetryMarket = {},
                )
            }
        }
        compose.runOnIdle { state = state.copy(loading = false, error = "Community unavailable") }
        compose.onNodeWithTag("workshop_card_author_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("workshop_card_rating_loading", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(summary.title).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workshop_unknown_author)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_rating_unrated_content_description)).assertExists()
        compose.onNodeWithText(context.getString(R.string.community_patch_market_retry)).assertExists()
    }

    @Test fun marketFailureRetainsPatchBrowsingAndOffersIndependentRetry() {
        var retries = 0
        showContent(CommunityPatchMarketState(error = "Network unavailable"), onRetry = { retries++ })
        compose.onNodeWithText(context.getString(R.string.community_patch_market_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.community_patch_market_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_action_download)).assertIsNotEnabled()
        compose.onNodeWithTag("community_patch_details")
            .performScrollToNode(hasTestTag("community_patch_${shortPatch.communityItemKey()}"))
        compose.onNodeWithText(shortPatch.description).assertIsDisplayed()
    }

    @Test fun missingWorkshopIdDoesNotProvideFakeNavigationOrDownload() {
        showContent(CommunityPatchMarketState(), item = mod.copy(workshopId = null))
        compose.onNodeWithText(context.getString(R.string.community_patch_market_unlinked)).assertExists()
        compose.onNodeWithTag("community_market_card").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_action_download)).assertDoesNotExist()
    }

    @Test fun failedRefreshKeepsPreviouslyLoadedMarketCardAvailable() {
        var navigations = 0
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    mod, CommunityPatchMarketState(summary, error = "Network unavailable"), WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = { navigations++ }, onDownload = { downloads++ }, onRetryMarket = {},
                )
            }
        }
        compose.onNodeWithText(summary.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.workshop_action_download)).performClick()
        compose.onNodeWithTag("community_market_card").performClick()
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(1, downloads) }
    }

    @Test fun largeTextAndLongMetadataKeepFullSizeIconAndAccessibleExpansion() {
        val item = longPatch.copy(
            name = "A long patch title that must wrap instead of covering the version badge",
            authorName = "A contributor with a long display name",
            targetVersions = listOf("1.0.0", "2.0.0", "3.0.0", "4.0.0"),
        )
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { CommunityPatchCard(item) }
                }
            }
        }
        compose.onNodeWithTag("community_patch_icon_${item.communityItemKey()}").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("community_patch_expand_${item.communityItemKey()}").performScrollTo().performClick()
        compose.onNodeWithTag("community_patch_expand_${item.communityItemKey()}")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.community_patch_description_expanded)))
    }

    @Test fun returningFromAnotherEntryRestoresListPositionAndPatchExpansion() {
        val patches = (0..10).map { index -> longPatch.copy(name = "Patch $index", sha256 = "hash-$index") }
        val target = patches.last()
        val toggle = "community_patch_expand_${target.communityItemKey()}"
        var showDetail by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                if (showDetail) {
                    holder.SaveableStateProvider("community-detail") {
                        CommunityPatchDetailContent(
                            mod.copy(patches = patches), CommunityPatchMarketState(summary), WorkshopModDownloadState.NotDownloaded,
                            onOpenDetails = {}, onDownload = {}, onRetryMarket = {},
                        )
                    }
                } else {
                    Box(Modifier.testTag("another_entry"))
                }
            }
        }
        compose.onNodeWithTag("community_patch_details").performScrollToNode(hasTestTag(toggle))
        compose.onNodeWithTag(toggle).performClick()
        compose.onNodeWithTag("community_patch_details").performScrollToNode(hasTestTag(toggle))
        compose.runOnIdle { showDetail = false }
        compose.onNodeWithTag("another_entry").assertExists()
        compose.runOnIdle { showDetail = true }
        // No scroll after returning: both the position and the expanded state must be retained.
        compose.onNodeWithTag(toggle).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.community_patch_description_expanded)))
    }

    private fun showContent(state: CommunityPatchMarketState, item: CommunityPatchMod = mod, onRetry: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                CommunityPatchDetailContent(
                    item, state, WorkshopModDownloadState.NotDownloaded,
                    onOpenDetails = {}, onDownload = {}, onRetryMarket = onRetry,
                )
            }
        }
    }

    private fun patch(hash: String, version: String, description: String) = RemoteAgentPatch(
        name = "AI Patch", description = description, authorName = "Contributor", version = version,
        patchModId = "same-patch-id", workshopId = "1234", targetVersions = listOf("1.1.5"),
        jarName = "patch.jar", downloadUrl = "https://raw.githubusercontent.com/example/patch.jar", size = 1, sha256 = hash,
    )
}
