@file:OptIn(ExperimentalMaterial3Api::class)

package io.stamethyst.ui.workshop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.backend.workshop.WorkshopBrowseTimeFilter
import io.stamethyst.backend.workshop.WorkshopItemRating
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopModCategory
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.backend.workshop.isActiveDownload
import io.stamethyst.ui.AppSearchBar
import io.stamethyst.ui.CollapsibleFloatingGlassHeader
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LoadingSkeletonBlock
import io.stamethyst.ui.SearchHistoryStore
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.icon.KeyboardArrowUp
import io.stamethyst.ui.rememberLoadingSkeletonStyle
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WorkshopBackToTopButtonScrollThreshold = 320.dp
private val WorkshopBackToTopButtonBottomPadding = 112.dp

@Composable
internal fun WorkshopScreen(
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    initialListMode: WorkshopListMode = WorkshopListMode.Browse,
    showSubscriptionsButton: Boolean = false,
    useFloatingHeader: Boolean = true,
    active: Boolean = true,
    title: String? = null,
    subtitle: String? = null,
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenSubscriptions: () -> Unit = {},
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val headerHazeState = rememberHazeState()
    var headerBaseHeightPx by remember { mutableIntStateOf(0) }
    var headerSearchExpandedHeightPx by remember { mutableIntStateOf(0) }
    var headerSearchHistoryExpanded by remember { mutableStateOf(false) }
    val headerCollapseOffsetPx = with(density) { 24.dp.roundToPx() }
    val backToTopButtonScrollThresholdPx = with(density) {
        WorkshopBackToTopButtonScrollThreshold.roundToPx()
    }
    val headerPastCollapseOffset by remember(listState, headerCollapseOffsetPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > headerCollapseOffsetPx
        }
    }
    val headerCollapsed by remember {
        derivedStateOf { headerPastCollapseOffset }
    }
    val showBackToTopButton by remember(listState, backToTopButtonScrollThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > backToTopButtonScrollThresholdPx
        }
    }
    val effectiveHeaderHeightPx = if (
        headerSearchHistoryExpanded &&
        headerSearchExpandedHeightPx > 0
    ) {
        headerSearchExpandedHeightPx
    } else {
        headerBaseHeightPx
    }
    val headerPlaceholderHeight = if (useFloatingHeader && state.listMode == WorkshopListMode.Browse) 250.dp else 102.dp
    val headerContentHeight = if (effectiveHeaderHeightPx == 0) {
        headerPlaceholderHeight
    } else {
        with(density) { effectiveHeaderHeightPx.toDp() }
    }
    val showHeaderLoadProgress = useFloatingHeader &&
        state.listMode == WorkshopListMode.Browse &&
        state.browseLoading
    // One animation owns the progress row's height. The row renders at this height and the list inset
    // adds the same value, so both move together. Deriving the inset from the row's measured size
    // instead would put the list a frame behind the header and read as a stutter.
    val headerProgressHeight by animateDpAsState(
        targetValue = if (showHeaderLoadProgress) workshopLoadProgressBarHeight() else 0.dp,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "workshopHeaderProgressHeight",
    )
    val headerMeasuredHeight = headerContentHeight + headerProgressHeight
    val headerContentTopInset = headerMeasuredHeight + 16.dp
    val refreshIndicatorTopInset = headerMeasuredHeight + 8.dp
    val pullToRefreshState = rememberPullToRefreshState()
    val downloadTaskStatuses = WorkshopDownloadCenterStore.taskStatuses
    val activeDownloadTaskCount by remember {
        derivedStateOf { downloadTaskStatuses.values.count { it.isActiveDownload() } }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(WorkshopBrowseSort.MostPopular) }
    var timeFilter by rememberSaveable { mutableStateOf(WorkshopBrowseTimeFilter.OneWeek) }
    var category by rememberSaveable { mutableStateOf(WorkshopModCategory.All) }
    fun searchWithPopularAllTime(searchQuery: String = query) {
        val normalizedQuery = searchQuery.trim()
        val searchSort = WorkshopBrowseSort.MostPopular
        val searchTimeFilter = WorkshopBrowseTimeFilter.AllTime
        query = normalizedQuery
        sort = searchSort
        timeFilter = searchTimeFilter
        viewModel.search(context.applicationContext, normalizedQuery, searchSort, searchTimeFilter, category)
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(initialListMode) {
        viewModel.load(context.applicationContext, initialListMode)
    }

    LaunchedEffect(state.listMode) {
        listState.animateScrollToItem(0)
    }

    LaunchedEffect(active, state.downloadInProgress) {
        if (!active || !state.downloadInProgress) return@LaunchedEffect
        while (true) {
            delay(WORKSHOP_DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
            viewModel.refreshDownloadTaskState(context.applicationContext)
        }
    }

    LaunchedEffect(shouldLoadMore, state.items.size, state.hasMorePages) {
        if (shouldLoadMore) {
            viewModel.loadNextPage(context.applicationContext)
        }
    }

    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        Box(
            modifier = contentModifier.fillMaxSize()
        ) {
        PullToRefreshBox(
            isRefreshing = state.browseLoading && state.items.isNotEmpty(),
            onRefresh = { viewModel.refreshBrowse(context.applicationContext) },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = refreshIndicatorTopInset),
                    isRefreshing = state.browseLoading && state.items.isNotEmpty(),
                    state = pullToRefreshState,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .then(if (useFloatingHeader) Modifier.hazeSource(state = headerHazeState) else Modifier)
                .padding(start = 16.dp, top = if (useFloatingHeader) 18.dp else 0.dp, end = 16.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (useFloatingHeader) {
                    item {
                        Spacer(modifier = Modifier.height(headerContentTopInset))
                    }
                }
                if (!state.steamLoggedIn) {
                    item(key = "workshop-status-header") {
                        WorkshopStatusHeader(
                            listMode = state.listMode,
                            onOpenSteamLogin = onOpenSteamLogin,
                        )
                    }
                }

                if (!useFloatingHeader && state.listMode == WorkshopListMode.Browse) {
                    item(key = "workshop-search-panel") {
                        SearchPanel(
                            query = query,
                            loading = state.browseLoading,
                            sort = sort,
                            timeFilter = timeFilter,
                            category = category,
                            onQueryChange = { query = it },
                            onSearch = ::searchWithPopularAllTime,
                            onOpenDetailsById = { publishedFileId ->
                                onOpenDetails(publishedFileId.toWorkshopItemSummary(context))
                            },
                            onSortChange = { selectedSort ->
                                sort = selectedSort
                                viewModel.search(context.applicationContext, query, selectedSort, timeFilter, category)
                            },
                            onTimeFilterChange = { selectedTimeFilter ->
                                timeFilter = selectedTimeFilter
                                viewModel.search(context.applicationContext, query, sort, selectedTimeFilter, category)
                            },
                            onCategoryChange = { selectedCategory ->
                                category = selectedCategory
                                viewModel.search(context.applicationContext, query, sort, timeFilter, selectedCategory)
                            },
                        )
                    }
                }

                if (state.errorMessage != null) {
                    item(key = "workshop-error") {
                        ErrorPanel(
                            modifier = workshopListPlacementAnimation(enabled = !useFloatingHeader),
                            message = state.errorMessage,
                            onRetry = {
                                when (state.listMode) {
                                    WorkshopListMode.Browse -> viewModel.search(context.applicationContext, query, sort, timeFilter, category)
                                    WorkshopListMode.Subscriptions -> viewModel.showSubscribedWorkshopMods(context.applicationContext)
                                }
                            },
                        )
                    }
                }

                item(key = "workshop-section-title") {
                    SectionTitle(
                        title = when (state.listMode) {
                            WorkshopListMode.Browse -> stringResource(R.string.workshop_section_browse)
                            WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_subscriptions_title)
                        },
                        subtitle = when {
                            state.items.isEmpty() && state.browseLoading -> ""
                            state.items.isEmpty() -> stringResource(R.string.workshop_section_no_results)
                            else -> stringResource(R.string.workshop_section_item_count, state.items.size)
                        },
                    )
                }

                when {
                    state.browseLoading && state.items.isEmpty() -> {
                        items(
                            count = WorkshopListSkeletonItemCount,
                            key = { index -> "workshop-loading-skeleton-$index" },
                        ) { index ->
                            WorkshopListSkeletonCard(
                                modifier = Modifier.fillMaxWidth(),
                                variant = index,
                            )
                        }
                    }
                    state.items.isEmpty() && state.errorMessage == null -> {
                        item(key = "workshop-empty") {
                            EmptyPanel(
                                modifier = workshopListPlacementAnimation(enabled = !useFloatingHeader),
                                title = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.workshop_empty_title)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_empty_subscriptions_title)
                                },
                                description = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.workshop_empty_description)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_empty_subscriptions_description)
                                },
                                actionLabel = when (state.listMode) {
                                    WorkshopListMode.Browse -> stringResource(R.string.common_action_refresh)
                                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_action_refresh_subscriptions)
                                },
                                onRetry = {
                                    when (state.listMode) {
                                        WorkshopListMode.Browse -> viewModel.search(context.applicationContext, query, sort, timeFilter, category)
                                        WorkshopListMode.Subscriptions -> viewModel.showSubscribedWorkshopMods(context.applicationContext)
                                    }
                                },
                            )
                        }
                    }
                    else -> {
                        items(state.items, key = { it.publishedFileId.toString() }) { item ->
                            val downloadState = resolveWorkshopModDownloadState(
                                item = item,
                                installedMods = state.installedMods,
                                downloadTaskStatuses = downloadTaskStatuses,
                                preparingDownloadIds = state.preparingDownloadIds,
                            )
                            WorkshopItemCard(
                                modifier = workshopListPlacementAnimation(enabled = !useFloatingHeader),
                                item = item,
                                downloadState = downloadState,
                                onClick = { onOpenDetails(item) },
                                onDownload = {
                                    requestNotificationPermissionIfNeeded()
                                    viewModel.download(context.applicationContext, item)
                                },
                            )
                        }
                        item(key = "workshop-pagination-footer") {
                            BrowsePaginationFooter(
                                modifier = workshopListPlacementAnimation(enabled = !useFloatingHeader),
                                loading = state.loadingMore,
                                hasMorePages = state.hasMorePages,
                                itemCount = state.items.size,
                            )
                        }
                    }
                }
            }
        }

        if (useFloatingHeader) {
            CollapsibleFloatingGlassHeader(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                hazeState = headerHazeState,
                collapsed = headerCollapsed,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                contentPadding = PaddingValues(0.dp),
                onHeightChanged = {
                    if (!headerCollapsed) {
                        // Subtract the animating progress row so the cached card height stays stable.
                        // Folding a transient height in here would leave a gap above the list after
                        // loading ends, and would also feed layout back into layout.
                        val progressHeightPx = with(density) { headerProgressHeight.roundToPx() }
                        val cardHeightWithoutProgress = (it - progressHeightPx).coerceAtLeast(0)
                        when {
                            headerSearchHistoryExpanded -> {
                                headerSearchExpandedHeightPx = cardHeightWithoutProgress
                            }
                            headerBaseHeightPx == 0 || headerSearchExpandedHeightPx == 0 -> {
                                headerBaseHeightPx = maxOf(headerBaseHeightPx, cardHeightWithoutProgress)
                            }
                        }
                    }
                },
                pinnedContent = {
                    WorkshopHeaderPinnedContent(
                        showBackButton = showBackButton,
                        activeDownloadTaskCount = activeDownloadTaskCount,
                        showSubscriptionsButton = showSubscriptionsButton && state.steamLoggedIn,
                        title = title ?: stringResource(R.string.workshop_market_title),
                        subtitle = subtitle ?: stringResource(R.string.workshop_market_subtitle),
                        onBack = onBack,
                        onOpenSubscriptions = onOpenSubscriptions,
                        onOpenDownloadCenter = onOpenDownloadCenter,
                    )
                },
                expandedContent = if (state.listMode == WorkshopListMode.Browse) {
                    {
                        SearchPanel(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            query = query,
                            loading = state.browseLoading,
                            sort = sort,
                            timeFilter = timeFilter,
                            category = category,
                            onQueryChange = { query = it },
                            onSearch = ::searchWithPopularAllTime,
                            onOpenDetailsById = { publishedFileId ->
                                onOpenDetails(publishedFileId.toWorkshopItemSummary(context))
                            },
                            onSortChange = { selectedSort ->
                                sort = selectedSort
                                viewModel.search(context.applicationContext, query, selectedSort, timeFilter, category)
                            },
                            onTimeFilterChange = { selectedTimeFilter ->
                                timeFilter = selectedTimeFilter
                                viewModel.search(context.applicationContext, query, sort, selectedTimeFilter, category)
                            },
                            onCategoryChange = { selectedCategory ->
                                category = selectedCategory
                                viewModel.search(context.applicationContext, query, sort, timeFilter, selectedCategory)
                            },
                            onSearchHistoryExpandedChange = { expanded ->
                                if (expanded) {
                                    headerSearchExpandedHeightPx = 0
                                }
                                headerSearchHistoryExpanded = expanded
                            },
                            contained = false,
                        )
                    }
                } else {
                    null
                },
                footerContent = {
                    WorkshopLoadProgressBar(
                        progress = state.loadProgress,
                        revealHeight = headerProgressHeight,
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = showBackToTopButton,
            enter = fadeIn() + scaleIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = WorkshopBackToTopButtonBottomPadding),
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.workshop_scroll_to_top),
                )
            }
        }
        }
    }

    if (useFloatingHeader) {
        content(modifier)
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                WorkshopStandardTopBar(
                    showBackButton = showBackButton,
                    activeDownloadTaskCount = activeDownloadTaskCount,
                    title = title ?: stringResource(R.string.workshop_market_title),
                    subtitle = subtitle ?: stringResource(R.string.workshop_market_subtitle),
                    onBack = onBack,
                    onOpenDownloadCenter = onOpenDownloadCenter,
                )
            },
        ) { padding ->
            content(Modifier.padding(padding))
        }
    }

    state.pendingDependencyDownload?.let { pending ->
        MissingWorkshopDependenciesDialog(
            modTitle = pending.details.summary.title,
            missingDependencies = pending.missingDependencies,
            onDismiss = { viewModel.dismissPendingDependencyDownload() },
            onDownloadCurrentOnly = {
                requestNotificationPermissionIfNeeded()
                viewModel.downloadPendingCurrentOnly(context.applicationContext)
            },
            onConfirm = {
                requestNotificationPermissionIfNeeded()
                viewModel.confirmPendingDependencyDownload(context.applicationContext)
            },
        )
    }
}

private fun LazyItemScope.workshopListPlacementAnimation(
    enabled: Boolean,
    modifier: Modifier = Modifier,
): Modifier = if (enabled) modifier.animateItem() else modifier

@Composable
internal fun WorkshopSubscriptionsScreen(
    viewModel: WorkshopViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenDetails: (WorkshopItemSummary) -> Unit,
) {
    WorkshopScreen(
        viewModel = viewModel,
        modifier = modifier,
        showBackButton = true,
        initialListMode = WorkshopListMode.Subscriptions,
        useFloatingHeader = false,
        title = stringResource(R.string.workshop_subscriptions_title),
        subtitle = stringResource(R.string.workshop_subscriptions_subtitle),
        onBack = onBack,
        onOpenSteamLogin = onOpenSteamLogin,
        onOpenDownloadCenter = onOpenDownloadCenter,
        onOpenDetails = onOpenDetails,
    )
}

@Composable
private fun WorkshopHeaderPinnedContent(
    showBackButton: Boolean,
    activeDownloadTaskCount: Int,
    showSubscriptionsButton: Boolean,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    val downloadCenterDescription = if (activeDownloadTaskCount > 0) {
        stringResource(R.string.workshop_download_center_with_active_tasks, activeDownloadTaskCount)
    } else {
        stringResource(R.string.workshop_download_center_title)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_dock_market),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSubscriptionsButton) {
            IconButton(
                onClick = onOpenSubscriptions,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_workshop_subscriptions),
                    contentDescription = stringResource(R.string.workshop_subscriptions_title),
                )
            }
        }
        IconButton(
            onClick = onOpenDownloadCenter,
            modifier = Modifier.size(48.dp),
        ) {
            BadgedBox(
                badge = {
                    if (activeDownloadTaskCount > 0) {
                        Badge { Text(if (activeDownloadTaskCount > 99) "99+" else activeDownloadTaskCount.toString()) }
                    }
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_workshop_download),
                    contentDescription = downloadCenterDescription,
                )
            }
        }
        if (showBackButton) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_desc_back),
                )
            }
        }
    }
}

@Composable
private fun WorkshopStandardTopBar(
    showBackButton: Boolean,
    activeDownloadTaskCount: Int,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
) {
    val downloadCenterDescription = if (activeDownloadTaskCount > 0) {
        stringResource(R.string.workshop_download_center_with_active_tasks, activeDownloadTaskCount)
    } else {
        stringResource(R.string.workshop_download_center_title)
    }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.ArrowBack,
                        contentDescription = stringResource(R.string.common_content_desc_back),
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenDownloadCenter) {
                BadgedBox(
                    badge = {
                        if (activeDownloadTaskCount > 0) {
                            Badge {
                                Text(if (activeDownloadTaskCount > 99) "99+" else activeDownloadTaskCount.toString())
                            }
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_workshop_download),
                        contentDescription = downloadCenterDescription,
                    )
                }
            }
        },
    )
}

@Composable
private fun BrowsePaginationFooter(
    modifier: Modifier = Modifier,
    loading: Boolean,
    hasMorePages: Boolean,
    itemCount: Int,
) {
    val showFooter = loading || (!hasMorePages && itemCount > 0)
    if (!showFooter) {
        Spacer(modifier = Modifier.height(72.dp))
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.workshop_loading_more), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Text(stringResource(R.string.workshop_no_more_items), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorkshopStatusHeader(
    listMode: WorkshopListMode,
    onOpenSteamLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = when (listMode) {
                    WorkshopListMode.Browse -> stringResource(R.string.workshop_not_logged_in_browse)
                    WorkshopListMode.Subscriptions -> stringResource(R.string.workshop_not_logged_in_subscriptions)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSteamLogin) { Text(stringResource(R.string.workshop_action_login_steam)) }
        }
    }
}

@Composable
private fun SearchPanel(
    modifier: Modifier = Modifier,
    query: String,
    loading: Boolean,
    sort: WorkshopBrowseSort,
    timeFilter: WorkshopBrowseTimeFilter,
    category: WorkshopModCategory,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenDetailsById: (ULong) -> Unit,
    onSortChange: (WorkshopBrowseSort) -> Unit,
    onTimeFilterChange: (WorkshopBrowseTimeFilter) -> Unit,
    onCategoryChange: (WorkshopModCategory) -> Unit,
    onSearchHistoryExpandedChange: (Boolean) -> Unit = {},
    contained: Boolean = true,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var timeMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var openDetailsByIdDialogVisible by rememberSaveable { mutableStateOf(false) }
    var openDetailsByIdText by rememberSaveable { mutableStateOf("") }
    var openDetailsByIdError by rememberSaveable { mutableStateOf<String?>(null) }
    var searchHistoryExpanded by remember { mutableStateOf(false) }
    var wasSearchKeyboardVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    var searchHistory by remember(context) {
        mutableStateOf(SearchHistoryStore.loadWorkshopSearchHistory(context))
    }
    val invalidWorkshopIdMessage = stringResource(R.string.workshop_download_by_id_invalid)
    val searchKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    fun setSearchHistoryExpanded(expanded: Boolean) {
        if (searchHistoryExpanded != expanded) {
            searchHistoryExpanded = expanded
            onSearchHistoryExpandedChange(expanded)
        }
    }
    LaunchedEffect(searchKeyboardVisible) {
        if (
            wasSearchKeyboardVisible &&
            !searchKeyboardVisible &&
            searchHistoryExpanded
        ) {
            focusManager.clearFocus(force = true)
            setSearchHistoryExpanded(false)
        }
        wasSearchKeyboardVisible = searchKeyboardVisible
    }
    fun submitSearch(searchQuery: String = query) {
        val normalizedQuery = searchQuery.trim()
        keyboardController?.hide()
        setSearchHistoryExpanded(false)
        if (normalizedQuery.isNotEmpty()) {
            searchHistory = SearchHistoryStore.recordWorkshopSearch(context, normalizedQuery)
        }
        onSearch(normalizedQuery)
    }
    fun deleteSearchHistory(entry: String) {
        searchHistory = SearchHistoryStore.deleteWorkshopSearch(context, entry)
    }
    fun submitOpenDetailsById() {
        val publishedFileId = parseWorkshopPublishedFileId(openDetailsByIdText)
        if (publishedFileId == null) {
            openDetailsByIdError = invalidWorkshopIdMessage
            return
        }
        keyboardController?.hide()
        openDetailsByIdError = null
        openDetailsByIdDialogVisible = false
        onOpenDetailsById(publishedFileId)
    }

    if (contained) {
        Card(
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            SearchPanelContent(
                modifier = Modifier.padding(0.dp),
                query = query,
                loading = loading,
                sort = sort,
                timeFilter = timeFilter,
                category = category,
                sortMenuExpanded = sortMenuExpanded,
                timeMenuExpanded = timeMenuExpanded,
                categoryMenuExpanded = categoryMenuExpanded,
                searchHistory = searchHistory,
                searchHistoryExpanded = searchHistoryExpanded,
                onQueryChange = onQueryChange,
                onSearch = ::submitSearch,
                onSearchHistoryExpandedChange = ::setSearchHistoryExpanded,
                onSearchHistorySelected = { selected ->
                    onQueryChange(selected)
                    submitSearch(selected)
                },
                onSearchHistoryDeleted = ::deleteSearchHistory,
                onOpenDetailsByIdClick = { openDetailsByIdDialogVisible = true },
                onSortMenuExpandedChange = { sortMenuExpanded = it },
                onTimeMenuExpandedChange = { timeMenuExpanded = it },
                onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
                onSortChange = onSortChange,
                onTimeFilterChange = onTimeFilterChange,
                onCategoryChange = onCategoryChange,
            )
        }
    } else {
        SearchPanelContent(
            modifier = modifier.fillMaxWidth(),
            query = query,
            loading = loading,
            sort = sort,
            timeFilter = timeFilter,
            category = category,
            sortMenuExpanded = sortMenuExpanded,
            timeMenuExpanded = timeMenuExpanded,
            categoryMenuExpanded = categoryMenuExpanded,
            searchHistory = searchHistory,
            searchHistoryExpanded = searchHistoryExpanded,
            onQueryChange = onQueryChange,
            onSearch = ::submitSearch,
            onSearchHistoryExpandedChange = ::setSearchHistoryExpanded,
            onSearchHistorySelected = { selected ->
                onQueryChange(selected)
                submitSearch(selected)
            },
            onSearchHistoryDeleted = ::deleteSearchHistory,
            onOpenDetailsByIdClick = { openDetailsByIdDialogVisible = true },
            onSortMenuExpandedChange = { sortMenuExpanded = it },
            onTimeMenuExpandedChange = { timeMenuExpanded = it },
            onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
            onSortChange = onSortChange,
            onTimeFilterChange = onTimeFilterChange,
            onCategoryChange = onCategoryChange,
        )
    }

    if (openDetailsByIdDialogVisible) {
        WorkshopOpenDetailsByIdDialog(
            workshopId = openDetailsByIdText,
            errorMessage = openDetailsByIdError,
            onWorkshopIdChange = {
                openDetailsByIdText = it
                openDetailsByIdError = null
            },
            onDismiss = {
                openDetailsByIdDialogVisible = false
                openDetailsByIdError = null
            },
            onConfirm = ::submitOpenDetailsById,
        )
    }
}

@Composable
private fun SearchPanelContent(
    modifier: Modifier = Modifier,
    query: String,
    loading: Boolean,
    sort: WorkshopBrowseSort,
    timeFilter: WorkshopBrowseTimeFilter,
    category: WorkshopModCategory,
    sortMenuExpanded: Boolean,
    timeMenuExpanded: Boolean,
    categoryMenuExpanded: Boolean,
    searchHistory: List<String>,
    searchHistoryExpanded: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSearchHistoryExpandedChange: (Boolean) -> Unit,
    onSearchHistorySelected: (String) -> Unit,
    onSearchHistoryDeleted: (String) -> Unit,
    onOpenDetailsByIdClick: () -> Unit,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onTimeMenuExpandedChange: (Boolean) -> Unit,
    onCategoryMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (WorkshopBrowseSort) -> Unit,
    onTimeFilterChange: (WorkshopBrowseTimeFilter) -> Unit,
    onCategoryChange: (WorkshopModCategory) -> Unit,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            expanded = searchHistoryExpanded,
            onExpandedChange = onSearchHistoryExpandedChange,
            history = searchHistory,
            onHistorySelected = onSearchHistorySelected,
            onHistoryDeleted = onSearchHistoryDeleted,
            placeholder = stringResource(R.string.workshop_search_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
        val filterButtonContentPadding = PaddingValues(horizontal = 8.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    enabled = !loading,
                    onClick = { onCategoryMenuExpandedChange(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 0.dp),
                    contentPadding = filterButtonContentPadding,
                ) {
                    Text(
                        text = category.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { onCategoryMenuExpandedChange(false) }
                ) {
                    WorkshopModCategory.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName()) },
                            onClick = {
                                onCategoryMenuExpandedChange(false)
                                if (option != category) {
                                    onCategoryChange(option)
                                }
                            }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    enabled = !loading && sort.usesTimeFilter,
                    onClick = { onTimeMenuExpandedChange(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 0.dp),
                    contentPadding = filterButtonContentPadding,
                ) {
                    Text(
                        text = timeFilter.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = timeMenuExpanded,
                    onDismissRequest = { onTimeMenuExpandedChange(false) }
                ) {
                    WorkshopBrowseTimeFilter.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName()) },
                            onClick = {
                                onTimeMenuExpandedChange(false)
                                if (option != timeFilter) {
                                    onTimeFilterChange(option)
                                }
                            }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    enabled = !loading,
                    onClick = { onSortMenuExpandedChange(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 0.dp),
                    contentPadding = filterButtonContentPadding,
                ) {
                    Text(
                        text = sort.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { onSortMenuExpandedChange(false) }
                ) {
                    WorkshopBrowseSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName()) },
                            onClick = {
                                onSortMenuExpandedChange(false)
                                if (option != sort) {
                                    onSortChange(option)
                                }
                            }
                        )
                    }
                }
            }
            TextButton(
                onClick = onOpenDetailsByIdClick,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 0.dp),
                contentPadding = filterButtonContentPadding,
            ) {
                Text(
                    text = stringResource(R.string.workshop_download_by_id_action),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkshopOpenDetailsByIdDialog(
    workshopId: String,
    errorMessage: String?,
    onWorkshopIdChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workshop_download_by_id_title)) },
        text = {
            OutlinedTextField(
                value = workshopId,
                onValueChange = onWorkshopIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workshop_download_by_id_label)) },
                placeholder = { Text(stringResource(R.string.workshop_download_by_id_placeholder)) },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(errorMessage)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            Button(
                enabled = workshopId.isNotBlank(),
                onClick = onConfirm,
            ) { Text(stringResource(R.string.workshop_download_by_id_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_folder_dialog_cancel)) } },
    )
}

@Composable
internal fun WorkshopItemCard(
    modifier: Modifier = Modifier,
    item: WorkshopItemSummary,
    downloadState: WorkshopModDownloadState,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    WorkshopSummaryCard(
        modifier = modifier,
        publishedFileId = item.publishedFileId,
        previewUrl = item.previewUrl,
        title = item.title,
        supportingText = item.authorName.ifBlank {
            item.description.ifBlank { stringResource(R.string.workshop_open_detail_hint) }
        },
        onClick = onClick,
        footerContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkshopRatingIndicator(
                    rating = item.rating,
                    modifier = Modifier.width(WorkshopRatingIndicatorWidth),
                )
                WorkshopDownloadCountIndicator(
                    downloadCount = item.downloadCount,
                    modifier = Modifier.width(WorkshopDownloadCountIndicatorWidth),
                )
            }
        },
        trailingContent = {
            WorkshopDownloadActionButton(
                state = downloadState,
                onClick = onDownload,
                iconOnly = true,
            )
        },
    )
}

@Composable
internal fun WorkshopSummaryCard(
    publishedFileId: ULong,
    previewUrl: String,
    title: String,
    supportingText: String,
    onClick: () -> Unit,
    footerContent: @Composable () -> Unit,
    trailingContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkshopPreviewImage(
                publishedFileId = publishedFileId,
                url = previewUrl,
                contentDescription = stringResource(R.string.workshop_preview_content_description, title),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                footerContent()
            }
            trailingContent()
        }
    }
}

@Composable
internal fun WorkshopListSkeletonCard(
    modifier: Modifier = Modifier,
    variant: Int,
) {
    val skeletonStyle = rememberLoadingSkeletonStyle("workshop_list_skeleton_$variant")
    val titleWidth = when (variant % 3) {
        0 -> 0.86f
        1 -> 0.68f
        else -> 0.78f
    }
    val bodyWidth = when (variant % 3) {
        0 -> 0.94f
        1 -> 0.82f
        else -> 0.88f
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingSkeletonBlock(
                modifier = Modifier.size(72.dp),
                style = skeletonStyle,
                shape = RoundedCornerShape(12.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(titleWidth)
                        .height(18.dp),
                    style = skeletonStyle,
                )
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(bodyWidth)
                        .height(13.dp),
                    style = skeletonStyle,
                )
                LoadingSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.56f)
                        .height(13.dp),
                    style = skeletonStyle,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .width(WorkshopRatingIndicatorWidth)
                            .height(18.dp),
                        style = skeletonStyle,
                    )
                    LoadingSkeletonBlock(
                        modifier = Modifier
                            .width(WorkshopDownloadCountIndicatorWidth)
                            .height(18.dp),
                        style = skeletonStyle,
                    )
                }
            }
            LoadingSkeletonBlock(
                modifier = Modifier.size(48.dp),
                style = skeletonStyle,
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

@Composable
private fun WorkshopDownloadCountIndicator(
    downloadCount: Long,
    modifier: Modifier = Modifier,
) {
    val countText = formatWorkshopCount(downloadCount)
    val countDescription = stringResource(R.string.workshop_download_count_content_description, countText)
    Row(
        modifier = modifier.semantics {
            contentDescription = countDescription
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_workshop_download),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun formatWorkshopCount(value: Long): String {
    if (value <= 0L) return stringResource(R.string.workshop_unknown_value)
    return when {
        value >= 100_000_000L -> stringResource(R.string.workshop_count_hundred_million, value / 100_000_000.0)
        value >= 10_000L -> stringResource(R.string.workshop_count_ten_thousand, value / 10_000.0)
        else -> value.toString()
    }
}

@Composable
private fun WorkshopRatingIndicator(
    rating: WorkshopItemRating?,
    modifier: Modifier = Modifier,
) {
    val maxScore = rating?.maxScore?.takeIf { it > 0 } ?: 5
    val progress = rating
        ?.let { it.score.toFloat() / maxScore.toFloat() }
        ?.coerceIn(0f, 1f)
        ?: 0f
    val scoreText = rating?.let { stringResource(R.string.workshop_rating_score_format, it.score, it.maxScore) }
        ?: stringResource(R.string.workshop_rating_unrated_score)
    val scoreDescription = rating?.let {
        stringResource(R.string.workshop_rating_content_description, it.score, it.maxScore)
    } ?: stringResource(R.string.workshop_rating_unrated_content_description)

    Row(
        modifier = modifier.semantics {
            contentDescription = scoreDescription
            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkshopRatingStar(
            progress = progress,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = scoreText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val WorkshopRatingIndicatorWidth = 90.dp

private val WorkshopDownloadCountIndicatorWidth = 84.dp

private const val WorkshopListSkeletonItemCount = 5

private const val WorkshopRatingStarPartialFillScale = 0.9f

@Composable
private fun WorkshopRatingStar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val iconSize = 18.dp
    val normalizedProgress = progress.coerceIn(0f, 1f)
    val fillProgress = if (normalizedProgress >= 1f) {
        1f
    } else {
        normalizedProgress * WorkshopRatingStarPartialFillScale
    }
    val fillColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.primary
    val starInteriorPath = remember { PathParser().parsePathString(WorkshopRatingStarInteriorPathData).toPath() }
    val starInteriorBounds = remember { starInteriorPath.getBounds() }
    Box(modifier = modifier.size(iconSize)) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val scaleX = size.width / WorkshopRatingStarViewportSize
            val scaleY = size.height / WorkshopRatingStarViewportSize
            withTransform({ scale(scaleX, scaleY, pivot = Offset.Zero) }) {
                clipPath(starInteriorPath) {
                    drawRect(
                        color = fillColor,
                        topLeft = Offset(starInteriorBounds.left, starInteriorBounds.top),
                        size = Size(starInteriorBounds.width * fillProgress, starInteriorBounds.height),
                    )
                }
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_workshop_rating_star),
            contentDescription = null,
            tint = outlineColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

private const val WorkshopRatingStarViewportSize = 1024f

private const val WorkshopRatingStarInteriorPathData =
    "M512,150.25664c7.1424,0 20.224,2.2272 27.59168,17.152l81.24416,164.61312a82.0224,82.0224 0,0 0,61.78816,44.91264l181.69344,26.40384c16.46592,2.39104 22.6304,14.14656 24.83712,20.9408 2.20672,6.79424 4.13184,19.92192 -7.7824,31.5392l-131.48672,128.16384a82.03776,82.03776 0,0 0,-23.58272,72.61184l31.03744,180.96128c1.6128,9.41568 -0.54272,17.72032 -6.40512,24.6784 -6.08256,7.21408 -15.01696,11.52 -23.90016,11.52 -4.83328,0 -9.5232,-1.2288 -14.336,-3.7632l-162.49856,-85.43232a82.35008,82.35008 0,0 0,-38.19008,-9.43104c-13.25056,0 -26.45504,3.26144 -38.17984,9.42592L311.31648,869.9904c-4.75136,2.49856 -9.5744,3.7632 -14.336,3.7632 -8.88832,0 -17.8176,-4.30592 -23.90016,-11.51488 -5.8624,-6.95808 -8.01792,-15.26272 -6.40512,-24.6784l31.03744,-180.95104a82.03264,82.03264 0,0 0,-23.59808,-72.6272l-131.4816,-128.16896c-11.91424,-11.61728 -9.99424,-24.74496 -7.7824,-31.5392 2.20672,-6.79424 8.36608,-18.54464 24.83712,-20.9408l181.69344,-26.39872a81.9968,81.9968 0,0 0,61.7728,-44.88192l81.25952,-164.64384c7.36256,-14.9248 20.44416,-17.152 27.58656,-17.152z"

@Composable
internal fun WorkshopDownloadActionButton(
    state: WorkshopModDownloadState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
) {
    val enabled = state.canStartDownload
    if (iconOnly) {
        IconButton(
            modifier = modifier.size(48.dp),
            enabled = enabled,
            onClick = onClick,
        ) {
            val contentDescription = stringResource(state.actionLabelResId)
            if (state == WorkshopModDownloadState.Downloading) {
                WorkshopDownloadingAnimatedIcon(contentDescription = contentDescription)
            } else {
                Icon(
                    painter = painterResource(state.actionIconRes),
                    contentDescription = contentDescription,
                )
            }
        }
        return
    }
    when (state) {
        WorkshopModDownloadState.Downloaded -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.NotDownloaded -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.UpdateAvailable -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.Paused -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.DownloadFailed -> Button(
            modifier = modifier,
            enabled = enabled,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
        WorkshopModDownloadState.Queued,
        WorkshopModDownloadState.Cancelling,
        WorkshopModDownloadState.Downloading,
        WorkshopModDownloadState.Unavailable -> OutlinedButton(
            modifier = modifier,
            enabled = false,
            onClick = onClick,
        ) { Text(stringResource(state.actionLabelResId)) }
    }
}

@Composable
private fun WorkshopDownloadingAnimatedIcon(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.workshop_downloading))
    val tint = LocalContentColor.current
    val colorFilter: ColorFilter = remember(tint) {
        PorterDuffColorFilter(tint.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.COLOR_FILTER, colorFilter, "**"),
    )
    LottieAnimation(
        composition = composition,
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamicProperties,
        safeMode = true,
    )
}

@Composable
internal fun MissingWorkshopDependenciesDialog(
    modTitle: String,
    missingDependencies: List<WorkshopItemSummary>,
    onDismiss: () -> Unit,
    onDownloadCurrentOnly: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workshop_missing_dependencies_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.workshop_missing_dependencies_message, modTitle))
                missingDependencies.forEach { dependency ->
                    Text(
                        text = "${dependency.title.ifBlank { stringResource(R.string.workshop_dependency_fallback_title, dependency.publishedFileId.toString()) }} (${dependency.publishedFileId})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.workshop_action_install_and_download)) } },
        dismissButton = { TextButton(onClick = onDownloadCurrentOnly) { Text(stringResource(R.string.workshop_action_download_without_dependencies)) } },
    )
}

private val WorkshopModDownloadState.actionIconRes: Int
    get() = when (this) {
        WorkshopModDownloadState.Downloaded -> R.drawable.ic_workshop_installed
        WorkshopModDownloadState.NotDownloaded -> R.drawable.ic_workshop_download
        WorkshopModDownloadState.UpdateAvailable -> R.drawable.ic_workshop_update
        WorkshopModDownloadState.Queued -> R.drawable.ic_workshop_queue
        WorkshopModDownloadState.Downloading -> R.drawable.ic_workshop_downloading
        WorkshopModDownloadState.Paused -> R.drawable.ic_workshop_paused
        WorkshopModDownloadState.Cancelling -> R.drawable.ic_workshop_cancelling
        WorkshopModDownloadState.DownloadFailed -> R.drawable.ic_workshop_retry
        WorkshopModDownloadState.Unavailable -> R.drawable.ic_workshop_cancelling
    }

@Composable
internal fun WorkshopPreviewImage(
    publishedFileId: ULong,
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier.size(72.dp),
) {
    val context = LocalContext.current
    val imageState by produceState<PreviewImageState>(
        initialValue = PreviewImageState.Loading,
        key1 = publishedFileId,
        key2 = url,
    ) {
        value = when {
            url.isBlank() -> PreviewImageState.Failed
            else -> withContext(Dispatchers.IO) {
                WorkshopPreviewCacheStore.load(context.applicationContext, publishedFileId, url)
                    ?.let(PreviewImageState::Loaded)
                    ?: PreviewImageState.Failed
            }
        }
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = imageState) {
                PreviewImageState.Loading -> PreviewImageSkeleton(showLabel = false)
                PreviewImageState.Failed -> PreviewImageSkeleton(showLabel = true)
                is PreviewImageState.Loaded -> Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

private sealed interface PreviewImageState {
    data object Loading : PreviewImageState
    data object Failed : PreviewImageState
    data class Loaded(val bitmap: android.graphics.Bitmap) : PreviewImageState
}

@Composable
private fun PreviewImageSkeleton(showLabel: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (showLabel) {
            Text("MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LoadingSkeletonBlock(
                modifier = Modifier.fillMaxSize(),
                style = rememberLoadingSkeletonStyle("workshop_preview_image_skeleton"),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun ErrorPanel(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workshop_error_loading_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            WorkshopLoadFailureHint()
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.workshop_action_retry)) }
        }
    }
}

@Composable
private fun EmptyPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    actionLabel: String? = null,
    onRetry: () -> Unit,
) {
    val resolvedTitle = title ?: stringResource(R.string.workshop_empty_title)
    val resolvedDescription = description ?: stringResource(R.string.workshop_empty_description)
    val resolvedActionLabel = actionLabel ?: stringResource(R.string.common_action_refresh)
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(resolvedTitle, style = MaterialTheme.typography.titleMedium)
            Text(resolvedDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRetry) { Text(resolvedActionLabel) }
        }
    }
}

@Composable
private fun WorkshopBrowseSort.displayName(): String = when (this) {
    WorkshopBrowseSort.MostPopular -> stringResource(R.string.workshop_sort_most_popular)
    WorkshopBrowseSort.MostRecent -> stringResource(R.string.workshop_sort_most_recent)
    WorkshopBrowseSort.LastUpdated -> stringResource(R.string.workshop_sort_last_updated)
    WorkshopBrowseSort.MostSubscribed -> stringResource(R.string.workshop_sort_most_subscribed)
}

@Composable
private fun WorkshopBrowseTimeFilter.displayName(): String = when (this) {
    WorkshopBrowseTimeFilter.Today -> stringResource(R.string.workshop_time_today)
    WorkshopBrowseTimeFilter.OneWeek -> stringResource(R.string.workshop_time_one_week)
    WorkshopBrowseTimeFilter.ThirtyDays -> stringResource(R.string.workshop_time_thirty_days)
    WorkshopBrowseTimeFilter.ThreeMonths -> stringResource(R.string.workshop_time_three_months)
    WorkshopBrowseTimeFilter.SixMonths -> stringResource(R.string.workshop_time_six_months)
    WorkshopBrowseTimeFilter.OneYear -> stringResource(R.string.workshop_time_one_year)
    WorkshopBrowseTimeFilter.AllTime -> stringResource(R.string.workshop_time_all_time)
}

private val WORKSHOP_MOD_CATEGORY_LABEL_RES_IDS = mapOf(
    WorkshopModCategory.All to R.string.workshop_category_all,
    WorkshopModCategory.Tools to R.string.workshop_category_tools,
    WorkshopModCategory.Api to R.string.workshop_category_api,
    WorkshopModCategory.Character to R.string.workshop_category_character,
    WorkshopModCategory.Utility to R.string.workshop_category_utility,
    WorkshopModCategory.Relics to R.string.workshop_category_relics,
    WorkshopModCategory.Events to R.string.workshop_category_events,
    WorkshopModCategory.Cards to R.string.workshop_category_cards,
    WorkshopModCategory.Bosses to R.string.workshop_category_bosses,
    WorkshopModCategory.Elites to R.string.workshop_category_elites,
    WorkshopModCategory.Monsters to R.string.workshop_category_monsters,
    WorkshopModCategory.Modifiers to R.string.workshop_category_modifiers,
    WorkshopModCategory.Potions to R.string.workshop_category_potions,
    WorkshopModCategory.Rooms to R.string.workshop_category_rooms,
    WorkshopModCategory.Neow to R.string.workshop_category_neow,
    WorkshopModCategory.Twitch to R.string.workshop_category_twitch,
    WorkshopModCategory.Qol to R.string.workshop_category_qol,
    WorkshopModCategory.Expansion to R.string.workshop_category_expansion,
    WorkshopModCategory.Content to R.string.workshop_category_content,
    WorkshopModCategory.Rewards to R.string.workshop_category_rewards,
)

@Composable
private fun WorkshopModCategory.displayName(): String = stringResource(WORKSHOP_MOD_CATEGORY_LABEL_RES_IDS.getValue(this))

private fun parseWorkshopPublishedFileId(input: String): ULong? {
    val trimmed = input.trim()
    trimmed.toULongOrNull()?.takeIf { it > 0uL }?.let { return it }
    return Regex("""(?:^|[?&])id=(\d+)""").find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.toULongOrNull()
        ?.takeIf { it > 0uL }
}

private fun ULong.toWorkshopItemSummary(context: Context): WorkshopItemSummary = WorkshopItemSummary(
    publishedFileId = this,
    appId = SLAY_THE_SPIRE_WORKSHOP_APP_ID,
    title = context.getString(R.string.workshop_dependency_fallback_title, toString()),
    previewUrl = "",
    description = "",
)

private val SLAY_THE_SPIRE_WORKSHOP_APP_ID = 646570u

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
