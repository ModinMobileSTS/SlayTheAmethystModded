package io.stamethyst.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.config.RenderSurfaceBackend
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.Icons
import io.stamethyst.ui.resolve
import io.stamethyst.ui.UiBusyOperation
import io.stamethyst.ui.icon.ArrowBack
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val WORKSHOP_DOWNLOADER_PACKAGE_NAME = "top.apricityx.workshop"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        delay(320)
        viewModel.bind(activity)
    }

    LauncherSettingsScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onImportJar = viewModel::onImportJar,
        onImportMods = viewModel::onImportMods,
        onExportMods = viewModel::onExportMods,
        onImportSaves = viewModel::onImportSaves,
        onExportSaves = viewModel::onExportSaves,
        onExportLogs = { viewModel.onExportLogs(activity) },
        onExportLogsToFile = viewModel::onExportLogsToFile,
        onRenderScaleSelected = { value -> viewModel.onRenderScaleSelected(activity, value) },
        onTargetFpsSelected = { fps -> viewModel.onTargetFpsSelected(activity, fps) },
        onRendererSelectionModeChanged = { mode ->
            viewModel.onRendererSelectionModeChanged(activity, mode)
        },
        onManualRendererBackendChanged = { backend ->
            viewModel.onManualRendererBackendChanged(activity, backend)
        },
        onRenderSurfaceBackendChanged = { backend ->
            viewModel.onRenderSurfaceBackendChanged(activity, backend)
        },
        onThemeModeChanged = { themeMode ->
            viewModel.onThemeModeChanged(activity, themeMode)
        },
        onJvmHeapMaxSelected = { value -> viewModel.onJvmHeapMaxSelected(activity, value) },
        onJvmCompressedPointersChanged = { enabled ->
            viewModel.onJvmCompressedPointersChanged(activity, enabled)
        },
        onJvmStringDeduplicationChanged = { enabled ->
            viewModel.onJvmStringDeduplicationChanged(activity, enabled)
        },
        onPlayerNameChanged = { name -> viewModel.onPlayerNameChanged(activity, name) },
        onBackBehaviorChanged = { behavior -> viewModel.onBackBehaviorChanged(activity, behavior) },
        onManualDismissBootOverlayChanged = { enabled -> viewModel.onManualDismissBootOverlayChanged(activity, enabled) },
        onShowFloatingMouseWindowChanged = { enabled -> viewModel.onShowFloatingMouseWindowChanged(activity, enabled) },
        onTouchMouseDoubleTapLockChanged = { enabled -> viewModel.onTouchMouseDoubleTapLockChanged(activity, enabled) },
        onLongPressMouseShowsKeyboardChanged = { enabled -> viewModel.onLongPressMouseShowsKeyboardChanged(activity, enabled) },
        onAutoSwitchLeftAfterRightClickChanged = { enabled -> viewModel.onAutoSwitchLeftAfterRightClickChanged(activity, enabled) },
        onShowModFileNameChanged = { enabled -> viewModel.onShowModFileNameChanged(activity, enabled) },
        onMobileHudEnabledChanged = { enabled -> viewModel.onMobileHudEnabledChanged(activity, enabled) },
        onCompendiumUpgradeTouchFixEnabledChanged = { enabled ->
            viewModel.onCompendiumUpgradeTouchFixEnabledChanged(activity, enabled)
        },
        onDisplayCutoutAvoidanceChanged = { enabled ->
            viewModel.onDisplayCutoutAvoidanceChanged(activity, enabled)
        },
        onScreenBottomCropChanged = { enabled ->
            viewModel.onScreenBottomCropChanged(activity, enabled)
        },
        onGamePerformanceOverlayChanged = { enabled ->
            viewModel.onGamePerformanceOverlayChanged(activity, enabled)
        },
        onSustainedPerformanceModeChanged = { enabled ->
            viewModel.onSustainedPerformanceModeChanged(activity, enabled)
        },
        onLwjglDebugChanged = { enabled -> viewModel.onLwjglDebugChanged(activity, enabled) },
        onPreloadAllJreLibrariesChanged = {
            enabled -> viewModel.onPreloadAllJreLibrariesChanged(activity, enabled)
        },
        onLogcatCaptureChanged = { enabled -> viewModel.onLogcatCaptureChanged(activity, enabled) },
        onJvmLogcatMirrorChanged = { enabled -> viewModel.onJvmLogcatMirrorChanged(activity, enabled) },
        onGpuResourceDiagChanged = { enabled -> viewModel.onGpuResourceDiagChanged(activity, enabled) },
        onGdxPadCursorDebugChanged = { enabled -> viewModel.onGdxPadCursorDebugChanged(activity, enabled) },
        onGlBridgeSwapHeartbeatDebugChanged = { enabled -> viewModel.onGlBridgeSwapHeartbeatDebugChanged(activity, enabled) },
        onTouchscreenEnabledChanged = { enabled -> viewModel.onTouchscreenEnabledChanged(activity, enabled) },
        onAutoCheckUpdatesChanged = { enabled ->
            viewModel.onAutoCheckUpdatesChanged(activity, enabled)
        },
        onPreferredUpdateMirrorChanged = { source ->
            viewModel.onPreferredUpdateMirrorChanged(activity, source)
        },
        onManualCheckUpdates = { viewModel.onManualCheckUpdates(activity) },
        onOpenCompatibility = viewModel::onOpenCompatibility,
        onOpenMobileGluesSettings = viewModel::onOpenMobileGluesSettings,
        onOpenFeedback = viewModel::onOpenFeedback,
        onOpenFeedbackSubscriptions = { navigator.push(Route.FeedbackSubscriptions) },
        onOpenFeedbackIssueBrowser = { navigator.push(Route.FeedbackIssueBrowser) },
        feedbackSubmissionNotice = feedbackSubmissionNotice,
        onDismissFeedbackSubmissionNotice = onDismissFeedbackSubmissionNotice,
    )
    SettingsEffectsHandler(viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, heightDp = 2000)
@Composable
private fun LauncherSettingsScreenPreview() {
    LauncherSettingsScreenContent(
        uiState = SettingsScreenViewModel.UiState(
            busy = false,
            playerName = "player",
            selectedRenderScale = 1.00f,
            selectedTargetFps = 60,
            renderSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW,
            themeMode = LauncherThemeMode.FOLLOW_SYSTEM,
            selectedJvmHeapMaxMb = 512,
            compressedPointersEnabled = false,
            stringDeduplicationEnabled = false,
            jvmHeapMinMb = 256,
            jvmHeapMaxMb = 2048,
            jvmHeapStepMb = 128,
            backBehavior = BackBehavior.EXIT_TO_LAUNCHER,
            manualDismissBootOverlay = false,
            showFloatingMouseWindow = true,
            touchMouseDoubleTapLockEnabled = true,
            longPressMouseShowsKeyboard = true,
            autoSwitchLeftAfterRightClick = true,
            showModFileName = false,
            mobileHudEnabled = false,
            avoidDisplayCutout = false,
            cropScreenBottom = false,
            showGamePerformanceOverlay = false,
            sustainedPerformanceModeEnabled = false,
            lwjglDebugEnabled = false,
            preloadAllJreLibrariesEnabled = false,
            logcatCaptureEnabled = true,
            jvmLogcatMirrorEnabled = false,
            gpuResourceDiagEnabled = true,
            gdxPadCursorDebugEnabled = false,
            glBridgeSwapHeartbeatDebugEnabled = false,
            touchscreenEnabled = true,
            statusText = "desktop-1.0.jar: OK\nBaseMod.jar: OK\nStSLib.jar: OK",
            logPathText = "/example/path/to/logs",
            targetFpsOptions = listOf(24, 30, 60, 120, 240),
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
private fun LauncherSettingsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onImportJar: () -> Unit = {},
    onImportMods: () -> Unit = {},
    onExportMods: () -> Unit = {},
    onImportSaves: () -> Unit = {},
    onExportSaves: () -> Unit = {},
    onExportLogs: () -> Unit = {},
    onExportLogsToFile: () -> Unit = {},
    onRenderScaleSelected: (Float) -> Unit = {},
    onTargetFpsSelected: (Int) -> Unit = {},
    onRendererSelectionModeChanged: (RendererSelectionMode) -> Unit = {},
    onManualRendererBackendChanged: (RendererBackend) -> Unit = {},
    onRenderSurfaceBackendChanged: (RenderSurfaceBackend) -> Unit = {},
    onThemeModeChanged: (LauncherThemeMode) -> Unit = {},
    onJvmHeapMaxSelected: (Int) -> Unit = {},
    onJvmCompressedPointersChanged: (Boolean) -> Unit = {},
    onJvmStringDeduplicationChanged: (Boolean) -> Unit = {},
    onPlayerNameChanged: (String) -> Boolean = { true },
    onBackBehaviorChanged: (BackBehavior) -> Unit = {},
    onManualDismissBootOverlayChanged: (Boolean) -> Unit = {},
    onShowFloatingMouseWindowChanged: (Boolean) -> Unit = {},
    onTouchMouseDoubleTapLockChanged: (Boolean) -> Unit = {},
    onLongPressMouseShowsKeyboardChanged: (Boolean) -> Unit = {},
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit = {},
    onShowModFileNameChanged: (Boolean) -> Unit = {},
    onMobileHudEnabledChanged: (Boolean) -> Unit = {},
    onCompendiumUpgradeTouchFixEnabledChanged: (Boolean) -> Unit = {},
    onDisplayCutoutAvoidanceChanged: (Boolean) -> Unit = {},
    onScreenBottomCropChanged: (Boolean) -> Unit = {},
    onGamePerformanceOverlayChanged: (Boolean) -> Unit = {},
    onSustainedPerformanceModeChanged: (Boolean) -> Unit = {},
    onLwjglDebugChanged: (Boolean) -> Unit = {},
    onPreloadAllJreLibrariesChanged: (Boolean) -> Unit = {},
    onLogcatCaptureChanged: (Boolean) -> Unit = {},
    onJvmLogcatMirrorChanged: (Boolean) -> Unit = {},
    onGpuResourceDiagChanged: (Boolean) -> Unit = {},
    onGdxPadCursorDebugChanged: (Boolean) -> Unit = {},
    onGlBridgeSwapHeartbeatDebugChanged: (Boolean) -> Unit = {},
    onTouchscreenEnabledChanged: (Boolean) -> Unit = {},
    onAutoCheckUpdatesChanged: (Boolean) -> Unit = {},
    onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit = {},
    onManualCheckUpdates: () -> Unit = {},
    onOpenCompatibility: () -> Unit = {},
    onOpenMobileGluesSettings: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    onOpenFeedbackSubscriptions: () -> Unit = {},
    onOpenFeedbackIssueBrowser: () -> Unit = {},
    feedbackSubmissionNotice: FeedbackSubmissionNotice? = null,
    onDismissFeedbackSubmissionNotice: () -> Unit = {},
) {
    val blockingImportInteractionLocked = uiState.busyOperation == UiBusyOperation.MOD_IMPORT
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    HapticIconButton(
                        onClick = onGoBack,
                        enabled = !blockingImportInteractionLocked
                    ) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsBusyIndicator(uiState = uiState)
            }

            item {
                SettingsFeedbackEntryCard(
                    busy = uiState.busy,
                    onOpenFeedback = onOpenFeedback,
                    onOpenFeedbackSubscriptions = onOpenFeedbackSubscriptions,
                    onOpenFeedbackIssueBrowser = onOpenFeedbackIssueBrowser
                )
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.settings_appearance_section_title)) {
                    SettingsAppearanceSection(
                        uiState = uiState,
                        onThemeModeChanged = onThemeModeChanged
                    )
                }
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

            item {
                SettingsSectionCard(title = stringResource(R.string.settings_section_render)) {
                    SettingsRenderSection(
                        uiState = uiState,
                        onRenderScaleSelected = onRenderScaleSelected,
                        onTargetFpsSelected = onTargetFpsSelected,
                        onRendererSelectionModeChanged = onRendererSelectionModeChanged,
                        onManualRendererBackendChanged = onManualRendererBackendChanged,
                        onOpenMobileGluesSettings = onOpenMobileGluesSettings,
                        onRenderSurfaceBackendChanged = onRenderSurfaceBackendChanged,
                        onJvmHeapMaxSelected = onJvmHeapMaxSelected,
                        onJvmCompressedPointersChanged = onJvmCompressedPointersChanged,
                        onJvmStringDeduplicationChanged = onJvmStringDeduplicationChanged,
                        onSustainedPerformanceModeChanged = onSustainedPerformanceModeChanged,
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.settings_section_input)) {
                    SettingsInputSection(
                        uiState = uiState,
                        onPlayerNameChanged = onPlayerNameChanged,
                        onBackBehaviorChanged = onBackBehaviorChanged,
                        onManualDismissBootOverlayChanged = onManualDismissBootOverlayChanged,
                        onShowFloatingMouseWindowChanged = onShowFloatingMouseWindowChanged,
                        onTouchMouseDoubleTapLockChanged = onTouchMouseDoubleTapLockChanged,
                        onLongPressMouseShowsKeyboardChanged = onLongPressMouseShowsKeyboardChanged,
                        onAutoSwitchLeftAfterRightClickChanged = onAutoSwitchLeftAfterRightClickChanged,
                        onShowModFileNameChanged = onShowModFileNameChanged,
                        onMobileHudEnabledChanged = onMobileHudEnabledChanged,
                        onCompendiumUpgradeTouchFixEnabledChanged =
                            onCompendiumUpgradeTouchFixEnabledChanged,
                        onDisplayCutoutAvoidanceChanged = onDisplayCutoutAvoidanceChanged,
                        onScreenBottomCropChanged = onScreenBottomCropChanged,
                        onGamePerformanceOverlayChanged = onGamePerformanceOverlayChanged,
                        onTouchscreenEnabledChanged = onTouchscreenEnabledChanged,
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.update_section_title)) {
                    SettingsUpdateSection(
                        uiState = uiState,
                        onAutoCheckUpdatesChanged = onAutoCheckUpdatesChanged,
                        onPreferredUpdateMirrorChanged = onPreferredUpdateMirrorChanged,
                        onManualCheckUpdates = onManualCheckUpdates
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.compat_settings_title)) {
                    SettingsCompatibilitySection(
                        busy = uiState.busy,
                        onOpenCompatibility = onOpenCompatibility,
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.settings_section_status_logs)) {
                    SettingsStatusSection(
                        uiState = uiState,
                        onLwjglDebugChanged = onLwjglDebugChanged,
                        onPreloadAllJreLibrariesChanged = onPreloadAllJreLibrariesChanged,
                        onLogcatCaptureChanged = onLogcatCaptureChanged,
                        onJvmLogcatMirrorChanged = onJvmLogcatMirrorChanged,
                        onGpuResourceDiagChanged = onGpuResourceDiagChanged,
                        onGdxPadCursorDebugChanged = onGdxPadCursorDebugChanged,
                        onGlBridgeSwapHeartbeatDebugChanged = onGlBridgeSwapHeartbeatDebugChanged
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.settings_author_info_title)) {
                    SettingsAuthorInfoSection()
                }
            }
        }
    }

    feedbackSubmissionNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = onDismissFeedbackSubmissionNotice,
            title = { Text(notice.title) },
            text = { Text(notice.message) },
            confirmButton = {
                if (!notice.issueUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            onDismissFeedbackSubmissionNotice()
                            uriHandler.openUri(notice.issueUrl)
                        }
                    ) {
                        Text(stringResource(R.string.common_action_open_issue))
                    }
                } else {
                    TextButton(onClick = onDismissFeedbackSubmissionNotice) {
                        Text(stringResource(R.string.common_action_acknowledge))
                    }
                }
            },
            dismissButton = {
                if (!notice.issueUrl.isNullOrBlank()) {
                    TextButton(onClick = onDismissFeedbackSubmissionNotice) {
                        Text(stringResource(R.string.common_action_acknowledge))
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingsAppearanceSection(
    uiState: SettingsScreenViewModel.UiState,
    onThemeModeChanged: (LauncherThemeMode) -> Unit,
) {
    var showThemeModeDialog by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsActionListItem(
            title = stringResource(R.string.settings_theme_mode_title),
            supportingText = themeModeDisplayName(uiState.themeMode),
            enabled = !uiState.busy,
            onClick = { showThemeModeDialog = true }
        )
        Text(
            text = stringResource(R.string.settings_theme_mode_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showThemeModeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeModeDialog = false },
            title = { Text(stringResource(R.string.settings_theme_mode_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherThemeMode.entries.forEach { themeMode ->
                        SettingsRadioOptionRow(
                            selected = uiState.themeMode == themeMode,
                            enabled = !uiState.busy,
                            text = themeModeDisplayName(themeMode),
                            onSelect = {
                                onThemeModeChanged(themeMode)
                                showThemeModeDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showThemeModeDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }
}

@Composable
private fun themeModeDisplayName(themeMode: LauncherThemeMode): String {
    return when (themeMode) {
        LauncherThemeMode.FOLLOW_SYSTEM ->
            stringResource(R.string.settings_theme_mode_follow_system)
        LauncherThemeMode.LIGHT ->
            stringResource(R.string.settings_theme_mode_light)
        LauncherThemeMode.DARK ->
            stringResource(R.string.settings_theme_mode_dark)
    }
}

@Composable
private fun SettingsUpdateSection(
    uiState: SettingsScreenViewModel.UiState,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit,
    onManualCheckUpdates: () -> Unit,
) {
    var showMirrorDialog by rememberSaveable { mutableStateOf(false) }
    val controlsEnabled = !uiState.busy && !uiState.updateCheckInProgress

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchSettingRow(
            checked = uiState.autoCheckUpdatesEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.update_auto_check_enabled),
            disabledText = stringResource(R.string.update_auto_check_disabled),
            description = stringResource(R.string.update_auto_check_desc),
            onCheckedChange = onAutoCheckUpdatesChanged
        )

        SettingsActionListItem(
            title = stringResource(R.string.update_mirror_title),
            supportingText = uiState.preferredUpdateMirror.displayName,
            enabled = controlsEnabled,
            onClick = { showMirrorDialog = true }
        )
        Text(
            text = stringResource(R.string.update_mirror_desc),
            style = MaterialTheme.typography.bodySmall
        )

        if (uiState.updateCheckInProgress) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        SettingsActionListItem(
            title = stringResource(
                if (uiState.updateCheckInProgress) {
                    R.string.update_manual_check_running
                } else {
                    R.string.update_manual_check_title
                }
            ),
            enabled = controlsEnabled,
            onClick = onManualCheckUpdates
        )

        Text(
            text = stringResource(R.string.update_current_version, uiState.currentVersionText),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.update_status_title),
            style = MaterialTheme.typography.bodyMedium
        )
        SelectionContainer {
            Text(
                text = uiState.updateStatusSummary.ifBlank {
                    stringResource(R.string.update_status_not_checked)
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showMirrorDialog) {
        AlertDialog(
            onDismissRequest = { showMirrorDialog = false },
            title = { Text(stringResource(R.string.update_mirror_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.availableUpdateMirrors.forEach { source ->
                        SettingsRadioOptionRow(
                            selected = uiState.preferredUpdateMirror == source,
                            enabled = !uiState.busy,
                            text = source.displayName,
                            onSelect = {
                                onPreferredUpdateMirrorChanged(source)
                                showMirrorDialog = false
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.update_mirror_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showMirrorDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            }
        )
    }
}

@Composable
private fun SettingsFeedbackEntryCard(
    busy: Boolean,
    onOpenFeedback: () -> Unit,
    onOpenFeedbackSubscriptions: () -> Unit,
    onOpenFeedbackIssueBrowser: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_feedback_entry_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_feedback_entry_desc),
                style = MaterialTheme.typography.bodySmall
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_new),
                enabled = !busy,
                onClick = onOpenFeedback
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_subscriptions),
                enabled = !busy,
                onClick = onOpenFeedbackSubscriptions
            )
            SettingsActionListItem(
                title = stringResource(R.string.settings_feedback_entry_issue_browser),
                enabled = !busy,
                onClick = onOpenFeedbackIssueBrowser
            )
        }
    }
}

@Composable
internal fun SettingsBusyIndicator(
    uiState: SettingsScreenViewModel.UiState
) {
    if (!uiState.busy) {
        return
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    uiState.busyMessage?.let {
        Text(text = it.resolve(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingsImportSection(
    busy: Boolean,
    onImportJar: () -> Unit,
    onImportMods: () -> Unit,
    onExportMods: () -> Unit,
    onImportSaves: () -> Unit,
    onExportSaves: () -> Unit,
    onExportLogs: () -> Unit,
    onExportLogsToFile: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val getNewModsProjectUrl = stringResource(R.string.main_get_new_mods_dialog_url)
    var showGetNewModsDialog by rememberSaveable { mutableStateOf(false) }

    GetNewModsDialog(
        visible = showGetNewModsDialog,
        onDismiss = { showGetNewModsDialog = false },
        onOpenProject = {
            showGetNewModsDialog = false
            uriHandler.openUri(getNewModsProjectUrl)
        }
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsActionListItem(
            title = stringResource(R.string.main_get_new_mods),
            enabled = !busy,
            onClick = {
                if (!openWorkshopDownloader(context)) {
                    showGetNewModsDialog = true
                }
            }
        )
        SettingsActionListItem(
            title = stringResource(R.string.main_import_mods),
            enabled = !busy,
            onClick = onImportMods
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_export_all_mods),
            enabled = !busy,
            onClick = onExportMods
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_import_saves),
            enabled = !busy,
            onClick = onImportSaves
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_export_saves),
            enabled = !busy,
            onClick = onExportSaves
        )
        SettingsActionListItem(
            title = stringResource(R.string.sts_share_crash_report),
            enabled = !busy,
            onClick = onExportLogs
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_export_error_logs),
            enabled = !busy,
            onClick = onExportLogsToFile
        )
        SettingsActionListItem(
            title = stringResource(R.string.settings_reimport_sts_jar_title),
            supportingText = stringResource(R.string.settings_reimport_sts_jar_desc),
            enabled = !busy,
            onClick = onImportJar
        )
    }
}

private fun openWorkshopDownloader(context: Context): Boolean {
    if (!isPackageInstalled(context, WORKSHOP_DOWNLOADER_PACKAGE_NAME)) {
        return false
    }
    val launchIntent =
        context.packageManager.getLaunchIntentForPackage(WORKSHOP_DOWNLOADER_PACKAGE_NAME)
            ?: return false
    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

@Composable
private fun GetNewModsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenProject: () -> Unit,
) {
    if (!visible) {
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.main_get_new_mods_dialog_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = stringResource(
                        R.string.main_get_new_mods_dialog_message,
                        stringResource(R.string.main_get_new_mods_dialog_url)
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenProject) {
                Text(text = stringResource(R.string.main_get_new_mods_dialog_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.main_get_new_mods_dialog_close))
            }
        }
    )
}

@Composable
private fun SettingsRenderSection(
    uiState: SettingsScreenViewModel.UiState,
    onRenderScaleSelected: (Float) -> Unit,
    onTargetFpsSelected: (Int) -> Unit,
    onRendererSelectionModeChanged: (RendererSelectionMode) -> Unit,
    onManualRendererBackendChanged: (RendererBackend) -> Unit,
    onOpenMobileGluesSettings: () -> Unit,
    onRenderSurfaceBackendChanged: (RenderSurfaceBackend) -> Unit,
    onJvmHeapMaxSelected: (Int) -> Unit,
    onJvmCompressedPointersChanged: (Boolean) -> Unit,
    onJvmStringDeduplicationChanged: (Boolean) -> Unit,
    onSustainedPerformanceModeChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    var showGameModeDialog by rememberSaveable { mutableStateOf(false) }
    var renderScaleSliderValue by remember(uiState.selectedRenderScale) {
        mutableFloatStateOf(uiState.selectedRenderScale)
    }
    var heapSliderValue by remember(uiState.selectedJvmHeapMaxMb) {
        mutableFloatStateOf(uiState.selectedJvmHeapMaxMb.toFloat())
    }
    var lastRenderScaleStep by remember(uiState.selectedRenderScale) {
        mutableIntStateOf(renderScaleToStep(uiState.selectedRenderScale))
    }
    var lastHeapStep by remember(
        uiState.selectedJvmHeapMaxMb,
        uiState.jvmHeapMinMb,
        uiState.jvmHeapStepMb,
    ) {
        mutableIntStateOf(
            heapSliderToStep(
                value = uiState.selectedJvmHeapMaxMb.toFloat(),
                min = uiState.jvmHeapMinMb,
                step = uiState.jvmHeapStepMb,
            )
        )
    }

    Text(text = "画面清晰度", style = MaterialTheme.typography.bodyMedium)
    Text(
        text = RenderScaleService.format(renderScaleSliderValue),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = "越高越清楚，越低越省性能",
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = renderScaleSliderValue,
        onValueChange = { value ->
            renderScaleSliderValue = value
            val step = renderScaleToStep(value)
            if (step != lastRenderScaleStep) {
                lastRenderScaleStep = step
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = { onRenderScaleSelected(renderScaleSliderValue) },
        valueRange = RenderScaleService.MIN_RENDER_SCALE..RenderScaleService.MAX_RENDER_SCALE,
        steps = ((RenderScaleService.MAX_RENDER_SCALE - RenderScaleService.MIN_RENDER_SCALE) / 0.01f)
            .roundToInt() - 1,
        enabled = !uiState.busy,
        modifier = Modifier.fillMaxWidth()
    )

    Text(text = "刷新率上限", style = MaterialTheme.typography.bodyMedium)
    uiState.targetFpsOptions.forEach { fps ->
        TargetFpsOptionRow(
            fps = fps,
            selected = uiState.selectedTargetFps == fps,
            enabled = !uiState.busy,
            onSelect = onTargetFpsSelected
        )
    }

    SwitchSettingRow(
        checked = uiState.sustainedPerformanceModeEnabled,
        enabled = !uiState.busy,
        enabledText = "持续性能模式：启用",
        disabledText = "持续性能模式：禁用",
        description = "仅在设备支持时生效。启用后会在游戏前台请求 Sustained Performance Mode，通常更偏向长时间稳定，而不是更高瞬时峰值；默认关闭。修改后下次进入游戏生效。",
        onCheckedChange = onSustainedPerformanceModeChanged
    )

    SettingsActionListItem(
        title = "系统 Game Mode",
        supportingText = buildString {
            append("当前：")
            append(uiState.systemGameModeDisplayName)
            append("  |  已声明支持：Performance / Battery")
        },
        enabled = !uiState.busy,
        onClick = { showGameModeDialog = true }
    )
    Text(
        text = uiState.systemGameModeDescription,
        style = MaterialTheme.typography.bodySmall
    )

    Text(text = "图形后端", style = MaterialTheme.typography.bodyMedium)
    SwitchSettingRow(
        checked = uiState.rendererSelectionMode == RendererSelectionMode.AUTO,
        enabled = !uiState.busy,
        enabledText = "自动选择：启用",
        disabledText = "自动选择：禁用",
        description = buildString {
            if (uiState.rendererSelectionMode == RendererSelectionMode.AUTO) {
                append("\n当前自动选择：")
                append(uiState.autoSelectedRendererBackend.displayName)
                append("（")
                append(uiState.autoSelectedRendererBackend.briefProsCons())
                append("）")
            } else {
                append("\n关闭后使用手动后端：")
                append(uiState.manualRendererBackend.displayName)
                append("（")
                append(uiState.manualRendererBackend.briefProsCons())
                append("）")
            }
        },
        onCheckedChange = { checked ->
            onRendererSelectionModeChanged(
                if (checked) RendererSelectionMode.AUTO else RendererSelectionMode.MANUAL
            )
        }
    )
    uiState.rendererFallbackText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (uiState.rendererSelectionMode == RendererSelectionMode.MANUAL) {
        SettingsDropdownField(
            label = "手动后端",
            valueText = uiState.manualRendererBackend.displayName,
            enabled = !uiState.busy,
            supportingText = "当前手动选择：${uiState.manualRendererBackend.displayName}（${uiState.manualRendererBackend.briefProsCons()}）",
            options = uiState.rendererBackendOptions,
            optionEnabled = { option -> option.available },
            optionLabel = { option -> option.backend.displayName },
            optionDescription = { option ->
                buildList {
                    add(option.backend.briefProsCons())
                    option.reasonText?.let(::add)
                }.joinToString("  ")
            },
            onOptionSelected = { option -> onManualRendererBackendChanged(option.backend) }
        )
    }

    if (uiState.effectiveRendererBackend == RendererBackend.OPENGL_ES_MOBILEGLUES) {
        SettingsActionListItem(
            title = "MobileGlues 专项设置",
            supportingText = buildString {
                append("ANGLE: ")
                append(uiState.mobileGluesAnglePolicy.displayName)
                append("  |  Multidraw: ")
                append(uiState.mobileGluesMultidrawMode.displayName)
                append("  |  Custom GL: ")
                append(uiState.mobileGluesCustomGlVersion.displayName)
            },
            enabled = !uiState.busy,
            onClick = onOpenMobileGluesSettings
        )
    }

    Text(
        text = stringResource(R.string.settings_render_surface_backend_title),
        style = MaterialTheme.typography.bodyMedium
    )
    SettingsDropdownField(
        label = stringResource(R.string.settings_render_surface_backend_title),
        valueText = uiState.renderSurfaceBackend.displayName(),
        enabled = !uiState.busy,
        supportingText = if (uiState.surfaceBackendForcedByRenderer) {
            "当前后端已强制覆盖为 ${uiState.effectiveRenderSurfaceBackend.displayName()}。"
        } else {
            stringResource(R.string.settings_render_surface_backend_desc)
        },
        supportingTextColor = if (uiState.surfaceBackendForcedByRenderer) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        options = RenderSurfaceBackend.entries,
        optionLabel = { backend -> backend.displayName() },
        onOptionSelected = onRenderSurfaceBackendChanged
    )

    Text(text = "JVM 堆上限", style = MaterialTheme.typography.bodyMedium)
    Text(
        text = "${heapSliderValue.roundToInt()} MB",
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = heapSliderValue,
        onValueChange = { value ->
            heapSliderValue = value
            val step = heapSliderToStep(
                value = value,
                min = uiState.jvmHeapMinMb,
                step = uiState.jvmHeapStepMb,
            )
            if (step != lastHeapStep) {
                lastHeapStep = step
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = { onJvmHeapMaxSelected(heapSliderValue.roundToInt()) },
        valueRange = uiState.jvmHeapMinMb.toFloat()..uiState.jvmHeapMaxMb.toFloat(),
        steps = ((uiState.jvmHeapMaxMb - uiState.jvmHeapMinMb) / uiState.jvmHeapStepMb - 1)
            .coerceAtLeast(0),
        enabled = !uiState.busy,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "如果你不知道你在做什么，请勿修改此项。如果遇到黑屏等问题，可以尝试提高这个值，但过高可能会导致无法进入游戏的问题。为降低大堆启动失败风险，启动时会先使用较小初始堆，再按需扩容到上限。",
        style = MaterialTheme.typography.bodySmall
    )

    SwitchSettingRow(
        checked = uiState.compressedPointersEnabled,
        enabled = !uiState.busy,
        enabledText = "Compressed Oops / Class Pointers：启用",
        disabledText = "Compressed Oops / Class Pointers：禁用",
        description = "控制 64 位 JVM 是否启用压缩对象指针和类指针。默认关闭以优先兼容性，启用后可能降低内存占用，但也可能引入设备相关启动问题。",
        onCheckedChange = onJvmCompressedPointersChanged
    )

    SwitchSettingRow(
        checked = uiState.stringDeduplicationEnabled,
        enabled = !uiState.busy,
        enabledText = "String Deduplication：启用",
        disabledText = "String Deduplication：禁用",
        description = "控制 64 位 G1GC 是否启用字符串去重。启用后可能降低堆占用，但会带来额外的 GC CPU 开销。",
        onCheckedChange = onJvmStringDeduplicationChanged
    )

    if (showGameModeDialog) {
        AlertDialog(
            onDismissRequest = { showGameModeDialog = false },
            title = { Text(stringResource(R.string.settings_system_game_mode_title)) },
            text = {
                Text(
                    text = buildString {
                        append("当前模式：")
                        append(uiState.systemGameModeDisplayName)
                        append("\n\n")
                        append(uiState.systemGameModeDescription)
                        append("\n\n")
                        append("这个模式由系统 Game Dashboard 或厂商游戏助手控制，启动器不能直接改系统模式。")
                        append("\n\n")
                        append("如果你的设备支持游戏面板，请在游戏运行时从系统面板中切换；不同厂商也可能叫“游戏助手”“游戏空间”等。")
                        append("\n\n")
                        append("当前启动器已向系统声明支持 Performance / Battery 两种模式。Battery 模式的系统策略仍可能影响功耗与调度，但启动器不会再额外把目标 FPS 压到 60。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { showGameModeDialog = false }) {
                    Text(stringResource(R.string.settings_system_game_mode_acknowledge))
                }
            }
        )
    }
}

private fun renderScaleToStep(value: Float): Int {
    return ((value - RenderScaleService.MIN_RENDER_SCALE) / 0.01f).roundToInt()
}

private fun heapSliderToStep(value: Float, min: Int, step: Int): Int {
    val safeStep = step.coerceAtLeast(1)
    return ((value - min.toFloat()) / safeStep.toFloat()).roundToInt()
}

@Composable
private fun SettingsInputSection(
    uiState: SettingsScreenViewModel.UiState,
    onPlayerNameChanged: (String) -> Boolean,
    onBackBehaviorChanged: (BackBehavior) -> Unit,
    onManualDismissBootOverlayChanged: (Boolean) -> Unit,
    onShowFloatingMouseWindowChanged: (Boolean) -> Unit,
    onTouchMouseDoubleTapLockChanged: (Boolean) -> Unit,
    onLongPressMouseShowsKeyboardChanged: (Boolean) -> Unit,
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit,
    onShowModFileNameChanged: (Boolean) -> Unit,
    onMobileHudEnabledChanged: (Boolean) -> Unit,
    onCompendiumUpgradeTouchFixEnabledChanged: (Boolean) -> Unit,
    onDisplayCutoutAvoidanceChanged: (Boolean) -> Unit,
    onScreenBottomCropChanged: (Boolean) -> Unit,
    onGamePerformanceOverlayChanged: (Boolean) -> Unit,
    onTouchscreenEnabledChanged: (Boolean) -> Unit,
) {
    var showPlayerNameDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPlayerName by rememberSaveable { mutableStateOf(uiState.playerName) }

    SettingsActionListItem(
        title = stringResource(R.string.settings_player_name_title),
        supportingText = uiState.playerName,
        enabled = !uiState.busy,
        onClick = {
            pendingPlayerName = uiState.playerName
            showPlayerNameDialog = true
        }
    )
    Text(
        text = stringResource(R.string.settings_player_name_desc),
        style = MaterialTheme.typography.bodySmall
    )

    Text(text = "Back 键行为", style = MaterialTheme.typography.bodyMedium)
    BackBehaviorOptionRow(
        behavior = BackBehavior.EXIT_TO_LAUNCHER,
        selected = uiState.backBehavior == BackBehavior.EXIT_TO_LAUNCHER,
        enabled = !uiState.busy,
        text = "立即退出到启动器",
        onSelect = onBackBehaviorChanged
    )
    BackBehaviorOptionRow(
        behavior = BackBehavior.SEND_ESCAPE,
        selected = uiState.backBehavior == BackBehavior.SEND_ESCAPE,
        enabled = !uiState.busy,
        text = "映射为 Esc 按键，可用于暂停游戏等",
        onSelect = onBackBehaviorChanged
    )
    BackBehaviorOptionRow(
        behavior = BackBehavior.NONE,
        selected = uiState.backBehavior == BackBehavior.NONE,
        enabled = !uiState.busy,
        text = "无行为",
        onSelect = onBackBehaviorChanged
    )
    Text(
        text = "决定游戏运行时按下系统 Back 键后的行为。",
        style = MaterialTheme.typography.bodySmall
    )

    SwitchSettingRow(
        checked = uiState.manualDismissBootOverlay,
        enabled = !uiState.busy,
        enabledText = "加载遮幕：手动关闭",
        disabledText = "加载遮幕：自动关闭",
        description = "启用后，启动时加载遮幕不会自动消失，需要点击遮幕上的按钮手动关闭。",
        onCheckedChange = onManualDismissBootOverlayChanged
    )

    SwitchSettingRow(
        checked = uiState.showFloatingMouseWindow,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_touch_mouse_floating_window_visible),
        disabledText = stringResource(R.string.settings_touch_mouse_floating_window_hidden),
        description = stringResource(R.string.settings_touch_mouse_floating_window_desc),
        onCheckedChange = onShowFloatingMouseWindowChanged
    )

    SwitchSettingRow(
        checked = uiState.touchMouseDoubleTapLockEnabled,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_touch_mouse_double_tap_lock_enabled),
        disabledText = stringResource(R.string.settings_touch_mouse_double_tap_lock_disabled),
        description = stringResource(R.string.settings_touch_mouse_double_tap_lock_desc),
        onCheckedChange = onTouchMouseDoubleTapLockChanged
    )

    SwitchSettingRow(
        checked = uiState.longPressMouseShowsKeyboard,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_touch_mouse_long_press_keyboard_enabled),
        disabledText = stringResource(R.string.settings_touch_mouse_long_press_keyboard_disabled),
        description = stringResource(R.string.settings_touch_mouse_long_press_keyboard_desc),
        onCheckedChange = onLongPressMouseShowsKeyboardChanged
    )

    SwitchSettingRow(
        checked = uiState.autoSwitchLeftAfterRightClick,
        enabled = !uiState.busy,
        enabledText = "右键后自动切回左键：启用",
        disabledText = "右键后自动切回左键：禁用",
        description = "启用后，触发一次右键后会自动切换回左键模式。",
        onCheckedChange = onAutoSwitchLeftAfterRightClickChanged
    )

    SwitchSettingRow(
        checked = uiState.showModFileName,
        enabled = !uiState.busy,
        enabledText = "模组显示名：文件名",
        disabledText = "模组显示名：原名",
        description = "启用后，模组卡片标题使用导入文件名。",
        onCheckedChange = onShowModFileNameChanged
    )

    SwitchSettingRow(
        checked = uiState.mobileHudEnabled,
        enabled = !uiState.busy,
        enabledText = "移动端 UI：启用",
        disabledText = "移动端 UI：禁用",
        description = "控制是否启用原生移动端 UI，启用后 UI 会部分变大，但是可能会出现一些模组渲染不兼容的问题，例如 loadout 控制台不会出现。",
        onCheckedChange = onMobileHudEnabledChanged
    )

    SwitchSettingRow(
        checked = uiState.compendiumUpgradeTouchFixEnabled,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_compendium_upgrade_touch_fix_enabled),
        disabledText = stringResource(R.string.settings_compendium_upgrade_touch_fix_disabled),
        description = stringResource(R.string.settings_compendium_upgrade_touch_fix_desc),
        onCheckedChange = onCompendiumUpgradeTouchFixEnabledChanged
    )

    SwitchSettingRow(
        checked = uiState.avoidDisplayCutout,
        enabled = !uiState.busy,
        enabledText = "避让摄像头显示：启用",
        disabledText = "避让摄像头显示：禁用",
        description = "启用后，游戏会避开刘海或挖孔区域显示，减少前置摄像头遮挡内容的问题。",
        onCheckedChange = onDisplayCutoutAvoidanceChanged
    )

    SwitchSettingRow(
        checked = uiState.cropScreenBottom,
        enabled = !uiState.busy,
        enabledText = "裁剪屏幕底部：启用",
        disabledText = "裁剪屏幕底部：禁用",
        description = "启用后，在横屏右侧留出黑边，改善全面屏右侧边缘难点击的问题。",
        onCheckedChange = onScreenBottomCropChanged
    )

    SwitchSettingRow(
        checked = uiState.showGamePerformanceOverlay,
        enabled = !uiState.busy,
        enabledText = "性能浮窗：启用",
        disabledText = "性能浮窗：禁用",
        description = "控制游戏内右上角的 FPS / 内存实时监控浮窗是否显示。",
        onCheckedChange = onGamePerformanceOverlayChanged
    )

    SwitchSettingRow(
        checked = uiState.touchscreenEnabled,
        enabled = !uiState.busy,
        enabledText = "触屏输入：启用",
        disabledText = "触屏输入：禁用",
        description = "控制是否使用原生触控适配模式，关闭后会显示鼠标指针，启用电脑版 UI。",
        onCheckedChange = onTouchscreenEnabledChanged
    )

    if (showPlayerNameDialog) {
        AlertDialog(
            onDismissRequest = { showPlayerNameDialog = false },
            title = { Text(stringResource(R.string.settings_player_name_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pendingPlayerName,
                        onValueChange = { pendingPlayerName = it },
                        singleLine = true,
                        enabled = !uiState.busy,
                        label = { Text(stringResource(R.string.settings_player_name_hint)) }
                    )
                    Text(
                        text = stringResource(R.string.settings_player_name_dialog_message),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        if (onPlayerNameChanged(pendingPlayerName)) {
                            showPlayerNameDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.main_folder_dialog_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showPlayerNameDialog = false }) {
                    Text(stringResource(R.string.main_folder_dialog_cancel))
                }
            }
        )
    }
}

@Composable
internal fun SwitchSettingRow(
    checked: Boolean,
    enabled: Boolean,
    enabledText: String,
    disabledText: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { changed ->
                performTapHapticFeedback(view)
                onCheckedChange(changed)
            }
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = if (checked) enabledText else disabledText)
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SettingsCompatibilitySection(
    busy: Boolean,
    onOpenCompatibility: () -> Unit,
) {
    SettingsActionListItem(
        title = stringResource(R.string.compat_settings_open),
        enabled = !busy,
        onClick = onOpenCompatibility
    )
}

@Composable
internal fun SettingsActionListItem(
    title: String,
    supportingText: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = title)
        },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    text = value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .hapticClickable(
                enabled = enabled,
                onClick = onClick
            )
    )
}

@Composable
private fun SettingsStatusSection(
    uiState: SettingsScreenViewModel.UiState,
    onLwjglDebugChanged: (Boolean) -> Unit,
    onPreloadAllJreLibrariesChanged: (Boolean) -> Unit,
    onLogcatCaptureChanged: (Boolean) -> Unit,
    onJvmLogcatMirrorChanged: (Boolean) -> Unit,
    onGpuResourceDiagChanged: (Boolean) -> Unit,
    onGdxPadCursorDebugChanged: (Boolean) -> Unit,
    onGlBridgeSwapHeartbeatDebugChanged: (Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val unplayableModsSheetUrl = stringResource(R.string.settings_unplayable_mods_sheet_url)
    var showStatusDialog by rememberSaveable { mutableStateOf(false) }
    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    var showUnplayableModsDialog by rememberSaveable { mutableStateOf(false) }
    val statusPreview = remember(uiState.statusText) {
        uiState.statusText
            .lineSequence()
            .take(3)
            .joinToString("\n")
    }

    Text(
        text = statusPreview.ifBlank { "状态加载中..." },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )

    SwitchSettingRow(
        checked = uiState.lwjglDebugEnabled,
        enabled = !uiState.busy,
        enabledText = "LWJGL Debug：启用",
        disabledText = "LWJGL Debug：禁用",
        description = "控制 JVM 启动参数中的 org.lwjgl.util.Debug / DebugLoader / DebugFunctions。",
        onCheckedChange = onLwjglDebugChanged
    )
    SwitchSettingRow(
        checked = uiState.preloadAllJreLibrariesEnabled,
        enabled = !uiState.busy,
        enabledText = "预加载全部 JRE 库：启用",
        disabledText = "预加载全部 JRE 库：禁用",
        description = "兼容性兜底开关。启用后恢复旧行为，启动时递归预加载运行时目录下全部 .so；禁用时只加载核心 JVM 和当前渲染器所需库，以降低常驻原生内存。",
        onCheckedChange = onPreloadAllJreLibrariesChanged
    )
    SwitchSettingRow(
        checked = uiState.logcatCaptureEnabled,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_logcat_capture_enabled),
        disabledText = stringResource(R.string.settings_logcat_capture_disabled),
        description = stringResource(R.string.settings_logcat_capture_desc),
        onCheckedChange = onLogcatCaptureChanged
    )
    SwitchSettingRow(
        checked = uiState.jvmLogcatMirrorEnabled,
        enabled = !uiState.busy,
        enabledText = "JVM 日志转发到 logcat：启用",
        disabledText = "JVM 日志转发到 logcat：禁用",
        description = "将 latest.log 的新增内容同步输出到 logcat，默认禁用。",
        onCheckedChange = onJvmLogcatMirrorChanged
    )
    SwitchSettingRow(
        checked = uiState.gpuResourceDiagEnabled,
        enabled = !uiState.busy,
        enabledText = "GPU 资源诊断：启用",
        disabledText = "GPU 资源诊断：禁用",
        description = "控制 [gdx-diag] GPU 资源摘要、FBO 栈采样和大纹理上传诊断日志。默认启用，关闭后可减少日志量和少量运行时开销。",
        onCheckedChange = onGpuResourceDiagChanged
    )
    SwitchSettingRow(
        checked = uiState.gdxPadCursorDebugEnabled,
        enabled = !uiState.busy,
        enabledText = "GDX 手柄光标日志：启用",
        disabledText = "GDX 手柄光标日志：禁用",
        description = "控制 [gdx-pad-debug] setCursorPosition 输出，默认禁用以减少日志刷屏。",
        onCheckedChange = onGdxPadCursorDebugChanged
    )
    SwitchSettingRow(
        checked = uiState.glBridgeSwapHeartbeatDebugEnabled,
        enabled = !uiState.busy,
        enabledText = "GLBridge 心跳日志：启用",
        disabledText = "GLBridge 心跳日志：禁用",
        description = "控制 GLBridgeDiag 的 swap heartbeat 输出，默认禁用。",
        onCheckedChange = onGlBridgeSwapHeartbeatDebugChanged
    )

    HorizontalDivider()

    SettingsActionListItem(
        title = "查看完整状态信息",
        enabled = uiState.statusText.isNotBlank(),
        onClick = { showStatusDialog = true }
    )
    SettingsActionListItem(
        title = "查看日志路径",
        enabled = uiState.logPathText.isNotBlank(),
        onClick = { showLogDialog = true }
    )
    SettingsActionListItem(
        title = stringResource(R.string.settings_unplayable_mods_entry_title),
        enabled = true,
        onClick = { showUnplayableModsDialog = true }
    )

    if (showStatusDialog) {
        val emptyStatusInfo = stringResource(R.string.settings_status_info_empty)
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text(stringResource(R.string.settings_status_info_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.statusText.ifBlank { emptyStatusInfo },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showStatusDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

    if (showLogDialog) {
        val emptyLogPaths = stringResource(R.string.settings_log_paths_empty)
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(stringResource(R.string.settings_log_paths_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.logPathText.ifBlank { emptyLogPaths },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = { showLogDialog = false }) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }

    if (showUnplayableModsDialog) {
        AlertDialog(
            onDismissRequest = { showUnplayableModsDialog = false },
            title = { Text(stringResource(R.string.settings_unplayable_mods_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_unplayable_mods_dialog_message),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showUnplayableModsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsAuthorInfoSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_author_repo_label),
            style = MaterialTheme.typography.bodyMedium
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_repo_url),
            url = stringResource(R.string.settings_author_repo_url),
        )
        Text(
            text = stringResource(R.string.settings_author_contributors_label),
            style = MaterialTheme.typography.bodyMedium
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_ketal_name),
            url = stringResource(R.string.settings_author_contributor_ketal_url),
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_apricityx_name),
            url = stringResource(R.string.settings_author_contributor_apricityx_url),
        )
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_freude916_name),
            url = stringResource(R.string.settings_author_contributor_freude916_url),
        )
        Text(
            text = stringResource(R.string.settings_author_icon_design_label),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.width(4.dp))
        SettingsExternalLinkText(
            text = stringResource(R.string.settings_author_contributor_raw_filter_name),
            url = stringResource(R.string.settings_author_contributor_raw_filter_url),
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_label),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_item_1),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_item_2),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_item_3),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_special_thanks_footer),
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_author_release_notice),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_report_notice),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = stringResource(R.string.settings_author_follow_notice),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingsExternalLinkText(
    text: String,
    url: String,
) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        modifier = Modifier.hapticClickable(enabled = true) {
            uriHandler.openUri(url)
        }
    )
}

@Composable
fun SettingsEffectsHandler(
    viewModel: SettingsScreenViewModel,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val importJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onJarPicked(activity, uri)
    }
    val importModsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onModJarsPicked(activity, uris)
    }
    val importSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onSavesArchivePicked(activity, uri)
    }
    val exportModsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onModsExportPicked(activity, uri)
    }
    val exportSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onSavesExportPicked(activity, uri)
    }
    val exportLogsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onLogsExportPicked(activity, uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsScreenViewModel.Effect.OpenImportJarPicker -> {
                    importJarLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportModsPicker -> {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportSavesPicker -> {
                    importSavesLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                }

                is SettingsScreenViewModel.Effect.OpenExportModsPicker -> {
                    exportModsLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenExportSavesPicker -> {
                    exportSavesLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenExportLogsPicker -> {
                    exportLogsLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.ShareJvmLogsBundle -> {
                    val shareIntent = JvmLogShareService.buildShareIntent(effect.payload)
                    activity.startActivity(Intent.createChooser(shareIntent, "分享日志"))
                }

                SettingsScreenViewModel.Effect.OpenCompatibility -> {
                    navigator.push(Route.Compatibility)
                }

                SettingsScreenViewModel.Effect.OpenMobileGluesSettings -> {
                    navigator.push(Route.MobileGluesSettings)
                }

                SettingsScreenViewModel.Effect.OpenFeedback -> {
                    navigator.push(Route.Feedback)
                }
            }
        }
    }

}

@Composable
private fun TargetFpsOptionRow(
    fps: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    SettingsRadioOptionRow(
        selected = selected,
        enabled = enabled,
        text = "$fps FPS",
        onSelect = { onSelect(fps) }
    )
}

@Composable
private fun BackBehaviorOptionRow(
    behavior: BackBehavior,
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onSelect: (BackBehavior) -> Unit,
) {
    SettingsRadioOptionRow(
        selected = selected,
        enabled = enabled,
        text = text,
        onSelect = { onSelect(behavior) }
    )
}

@Composable
private fun SettingsRadioOptionRow(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticToggleable(
                value = selected,
                enabled = enabled,
                onValueChange = { onSelect() }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
    }
}

@Composable
internal fun <T> SettingsDropdownField(
    label: String,
    valueText: String,
    enabled: Boolean,
    supportingText: String? = null,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    options: List<T>,
    optionEnabled: (T) -> Boolean = { true },
    optionLabel: (T) -> String,
    optionDescription: ((T) -> String?)? = null,
    onOptionSelected: (T) -> Unit,
) {
    var expanded by remember(label, options, enabled) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(text = label)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    supportingText?.let {
                        Text(
                            text = it,
                            color = supportingTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            trailingContent = {
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .hapticClickable(enabled = enabled) { expanded = true }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val optionIsEnabled = optionEnabled(option)
                DropdownMenuItem(
                    enabled = optionIsEnabled,
                    text = {
                        Column {
                            Text(text = optionLabel(option))
                            optionDescription?.invoke(option)?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                    }
                )
            }
        }
    }
}

private fun RenderSurfaceBackend.displayName(): String {
    return when (this) {
        RenderSurfaceBackend.SURFACE_VIEW -> "SurfaceView"
        RenderSurfaceBackend.TEXTURE_VIEW -> "TextureView"
    }
}

private fun RendererBackend.briefProsCons(): String {
    return when (this) {
        RendererBackend.OPENGL_ES_MOBILEGLUES ->
            "优：兼容性取向；缺：仍有部分机型适配问题"
        RendererBackend.OPENGL_ES2_NATIVE ->
            "优：最接近旧实现、稳定；缺：功能最保守"
        RendererBackend.OPENGL_ES2_GL4ES ->
            "优：桌面 GL 兼容更好；缺：有额外转译开销"
        RendererBackend.OPENGL_ES3_DESKTOPGL_ZINK_KOPPER ->
            "优：走 Vulkan，部分设备更稳；缺：依赖 Vulkan 且强制 TextureView"
        RendererBackend.VULKAN_ZINK ->
            "优：Mesa/Zink 兼容潜力高；缺：驱动和库要求最高"
    }
}

@Composable
private fun HapticIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    IconButton(
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        },
        enabled = enabled,
        content = content
    )
}

@Composable
private fun HapticTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val view = LocalView.current
    TextButton(
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        },
        enabled = enabled,
        content = content
    )
}

private fun Modifier.hapticClickable(
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    clickable(
        enabled = enabled,
        onClick = {
            performTapHapticFeedback(view)
            onClick()
        }
    )
}

private fun Modifier.hapticToggleable(
    value: Boolean,
    enabled: Boolean,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    val view = LocalView.current
    toggleable(
        value = value,
        enabled = enabled,
        onValueChange = { changed ->
            performTapHapticFeedback(view)
            onValueChange(changed)
        }
    )
}

private fun performTapHapticFeedback(view: android.view.View) {
    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}
