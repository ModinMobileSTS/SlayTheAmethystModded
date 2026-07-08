package io.stamethyst.ui.settings.core

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.importing.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.openBasicTutorial


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        showBackButton = showBackButton,
        onOpenSettingsRoute = navigator::push,
        feedbackSubmissionNotice = feedbackSubmissionNotice,
        onDismissFeedbackSubmissionNotice = onDismissFeedbackSubmissionNotice,
    )
}


@Composable
fun LauncherSettingsLauncherScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val context = LocalContext.current
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsLauncherScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onOpenBasicTutorial = { openBasicTutorial(context) },
        onThemeModeChanged = { themeMode ->
            viewModel.onThemeModeChanged(activity, themeMode)
        },
        onThemeColorChanged = { themeColor ->
            viewModel.onThemeColorChanged(activity, themeColor)
        },
        onLauncherIconModeChanged = { iconMode ->
            viewModel.onLauncherIconModeChanged(activity, iconMode)
        },
        onChromeBackgroundOpacityChanged = { opacity ->
            viewModel.onChromeBackgroundOpacityChanged(activity, opacity)
        },
        onBootOverlayStyleChanged = { style ->
            viewModel.onBootOverlayStyleChanged(activity, style)
        },
        onBootOverlayAnimationChanged = { animation ->
            viewModel.onBootOverlayAnimationChanged(activity, animation)
        },
        onBootOverlayImageModeChanged = { mode ->
            viewModel.onBootOverlayImageModeChanged(activity, mode)
        },
        onPickBootOverlayImage = viewModel::onPickBootOverlayImage,
        onResetBootOverlayImages = { viewModel.onResetBootOverlayImages(activity) },
        onShowModFileNameChanged = { enabled ->
            viewModel.onShowModFileNameChanged(activity, enabled)
        },
        onAutoCheckUpdatesChanged = { enabled ->
            viewModel.onAutoCheckUpdatesChanged(activity, enabled)
        },
        onPreferredUpdateMirrorChanged = { source ->
            viewModel.onPreferredUpdateMirrorChanged(activity, source)
        },
        onManualCheckUpdates = { viewModel.onManualCheckUpdates(activity) },
        onOpenReleaseHistory = { viewModel.onOpenReleaseHistory(activity) },
        onDismissReleaseHistoryDialog = viewModel::dismissReleaseHistoryDialog,
        onOpenFirstRunSetup = { navigator.push(Route.FirstRunSetup) },
        onApplyModFileNameAliases = { viewModel.onApplyModFileNameAliases(activity) },
    )
}


@Composable
fun LauncherSettingsGameScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsGameScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onRenderScaleSelected = { value -> viewModel.onRenderScaleSelected(activity, value) },
        onTargetFpsSelected = { fps -> viewModel.onTargetFpsSelected(activity, fps) },
        onVirtualResolutionModeChanged = { mode ->
            viewModel.onVirtualResolutionModeChanged(activity, mode)
        },
        onDisplayCutoutAvoidanceChanged = { enabled ->
            viewModel.onDisplayCutoutAvoidanceChanged(activity, enabled)
        },
        onScreenBottomCropChanged = { enabled ->
            viewModel.onScreenBottomCropChanged(activity, enabled)
        },
        onRamSaverEnabledChanged = { enabled ->
            viewModel.onRamSaverEnabledChanged(activity, enabled)
        },
        onMtsPatchCacheEnabledChanged = { enabled ->
            viewModel.onMtsPatchCacheEnabledChanged(activity, enabled)
        },
        onKeepScreenOnTimeoutSelected = { timeoutMinutes ->
            viewModel.onKeepScreenOnTimeoutSelected(activity, timeoutMinutes)
        },
        onGameplayFontScaleChanged = { value ->
            viewModel.onGameplayFontScaleChanged(activity, value)
        },
        onGameplayLargerUiChanged = { enabled ->
            viewModel.onGameplayLargerUiChanged(activity, enabled)
        },
        onPlayerNameChanged = { name -> viewModel.onPlayerNameChanged(activity, name) },
        onBackBehaviorChanged = { behavior -> viewModel.onBackBehaviorChanged(activity, behavior) },
        onTouchscreenInputModeChanged = { mode ->
            viewModel.onTouchscreenInputModeChanged(activity, mode)
        },
        onCardPlayOptimizationModeChanged = { mode ->
            viewModel.onCardPlayOptimizationModeChanged(activity, mode)
        },
        onTouchIndicatorEnabledChanged = { enabled ->
            viewModel.onTouchIndicatorEnabledChanged(activity, enabled)
        },
        onSpecialKeyInputModeChanged = { mode ->
            viewModel.onSpecialKeyInputModeChanged(activity, mode)
        },
        onTouchMouseInteractionModeChanged = { mode ->
            viewModel.onTouchMouseInteractionModeChanged(activity, mode)
        },
        onTouchDoubleClickAsRightClickChanged = { enabled ->
            viewModel.onTouchDoubleClickAsRightClickChanged(activity, enabled)
        },
        onIgnoreLongPressRightClickWhilePlayingCardChanged = { enabled ->
            viewModel.onIgnoreLongPressRightClickWhilePlayingCardChanged(activity, enabled)
        },
        onBuiltInSoftKeyboardChanged = { enabled ->
            viewModel.onBuiltInSoftKeyboardChanged(activity, enabled)
        },
        onHapticFeedbackChanged = { enabled ->
            viewModel.onHapticFeedbackChanged(activity, enabled)
        },
        onAutoSwitchLeftAfterRightClickChanged = { enabled ->
            viewModel.onAutoSwitchLeftAfterRightClickChanged(activity, enabled)
        },
        onGamePerformanceOverlayChanged = { enabled ->
            viewModel.onGamePerformanceOverlayChanged(activity, enabled)
        },
    )
}


@Composable
fun LauncherSettingsMarketCloudScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsMarketCloudScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onOpenSteamCloudLogin = { navigator.push(Route.SteamCloudLogin) },
        onSteamCloudWattAccelerationChanged = { enabled ->
            viewModel.onSteamCloudWattAccelerationChanged(activity, enabled)
        },
        onSteamCloudAutoLaunchAfterSyncChanged = { enabled ->
            viewModel.onSteamCloudAutoLaunchAfterSyncChanged(activity, enabled)
        },
        onOpenSteamCloudSaveSettings = { navigator.push(Route.SteamCloudSaveSettings) },
        onClearSteamCloudCredentials = { viewModel.onClearSteamCloudCredentials(activity) },
        onClearSteamCloudNetworkCache = { viewModel.onClearSteamCloudNetworkCache(activity) },
        onWorkshopMaxConcurrentDownloadsChanged = { value ->
            viewModel.onWorkshopMaxConcurrentDownloadsChanged(activity, value)
        },
        onWorkshopDownloadThreadsChanged = { value ->
            viewModel.onWorkshopDownloadThreadsChanged(activity, value)
        },
        onWorkshopWattAccelerationChanged = { enabled ->
            viewModel.onWorkshopWattAccelerationChanged(activity, enabled)
        },
        onWorkshopSteamLanguageChanged = { language ->
            viewModel.onWorkshopSteamLanguageChanged(activity, language)
        },
        onWorkshopAutoImportChanged = { enabled ->
            viewModel.onWorkshopAutoImportChanged(activity, enabled)
        },
        onOpenWorkshopAutoImportDefaults = {
            navigator.push(Route.SettingsWorkshopAutoImportDefaults)
        },
        onClearWorkshopPreviewCache = { viewModel.onClearWorkshopPreviewCache(activity) },
        onOpenBaiduTranslationCredentials = { navigator.push(Route.BaiduTranslationCredentials()) },
    )
}


@Composable
fun LauncherSettingsWorkshopAutoImportDefaultsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsWorkshopAutoImportDefaultsScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onAtlasDownscaleChanged = { enabled ->
            viewModel.onWorkshopAutoImportAtlasDownscaleChanged(activity, enabled)
        },
        onAtlasDownscaleMaxEdgeChanged = { maxEdgePx ->
            viewModel.onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged(activity, maxEdgePx)
        },
    )
}


@Composable
fun LauncherSettingsFeedbackScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsFeedbackScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onOpenFeedback = { navigator.push(Route.Feedback) },
        onOpenFeedbackSubscriptions = { navigator.push(Route.FeedbackSubscriptions) },
        onOpenFeedbackIssueBrowser = { navigator.push(Route.FeedbackIssueBrowser) },
        onImportJar = viewModel::onImportJar,
        onImportMods = viewModel::onImportMods,
        onExportMods = viewModel::onExportMods,
        onImportSaves = viewModel::onImportSaves,
        onExportSaves = viewModel::onExportSaves,
        onExportLogs = { viewModel.onExportLogs(activity) },
        onExportLogsToFile = viewModel::onExportLogsToFile,
        feedbackSubmissionNotice = feedbackSubmissionNotice,
        onDismissFeedbackSubmissionNotice = onDismissFeedbackSubmissionNotice,
    )
}


@Composable
fun LauncherSettingsAboutScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherSettingsAboutScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherDeveloperSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LauncherDeveloperSettingsScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onManualDismissBootOverlayChanged = { enabled ->
            viewModel.onManualDismissBootOverlayChanged(activity, enabled)
        },
        onSustainedPerformanceModeChanged = { enabled ->
            viewModel.onSustainedPerformanceModeChanged(activity, enabled)
        },
        onCompendiumUpgradeTouchFixEnabledChanged = { enabled ->
            viewModel.onCompendiumUpgradeTouchFixEnabledChanged(activity, enabled)
        },
        onSaveSteamCloudPhase0Credentials = { accountName, refreshToken, proxyUrl ->
            viewModel.onSaveSteamCloudPhase0Credentials(activity, accountName, refreshToken, proxyUrl)
        },
        onRunSteamCloudPhase0Probe = {
            viewModel.onRunSteamCloudPhase0Probe(activity)
        },
        onClearSteamCloudPhase0Credentials = {
            viewModel.onClearSteamCloudPhase0Credentials(activity)
        },
        onRendererSelectionModeChanged = { mode ->
            viewModel.onRendererSelectionModeChanged(activity, mode)
        },
        onManualRendererBackendChanged = { backend ->
            viewModel.onManualRendererBackendChanged(activity, backend)
        },
        onOpenMobileGluesSettings = { navigator.push(Route.MobileGluesSettings) },
        onRenderSurfaceBackendChanged = { backend ->
            viewModel.onRenderSurfaceBackendChanged(activity, backend)
        },
        onGpuResourceGuardianModeChanged = { mode ->
            viewModel.onGpuResourceGuardianModeChanged(activity, mode)
        },
        onGpuResourceGuardianPressureDownscaleChanged = { enabled ->
            viewModel.onGpuResourceGuardianPressureDownscaleChanged(activity, enabled)
        },
        onJvmHeapMaxSelected = { value -> viewModel.onJvmHeapMaxSelected(activity, value) },
        onJvmCompressedPointersChanged = { enabled ->
            viewModel.onJvmCompressedPointersChanged(activity, enabled)
        },
        onJvmStringDeduplicationChanged = { enabled ->
            viewModel.onJvmStringDeduplicationChanged(activity, enabled)
        },
        onOpenCompatibility = { navigator.push(Route.Compatibility) },
        onLwjglDebugChanged = { enabled -> viewModel.onLwjglDebugChanged(activity, enabled) },
        onPreloadAllJreLibrariesChanged = { enabled ->
            viewModel.onPreloadAllJreLibrariesChanged(activity, enabled)
        },
        onLogcatCaptureChanged = { enabled -> viewModel.onLogcatCaptureChanged(activity, enabled) },
        onLauncherLogcatCaptureChanged = { enabled ->
            viewModel.onLauncherLogcatCaptureChanged(activity, enabled)
        },
        onJvmLogcatMirrorChanged = { enabled ->
            viewModel.onJvmLogcatMirrorChanged(activity, enabled)
        },
        onGpuResourceDiagChanged = { enabled ->
            viewModel.onGpuResourceDiagChanged(activity, enabled)
        },
        onGdxPadCursorDebugChanged = { enabled ->
            viewModel.onGdxPadCursorDebugChanged(activity, enabled)
        },
        onGlBridgeSwapHeartbeatDebugChanged = { enabled ->
            viewModel.onGlBridgeSwapHeartbeatDebugChanged(activity, enabled)
        },
        onClearJunkFiles = {
            viewModel.onClearJunkFiles(activity)
        },
        onOpenCloudControlConfig = {
            viewModel.onOpenCloudControlConfig(activity)
        },
        onDismissCloudControlConfigDialog = viewModel::dismissCloudControlConfigDialog,
        onResetLauncherSettingsToDefaults = {
            viewModel.onResetLauncherSettingsToDefaults(activity)
        },
    )
}


