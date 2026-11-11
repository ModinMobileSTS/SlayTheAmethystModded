package io.stamethyst.ui.main

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.R
import io.stamethyst.backend.mods.AgentPatchCatalogueService
import io.stamethyst.backend.mods.CatalogueLoadProgressReporter
import io.stamethyst.backend.mods.CatalogueRequestPhase
import io.stamethyst.backend.mods.CommunityPatchMod
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopService
import io.stamethyst.ui.LauncherTopAppBar
import io.stamethyst.ui.LoadingSkeletonBlock
import io.stamethyst.ui.loadStageProgressBarHeight
import io.stamethyst.ui.rememberLoadingSkeletonStyle
import io.stamethyst.ui.workshop.WorkshopViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val communityPreviewRequests = Semaphore(4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityPatchesScreen(
    workshopViewModel: WorkshopViewModel,
    modId: String? = null,
    onBack: () -> Unit,
    onOpenMod: (String) -> Unit = {},
    onOpenLlmSettings: () -> Unit = {},
    onOpenLlmTutorial: () -> Unit = {},
    onOpenWorkshopDetails: (WorkshopItemSummary) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val service = remember(context) { WorkshopService(context) }
    var cachedMods by remember { mutableStateOf<List<CommunityPatchMod>?>(null) }
    val requestState by AgentPatchCatalogueService.requestState.collectAsState()
    val catalogueProgress by CatalogueLoadProgressReporter.progress.collectAsState()
    val mods = requestState.data?.mods ?: cachedMods
    val refreshing = requestState.phase == CatalogueRequestPhase.FETCHING
    val error = requestState.error
    var refreshToken by rememberSaveable { mutableIntStateOf(0) }
    // A restored token keeps the entry's market request stable; returning is not a forced refresh.
    val initialRefreshToken = remember { refreshToken }

    // Mirror the market header: the slot and everything below it grow from one animated value, and
    // the bar keeps its last snapshot while collapsing so the text fades instead of blanking out.
    // A retained snapshot from an earlier visit is terminal, so it stays hidden until the new
    // session publishes its first stage; that avoids flashing "loaded, 100%" before "preparing".
    val narrating = refreshing && catalogueProgress?.phase?.isTerminal == false
    val progressHeight by animateDpAsState(
        targetValue = if (narrating) loadStageProgressBarHeight() else 0.dp,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "catalogueLoadProgressHeight",
    )

    LaunchedEffect(refreshToken) {
        try {
            if (mods == null) cachedMods = withContext(Dispatchers.IO) { AgentPatchCatalogueService.readCachedMods(context) }
            AgentPatchCatalogueService.refreshMods(context, force = refreshToken != initialRefreshToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The shared state machine publishes failures for every observer.
        }
    }
    val selected = mods?.firstOrNull { it.modId == modId }
    val marketViewModel = if (modId != null) {
        viewModel<CommunityPatchMarketViewModel>(key = "community_market_$modId")
    } else null
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LauncherTopAppBar(
                title = {
                    Text(
                        selected?.let { marketViewModel?.stateFor(it)?.summary?.title ?: it.name }
                            ?: stringResource(R.string.community_patches_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = stringResource(R.string.common_content_desc_back),
                            modifier = Modifier.graphicsLayer { rotationZ = 180f },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshToken++ }, enabled = !refreshing) {
                        Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.community_patches_refresh))
                    }
                },
                // Same slot the market puts its stage bar in: the bar lives inside the header
                // surface, glued under the title row, instead of at the top of the page body. It
                // stays composed so its own height animation can play out after a load settles and
                // the last stage fades away instead of vanishing in one frame.
                footer = {
                    CatalogueLoadProgressBar(progress = catalogueProgress, revealHeight = progressHeight)
                },
                footerHeight = progressHeight,
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (error != null) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.community_patches_load_failed, error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { refreshToken++ }, enabled = !refreshing) {
                        Text(stringResource(R.string.community_patches_refresh))
                    }
                }
            }
            when {
                modId == null -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "community_patches_guide") {
                        CommunityPatchesGuideCard(
                            onOpenLlmSettings = onOpenLlmSettings,
                            onOpenLlmTutorial = onOpenLlmTutorial,
                        )
                    }
                    when {
                        mods == null && refreshing -> repeat(CommunityPatchSkeletonRows) {
                            // The stage bar already narrates the fetch, so the rows just hold the
                            // layout instead of adding a second, indeterminate spinner.
                            item { CommunityPatchSkeletonCard() }
                        }
                        mods == null && error != null -> Unit
                        mods.isNullOrEmpty() -> item {
                            Text(
                                stringResource(R.string.community_patches_empty),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> items(mods.orEmpty(), key = { it.modId }) { mod ->
                            CommunityPatchModCard(mod, service, onClick = { onOpenMod(mod.modId) })
                        }
                    }
                }
                selected == null -> {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.community_patches_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> CommunityPatchDetail(
                    mod = selected,
                    service = service,
                    marketViewModel = requireNotNull(marketViewModel),
                    workshopViewModel = workshopViewModel,
                    refreshToken = refreshToken,
                    onOpenDetails = onOpenWorkshopDetails,
                )
            }
        }
    }
}

private const val COMMUNITY_PATCH_BADGE_INLINE_ID = "community_patch_badge"

/**
 * Explains what this page collects and how to reach the two things a reader needs next: the LLM
 * settings and the setup guide. The badge is drawn inline at the point of the sentence that names
 * it, so the picture the text points at is on screen instead of on another page.
 */
@Composable
private fun CommunityPatchesGuideCard(
    onOpenLlmSettings: () -> Unit,
    onOpenLlmTutorial: () -> Unit,
) {
    val badgeLabel = stringResource(R.string.main_mod_agent_patch_badge)
    val badgeTextStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val badgeIconSize = 12.dp
    val badgeHorizontalPadding = 7.dp
    val badgeVerticalPadding = 2.dp
    val badgeIconGap = 3.dp
    // Inline content keeps a fixed box, so the badge is measured against its own label and the
    // pill's padding; the extra pixel covers rounding in the conversion back to sp.
    val badgeBox = remember(badgeLabel, badgeTextStyle, density) {
        val label = measurer.measure(badgeLabel, badgeTextStyle).size
        with(density) {
            val boxWidth = label.width + (badgeIconSize + badgeIconGap + badgeHorizontalPadding * 2).roundToPx() + 2
            val boxHeight = label.height + badgeVerticalPadding.roundToPx() * 2
            boxWidth.toSp() to boxHeight.toSp()
        }
    }
    val inlineContent = remember(badgeLabel, badgeBox) {
        mapOf(
            COMMUNITY_PATCH_BADGE_INLINE_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = badgeBox.first,
                    height = badgeBox.second,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = badgeHorizontalPadding,
                            vertical = badgeVerticalPadding,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(badgeIconGap),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_code),
                            contentDescription = null,
                            modifier = Modifier.size(badgeIconSize),
                        )
                        Text(text = badgeLabel, style = badgeTextStyle, maxLines = 1)
                    }
                }
            }
        )
    }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        )
    )
    val text = buildAnnotatedString {
        append(stringResource(R.string.community_patches_guide_body_prefix))
        appendInlineContent(
            COMMUNITY_PATCH_BADGE_INLINE_ID,
            alternateText = badgeLabel,
        )
        append(stringResource(R.string.community_patches_guide_body_after_badge))
        withLink(
            LinkAnnotation.Clickable(
                tag = "community_patches_llm_settings",
                styles = linkStyles,
                linkInteractionListener = { onOpenLlmSettings() },
            )
        ) {
            append(stringResource(R.string.community_patches_guide_settings_link))
        }
        append(stringResource(R.string.community_patches_guide_body_between))
        withLink(
            LinkAnnotation.Clickable(
                tag = "community_patches_llm_tutorial",
                styles = linkStyles,
                linkInteractionListener = { onOpenLlmTutorial() },
            )
        ) {
            append(stringResource(R.string.community_patches_guide_tutorial_link))
        }
        append(stringResource(R.string.community_patches_guide_body_tail))
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info_outline),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.community_patches_guide_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                inlineContent = inlineContent,
            )
        }
    }
}

/** Matches the normal mod cards' shape, outline, elevation, surface and inner spacing. */
@Composable
private fun communityModCardModifier(): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return Modifier.fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 3.dp)
        .shadow(2.dp, shape, clip = false)
        .clip(shape)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
}

private const val CommunityPatchSkeletonRows = 4

/**
 * Placeholder row for the first catalogue fetch.
 *
 * It reuses the real card's shape and inner spacing so the list does not jump when the entries
 * arrive; the shimmer marks the row as pending, while the stage bar above explains the wait.
 */
@Composable
private fun CommunityPatchSkeletonCard() {
    val style = rememberLoadingSkeletonStyle("community_patch_card")
    Row(
        modifier = communityModCardModifier().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LoadingSkeletonBlock(Modifier.size(48.dp), style, RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LoadingSkeletonBlock(Modifier.fillMaxWidth(0.52f).height(14.dp), style)
            LoadingSkeletonBlock(Modifier.fillMaxWidth(0.34f).height(12.dp), style)
        }
    }
}

@Composable
private fun CommunityPatchModCard(mod: CommunityPatchMod, service: WorkshopService, onClick: () -> Unit) {
    Row(
        modifier = communityModCardModifier().clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CommunityPatchThumbnail(mod.workshopId, service)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mod.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.community_patches_count, mod.patches.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommunityPatchThumbnail(workshopId: ULong?, service: WorkshopService) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(workshopId) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(workshopId) { mutableStateOf(workshopId != null) }
    LaunchedEffect(workshopId) {
        if (workshopId == null) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) { WorkshopPreviewCacheStore.decodeCached(context, workshopId) }
            if (bitmap != null) loading = false
            // Even a cached image needs its API summary retained for the detail card.
            val summary = CommunityPatchSummaryStore.shared.load(646570u, workshopId) {
                communityPreviewRequests.withPermit {
                    service.getDetails(
                        appId = 646570u,
                        publishedFileId = workshopId,
                        includeCommunityData = false,
                        includeDependencyData = false,
                    ).summary
                }
            }
            if (bitmap == null && summary.previewUrl.isNotBlank()) {
                bitmap = withContext(Dispatchers.IO) {
                    WorkshopPreviewCacheStore.load(context, workshopId, summary.previewUrl)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Failed/absent previews fall back to the same default icon as the mod page.
        } finally {
            loading = false
        }
    }
    val imageModifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
    when {
        loading -> LoadingSkeletonBlock(imageModifier, rememberLoadingSkeletonStyle("community_patch_thumbnail"))
        bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = imageModifier, contentScale = ContentScale.Crop)
        else -> Icon(painterResource(R.drawable.ic_image_mod), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
    }
}
