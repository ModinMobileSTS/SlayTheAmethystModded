package io.stamethyst.ui.settings.core

import android.app.Activity
import io.stamethyst.ui.settings.sections.AccelerationSettingsActions
import io.stamethyst.ui.settings.sections.AdvancedRenderSettingsActions
import io.stamethyst.ui.settings.sections.AppearanceSettingsActions
import io.stamethyst.ui.settings.sections.GameplayDisplaySettingsActions
import io.stamethyst.ui.settings.sections.InputSettingsActions
import io.stamethyst.ui.settings.sections.MarketSettingsActions
import io.stamethyst.ui.settings.sections.PerformanceSettingsActions
import io.stamethyst.ui.settings.sections.SettingsSearchActions
import io.stamethyst.ui.settings.sections.UpdateSettingsActions

internal fun settingsSearchActions(
    viewModel: SettingsScreenViewModel,
    activity: Activity,
): SettingsSearchActions = SettingsSearchActions(
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
        onOpenMtsCacheOptimizations = {},
    ),
    market = MarketSettingsActions(
        onWorkshopMaxConcurrentDownloadsChanged = { viewModel.onWorkshopMaxConcurrentDownloadsChanged(activity, it) },
        onWorkshopDownloadThreadsChanged = { viewModel.onWorkshopDownloadThreadsChanged(activity, it) },
        onWorkshopWattAccelerationChanged = { viewModel.onWorkshopWattAccelerationChanged(activity, it) },
        onWorkshopSteamLanguageChanged = { viewModel.onWorkshopSteamLanguageChanged(activity, it) },
        onWorkshopDefaultSortChanged = { viewModel.onWorkshopDefaultSortChanged(activity, it) },
        onWorkshopAutoImportChanged = { viewModel.onWorkshopAutoImportChanged(activity, it) },
        onOpenWorkshopAutoImportDefaults = {},
        onClearWorkshopPreviewCache = { viewModel.onClearWorkshopPreviewCache(activity) },
        onOpenBaiduTranslationCredentials = {},
    ),
    acceleration = AccelerationSettingsActions(
        onAccelerationStrategyChanged = { viewModel.onAccelerationStrategyChanged(activity, it) },
    ),
    advancedRender = AdvancedRenderSettingsActions(
        onRendererSelectionModeChanged = { viewModel.onRendererSelectionModeChanged(activity, it) },
        onManualRendererBackendChanged = { viewModel.onManualRendererBackendChanged(activity, it) },
        onOpenMobileGluesSettings = {},
        onRenderSurfaceBackendChanged = { viewModel.onRenderSurfaceBackendChanged(activity, it) },
        onGpuResourceGuardianModeChanged = { viewModel.onGpuResourceGuardianModeChanged(activity, it) },
        onGpuResourceGuardianPressureDownscaleChanged = { viewModel.onGpuResourceGuardianPressureDownscaleChanged(activity, it) },
        onJvmHeapMaxSelected = { viewModel.onJvmHeapMaxSelected(activity, it) },
        onJvmTieredStopAtLevelChanged = { viewModel.onJvmTieredStopAtLevelChanged(activity, it) },
        onJvmCompressedPointersChanged = { viewModel.onJvmCompressedPointersChanged(activity, it) },
        onJvmStringDeduplicationChanged = { viewModel.onJvmStringDeduplicationChanged(activity, it) },
    ),
    onPlayerNameChanged = { viewModel.onPlayerNameChanged(activity, it) },
    onApplyModFileNameAliases = { viewModel.onApplyModFileNameAliases(activity) },
    onResetLauncherSettingsToDefaults = { viewModel.onResetLauncherSettingsToDefaults(activity) },
    onImportMods = viewModel::onImportMods,
    onExportMods = viewModel::onExportMods,
    onImportSaves = viewModel::onImportSaves,
    onExportSaves = { viewModel.onExportSaves(activity) },
    onExportLogs = { viewModel.onExportLogs(activity) },
    onExportLogsToFile = viewModel::onExportLogsToFile,
)
