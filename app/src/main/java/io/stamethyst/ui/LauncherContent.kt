package io.stamethyst.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.model.ModItemUi
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.backend.workshop.WorkshopUpdateCheckCoordinator
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.stamethyst.navigation.LocalNavigator
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.rememberAppNavigator
import io.stamethyst.backend.feedback.FeedbackInboxCoordinator
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.feedback.LauncherFeedbackScreen
import io.stamethyst.ui.feedback.LauncherFeedbackConversationScreen
import io.stamethyst.ui.feedback.LauncherFeedbackIssueBrowserScreen
import io.stamethyst.ui.feedback.LauncherFeedbackIssuePreviewScreen
import io.stamethyst.ui.feedback.LauncherFeedbackSubscriptionsScreen
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.main.LauncherMainScreen
import io.stamethyst.ui.main.LauncherCrashRecoveryScreen
import io.stamethyst.ui.main.LauncherGameScreenContent
import io.stamethyst.ui.main.LauncherMainRoute
import io.stamethyst.ui.main.LauncherModsScreen
import io.stamethyst.ui.main.LauncherModsScreenContent
import io.stamethyst.ui.main.LauncherUpdateNoticeUiState
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.modimport.ModImportHost
import io.stamethyst.ui.workshop.WorkshopScreen
import io.stamethyst.ui.workshop.WorkshopDownloadCenterScreen
import io.stamethyst.ui.workshop.WorkshopDownloadCenterStore
import io.stamethyst.ui.workshop.WorkshopDetailScreen
import io.stamethyst.ui.workshop.resolveWorkshopModDownloadState
import io.stamethyst.ui.workshop.WorkshopSubscriptionsScreen
import io.stamethyst.ui.workshop.WorkshopViewModel
import io.stamethyst.ui.quickstart.QuickStartScreen
import io.stamethyst.ui.quickstart.QuickStartAutomaticImportScreen
import io.stamethyst.ui.quickstart.QuickStartJarImportScreen
import io.stamethyst.ui.quickstart.QuickStartSteamDownloadScreen
import io.stamethyst.ui.settings.first_run.LauncherFirstRunSetupScreen
import io.stamethyst.ui.settings.core.LauncherDeveloperSettingsScreen
import io.stamethyst.ui.settings.baidu.LauncherBaiduTranslationCredentialsScreen
import io.stamethyst.ui.settings.mobileglues.LauncherMobileGluesSettingsScreen
import io.stamethyst.ui.settings.native_library.LauncherNativeLibraryMarketScreen
import io.stamethyst.ui.settings.core.LauncherSettingsAboutScreen
import io.stamethyst.ui.settings.core.LauncherSettingsFeedbackScreen
import io.stamethyst.ui.settings.core.LauncherSettingsGameScreen
import io.stamethyst.ui.settings.core.LauncherSettingsPerformanceScreen
import io.stamethyst.ui.settings.core.LauncherSettingsLauncherScreen
import io.stamethyst.ui.settings.core.LauncherSettingsMarketCloudScreen
import io.stamethyst.ui.settings.core.LauncherSettingsScreen
import io.stamethyst.ui.settings.core.LauncherSettingsWorkshopAutoImportDefaultsScreen
import io.stamethyst.ui.settings.steamcloud.LauncherSteamCloudGuardScreen
import io.stamethyst.ui.settings.steamcloud.LauncherSteamCloudLoginScreen
import io.stamethyst.ui.settings.steamcloud.LauncherSteamCloudLoginMethodScreen
import io.stamethyst.ui.settings.steamcloud.LauncherSteamCloudSaveSettingsScreen
import io.stamethyst.ui.settings.steamcloud.LauncherSteamCloudSyncBlacklistSettingsScreen
import io.stamethyst.ui.settings.core.SettingsEffectsHandler
import io.stamethyst.ui.settings.core.SettingsScreenViewModel
import io.stamethyst.ui.settings.core.StsJarIntegrityDialogHost
import io.stamethyst.ui.preferences.LauncherPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val PAGE_TRANSITION_DURATION_MS = 420
private const val DOCK_VISIBILITY_ANIMATION_MS = 220
private const val QUARK_BROWSER_PACKAGE_NAME = "com.quark.browser"
internal const val LAUNCHER_DOCK_ITEM_TAG_PREFIX = "launcher_dock_item_"

internal data class SteamCloudMainRefreshTrackerState(
    val observedRefreshTokenConfigured: Boolean,
    val observedSaveMode: SteamCloudSaveMode,
    val pendingRefreshOnMain: Boolean = false,
)

internal data class SteamCloudMainRefreshPlan(
    val nextState: SteamCloudMainRefreshTrackerState,
    val shouldRefreshMain: Boolean,
    val shouldForceSyncIndicator: Boolean,
)

internal fun planSteamCloudMainRefresh(
    route: Route?,
    state: SteamCloudMainRefreshTrackerState,
    refreshTokenConfigured: Boolean,
    saveMode: SteamCloudSaveMode,
): SteamCloudMainRefreshPlan {
    val stateChanged =
        state.observedRefreshTokenConfigured != refreshTokenConfigured ||
            state.observedSaveMode != saveMode
    val pendingRefreshOnMain = state.pendingRefreshOnMain || stateChanged
    val shouldRefreshMain = pendingRefreshOnMain && route == Route.Main
    val shouldForceSyncIndicator =
        shouldRefreshMain &&
            refreshTokenConfigured &&
            saveMode == SteamCloudSaveMode.STEAM_CLOUD
    return SteamCloudMainRefreshPlan(
        nextState = SteamCloudMainRefreshTrackerState(
            observedRefreshTokenConfigured = refreshTokenConfigured,
            observedSaveMode = saveMode,
            pendingRefreshOnMain = if (shouldRefreshMain) false else pendingRefreshOnMain,
        ),
        shouldRefreshMain = shouldRefreshMain,
        shouldForceSyncIndicator = shouldForceSyncIndicator,
    )
}

private val LauncherDockRoutes = listOf(
    Route.Main,
    Route.Mods,
    Route.Workshop,
    Route.Settings,
)

@Composable
fun LauncherContent(
    initialRoute: Route = Route.Main,
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
    onMainScreenOpened: () -> Unit = {},
    onCurrentDockRouteChanged: (Route?) -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = rememberAppNavigator(initialRoute)
    val transientNoticeHostState = remember { SnackbarHostState() }
    var pendingFeedbackNotice by remember {
        mutableStateOf<FeedbackSubmissionNotice?>(null)
    }
    val feedbackInboxState by FeedbackInboxCoordinator.uiState.collectAsState()
    val mainUiState = mainViewModel.uiState
    val settingsUiState = settingsViewModel.uiState
    val chromeBackgroundOpacity = settingsUiState.chromeBackgroundOpacity
    val updateNotice = settingsUiState.availableUpdatePromptState?.let { promptState ->
        LauncherUpdateNoticeUiState(
            currentVersion = promptState.currentVersion,
            latestVersion = promptState.latestVersion,
        )
    }
    val workshopViewModel: WorkshopViewModel = viewModel()
    val workshopSubscriptionsViewModel: WorkshopViewModel = viewModel(key = "workshop-subscriptions")
    val currentRoute = navigator.backStack.lastOrNull() as? Route
    val rootRoute = navigator.backStack.firstOrNull() as? Route
    var steamCloudMainRefreshTrackerState by remember {
        mutableStateOf(
            SteamCloudMainRefreshTrackerState(
                observedRefreshTokenConfigured = settingsUiState.steamCloudRefreshTokenConfigured,
                observedSaveMode = settingsUiState.steamCloudSaveMode,
            )
        )
    }
    val initialDockPage = initialRoute.launcherDockIndex() ?: 0
    val dockPagerState = rememberPagerState(initialPage = initialDockPage) {
        LauncherDockRoutes.size
    }
    val coroutineScope = rememberCoroutineScope()
    var pendingDockRoute by remember { mutableStateOf<Route?>(null) }
    var dockNavigationRequestId by remember { mutableStateOf(0) }
    var dockNavigationJob by remember { mutableStateOf<Job?>(null) }
    val dockPageRoute = pendingDockRoute ?: dockPagerState.currentLauncherDockRoute()
    val showDockPager = rootRoute.launcherDockIndex() != null || currentRoute.launcherDockIndex() != null
    val showOverlayNav = currentRoute.launcherDockIndex() == null || navigator.stackSize > 1
    var forwardPageTransition by remember { mutableStateOf(true) }
    var modsBatchSelectionMode by remember { mutableStateOf(false) }
    val showAnimatedLauncherDock = showDockPager && !showOverlayNav && !modsBatchSelectionMode
    val launcherDockHazeState = rememberHazeState()
    val isBlockingBusyInteractionLocked =
        mainUiState.busyOperation.usesBlockingOverlay() ||
            settingsUiState.busyOperation.usesBlockingOverlay()
    val shouldShowBlockingBusyWindow =
        isBlockingBusyInteractionLocked &&
            currentRoute != Route.QuickStart &&
            currentRoute != Route.QuickStartAutoImport &&
            currentRoute != Route.QuickStartJarImport &&
            currentRoute != Route.QuickStartSteamDownload
    val blockingBusyMessage = when {
        mainUiState.busyOperation.usesBlockingOverlay() -> mainUiState.busyMessage
        settingsUiState.busyOperation.usesBlockingOverlay() -> settingsUiState.busyMessage
        else -> null
    }
    val blockingBusyProgressPercent = when {
        mainUiState.busyOperation.usesBlockingOverlay() ->
            mainUiState.busyProgressPercent
        settingsUiState.busyOperation.usesBlockingOverlay() ->
            settingsUiState.busyProgressPercent
        else -> null
    }

    fun selectDockRoute(route: Route) {
        val page = route.launcherDockIndex() ?: return
        forwardPageTransition = isForwardDockTransition(
            from = pendingDockRoute ?: currentRoute.launcherDockRoute()
                ?: dockPagerState.currentLauncherDockRoute(),
            to = route,
        )
        dockNavigationJob?.cancel()
        dockNavigationJob = null
        dockNavigationRequestId += 1
        val requestId = dockNavigationRequestId
        if (!dockPagerState.isScrollInProgress && dockPagerState.settledPage == page) {
            pendingDockRoute = null
            if (currentRoute != route || navigator.stackSize > 1) {
                navigator.resetRoot(route)
            }
            return
        }
        pendingDockRoute = route
        dockNavigationJob = coroutineScope.launch {
            try {
                dockPagerState.animateScrollToPage(page)
                if (dockNavigationRequestId == requestId && pendingDockRoute == route) {
                    pendingDockRoute = null
                    navigator.resetRoot(route)
                }
            } finally {
                if (dockNavigationRequestId == requestId) {
                    dockNavigationJob = null
                    if (pendingDockRoute == route) {
                        pendingDockRoute = null
                    }
                }
            }
        }
    }

    fun checkForEasyTierCompatibilityUpdate() {
        selectDockRoute(Route.Settings)
        settingsViewModel.onManualCheckUpdates(activity)
    }

    fun refreshWorkshopSteamAuth() {
        val appContext = activity.applicationContext
        workshopViewModel.refreshSteamAuth(appContext)
        workshopSubscriptionsViewModel.refreshSteamAuth(appContext)
    }

    fun handleSteamCloudLoginCompleted() {
        refreshWorkshopSteamAuth()
        if (!navigator.popTo(Route.WorkshopSubscriptions) &&
            !navigator.popTo(Route.Workshop) &&
            !navigator.popTo(Route.SettingsMarketCloud) &&
            !navigator.popTo(Route.Settings) &&
            !navigator.popTo(Route.FirstRunSetup)
        ) {
            navigator.goBack()
        }
    }

    fun openFeedbackUpdates() {
        val unreadIssues = feedbackInboxState.subscriptions.filter { it.unread }
        when {
            unreadIssues.size == 1 -> {
                navigator.push(
                    Route.FeedbackConversation(unreadIssues.first().issueNumber)
                )
            }

            else -> {
                navigator.push(Route.FeedbackSubscriptions)
            }
        }
    }

    fun openWorkshopDetail(appId: UInt, publishedFileId: ULong) {
        forwardPageTransition = true
        navigator.push(
            Route.WorkshopDetail(
                publishedFileId = publishedFileId.toString(),
                appId = appId.toLong(),
            )
        )
    }

    fun openWorkshopItemDetails(item: WorkshopItemSummary) {
        openWorkshopDetail(item.appId, item.publishedFileId)
    }

    fun openInstalledWorkshopDetails(mod: ModItemUi) {
        val workshop = mod.workshop ?: return
        openWorkshopDetail(workshop.appId, workshop.publishedFileId)
    }

    LaunchedEffect(Unit) {
        LauncherNavigationRequestBus.workshopDetailRequests.collect(::openWorkshopItemDetails)
    }

    LaunchedEffect(currentRoute) {
        onCurrentDockRouteChanged(currentRoute.launcherDockRoute())
        if (currentRoute != Route.Mods) {
            modsBatchSelectionMode = false
        }
    }

    LaunchedEffect(
        settingsUiState.steamCloudAccountName,
        settingsUiState.steamCloudRefreshTokenConfigured,
        activity,
    ) {
        refreshWorkshopSteamAuth()
    }

    LaunchedEffect(
        currentRoute,
        settingsUiState.steamCloudRefreshTokenConfigured,
        settingsUiState.steamCloudSaveMode,
        activity,
    ) {
        val refreshPlan = planSteamCloudMainRefresh(
            route = currentRoute,
            state = steamCloudMainRefreshTrackerState,
            refreshTokenConfigured = settingsUiState.steamCloudRefreshTokenConfigured,
            saveMode = settingsUiState.steamCloudSaveMode,
        )
        if (steamCloudMainRefreshTrackerState != refreshPlan.nextState) {
            steamCloudMainRefreshTrackerState = refreshPlan.nextState
        }
        if (refreshPlan.shouldRefreshMain) {
            mainViewModel.refresh(activity)
            if (refreshPlan.shouldForceSyncIndicator) {
                mainViewModel.syncSteamCloudIndicatorIfNeeded(
                    host = activity,
                    force = true,
                    userInitiated = false,
                )
            }
        }
    }

    LaunchedEffect(currentRoute) {
        val page = currentRoute.launcherDockIndex() ?: return@LaunchedEffect
        if (pendingDockRoute != null && pendingDockRoute != currentRoute) {
            return@LaunchedEffect
        }
        if (dockPagerState.currentPage != page) {
            dockPagerState.scrollToPage(page)
        }
    }

    LaunchedEffect(showOverlayNav, pendingDockRoute) {
        if (showOverlayNav && pendingDockRoute != null) {
            dockNavigationJob?.cancel()
            dockNavigationJob = null
            pendingDockRoute = null
        }
    }

    LaunchedEffect(dockPagerState, showOverlayNav, currentRoute, navigator.stackSize, pendingDockRoute) {
        snapshotFlow { dockPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (!showOverlayNav && pendingDockRoute == null) {
                    val route = LauncherDockRoutes[page]
                    if (currentRoute != route || navigator.stackSize > 1) {
                        navigator.resetRoot(route)
                    }
                }
            }
    }

    LaunchedEffect(mainUiState.crashRecovery) {
        if (mainUiState.crashRecovery != null && currentRoute != Route.CrashRecovery) {
            navigator.push(Route.CrashRecovery)
        }
    }

    LaunchedEffect(currentRoute, mainUiState.crashRecovery) {
        if (currentRoute == Route.CrashRecovery && mainUiState.crashRecovery == null) {
            navigator.goBack()
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalChromeBackgroundOpacity provides chromeBackgroundOpacity,
    ) {
        SettingsEffectsHandler(viewModel = settingsViewModel)
        LaunchedEffect(activity) {
            FeedbackInboxCoordinator.bind(activity.applicationContext)
            FeedbackInboxCoordinator.syncOnLauncherStart(activity.applicationContext)
        }
        LaunchedEffect(activity, currentRoute) {
            if (currentRoute == Route.Main) {
                onMainScreenOpened()
            }
        }
        LaunchedEffect(activity, transientNoticeHostState) {
            LauncherTransientNoticeBus.requests.collect { request ->
                val result = transientNoticeHostState.showSnackbar(
                    message = request.message.resolve(activity),
                    actionLabel = request.actionLabel?.resolve(activity),
                    duration = when (request.duration) {
                        LauncherTransientNoticeDuration.SHORT -> SnackbarDuration.Short
                        LauncherTransientNoticeDuration.LONG -> SnackbarDuration.Long
                    }
                )
                if (result == SnackbarResult.ActionPerformed) {
                    request.onAction?.invoke()
                }
            }
        }
        LaunchedEffect(activity) {
            WorkshopUpdateCheckCoordinator.completionNotices.collect { completion ->
                LauncherTransientNoticeBus.show(
                    UiText.DynamicString(completion.toNoticeMessage())
                )
            }
        }
        BackHandler(enabled = isBlockingBusyInteractionLocked) {
            // Keep system back from dismissing the launcher while a blocking operation owns input.
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { scaffoldPadding ->
                    if (showDockPager) {
                        LauncherDockPager(
                            pagerState = dockPagerState,
                            mainViewModel = mainViewModel,
                            settingsViewModel = settingsViewModel,
                            workshopViewModel = workshopViewModel,
                            feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                            feedbackActiveIssueCount = feedbackInboxState.subscriptions.count { !it.isClosed },
                            feedbackSubmissionNotice = pendingFeedbackNotice,
                            onDismissFeedbackSubmissionNotice = {
                                pendingFeedbackNotice = null
                            },
                            onOpenFeedback = { navigator.push(Route.Feedback) },
                            onOpenWorkshop = { selectDockRoute(Route.Workshop) },
                            onOpenFeedbackUpdates = { openFeedbackUpdates() },
                            onOpenFeedbackSubscriptions = { navigator.push(Route.FeedbackSubscriptions) },
                            onOpenSteamLogin = { navigator.push(Route.SteamCloudLogin) },
                            onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                            onOpenSubscriptions = { navigator.push(Route.WorkshopSubscriptions) },
                            onOpenWorkshopDetails = ::openWorkshopItemDetails,
                            onOpenInstalledWorkshopDetails = ::openInstalledWorkshopDetails,
                            onCheckEasyTierCompatibilityUpdate = ::checkForEasyTierCompatibilityUpdate,
                            onBatchSelectionModeChange = { modsBatchSelectionMode = it },
                            userScrollEnabled = !modsBatchSelectionMode && !showOverlayNav,
                            handleMainEffects = !showOverlayNav,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(scaffoldPadding)
                                .hazeSource(state = launcherDockHazeState),
                        )
                    }
                    NavDisplay(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(scaffoldPadding),
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            onBack = {
                                if (!isBlockingBusyInteractionLocked) {
                                    if (currentRoute == Route.CrashRecovery) {
                                        mainViewModel.dismissCrashRecovery()
                                    } else {
                                        navigator.goBack()
                                    }
                                }
                            },
                            backStack = navigator.backStack,
                            transitionSpec = {
                                if (currentRoute == Route.FeedbackSubscriptions) {
                                    leftPageTransition()
                                } else {
                                    horizontalPageTransition(forward = forwardPageTransition)
                                }
                            },
                            popTransitionSpec = {
                                if (currentRoute == Route.FeedbackSubscriptions) {
                                    leftPageTransition()
                                } else {
                                    horizontalPageTransition(forward = false)
                                }
                            },
                            entryProvider = entryProvider {
                        entry<Route.QuickStart> {
                            QuickStartScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenAutoImport = { navigator.push(Route.QuickStartAutoImport) },
                                onOpenSteamLogin = { navigator.push(Route.QuickStartSteamLogin) },
                                onOpenJarImport = { navigator.push(Route.QuickStartJarImport) },
                                onOpenSteamDownload = { navigator.push(Route.QuickStartSteamDownload) },
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.QuickStartAutoImport> {
                            QuickStartAutomaticImportScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSteamLogin = {
                                    navigator.resetRoot(Route.QuickStartSteamLogin)
                                },
                                onChooseAnotherImportMode = {
                                    navigator.resetRoot(Route.QuickStart)
                                },
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.QuickStartJarImport> {
                            QuickStartJarImportScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.QuickStartSteamDownload> {
                            QuickStartSteamDownloadScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onImportSuccess = {
                                    navigator.resetRoot(
                                        if (LauncherPreferences.isFirstRunSetupCompleted(activity)) {
                                            Route.Main
                                        } else {
                                            Route.FirstRunSetup
                                        }
                                    )
                                }
                            )
                        }

                        entry<Route.FirstRunSetup> {
                            LauncherFirstRunSetupScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Main> {
                            if (showDockPager) {
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                LauncherMainScreen(
                                    viewModel = mainViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenFeedback = { navigator.push(Route.Feedback) },
                                    onOpenWorkshop = { selectDockRoute(Route.Workshop) },
                                    onOpenWorkshopDetails = ::openInstalledWorkshopDetails,
                                    updateNotice = updateNotice,
                                    feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                                    feedbackActiveIssueCount = feedbackInboxState.subscriptions.count { !it.isClosed },
                                    onOpenFeedbackUpdates = { openFeedbackUpdates() },
                                    onOpenFeedbackSubscriptions = { navigator.push(Route.FeedbackSubscriptions) },
                                    onUpdateNoticeClick = settingsViewModel::showUpdatePrompt,
                                    onEasyTierCompatibilityUpdateClick = ::checkForEasyTierCompatibilityUpdate,
                                )
                            }
                        }

                        entry<Route.CrashRecovery> {
                            LauncherCrashRecoveryScreen(
                                viewModel = mainViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onBack = {
                                    mainViewModel.dismissCrashRecovery()
                                },
                                onOpenSettings = { navigator.push(Route.Settings) },
                                onOpenFeedback = { navigator.push(Route.Feedback) },
                                onReturnToMainMenu = {
                                    mainViewModel.dismissCrashRecovery()
                                    navigator.resetRoot(Route.Main)
                                }
                            )
                        }

                        entry<Route.Mods> {
                            if (showDockPager) {
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                LauncherModsScreen(
                                    viewModel = mainViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenFeedback = { navigator.push(Route.Feedback) },
                                    onOpenWorkshop = { selectDockRoute(Route.Workshop) },
                                    onOpenWorkshopDetails = ::openInstalledWorkshopDetails,
                                    feedbackUnreadCount = feedbackInboxState.unreadIssueCount,
                                    onOpenFeedbackUpdates = { openFeedbackUpdates() },
                                    onBatchSelectionModeChange = { modsBatchSelectionMode = it }
                                )
                            }
                        }

                        entry<Route.Settings> {
                            if (showDockPager) {
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                LauncherSettingsScreen(
                                    viewModel = settingsViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    showBackButton = navigator.stackSize > 1,
                                    feedbackSubmissionNotice = pendingFeedbackNotice,
                                    onDismissFeedbackSubmissionNotice = {
                                        pendingFeedbackNotice = null
                                    }
                                )
                            }
                        }

                        entry<Route.SettingsLauncher> {
                            LauncherSettingsLauncherScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SettingsGame> {
                            LauncherSettingsGameScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SettingsPerformance> {
                            LauncherSettingsPerformanceScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SettingsMarketCloud> {
                            LauncherSettingsMarketCloudScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SettingsWorkshopAutoImportDefaults> {
                            LauncherSettingsWorkshopAutoImportDefaultsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SettingsFeedback> {
                            LauncherSettingsFeedbackScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                feedbackSubmissionNotice = pendingFeedbackNotice,
                                onDismissFeedbackSubmissionNotice = {
                                    pendingFeedbackNotice = null
                                },
                            )
                        }

                        entry<Route.SettingsAbout> {
                            LauncherSettingsAboutScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Workshop> {
                            if (showDockPager) {
                                Box(modifier = Modifier.fillMaxSize())
                            } else {
                                WorkshopScreen(
                                    viewModel = workshopViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    showBackButton = navigator.stackSize > 1,
                                    showSubscriptionsButton = true,
                                    onBack = { navigator.goBack() },
                                    onOpenSteamLogin = { navigator.push(Route.SteamCloudLogin) },
                                    onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                                    onOpenSubscriptions = { navigator.push(Route.WorkshopSubscriptions) },
                                    onOpenDetails = ::openWorkshopItemDetails,
                                )
                            }
                        }

                        entry<Route.WorkshopSubscriptions> {
                            WorkshopSubscriptionsScreen(
                                viewModel = workshopSubscriptionsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.goBack() },
                                onOpenSteamLogin = { navigator.push(Route.SteamCloudLogin) },
                                onOpenDownloadCenter = { navigator.push(Route.WorkshopDownloadCenter) },
                                onOpenDetails = ::openWorkshopItemDetails,
                            )
                        }

                        entry<Route.WorkshopDetail> { route ->
                            val publishedFileId = route.publishedFileId.toULongOrNull() ?: 0u
                            WorkshopDetailScreen(
                                appId = route.appId.toUInt(),
                                publishedFileId = publishedFileId,
                                viewModel = workshopViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.goBack() },
                                onOpenBaiduTranslationCredentials = { notice ->
                                    navigator.push(Route.BaiduTranslationCredentials(notice))
                                },
                                onOpenDetails = ::openWorkshopItemDetails,
                            )
                        }

                        entry<Route.WorkshopDownloadCenter> {
                            val context = LocalContext.current
                            WorkshopDownloadCenterScreen(
                                modifier = Modifier.fillMaxSize(),
                                onBack = { navigator.goBack() },
                                onPause = { workshopViewModel.pauseDownload(context.applicationContext, it) },
                                onResume = { workshopViewModel.resumeDownload(context.applicationContext, it) },
                                onCancel = { workshopViewModel.cancelDownload(context.applicationContext, it) },
                                onRetry = { workshopViewModel.retryDownload(context.applicationContext, it) },
                            )
                        }

                        entry<Route.BaiduTranslationCredentials> { route ->
                            LauncherBaiduTranslationCredentialsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                notice = route.notice,
                                onBack = { navigator.goBack() },
                            )
                        }

                        entry<Route.SteamCloudLogin> {
                            LauncherSteamCloudLoginScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onLoginCompleted = ::handleSteamCloudLoginCompleted,
                            )
                        }

                        entry<Route.QuickStartSteamLogin> {
                            LauncherSteamCloudLoginScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                challengeRoute = Route.QuickStartSteamMethod,
                                guardRoute = Route.QuickStartSteamGuard,
                                loginRoute = Route.QuickStartSteamLogin,
                                onLoginCompleted = {
                                    navigator.resetRoot(Route.QuickStartSteamDownload)
                                },
                            )
                        }

                        entry<Route.SteamCloudGuard> {
                            LauncherSteamCloudGuardScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                onLoginCompleted = ::handleSteamCloudLoginCompleted,
                            )
                        }

                        entry<Route.SteamCloudLoginMethod> {
                            LauncherSteamCloudLoginMethodScreen(
                                viewModel = settingsViewModel,
                                guardRoute = Route.SteamCloudGuard,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.QuickStartSteamMethod> {
                            LauncherSteamCloudLoginMethodScreen(
                                viewModel = settingsViewModel,
                                guardRoute = Route.QuickStartSteamGuard,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.QuickStartSteamGuard> {
                            LauncherSteamCloudGuardScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                                returnToLoginRoute = Route.QuickStartSteamLogin,
                                onLoginCompleted = {
                                    navigator.resetRoot(Route.QuickStartSteamDownload)
                                },
                            )
                        }

                        entry<Route.SteamCloudSaveSettings> {
                            LauncherSteamCloudSaveSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.SteamCloudSyncBlacklistSettings> {
                            LauncherSteamCloudSyncBlacklistSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.DeveloperSettings> {
                            LauncherDeveloperSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.NativeLibraryMarket> {
                            LauncherNativeLibraryMarketScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Compatibility> {
                            LauncherCompatibilityScreen(
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.MobileGluesSettings> {
                            LauncherMobileGluesSettingsScreen(
                                viewModel = settingsViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        entry<Route.Feedback> {
                            LauncherFeedbackScreen(
                                modifier = Modifier.fillMaxSize(),
                                onSubmissionCompleted = { notice ->
                                    pendingFeedbackNotice = notice
                                    navigator.goBack()
                                },
                            )
                        }

                        entry<Route.FeedbackSubscriptions> {
                            LauncherFeedbackSubscriptionsScreen(
                                modifier = Modifier.fillMaxSize(),
                                onOpenConversation = { issueNumber ->
                                    navigator.push(Route.FeedbackConversation(issueNumber))
                                }
                            )
                        }

                        entry<Route.FeedbackIssueBrowser> {
                            LauncherFeedbackIssueBrowserScreen(
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        entry<Route.FeedbackConversation> { route ->
                            LauncherFeedbackConversationScreen(
                                modifier = Modifier.fillMaxSize(),
                                issueNumber = route.issueNumber
                            )
                        }

                        entry<Route.FeedbackIssuePreview> { route ->
                            LauncherFeedbackIssuePreviewScreen(
                                modifier = Modifier.fillMaxSize(),
                                issueNumber = route.issueNumber
                            )
                        }
                    }
                    )
                }
                AnimatedVisibility(
                    visible = showAnimatedLauncherDock,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = DOCK_VISIBILITY_ANIMATION_MS)
                    ) + fadeIn(animationSpec = tween(durationMillis = DOCK_VISIBILITY_ANIMATION_MS)),
                    exit = slideOutVertically(
                        targetOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = DOCK_VISIBILITY_ANIMATION_MS)
                    ) + fadeOut(animationSpec = tween(durationMillis = DOCK_VISIBILITY_ANIMATION_MS)),
                    label = "launcherDockVisibility"
                ) {
                    LauncherDockBar(
                        hazeState = launcherDockHazeState,
                        currentRoute = dockPageRoute,
                        onSelectRoute = { route -> selectDockRoute(route) },
                    )
                }
                if (shouldShowBlockingBusyWindow) {
                    BlockingBusyInteractionBlocker(
                        message = blockingBusyMessage?.resolve()
                            ?: stringResource(R.string.mod_import_busy_message),
                        progressPercent = blockingBusyProgressPercent
                    )
                }
                ModImportHost(
                    onImportCompleted = {
                        mainViewModel.refresh(activity)
                        settingsViewModel.refreshStatus(activity)
                    }
                )
                SnackbarHost(
                    hostState = transientNoticeHostState,
                    snackbar = { snackbarData ->
                        Snackbar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(text = snackbarData.visuals.message)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        snackbarData.visuals.actionLabel?.let { actionLabel ->
                                            TextButton(onClick = { snackbarData.performAction() }) {
                                                Text(text = actionLabel)
                                            }
                                        }
                                        Button(onClick = { snackbarData.dismiss() }) {
                                            Text(text = stringResource(R.string.common_action_close))
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                )
                settingsUiState.updatePromptState?.let { promptState ->
                    val quarkDownloadUrl = stringResource(R.string.update_dialog_quark_download_url)
                    val cloudControlSettings by rememberCloudControlSettings()
                    val qqGroupNumber = cloudControlSettings.qqGroupNumber
                    val qqGroupUrl = cloudControlSettings.qqGroupUrl
                    var showDownloadChoiceDialog by remember(promptState) {
                        mutableStateOf(false)
                    }
                    if (showDownloadChoiceDialog) {
                        AlertDialog(
                            onDismissRequest = { showDownloadChoiceDialog = false },
                            title = {
                                Text(stringResource(R.string.update_download_choice_dialog_title))
                            },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 460.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.update_download_choice_dialog_message
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            confirmButton = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            showDownloadChoiceDialog = false
                                            settingsViewModel.dismissUpdatePrompt()
                                            copyQqGroupAndOpen(activity, qqGroupNumber, qqGroupUrl)
                                        }
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.update_download_choice_dialog_action_join_group
                                            )
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            showDownloadChoiceDialog = false
                                            settingsViewModel.dismissUpdatePrompt()
                                            copyAndOpenQuarkDownload(activity, quarkDownloadUrl)
                                        }
                                    ) {
                                        Text(stringResource(R.string.update_dialog_action_quark_download))
                                    }
                                }
                            }
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = settingsViewModel::dismissUpdatePrompt,
                            title = { Text(stringResource(R.string.update_dialog_title)) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_current_version,
                                            promptState.currentVersion
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_latest_version,
                                            promptState.latestVersion
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.update_dialog_download_source,
                                            promptState.downloadSourceDisplayName
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    SimpleMarkdownCard(
                                        title = stringResource(R.string.update_dialog_notes_title),
                                        markdown = promptState.notesText
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showDownloadChoiceDialog = true }) {
                                    Text(stringResource(R.string.update_dialog_action_download))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = settingsViewModel::dismissUpdatePrompt) {
                                    Text(stringResource(R.string.update_dialog_action_later))
                                }
                            }
                        )
                    }
                }
                settingsUiState.stsJarIntegrityDialogState?.let { dialogState ->
                    StsJarIntegrityDialogHost(
                        dialogState = dialogState,
                        onDismiss = settingsViewModel::dismissStsJarIntegrityDialog,
                        onRequestForceImport = settingsViewModel::requestStsJarForceImportConfirmation,
                        onDismissForceConfirm = settingsViewModel::dismissStsJarForceImportConfirmation,
                        onConfirmForceImport = { settingsViewModel.confirmPendingStsJarForceImport(activity) }
                    )
                }
            feedbackInboxState.pendingNotice?.let { notice ->
                AlertDialog(
                    onDismissRequest = FeedbackInboxCoordinator::dismissUnreadNotice,
                    title = { Text(stringResource(R.string.main_feedback_notice_title)) },
                    text = {
                        Text(
                            if (notice.unreadIssueCount == 1) {
                                stringResource(R.string.main_feedback_notice_single)
                            } else {
                                stringResource(
                                    R.string.main_feedback_notice_multiple,
                                    notice.unreadIssueCount
                                )
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                FeedbackInboxCoordinator.dismissUnreadNotice()
                                val unreadIssues = feedbackInboxState.subscriptions.filter { it.unread }
                                when {
                                    unreadIssues.size == 1 -> {
                                        navigator.push(
                                            Route.FeedbackConversation(unreadIssues.first().issueNumber)
                                        )
                                    }

                                    else -> {
                                        navigator.push(Route.FeedbackSubscriptions)
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.main_feedback_notice_open))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = FeedbackInboxCoordinator::dismissUnreadNotice) {
                            Text(stringResource(R.string.main_feedback_notice_later))
                        }
                    }
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherDockPager(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    mainViewModel: MainScreenViewModel,
    settingsViewModel: SettingsScreenViewModel,
    workshopViewModel: WorkshopViewModel,
    feedbackUnreadCount: Int,
    feedbackActiveIssueCount: Int,
    feedbackSubmissionNotice: FeedbackSubmissionNotice?,
    onDismissFeedbackSubmissionNotice: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onOpenFeedbackUpdates: () -> Unit,
    onOpenFeedbackSubscriptions: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onOpenDownloadCenter: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenWorkshopDetails: (WorkshopItemSummary) -> Unit,
    onOpenInstalledWorkshopDetails: (ModItemUi) -> Unit,
    onCheckEasyTierCompatibilityUpdate: () -> Unit,
    onBatchSelectionModeChange: (Boolean) -> Unit,
    userScrollEnabled: Boolean,
    handleMainEffects: Boolean,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val settingsUiState = settingsViewModel.uiState
    val updateNotice = settingsUiState.availableUpdatePromptState?.let { promptState ->
        LauncherUpdateNoticeUiState(
            currentVersion = promptState.currentVersion,
            latestVersion = promptState.latestVersion,
        )
    }
    val workshopUpdateCheckState by WorkshopUpdateCheckCoordinator.uiState.collectAsState()
    val workshopUiState = workshopViewModel.uiState
    val workshopDownloadTaskStatuses = WorkshopDownloadCenterStore.taskStatuses
    val currentDockRoute = pagerState.currentLauncherDockRoute()
    val shouldPollMainWorkshopDownloads = handleMainEffects &&
        (currentDockRoute == Route.Main || currentDockRoute == Route.Mods)
    val shouldPollWorkshopScreenDownloads = handleMainEffects && currentDockRoute == Route.Workshop

    LaunchedEffect(context) {
        WorkshopUpdateCheckCoordinator.bind(context.applicationContext)
    }

    LaunchedEffect(workshopUpdateCheckState.lastCompletedAtMs) {
        val hostActivity = activity
        if (hostActivity != null && workshopUpdateCheckState.lastCompletedAtMs > 0L) {
            mainViewModel.refresh(hostActivity)
        }
    }

    LauncherMainRoute(
        modifier = modifier,
        viewModel = mainViewModel,
        onOpenWorkshop = onOpenWorkshop,
        onOpenWorkshopDetails = onOpenInstalledWorkshopDetails,
        handleEffects = handleMainEffects,
        pollWorkshopDownloads = shouldPollMainWorkshopDownloads,
    ) { routeModifier, uiState, actions ->
        HorizontalPager(
            state = pagerState,
            modifier = routeModifier,
            beyondViewportPageCount = LauncherDockRoutes.lastIndex,
            userScrollEnabled = userScrollEnabled,
            key = { page -> LauncherDockRoutes[page].launcherDockTagSuffix() },
        ) { page ->
            when (LauncherDockRoutes[page]) {
                Route.Main -> {
                    LauncherGameScreenContent(
                        modifier = Modifier.fillMaxSize(),
                        uiState = uiState,
                        actions = actions,
                        updateNotice = updateNotice,
                        onOpenFeedback = onOpenFeedback,
                        feedbackUnreadCount = feedbackUnreadCount,
                        feedbackActiveIssueCount = feedbackActiveIssueCount,
                        onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                        onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
                        onUpdateNoticeClick = settingsViewModel::showUpdatePrompt,
                        onEasyTierCompatibilityUpdateClick = onCheckEasyTierCompatibilityUpdate,
                        showSteamCloudBottomSheetHost = currentDockRoute == Route.Main,
                        tutorialWorkshopDownloadState = { item ->
                            resolveWorkshopModDownloadState(
                                item = item,
                                installedMods = workshopUiState.installedMods,
                                downloadTaskStatuses = workshopDownloadTaskStatuses,
                                preparingDownloadIds = workshopUiState.preparingDownloadIds,
                            )
                        },
                        onOpenTutorialWorkshopDetails = onOpenWorkshopDetails,
                        onDownloadTutorialWorkshopItem = { item ->
                            workshopViewModel.download(context.applicationContext, item)
                        },
                    )
                }

                Route.Mods -> {
                    LauncherModsScreenContent(
                        modifier = Modifier.fillMaxSize(),
                        uiState = uiState,
                        actions = actions,
                        onOpenFeedback = onOpenFeedback,
                        onOpenWorkshop = onOpenWorkshop,
                        feedbackUnreadCount = feedbackUnreadCount,
                        onOpenFeedbackUpdates = onOpenFeedbackUpdates,
                        workshopUpdateCheckState = workshopUpdateCheckState,
                        onBatchSelectionModeChange = onBatchSelectionModeChange,
                        showSteamCloudBottomSheetHost = currentDockRoute == Route.Mods,
                        onCheckWorkshopUpdates = {
                            WorkshopUpdateCheckCoordinator.requestCheck(
                                context = context.applicationContext,
                                force = true,
                                notifyResult = true,
                            )
                            LauncherTransientNoticeBus.show(
                                UiText.StringResource(R.string.main_workshop_checking_notice)
                            )
                        },
                    )
                }

                Route.Workshop -> {
                    WorkshopScreen(
                        viewModel = workshopViewModel,
                        modifier = Modifier.fillMaxSize(),
                        showBackButton = false,
                        showSubscriptionsButton = true,
                        active = shouldPollWorkshopScreenDownloads,
                        onBack = {},
                        onOpenSteamLogin = onOpenSteamLogin,
                        onOpenDownloadCenter = onOpenDownloadCenter,
                        onOpenSubscriptions = onOpenSubscriptions,
                        onOpenDetails = onOpenWorkshopDetails,
                    )
                }

                Route.Settings -> {
                    LauncherSettingsScreen(
                        viewModel = settingsViewModel,
                        modifier = Modifier.fillMaxSize(),
                        showBackButton = false,
                        feedbackSubmissionNotice = feedbackSubmissionNotice,
                        onDismissFeedbackSubmissionNotice = onDismissFeedbackSubmissionNotice,
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun LauncherDockBar(
    modifier: Modifier = Modifier,
    hazeState: HazeState,
    currentRoute: Route?,
    onSelectRoute: (Route) -> Unit,
) {
    val selectedRoute = currentRoute.launcherDockRoute() ?: Route.Main
    FrostedGlassChrome(
        modifier = modifier
            .fillMaxWidth(),
        hazeState = hazeState,
        shape = RoundedCornerShape(0.dp),
        contentPadding = PaddingValues(0.dp),
        showBorder = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherDockItem(
                selected = selectedRoute == Route.Main,
                route = Route.Main,
                iconResId = R.drawable.ic_dock_game,
                label = stringResource(R.string.main_dock_game),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Mods,
                route = Route.Mods,
                iconResId = R.drawable.ic_dock_mods,
                label = stringResource(R.string.main_dock_mods),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Workshop,
                route = Route.Workshop,
                iconResId = R.drawable.ic_dock_market,
                label = stringResource(R.string.main_dock_market),
                onSelectRoute = onSelectRoute,
            )
            LauncherDockItem(
                selected = selectedRoute == Route.Settings,
                route = Route.Settings,
                iconResId = R.drawable.ic_dock_settings,
                label = stringResource(R.string.main_dock_settings),
                onSelectRoute = onSelectRoute,
            )
        }
    }
}

@Composable
private fun RowScope.LauncherDockItem(
    selected: Boolean,
    route: Route,
    @androidx.annotation.DrawableRes iconResId: Int,
    label: String,
    onSelectRoute: (Route) -> Unit,
) {
    val dockItemShape = RoundedCornerShape(20.dp)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(58.dp)
            .testTag(LAUNCHER_DOCK_ITEM_TAG_PREFIX + route.launcherDockTagSuffix())
            .clip(dockItemShape)
            .clickable { onSelectRoute(route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            contentColor = contentColor,
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

private fun horizontalPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
        initialOffsetX = { fullWidth -> fullWidth * direction }
    ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
            targetOffsetX = { fullWidth -> -fullWidth * direction }
        )
    )
}

private fun leftPageTransition(): ContentTransform {
    return slideInHorizontally(
        animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
        initialOffsetX = { fullWidth -> -fullWidth }
    ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(durationMillis = PAGE_TRANSITION_DURATION_MS),
            targetOffsetX = { fullWidth -> -fullWidth }
        )
    )
}

private fun isForwardDockTransition(from: Route?, to: Route?): Boolean {
    val fromIndex = from.launcherDockIndex()
    val toIndex = to.launcherDockIndex()
    return if (fromIndex != null && toIndex != null && fromIndex != toIndex) {
        toIndex > fromIndex
    } else {
        true
    }
}

private fun Route?.launcherDockIndex(): Int? {
    return when (this) {
        Route.Main -> 0
        Route.Mods -> 1
        Route.Workshop -> 2
        Route.Settings -> 3
        else -> null
    }
}

private fun PagerState.currentLauncherDockRoute(): Route {
    return LauncherDockRoutes[currentPage.coerceIn(LauncherDockRoutes.indices)]
}

private fun io.stamethyst.backend.workshop.WorkshopUpdateCheckCompletion.toNoticeMessage(): String {
    val error = errorSummary
    if (!error.isNullOrBlank()) {
        return "模组更新检查失败：$error"
    }
    val base = if (updateCount > 0) {
        "检查完成，发现 $updateCount 个可更新模组"
    } else {
        "检查完成，所有模组已为最新"
    }
    return if (failedCount > 0) {
        "$base，$failedCount 个检查失败"
    } else {
        base
    }
}

private fun Route?.launcherDockRoute(): Route? {
    return when (this) {
        Route.Main -> Route.Main
        Route.Mods -> Route.Mods
        Route.Workshop -> Route.Workshop
        Route.WorkshopSubscriptions -> Route.Workshop
        Route.Settings -> Route.Settings
         Route.SettingsLauncher,
         Route.SettingsGame,
         Route.SettingsPerformance,
         Route.SettingsMarketCloud,
        Route.SettingsWorkshopAutoImportDefaults,
        Route.SettingsFeedback,
        Route.SettingsAbout -> Route.Settings
        Route.CrashRecovery,
        is Route.WorkshopDetail,
        Route.WorkshopDownloadCenter,
         Route.SteamCloudLogin,
         Route.SteamCloudLoginMethod,
         Route.SteamCloudGuard,
        Route.SteamCloudSaveSettings,
        Route.SteamCloudSyncBlacklistSettings,
        is Route.BaiduTranslationCredentials,
        Route.DeveloperSettings,
        Route.NativeLibraryMarket,
        Route.Compatibility,
        Route.MobileGluesSettings,
        Route.QuickStart,
        Route.QuickStartAutoImport,
         Route.QuickStartSteamLogin,
         Route.QuickStartSteamMethod,
         Route.QuickStartSteamGuard,
        Route.QuickStartSteamDownload,
        Route.QuickStartJarImport,
        Route.FirstRunSetup,
        Route.Feedback,
        Route.FeedbackSubscriptions,
        Route.FeedbackIssueBrowser,
        is Route.FeedbackConversation,
        is Route.FeedbackIssuePreview,
        null -> null
    }
}

private fun Route.launcherDockTagSuffix(): String = when (this) {
    Route.Main -> "Main"
    Route.Mods -> "Mods"
    Route.Workshop -> "Workshop"
    Route.Settings -> "Settings"
    else -> this::class.simpleName.orEmpty()
}

private fun copyAndOpenQuarkDownload(context: Context, url: String) {
    copyToClipboard(context, "quark-download-url", url)
    val quarkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setPackage(QUARK_BROWSER_PACKAGE_NAME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!tryStartActivity(context, quarkIntent)) {
        openExternalUrl(context, url)
    }
}

private fun copyQqGroupAndOpen(context: Context, groupNumber: String, groupUrl: String) {
    copyToClipboard(context, "qq-group", groupNumber)
    openExternalUrl(context, groupUrl)
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    tryStartActivity(context, intent)
}

private fun tryStartActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
private fun BlockingBusyInteractionBlocker(
    message: String,
    progressPercent: Int? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .pointerInteropFilter { true },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val progressFraction = progressPercent
                    ?.coerceIn(0, 100)
                    ?.div(100f)
                if (progressFraction != null) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
