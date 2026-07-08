package io.stamethyst.ui.settings.sections

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.importing.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.workshop.SteamLanguagePreference
import io.stamethyst.navigation.Route
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.BootOverlayAnimation
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.LauncherIconMode
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.config.TouchscreenInputMode
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
            selectedTargetFps = 90,
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
            targetFpsOptions = listOf(24, 30, 60, 90, 120, 240),
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
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val blockingInteractionLocked = uiState.busyOperation.usesBlockingOverlay()
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsHomeRouteSpec,
        showBackButton = showBackButton,
        onGoBack = onGoBack,
    ) {
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

    SettingsFeedbackSubmissionNoticeDialog(
        notice = feedbackSubmissionNotice,
        onDismiss = onDismissFeedbackSubmissionNotice,
    )
}

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
    onBootOverlayStyleChanged: (BootOverlayStyle) -> Unit = {},
    onBootOverlayAnimationChanged: (BootOverlayAnimation) -> Unit = {},
    onBootOverlayImageModeChanged: (BootOverlayImageMode) -> Unit = {},
    onPickBootOverlayImage: (BootOverlayImageSlot) -> Unit = {},
    onResetBootOverlayImages: () -> Unit = {},
    onShowModFileNameChanged: (Boolean) -> Unit = {},
    onAutoCheckUpdatesChanged: (Boolean) -> Unit = {},
    onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit = {},
    onManualCheckUpdates: () -> Unit = {},
    onOpenReleaseHistory: () -> Unit = {},
    onDismissReleaseHistoryDialog: () -> Unit = {},
    onOpenFirstRunSetup: () -> Unit = {},
    onApplyModFileNameAliases: () -> Unit = {},
) {
    var showApplyModFileNameAliasesDialog by rememberSaveable { mutableStateOf(false) }

    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsLauncherRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_basic_tutorial_title)) {
                SettingsActionListItem(
                    title = stringResource(R.string.settings_basic_tutorial_action),
                    supportingText = stringResource(R.string.settings_basic_tutorial_desc),
                    enabled = !uiState.busy,
                    onClick = onOpenBasicTutorial,
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_appearance_section_title)) {
                SettingsAppearanceSection(
                    uiState = uiState,
                    actions = AppearanceSettingsActions(
                        onThemeModeChanged = onThemeModeChanged,
                        onThemeColorChanged = onThemeColorChanged,
                        onLauncherIconModeChanged = onLauncherIconModeChanged,
                        onChromeBackgroundOpacityChanged = onChromeBackgroundOpacityChanged,
                        onBootOverlayStyleChanged = onBootOverlayStyleChanged,
                        onBootOverlayAnimationChanged = onBootOverlayAnimationChanged,
                        onBootOverlayImageModeChanged = onBootOverlayImageModeChanged,
                        onPickBootOverlayImage = onPickBootOverlayImage,
                        onResetBootOverlayImages = onResetBootOverlayImages,
                        onShowModFileNameChanged = onShowModFileNameChanged,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.update_section_title)) {
                SettingsUpdateSection(
                    uiState = uiState,
                    actions = UpdateSettingsActions(
                        onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                        onPreferredUpdateMirrorChanged = onPreferredUpdateMirrorChanged,
                        onManualCheckUpdates = onManualCheckUpdates,
                        onOpenReleaseHistory = onOpenReleaseHistory,
                        onDismissReleaseHistoryDialog = onDismissReleaseHistoryDialog,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_launcher_other_section_title)) {
                SettingsActionListItem(
                    title = stringResource(R.string.settings_first_run_reopen_action),
                    supportingText = stringResource(R.string.settings_first_run_reopen_desc),
                    enabled = !uiState.busy,
                    onClick = onOpenFirstRunSetup,
                )
                SettingsDangerActionListItem(
                    title = stringResource(R.string.settings_mod_alias_apply_file_names_action),
                    supportingText = stringResource(R.string.settings_mod_alias_apply_file_names_desc),
                    enabled = !uiState.busy,
                    onClick = { showApplyModFileNameAliasesDialog = true },
                )
            }
        }
    }

    if (showApplyModFileNameAliasesDialog) {
        AlertDialog(
            onDismissRequest = { showApplyModFileNameAliasesDialog = false },
            title = { Text(stringResource(R.string.settings_mod_alias_apply_file_names_confirm_title)) },
            text = { Text(stringResource(R.string.settings_mod_alias_apply_file_names_confirm_message)) },
            confirmButton = {
                TextButton(
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
    onRenderScaleSelected: (Float) -> Unit = {},
    onTargetFpsSelected: (Int) -> Unit = {},
    onVirtualResolutionModeChanged: (VirtualResolutionMode) -> Unit = {},
    onDisplayCutoutAvoidanceChanged: (Boolean) -> Unit = {},
    onScreenBottomCropChanged: (Boolean) -> Unit = {},
    onRamSaverEnabledChanged: (Boolean) -> Unit = {},
    onMtsPatchCacheEnabledChanged: (Boolean) -> Unit = {},
    onKeepScreenOnTimeoutSelected: (Int) -> Unit = {},
    onGameplayFontScaleChanged: (Float) -> Unit = {},
    onGameplayLargerUiChanged: (Boolean) -> Unit = {},
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
    onHapticFeedbackChanged: (Boolean) -> Unit = {},
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit = {},
    onGamePerformanceOverlayChanged: (Boolean) -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsGameRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_render)) {
                SettingsPerformanceSection(
                    uiState = uiState,
                    actions = PerformanceSettingsActions(
                        onRenderScaleSelected = onRenderScaleSelected,
                        onTargetFpsSelected = onTargetFpsSelected,
                        onVirtualResolutionModeChanged = onVirtualResolutionModeChanged,
                        onDisplayCutoutAvoidanceChanged = onDisplayCutoutAvoidanceChanged,
                        onScreenBottomCropChanged = onScreenBottomCropChanged,
                        onRamSaverEnabledChanged = onRamSaverEnabledChanged,
                        onMtsPatchCacheEnabledChanged = onMtsPatchCacheEnabledChanged,
                        onGameplayFontScaleChanged = onGameplayFontScaleChanged,
                        onGameplayLargerUiChanged = onGameplayLargerUiChanged,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_section_input)) {
                SettingsInputSection(
                    uiState = uiState,
                    actions = InputSettingsActions(
                        onPlayerNameChanged = onPlayerNameChanged,
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
                        onHapticFeedbackChanged = onHapticFeedbackChanged,
                        onAutoSwitchLeftAfterRightClickChanged = onAutoSwitchLeftAfterRightClickChanged,
                        onKeepScreenOnTimeoutSelected = onKeepScreenOnTimeoutSelected,
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
    onOpenSteamCloudSaveSettings: () -> Unit = {},
    onClearSteamCloudCredentials: () -> Unit = {},
    onClearSteamCloudNetworkCache: () -> Unit = {},
    onWorkshopMaxConcurrentDownloadsChanged: (Int) -> Unit = {},
    onWorkshopDownloadThreadsChanged: (Int) -> Unit = {},
    onWorkshopWattAccelerationChanged: (Boolean) -> Unit = {},
    onWorkshopSteamLanguageChanged: (SteamLanguagePreference) -> Unit = {},
    onWorkshopAutoImportChanged: (Boolean) -> Unit = {},
    onOpenWorkshopAutoImportDefaults: () -> Unit = {},
    onClearWorkshopPreviewCache: () -> Unit = {},
    onOpenBaiduTranslationCredentials: () -> Unit = {},
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        spec = SettingsMarketCloudRouteSpec,
        onGoBack = onGoBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_steam_cloud_title)) {
                SettingsSteamCloudSection(
                    uiState = uiState,
                    actions = SteamCloudSettingsActions(
                        onOpenSteamCloudLogin = onOpenSteamCloudLogin,
                        onSteamCloudWattAccelerationChanged = onSteamCloudWattAccelerationChanged,
                        onSteamCloudAutoLaunchAfterSyncChanged = onSteamCloudAutoLaunchAfterSyncChanged,
                        onOpenSteamCloudSaveSettings = onOpenSteamCloudSaveSettings,
                        onClearSteamCloudCredentials = onClearSteamCloudCredentials,
                        onClearSteamCloudNetworkCache = onClearSteamCloudNetworkCache,
                    ),
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.settings_market_section_title)) {
                SettingsMarketSection(
                    uiState = uiState,
                    actions = MarketSettingsActions(
                        onWorkshopMaxConcurrentDownloadsChanged = onWorkshopMaxConcurrentDownloadsChanged,
                        onWorkshopDownloadThreadsChanged = onWorkshopDownloadThreadsChanged,
                        onWorkshopWattAccelerationChanged = onWorkshopWattAccelerationChanged,
                        onWorkshopSteamLanguageChanged = onWorkshopSteamLanguageChanged,
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
                        enabledText = stringResource(
                            R.string.settings_workshop_auto_import_defaults_atlas_enabled_title
                        ),
                        disabledText = stringResource(
                            R.string.settings_workshop_auto_import_defaults_atlas_disabled_title
                        ),
                        description = stringResource(
                            R.string.settings_workshop_auto_import_defaults_atlas_desc
                        ),
                        onCheckedChange = onAtlasDownscaleChanged,
                    )
                )
                Spacer(modifier = Modifier.size(8.dp))
                SettingsDropdownField(
                    label = stringResource(
                        R.string.settings_workshop_auto_import_defaults_atlas_level_title
                    ),
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
                    optionLabel = { maxEdgePx ->
                        stringResource(
                            R.string.mod_import_atlas_downscale_level_max_edge_label,
                            maxEdgePx
                        )
                    },
                    optionDescription = { maxEdgePx ->
                        stringResource(
                            R.string.mod_import_atlas_downscale_level_max_edge_desc,
                            maxEdgePx
                        )
                    },
                    onOptionSelected = onAtlasDownscaleMaxEdgeChanged,
                )
            }
        }
    }
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
            SettingsSectionCard(title = stringResource(R.string.settings_section_resources_files)) {
                SettingsImportSection(
                    busy = uiState.busy,
                    onImportJar = onImportJar,
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


