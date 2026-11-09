package io.stamethyst.ui.settings.core

import android.app.Activity
import io.stamethyst.navigation.Route
import io.stamethyst.ui.openBasicTutorial
import io.stamethyst.ui.LauncherNavigationRequestBus
import io.stamethyst.ui.settings.sections.DeveloperRuntimeSettingsActions
import io.stamethyst.ui.settings.sections.SteamCloudSettingsActions
import io.stamethyst.ui.settings.sections.StatusSettingsActions
import io.stamethyst.ui.settings.sections.TogetherInSpireSettingsActions
import io.stamethyst.ui.settings.sections.AccelerationSettingsActions
import io.stamethyst.ui.settings.sections.AdvancedRenderSettingsActions
import io.stamethyst.ui.settings.sections.AppearanceSettingsActions
import io.stamethyst.ui.settings.sections.GameplayDisplaySettingsActions
import io.stamethyst.ui.settings.sections.InputSettingsActions
import io.stamethyst.ui.settings.sections.MarketSettingsActions
import io.stamethyst.ui.settings.sections.PerformanceSettingsActions
import io.stamethyst.ui.settings.sections.SettingsSearchActions
import io.stamethyst.ui.settings.sections.SettingsSearchDetailContent
import io.stamethyst.ui.settings.sections.UpdateSettingsActions
import io.stamethyst.ui.compatibility.LauncherCompatibilityScreen
import io.stamethyst.ui.settings.baidu.LauncherBaiduTranslationCredentialsScreen
import io.stamethyst.ui.settings.llm.LlmSettingsContent
import io.stamethyst.ui.settings.mobileglues.LauncherMobileGluesSettingsScreen

internal fun settingsSearchActions(
    viewModel: SettingsScreenViewModel,
    activity: Activity,
    onOpenSettingsRoute: (Route) -> Unit,
): SettingsSearchActions = SettingsSearchActions(
    details = SettingsSearchDetailContent(
        baiduTranslation = {
            LauncherBaiduTranslationCredentialsScreen(viewModel, embedded = true, onBack = {})
        },
        mobileGlues = { LauncherMobileGluesSettingsScreen(viewModel, embedded = true) },
        compatibility = { LauncherCompatibilityScreen(embedded = true) },
        llm = {
            LlmSettingsContent(
                uiState = viewModel.uiState,
                embedded = true,
                onOpenTutorial = { onOpenSettingsRoute(Route.SettingsLlmTutorial) },
            )
        },
    ),
    appearance = AppearanceSettingsActions(
        onThemeModeChanged = { viewModel.onThemeModeChanged(activity, it) },
        onThemeColorChanged = { viewModel.onThemeColorChanged(activity, it) },
        onLauncherIconModeChanged = { viewModel.onLauncherIconModeChanged(activity, it) },
        onChromeBackgroundOpacityChanged = { viewModel.onChromeBackgroundOpacityChanged(activity, it) },
        onBootOverlayStyleChanged = { viewModel.onBootOverlayStyleChanged(activity, it) },
        onSlingBreakEngineModeChanged = { viewModel.onSlingBreakEngineModeChanged(activity, it) },
        onDismissSlingBreakDependencyFailure = viewModel::dismissSlingBreakDependencyFailure,
        onSwitchSlingBreakDownloadMirror = viewModel::switchSlingBreakDownloadMirror,
        onBootOverlayAnimationChanged = { viewModel.onBootOverlayAnimationChanged(activity, it) },
        onBootOverlayImageModeChanged = { viewModel.onBootOverlayImageModeChanged(activity, it) },
        onPickBootOverlayImage = viewModel::onPickBootOverlayImage,
        onResetBootOverlayImages = { viewModel.onResetBootOverlayImages(activity) },
        onShowModFileNameChanged = { viewModel.onShowModFileNameChanged(activity, it) },
    ),
    update = UpdateSettingsActions(
        onAutoCheckUpdatesChanged = { viewModel.onAutoCheckUpdatesChanged(activity, it) },
        onPreferredUpdateMirrorChanged = { viewModel.onPreferredUpdateMirrorChanged(activity, it) },
        onManualCheckUpdates = { viewModel.onManualCheckUpdates(activity) },
        onOpenReleaseHistory = { viewModel.onOpenReleaseHistory(activity) },
        onDismissReleaseHistoryDialog = viewModel::dismissReleaseHistoryDialog,
        onCheckSlingBreakUpdates = { viewModel.checkSlingBreakUpdates(activity) },
        onInstallSlingBreakUpdate = { viewModel.installSlingBreakUpdate(activity) },
    ),
    input = InputSettingsActions(
        onBackBehaviorChanged = { viewModel.onBackBehaviorChanged(activity, it) },
        onTouchscreenInputModeChanged = { viewModel.onTouchscreenInputModeChanged(activity, it) },
        onCardPlayOptimizationModeChanged = { viewModel.onCardPlayOptimizationModeChanged(activity, it) },
        onTouchIndicatorEnabledChanged = { viewModel.onTouchIndicatorEnabledChanged(activity, it) },
        onSpecialKeyInputModeChanged = { viewModel.onSpecialKeyInputModeChanged(activity, it) },
        onTouchMouseInteractionModeChanged = { viewModel.onTouchMouseInteractionModeChanged(activity, it) },
        onTouchDoubleClickAsRightClickChanged = { viewModel.onTouchDoubleClickAsRightClickChanged(activity, it) },
        onIgnoreLongPressRightClickWhilePlayingCardChanged = {
            viewModel.onIgnoreLongPressRightClickWhilePlayingCardChanged(activity, it)
        },
        onBuiltInSoftKeyboardChanged = { viewModel.onBuiltInSoftKeyboardChanged(activity, it) },
        onAutoPopupKeyboardChanged = { viewModel.onAutoPopupKeyboardChanged(activity, it) },
        onFloatingToolButtonChanged = { id, enabled -> viewModel.onFloatingToolButtonChanged(activity, id, enabled) },
        onHapticFeedbackChanged = { viewModel.onHapticFeedbackChanged(activity, it) },
        onAutoSwitchLeftAfterRightClickChanged = { viewModel.onAutoSwitchLeftAfterRightClickChanged(activity, it) },
    ),
    display = GameplayDisplaySettingsActions(
        onGameDisplayRegionChanged = { viewModel.onGameDisplayRegionChanged(activity, it) },
        onGameplayFontScaleChanged = { viewModel.onGameplayFontScaleChanged(activity, it) },
        onGameplayLargerUiChanged = { viewModel.onGameplayLargerUiChanged(activity, it) },
        onHideEnemyPanelWhileTargetingChanged = { viewModel.onHideEnemyPanelWhileTargetingChanged(activity, it) },
        onKeepScreenOnTimeoutSelected = { viewModel.onKeepScreenOnTimeoutSelected(activity, it) },
    ),
    performance = PerformanceSettingsActions(
        onRenderScaleSelected = { viewModel.onRenderScaleSelected(activity, it) },
        onTargetFpsSelected = { viewModel.onTargetFpsSelected(activity, it) },
        onFramePacingModeChanged = { viewModel.onFramePacingModeChanged(activity, it) },
        onVirtualResolutionModeChanged = { viewModel.onVirtualResolutionModeChanged(activity, it) },
        onRamSaverEnabledChanged = { viewModel.onRamSaverEnabledChanged(activity, it) },
        onMtsPatchCacheEnabledChanged = { viewModel.onMtsPatchCacheEnabledChanged(activity, it) },
        onOpenMtsCacheOptimizations = { onOpenSettingsRoute(Route.SettingsMtsCacheOptimizations) },
        onGamePerformanceOverlayChanged = { viewModel.onGamePerformanceOverlayChanged(activity, it) },
    ),
    market = MarketSettingsActions(
        onWorkshopMaxConcurrentDownloadsChanged = { viewModel.onWorkshopMaxConcurrentDownloadsChanged(activity, it) },
        onWorkshopDownloadThreadsChanged = { viewModel.onWorkshopDownloadThreadsChanged(activity, it) },
        onWorkshopWattAccelerationChanged = { viewModel.onWorkshopWattAccelerationChanged(activity, it) },
        onWorkshopSteamLanguageChanged = { viewModel.onWorkshopSteamLanguageChanged(activity, it) },
        onWorkshopDefaultSortChanged = { viewModel.onWorkshopDefaultSortChanged(activity, it) },
        onWorkshopAutoImportChanged = { viewModel.onWorkshopAutoImportChanged(activity, it) },
        onOpenWorkshopAutoImportDefaults = { onOpenSettingsRoute(Route.SettingsWorkshopAutoImportDefaults) },
        onClearWorkshopPreviewCache = { viewModel.onClearWorkshopPreviewCache(activity) },
        onOpenBaiduTranslationCredentials = { onOpenSettingsRoute(Route.BaiduTranslationCredentials()) },
    ),
    acceleration = AccelerationSettingsActions(
        onAccelerationStrategyChanged = { viewModel.onAccelerationStrategyChanged(activity, it) },
    ),
    advancedRender = AdvancedRenderSettingsActions(
        onRendererSelectionModeChanged = { viewModel.onRendererSelectionModeChanged(activity, it) },
        onManualRendererBackendChanged = { viewModel.onManualRendererBackendChanged(activity, it) },
        onOpenMobileGluesSettings = { onOpenSettingsRoute(Route.MobileGluesSettings) },
        onRenderSurfaceBackendChanged = { viewModel.onRenderSurfaceBackendChanged(activity, it) },
        onGpuResourceGuardianModeChanged = { viewModel.onGpuResourceGuardianModeChanged(activity, it) },
        onGpuResourceGuardianPressureDownscaleChanged = { viewModel.onGpuResourceGuardianPressureDownscaleChanged(activity, it) },
        onJvmHeapMaxSelected = { viewModel.onJvmHeapMaxSelected(activity, it) },
        onJvmTieredStopAtLevelChanged = { viewModel.onJvmTieredStopAtLevelChanged(activity, it) },
        onJvmCompressedPointersChanged = { viewModel.onJvmCompressedPointersChanged(activity, it) },
        onJvmStringDeduplicationChanged = { viewModel.onJvmStringDeduplicationChanged(activity, it) },
    ),
    steamCloud = SteamCloudSettingsActions(
        onOpenSteamCloudLogin = { onOpenSettingsRoute(Route.SteamCloudLogin) },
        onSteamCloudWattAccelerationChanged = { viewModel.onSteamCloudWattAccelerationChanged(activity, it) },
        onSteamCloudAutoLaunchAfterSyncChanged = { viewModel.onSteamCloudAutoLaunchAfterSyncChanged(activity, it) },
        onSteamCloudSyncDisabledChanged = { viewModel.onSteamCloudSyncDisabledChanged(activity, it) },
        onSteamGamePresenceChanged = { viewModel.onSteamGamePresenceChanged(activity, it) },
        onRichPresenceDisplayPreferencesChanged = { viewModel.onRichPresenceDisplayPreferencesChanged(activity, it) },
        onSteamAchievementSyncChanged = { viewModel.onSteamAchievementSyncChanged(activity, it) },
        onAchievementUnlockNotificationChanged = { viewModel.onAchievementUnlockNotificationChanged(activity, it) },
        onOpenSteamCloudSaveSettings = { onOpenSettingsRoute(Route.SteamCloudSaveSettings) },
        onClearSteamCloudCredentials = { viewModel.onClearSteamCloudCredentials(activity) },
        onClearSteamCloudNetworkCache = { viewModel.onClearSteamCloudNetworkCache(activity) },
    ),
    developerRuntime = DeveloperRuntimeSettingsActions(
        onGpuResourceDiagChanged = { viewModel.onGpuResourceDiagChanged(activity, it) },
        onArthasAnalysisChanged = { viewModel.onArthasAnalysisChanged(activity, it) },
        onSharePerformanceLogs = { viewModel.onSharePerformanceLogs(activity) },
        onExportPerformanceLogs = viewModel::onExportPerformanceLogsToFile,
        onInstallArthasResource = { viewModel.onInstallArthasResourceRequested(activity) },
        onRepairResourcePack = LauncherNavigationRequestBus::requestResourcePack,
        onManualDismissBootOverlayChanged = { viewModel.onManualDismissBootOverlayChanged(activity, it) },
        onSustainedPerformanceModeChanged = { viewModel.onSustainedPerformanceModeChanged(activity, it) },
        onCompendiumUpgradeTouchFixEnabledChanged = { viewModel.onCompendiumUpgradeTouchFixEnabledChanged(activity, it) },
    ),
    togetherInSpire = TogetherInSpireSettingsActions(
        onRouteLockEnabledChanged = { viewModel.onTogetherInSpireRouteLockEnabledChanged(activity, it) },
        onEasyTierAutofillEnabledChanged = { viewModel.onTogetherInSpireEasyTierAutofillEnabledChanged(activity, it) },
    ),
    status = StatusSettingsActions(
        onLwjglDebugChanged = { viewModel.onLwjglDebugChanged(activity, it) },
        onPreloadAllJreLibrariesChanged = { viewModel.onPreloadAllJreLibrariesChanged(activity, it) },
        onLogcatCaptureChanged = { viewModel.onLogcatCaptureChanged(activity, it) },
        onLauncherLogcatCaptureChanged = { viewModel.onLauncherLogcatCaptureChanged(activity, it) },
        onJvmLogcatMirrorChanged = { viewModel.onJvmLogcatMirrorChanged(activity, it) },
        onGdxPadCursorDebugChanged = { viewModel.onGdxPadCursorDebugChanged(activity, it) },
        onGlBridgeSwapHeartbeatDebugChanged = { viewModel.onGlBridgeSwapHeartbeatDebugChanged(activity, it) },
        onClearJunkFiles = { viewModel.onClearJunkFiles(activity) },
        onExportAgentWorkspace = viewModel::onExportAgentWorkspaceToFile,
    ),
    onRamSaverOptimizationChanged = { optimization, enabled ->
        viewModel.onRamSaverOptimizationChanged(activity, optimization, enabled)
    },
    onLocalTestCloudControlEnabledChanged = { viewModel.onLocalTestCloudControlEnabledChanged(activity, it) },
    onOpenBasicTutorial = { openBasicTutorial(activity) },
    onPlayerNameChanged = { viewModel.onPlayerNameChanged(activity, it) },
    onApplyModFileNameAliases = { viewModel.onApplyModFileNameAliases(activity) },
    onResetLauncherSettingsToDefaults = { viewModel.onResetLauncherSettingsToDefaults(activity) },
    onImportMods = viewModel::onImportMods,
    onExportMods = viewModel::onExportMods,
    onImportSaves = viewModel::onImportSaves,
    onExportSaves = { viewModel.onExportSaves(activity) },
    onExportLogs = { viewModel.onExportLogs(activity) },
    onExportLogsToFile = viewModel::onExportLogsToFile,
    onImportJar = viewModel::onImportJar,
    onWorkshopAutoImportAtlasDownscaleChanged = { viewModel.onWorkshopAutoImportAtlasDownscaleChanged(activity, it) },
    onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged = { viewModel.onWorkshopAutoImportAtlasDownscaleMaxEdgeChanged(activity, it) },
    onSteamCloudSaveModeChanged = { viewModel.onSteamCloudSaveModeChanged(activity, it) },
    onSteamCloudSyncBlacklistPathChanged = { path, selected ->
        viewModel.onSteamCloudSyncBlacklistPathChanged(activity, path, selected)
    },
    onMtsCacheOptimizationChanged = { optimization, enabled ->
        viewModel.onMtsCacheStartupOptimizationChanged(activity, optimization, enabled)
    },
)
