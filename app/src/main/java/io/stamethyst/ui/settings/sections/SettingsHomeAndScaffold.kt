package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import io.stamethyst.R
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.backend.workshop.WorkshopBrowseSort
import io.stamethyst.navigation.Route
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayArtwork
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.RichPresenceDisplayPreferences
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.config.TouchscreenInputMode
import io.stamethyst.ui.AppSearchBar
import io.stamethyst.ui.SearchHistoryStore
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.preferences.LauncherPreferences


@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, heightDp = 2000)
@Composable
internal fun LauncherSettingsScreenPreview() {
    LauncherSettingsScreenContent(
        uiState = SettingsScreenViewModel.UiState(
            busy = false,
            playerName = "player",
            selectedRenderScale = 1.00f,
             selectedTargetFps = 120f,
            virtualResolutionMode = VirtualResolutionMode.FULLSCREEN_FILL,
            renderSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            themeMode = LauncherThemeMode.FOLLOW_SYSTEM,
            themeColor = LauncherThemeColor.COLORLESS,
            selectedJvmHeapMaxMb = 512,
            compressedPointersEnabled = false,
            stringDeduplicationEnabled = false,
            jvmHeapMinMb = 256,
            jvmHeapMaxMb = 2048,
            jvmHeapStepMb = 128,
            backBehavior = BackBehavior.EXIT_TO_LAUNCHER,
            manualDismissBootOverlay = false,
            specialKeyInputMode = SpecialKeyInputMode.BUILT_IN_MOD,
            showFloatingMouseWindow = false,
            touchMouseInteractionMode = TouchMouseInteractionMode.OPEN_MENU_ON_TAP,
            builtInSoftKeyboardEnabled = true,
            autoPopupKeyboardEnabled = false,
            hapticFeedbackEnabled = true,
            autoSwitchLeftAfterRightClick = true,
            showModFileName = false,
            mobileHudEnabled = false,
            avoidDisplayCutout = false,
            cropScreenBottom = false,
            showGamePerformanceOverlay = false,
            keepScreenOnTimeoutMinutes = LauncherPreferences.DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
            sustainedPerformanceModeEnabled = true,
            lwjglDebugEnabled = false,
            preloadAllJreLibrariesEnabled = false,
            logcatCaptureEnabled = true,
            launcherLogcatCaptureEnabled = true,
            jvmLogcatMirrorEnabled = false,
            gpuResourceDiagEnabled = false,
            gdxPadCursorDebugEnabled = false,
            glBridgeSwapHeartbeatDebugEnabled = false,
            touchscreenInputMode = TouchscreenInputMode.HYBRID,
            cardPlayOptimizationMode = CardPlayOptimizationMode.RELEASE_POP_BACK,
            gameplayFontScale = 1.50f,
            gameplayLargerUiEnabled = GameplaySettingsService.DEFAULT_LARGER_UI_ENABLED,
            statusText = "desktop-1.0.jar: OK\nBaseMod.jar: OK\nStSLib.jar: OK\nAmethystRuntimeCompat.jar: OK",
            logPathText = "/example/path/to/logs",
             targetFpsOptions = listOf(120f, 60f, 40f, 30f, 24f),
            keepScreenOnTimeoutMinuteOptions = listOf(0, 5, 10, 30, 60),
            updateStatusSummary = "最近检查：2026-03-09 11:20\n远端版本：1.0.6-hotfix1\n结果：发现新版本\n下载源：gh-proxy.com",
        ),
        feedbackSubmissionNotice = FeedbackSubmissionNotice(
            title = "反馈已提交",
            message = "GitHub Issue #10 已创建。",
            issueUrl = "https://github.com/ModinMobileSTS/SlayTheAmethystModded/issues/10"
        )
    )
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherSettingsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    showBackButton: Boolean = true,
    onOpenSettingsRoute: (Route) -> Unit = {},
    searchActions: SettingsSearchActions? = null,
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val blockingInteractionLocked = uiState.busyOperation.locksInteraction(uiState.busy)
    val context = LocalContext.current.applicationContext
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchHistoryExpanded by remember { mutableStateOf(false) }
    var wasSearchKeyboardVisible by remember { mutableStateOf(false) }
    var searchHistory by remember(context) {
        mutableStateOf(SearchHistoryStore.loadSettingsSearchHistory(context))
    }
    val searchKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(searchKeyboardVisible) {
        if (
            wasSearchKeyboardVisible &&
            !searchKeyboardVisible &&
            searchHistoryExpanded
        ) {
            focusManager.clearFocus(force = true)
            searchHistoryExpanded = false
        }
        wasSearchKeyboardVisible = searchKeyboardVisible
    }
    fun submitSettingsSearch(query: String = searchQuery) {
        val normalizedQuery = query.trim()
        keyboardController?.hide()
        searchHistoryExpanded = false
        if (normalizedQuery.isNotEmpty()) {
            searchHistory = SearchHistoryStore.recordSettingsSearch(context, normalizedQuery)
        }
    }
    val filterKeyword = searchQuery.trim()
    val searchActive = filterKeyword.isNotEmpty()
    val resolvedSearchEntries = SettingsSearchEntries.map { entry ->
        ResolvedSettingsSearchEntry(
            entry = entry,
            title = stringResource(entry.titleResId),
            subtitle = entry.subtitleResId?.let { stringResource(it) },
        )
    }
    val searchSections = if (searchActive) {
        SettingsHomeDestinations.mapNotNull { destination ->
            val categoryTitleResId = destination.spec.titleResId
            val categoryTitle = stringResource(categoryTitleResId)
            val categorySubtitle = stringResource(destination.spec.subtitleResId)
            val categoryMatched =
                categoryTitle.contains(filterKeyword, ignoreCase = true) ||
                    categorySubtitle.contains(filterKeyword, ignoreCase = true)
            val matchingEntries = resolvedSearchEntries.filter { item ->
                item.entry.categoryTitleResId == categoryTitleResId &&
                    (
                        categoryMatched ||
                            item.title.contains(filterKeyword, ignoreCase = true) ||
                            item.subtitle?.contains(filterKeyword, ignoreCase = true) == true
                        )
            }
            if (!categoryMatched && matchingEntries.isEmpty()) {
                null
            } else {
                SettingsSearchSection(
                    destination = destination,
                    categoryTitle = categoryTitle,
                    categoryMatched = categoryMatched,
                    entries = matchingEntries,
                )
            }
        }
    } else {
        emptyList()
    }

    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsHomeRouteSpec,
        showBackButton = showBackButton,
        onGoBack = onGoBack,
    ) {
        item(key = "settings_search") {
            AppSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { submitSettingsSearch(it) },
                expanded = searchHistoryExpanded,
                onExpandedChange = { searchHistoryExpanded = it },
                history = searchHistory,
                onHistorySelected = { selected ->
                    searchQuery = selected
                    submitSettingsSearch(selected)
                },
                onHistoryDeleted = { entry ->
                    searchHistory = SearchHistoryStore.deleteSettingsSearch(context, entry)
                },
                placeholder = stringResource(R.string.settings_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp),
            )
        }
        if (searchActive) {
            if (searchSections.isEmpty()) {
                item(key = "settings_search_empty") {
                    Text(
                        text = stringResource(R.string.settings_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
            searchSections.forEach { section ->
                item(key = "search_section_${section.destination.route}") {
                    val sectionContent: @Composable () -> Unit = {
                        SettingsSectionCard(title = section.categoryTitle) {
                            if (section.categoryMatched && section.entries.isEmpty()) {
                                SettingsActionListItem(
                                    title = section.categoryTitle,
                                    supportingText = stringResource(section.destination.spec.subtitleResId),
                                    enabled = !blockingInteractionLocked,
                                    onClick = { onOpenSettingsRoute(section.destination.route) },
                                )
                            }
                            section.entries.forEach { item ->
                                key(item.entry.titleResId) {
                                    SettingsSearchResultItem(
                                        entry = item.entry,
                                        uiState = uiState,
                                        actions = searchActions,
                                        onOpenSettingsRoute = onOpenSettingsRoute,
                                    )
                                }
                            }
                        }
                    }
                    if (searchActions != null && section.destination.route == Route.DeveloperSettings) {
                        SettingsSearchDeveloperWarning(sectionContent)
                    } else {
                        sectionContent()
                    }
                }
            }
        } else {
            SettingsHomeDestinations.forEach { destination ->
                item(key = destination.route.toString()) {
                    val spec = destination.spec
                    SettingsCategoryCard(
                        iconResId = spec.iconResId,
                        title = stringResource(spec.titleResId),
                        subtitle = stringResource(spec.subtitleResId),
                        enabled = !blockingInteractionLocked,
                        onClick = { onOpenSettingsRoute(destination.route) },
                    )
                }
            }
        }
    }

    SettingsFeedbackSubmissionNoticeDialog(
        notice = feedbackSubmissionNotice,
        onDismiss = onDismissFeedbackSubmissionNotice,
    )
}

private data class ResolvedSettingsSearchEntry(
    val entry: SettingsSearchEntry,
    val title: String,
    val subtitle: String?,
)

private data class SettingsSearchSection(
    val destination: SettingsHomeDestination,
    val categoryTitle: String,
    val categoryMatched: Boolean,
    val entries: List<ResolvedSettingsSearchEntry>,
)

@Composable
internal fun LauncherSettingsLauncherScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onOpenBasicTutorial: () -> Unit = {},
    onThemeModeChanged: (LauncherThemeMode) -> Unit = {},
    onThemeColorChanged: (LauncherThemeColor) -> Unit = {},
    onLauncherIconModeChanged: (LauncherIconMode) -> Unit = {},
    onChromeBackgroundOpacityChanged: (Float) -> Unit = {},
    onLauncherBlurDisabledChanged: (Boolean) -> Unit = {},
    onBootOverlayStyleChanged: (BootOverlayStyle) -> Unit = {},
    onPrepareSlingBreak: () -> Unit = {},
    onDismissSlingBreakDependencyFailure: () -> Unit = {},
    onSwitchSlingBreakDownloadMirror: () -> Unit = {},
    onBootOverlayAnimationChanged: (BootOverlayAnimation) -> Unit = {},
    onBootOverlayImageModeChanged: (BootOverlayImageMode) -> Unit = {},
    onBootOverlayArtworkChanged: (BootOverlayArtwork) -> Unit = {},
    onPickBootOverlayImage: (BootOverlayImageSlot) -> Unit = {},
    onResetBootOverlayImages: () -> Unit = {},
    onShowModFileNameChanged: (Boolean) -> Unit = {},
    onAutoCheckUpdatesChanged: (Boolean) -> Unit = {},
    onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit = {},
    onManualCheckUpdates: () -> Unit = {},
    onCheckSlingBreakUpdates: () -> Unit = {},
    onInstallSlingBreakUpdate: () -> Unit = {},
    onOpenReleaseHistory: () -> Unit = {},
    onDismissReleaseHistoryDialog: () -> Unit = {},
    onOpenFirstRunSetup: () -> Unit = {},
    onApplyModFileNameAliases: () -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsLauncherRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_basic_tutorial_title), iconResId = R.drawable.ic_settings_tutorial) {
                SettingsActionListItem(
                    title = stringResource(R.string.settings_basic_tutorial_action),
                    supportingText = stringResource(R.string.settings_basic_tutorial_desc),
                    enabled = !uiState.busy,
                    onClick = onOpenBasicTutorial,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_appearance_section_title), iconResId = R.drawable.ic_settings_appearance) {
                SettingsAppearanceSection(
                    uiState = uiState,
                    actions = AppearanceSettingsActions(
                        onThemeModeChanged = onThemeModeChanged,
                        onThemeColorChanged = onThemeColorChanged,
                        onLauncherIconModeChanged = onLauncherIconModeChanged,
                        onChromeBackgroundOpacityChanged = onChromeBackgroundOpacityChanged,
                        onLauncherBlurDisabledChanged = onLauncherBlurDisabledChanged,
                        onBootOverlayStyleChanged = onBootOverlayStyleChanged,
                        onPrepareSlingBreak = onPrepareSlingBreak,
                        onDismissSlingBreakDependencyFailure = onDismissSlingBreakDependencyFailure,
                        onSwitchSlingBreakDownloadMirror = onSwitchSlingBreakDownloadMirror,
                        onBootOverlayAnimationChanged = onBootOverlayAnimationChanged,
                        onBootOverlayImageModeChanged = onBootOverlayImageModeChanged,
                        onBootOverlayArtworkChanged = onBootOverlayArtworkChanged,
                        onPickBootOverlayImage = onPickBootOverlayImage,
                        onResetBootOverlayImages = onResetBootOverlayImages,
                        onShowModFileNameChanged = onShowModFileNameChanged,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.update_section_title), iconResId = R.drawable.ic_settings_update) {
                SettingsUpdateSection(
                    uiState = uiState,
                    actions = UpdateSettingsActions(
                        onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                        onPreferredUpdateMirrorChanged = onPreferredUpdateMirrorChanged,
                        onManualCheckUpdates = onManualCheckUpdates,
                        onCheckSlingBreakUpdates = onCheckSlingBreakUpdates,
                        onInstallSlingBreakUpdate = onInstallSlingBreakUpdate,
                        onOpenReleaseHistory = onOpenReleaseHistory,
                        onDismissReleaseHistoryDialog = onDismissReleaseHistoryDialog,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_launcher_other_section_title), iconResId = R.drawable.ic_settings_other) {
                SettingsActionListItem(
                    title = stringResource(R.string.settings_first_run_reopen_action),
                    enabled = !uiState.busy,
                    onClick = onOpenFirstRunSetup,
                )
                SettingsApplyModFileNameAliasesAction(uiState.busy, onApplyModFileNameAliases)
            }
        }
    }

    SlingBreakDependencyDialogs(
        progress = uiState.slingBreakDependencyProgress,
        message = uiState.slingBreakDownloadMessage,
        failure = uiState.slingBreakDependencyFailure,
        slowDownloadSwitch = uiState.slingBreakSlowDownloadSwitch,
        onRetry = onPrepareSlingBreak,
        onSwitchMirror = onSwitchSlingBreakDownloadMirror,
        onDismissFailure = onDismissSlingBreakDependencyFailure,
    )
}

@Composable
internal fun SettingsApplyModFileNameAliasesAction(
    busy: Boolean,
    onApplyModFileNameAliases: () -> Unit,
) {
    var showApplyModFileNameAliasesDialog by rememberSaveable { mutableStateOf(false) }

    SettingsDangerActionListItem(
        title = stringResource(R.string.settings_mod_alias_apply_file_names_action),
        supportingText = stringResource(R.string.settings_mod_alias_apply_file_names_desc),
        enabled = !busy,
        onClick = { showApplyModFileNameAliasesDialog = true },
    )

    if (showApplyModFileNameAliasesDialog) {
        AlertDialog(
            onDismissRequest = { showApplyModFileNameAliasesDialog = false },
            title = { Text(stringResource(R.string.settings_mod_alias_apply_file_names_confirm_title)) },
            text = { Text(stringResource(R.string.settings_mod_alias_apply_file_names_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = settingsControlEnabled(!busy),
                    onClick = {
                        showApplyModFileNameAliasesDialog = false
                        onApplyModFileNameAliases()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_mod_alias_apply_file_names_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyModFileNameAliasesDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}



@Composable
internal fun LauncherSettingsGameScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onPlayerNameChanged: (String) -> Boolean = { true },
    onBackBehaviorChanged: (BackBehavior) -> Unit = {},
    onTouchscreenInputModeChanged: (TouchscreenInputMode) -> Unit = {},
    onCardPlayOptimizationModeChanged: (CardPlayOptimizationMode) -> Unit = {},
    onTouchIndicatorEnabledChanged: (Boolean) -> Unit = {},
    onSpecialKeyInputModeChanged: (SpecialKeyInputMode) -> Unit = {},
    onTouchMouseInteractionModeChanged: (TouchMouseInteractionMode) -> Unit = {},
    onTouchDoubleClickAsRightClickChanged: (Boolean) -> Unit = {},
    onIgnoreLongPressRightClickWhilePlayingCardChanged: (Boolean) -> Unit = {},
    onBuiltInSoftKeyboardChanged: (Boolean) -> Unit = {},
    onAutoPopupKeyboardChanged: (Boolean) -> Unit = {},
    onFloatingToolButtonChanged: (String, Boolean) -> Unit = { _, _ -> },
    onHapticFeedbackChanged: (Boolean) -> Unit = {},
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit = {},
    onGameDisplayRegionChanged: (io.stamethyst.backend.render.GameDisplayRegion) -> Unit = {},
    onGameplayFontScaleChanged: (Float) -> Unit = {},
    onGameplayLargerUiChanged: (Boolean) -> Unit = {},
    onHideEnemyPanelWhileTargetingChanged: (Boolean) -> Unit = {},
    onKeepScreenOnTimeoutSelected: (Int) -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsGameRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_player_name_title), iconResId = R.drawable.ic_settings_player) {
                SettingsPlayerNameAction(
                    uiState = uiState,
                    onPlayerNameChanged = onPlayerNameChanged,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_input), iconResId = R.drawable.ic_settings_input) {
                SettingsInputSection(
                    uiState = uiState,
                    actions = InputSettingsActions(
                        onBackBehaviorChanged = onBackBehaviorChanged,
                        onTouchscreenInputModeChanged = onTouchscreenInputModeChanged,
                        onCardPlayOptimizationModeChanged = onCardPlayOptimizationModeChanged,
                        onTouchIndicatorEnabledChanged = onTouchIndicatorEnabledChanged,
                        onSpecialKeyInputModeChanged = onSpecialKeyInputModeChanged,
                        onTouchMouseInteractionModeChanged = onTouchMouseInteractionModeChanged,
                        onTouchDoubleClickAsRightClickChanged = onTouchDoubleClickAsRightClickChanged,
                        onIgnoreLongPressRightClickWhilePlayingCardChanged =
                            onIgnoreLongPressRightClickWhilePlayingCardChanged,
                        onBuiltInSoftKeyboardChanged = onBuiltInSoftKeyboardChanged,
                        onAutoPopupKeyboardChanged = onAutoPopupKeyboardChanged,
                        onFloatingToolButtonChanged = onFloatingToolButtonChanged,
                        onHapticFeedbackChanged = onHapticFeedbackChanged,
                        onAutoSwitchLeftAfterRightClickChanged = onAutoSwitchLeftAfterRightClickChanged,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_game_display), iconResId = R.drawable.ic_settings_game_display) {
                SettingsGameplayDisplaySection(
                    uiState = uiState,
                    actions = GameplayDisplaySettingsActions(
                        onGameDisplayRegionChanged = onGameDisplayRegionChanged,
                        onGameplayFontScaleChanged = onGameplayFontScaleChanged,
                        onGameplayLargerUiChanged = onGameplayLargerUiChanged,
                        onHideEnemyPanelWhileTargetingChanged = onHideEnemyPanelWhileTargetingChanged,
                        onKeepScreenOnTimeoutSelected = onKeepScreenOnTimeoutSelected,
                    ),
                )
            }
        }
    }
}


@Composable
internal fun LauncherSettingsPerformanceScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onRenderScaleSelected: (Float) -> Unit = {},
    onTargetFpsSelected: (Float) -> Unit = {},
    onFramePacingModeChanged: (io.stamethyst.config.FramePacingMode) -> Unit = {},
    onVirtualResolutionModeChanged: (VirtualResolutionMode) -> Unit = {},
    onRamSaverEnabledChanged: (Boolean) -> Unit = {},
    onMtsPatchCacheEnabledChanged: (Boolean) -> Unit = {},
    onOpenMtsCacheOptimizations: () -> Unit = {},
    onGamePerformanceOverlayChanged: (Boolean) -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsPerformanceRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_render), iconResId = R.drawable.ic_settings_render) {
                SettingsPerformanceSection(
                    uiState = uiState,
                    actions = PerformanceSettingsActions(
                        onRenderScaleSelected = onRenderScaleSelected,
                        onTargetFpsSelected = onTargetFpsSelected,
                        onFramePacingModeChanged = onFramePacingModeChanged,
                        onVirtualResolutionModeChanged = onVirtualResolutionModeChanged,
                        onRamSaverEnabledChanged = onRamSaverEnabledChanged,
                        onMtsPatchCacheEnabledChanged = onMtsPatchCacheEnabledChanged,
                        onOpenMtsCacheOptimizations = onOpenMtsCacheOptimizations,
                        onGamePerformanceOverlayChanged = onGamePerformanceOverlayChanged,
                    ),
                )
            }
        }
    }
}



@Composable
internal fun LauncherSettingsMarketCloudScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onOpenSteamCloudLogin: () -> Unit = {},
    onSteamCloudWattAccelerationChanged: (Boolean) -> Unit = {},
    onSteamCloudAutoLaunchAfterSyncChanged: (Boolean) -> Unit = {},
    onSteamCloudSyncDisabledChanged: (Boolean) -> Unit = {},
    onSteamGamePresenceChanged: (Boolean) -> Unit = {},
    onRichPresenceDisplayPreferencesChanged: (RichPresenceDisplayPreferences) -> Unit = {},
    onSteamAchievementSyncChanged: (Boolean) -> Unit = {},
    onAchievementUnlockNotificationChanged: (Boolean) -> Unit = {},
    onOpenSteamCloudSaveSettings: () -> Unit = {},
    onClearSteamCloudCredentials: () -> Unit = {},
    onClearSteamCloudNetworkCache: () -> Unit = {},
    onWorkshopMaxConcurrentDownloadsChanged: (Int) -> Unit = {},
    onWorkshopDownloadThreadsChanged: (Int) -> Unit = {},
    onWorkshopWattAccelerationChanged: (Boolean) -> Unit = {},
    onWorkshopSteamLanguageChanged: (SteamLanguagePreference) -> Unit = {},
    onWorkshopDefaultSortChanged: (WorkshopBrowseSort) -> Unit = {},
    onWorkshopAutoImportChanged: (Boolean) -> Unit = {},
    onOpenWorkshopAutoImportDefaults: () -> Unit = {},
    onClearWorkshopPreviewCache: () -> Unit = {},
    onOpenBaiduTranslationCredentials: () -> Unit = {},
) {
    val steamActions = SteamCloudSettingsActions(
        onOpenSteamCloudLogin = onOpenSteamCloudLogin,
        onSteamCloudWattAccelerationChanged = onSteamCloudWattAccelerationChanged,
        onSteamCloudAutoLaunchAfterSyncChanged = onSteamCloudAutoLaunchAfterSyncChanged,
        onSteamCloudSyncDisabledChanged = onSteamCloudSyncDisabledChanged,
        onSteamGamePresenceChanged = onSteamGamePresenceChanged,
        onRichPresenceDisplayPreferencesChanged = onRichPresenceDisplayPreferencesChanged,
        onSteamAchievementSyncChanged = onSteamAchievementSyncChanged,
        onAchievementUnlockNotificationChanged = onAchievementUnlockNotificationChanged,
        onOpenSteamCloudSaveSettings = onOpenSteamCloudSaveSettings,
        onClearSteamCloudCredentials = onClearSteamCloudCredentials,
        onClearSteamCloudNetworkCache = onClearSteamCloudNetworkCache,
    )
    var showRequiresPurchaseDialog by remember { mutableStateOf(false) }
    val requiresPurchaseAction: @Composable () -> Unit = {
        IconButton(onClick = { showRequiresPurchaseDialog = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_info_outline),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showRequiresPurchaseDialog) {
        AlertDialog(
            onDismissRequest = { showRequiresPurchaseDialog = false },
            title = { Text(stringResource(R.string.settings_steam_services_requires_purchase_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_steam_services_requires_purchase_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showRequiresPurchaseDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsMarketCloudRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SteamAccountSection(uiState = uiState, actions = steamActions)
        }
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_steam_services_presence_section_title),
                iconResId = R.drawable.ic_settings_presence,
                trailingAction = requiresPurchaseAction,
            ) {
                SteamGamePresenceSection(uiState = uiState, actions = steamActions)
            }
        }
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_steam_services_achievement_section_title),
                iconResId = R.drawable.ic_settings_achievement,
                trailingAction = requiresPurchaseAction,
            ) {
                SteamAchievementSection(uiState = uiState, actions = steamActions)
            }
        }
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_steam_cloud_title),
                iconResId = R.drawable.ic_settings_cloud,
                trailingAction = requiresPurchaseAction,
            ) {
                SettingsSteamCloudSection(
                    uiState = uiState,
                    actions = steamActions,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_steam_services_network_section_title), iconResId = R.drawable.ic_settings_network) {
                SteamNetworkSection(uiState = uiState, actions = steamActions)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_market_section_title), iconResId = R.drawable.ic_settings_market) {
                SettingsMarketSection(
                    uiState = uiState,
                    actions = MarketSettingsActions(
                        onWorkshopMaxConcurrentDownloadsChanged = onWorkshopMaxConcurrentDownloadsChanged,
                        onWorkshopDownloadThreadsChanged = onWorkshopDownloadThreadsChanged,
                        onWorkshopWattAccelerationChanged = onWorkshopWattAccelerationChanged,
                        onWorkshopSteamLanguageChanged = onWorkshopSteamLanguageChanged,
                        onWorkshopDefaultSortChanged = onWorkshopDefaultSortChanged,
                        onWorkshopAutoImportChanged = onWorkshopAutoImportChanged,
                        onOpenWorkshopAutoImportDefaults = onOpenWorkshopAutoImportDefaults,
                        onClearWorkshopPreviewCache = onClearWorkshopPreviewCache,
                        onOpenBaiduTranslationCredentials = onOpenBaiduTranslationCredentials,
                    ),
                )
            }
        }
    }
}



@Composable
internal fun LauncherSettingsWorkshopAutoImportDefaultsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onAtlasDownscaleChanged: (Boolean) -> Unit = {},
    onAtlasDownscaleMaxEdgeChanged: (Int) -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsWorkshopAutoImportDefaultsRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_workshop_auto_import_defaults_atlas_section_title)
            ) {
                SettingsWorkshopAutoImportDefaultsSection(
                    uiState, onAtlasDownscaleChanged, onAtlasDownscaleMaxEdgeChanged,
                )
            }
        }
    }
}

@Composable
internal fun SettingsWorkshopAutoImportDefaultsSection(
    uiState: SettingsScreenViewModel.UiState,
    onAtlasDownscaleChanged: (Boolean) -> Unit,
    onAtlasDownscaleMaxEdgeChanged: (Int) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_workshop_auto_import_defaults_atlas_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.workshopAutoImportAtlasDownscaleEnabled,
            enabled = !uiState.busy,
            title = stringResource(R.string.settings_workshop_auto_import_defaults_atlas_enabled_title),
            description = stringResource(R.string.settings_workshop_auto_import_defaults_atlas_desc),
            onCheckedChange = onAtlasDownscaleChanged,
        )
    )
    Spacer(modifier = Modifier.size(8.dp))
    SettingsDropdownField(
        label = stringResource(R.string.settings_workshop_auto_import_defaults_atlas_level_title),
        valueText = stringResource(
            R.string.mod_import_atlas_downscale_level_max_edge_label,
            uiState.workshopAutoImportAtlasDownscaleMaxEdgePx,
        ),
        enabled = !uiState.busy && uiState.workshopAutoImportAtlasDownscaleEnabled,
        supportingText = stringResource(
            R.string.mod_import_atlas_downscale_level_max_edge_desc,
            uiState.workshopAutoImportAtlasDownscaleMaxEdgePx,
        ),
        options = LauncherPreferences.WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_OPTIONS.toList(),
        optionLabel = { stringResource(R.string.mod_import_atlas_downscale_level_max_edge_label, it) },
        optionDescription = { stringResource(R.string.mod_import_atlas_downscale_level_max_edge_desc, it) },
        onOptionSelected = onAtlasDownscaleMaxEdgeChanged,
    )
}



@Composable
internal fun LauncherSettingsFeedbackScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    onOpenFeedbackSubscriptions: () -> Unit = {},
    onOpenFeedbackIssueBrowser: () -> Unit = {},
     onImportJar: () -> Unit = {},
     onReinstallResourcePack: () -> Unit = {},
    onImportMods: () -> Unit = {},
    onExportMods: () -> Unit = {},
    onImportSaves: () -> Unit = {},
    onExportSaves: () -> Unit = {},
    onExportLogs: () -> Unit = {},
    onExportLogsToFile: () -> Unit = {},
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsFeedbackRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsFeedbackEntryCard(
                busy = uiState.busy,
                onOpenFeedback = onOpenFeedback,
                onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
                onOpenFeedbackIssueBrowser = onOpenFeedbackIssueBrowser,
            )
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_resources_files), iconResId = R.drawable.ic_settings_resources) {
                SettingsImportSection(
                    busy = uiState.busy,
                     onImportJar = onImportJar,
                     onReinstallResourcePack = onReinstallResourcePack,
                    onImportMods = onImportMods,
                    onExportMods = onExportMods,
                    onImportSaves = onImportSaves,
                    onExportSaves = onExportSaves,
                    onExportLogs = onExportLogs,
                    onExportLogsToFile = onExportLogsToFile,
                )
            }
        }
    }

    SettingsFeedbackSubmissionNoticeDialog(
        notice = feedbackSubmissionNotice,
        onDismiss = onDismissFeedbackSubmissionNotice,
    )
}



@Composable
internal fun LauncherSettingsAboutScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsAboutRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_author_info_title)) {
                SettingsAuthorInfoSection()
            }
        }
    }
}
