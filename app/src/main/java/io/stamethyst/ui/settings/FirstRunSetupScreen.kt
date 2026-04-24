package io.stamethyst.ui.settings

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.SimpleMarkdownCard
import kotlin.math.roundToInt

private enum class FirstRunSetupStep {
    APPEARANCE,
    RENDER,
    INPUT,
    UPDATES,
    STEAM_CLOUD,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFirstRunSetupScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    val steps = FirstRunSetupStep.entries
    var currentStepIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentStep = steps[currentStepIndex]
    val blockingInteractionLocked = uiState.busyOperation.usesBlockingOverlay()
    val canExitToPreviousRoute =
        navigator.backStack.lastIndex > 0 &&
            navigator.backStack[navigator.backStack.lastIndex - 1] == Route.Settings

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    fun goPrevious() {
        if (blockingInteractionLocked) {
            return
        }
        if (currentStepIndex > 0) {
            currentStepIndex--
        } else if (canExitToPreviousRoute) {
            navigator.goBack()
        }
    }

    fun finishSetup() {
        if (blockingInteractionLocked) {
            return
        }
        LauncherPreferences.setFirstRunSetupCompleted(activity, true)
        if (canExitToPreviousRoute) {
            navigator.goBack()
        } else {
            navigator.resetRoot(Route.Main)
        }
    }

    BackHandler(enabled = true) {
        goPrevious()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_first_run_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = ::goPrevious,
                        enabled = (currentStepIndex > 0 || canExitToPreviousRoute) &&
                            !blockingInteractionLocked
                    ) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = ::goPrevious,
                        enabled = (currentStepIndex > 0 || canExitToPreviousRoute) &&
                            !blockingInteractionLocked,
                    ) {
                        Text(stringResource(R.string.settings_first_run_action_back))
                    }
                    Button(
                        onClick = {
                            if (currentStepIndex == steps.lastIndex) {
                                finishSetup()
                            } else {
                                currentStepIndex++
                            }
                        },
                        enabled = !blockingInteractionLocked,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(
                                if (currentStepIndex == steps.lastIndex) {
                                    R.string.settings_first_run_action_finish
                                } else {
                                    R.string.settings_first_run_action_next
                                }
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsBusyIndicator(uiState = uiState)
            AnimatedContent(
                targetState = currentStepIndex,
                transitionSpec = {
                    val isForward = targetState > initialState
                    (
                        slideInHorizontally(
                            animationSpec = tween(durationMillis = 320),
                            initialOffsetX = { fullWidth ->
                                if (isForward) fullWidth / 3 else -fullWidth / 3
                            }
                        ) + fadeIn(animationSpec = tween(durationMillis = 320))
                        ).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(durationMillis = 260),
                            targetOffsetX = { fullWidth ->
                                if (isForward) -fullWidth / 4 else fullWidth / 4
                            }
                        ) + fadeOut(animationSpec = tween(durationMillis = 220))
                    ).using(SizeTransform(clip = false))
                },
                label = "firstRunSetupStepTransition",
            ) { stepIndex ->
                val animatedStep = steps[stepIndex]
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FirstRunSetupHeader(
                        currentStepIndex = stepIndex,
                        totalSteps = steps.size,
                        step = animatedStep,
                    )
                    when (animatedStep) {
                        FirstRunSetupStep.APPEARANCE -> {
                            FirstRunAppearanceStep(
                                uiState = uiState,
                                onThemeColorChanged = { themeColor ->
                                    viewModel.onThemeColorChanged(activity, themeColor)
                                },
                            )
                        }

                        FirstRunSetupStep.RENDER -> {
                            FirstRunRenderStep(
                                uiState = uiState,
                                onDisplayCutoutAvoidanceChanged = { enabled ->
                                    viewModel.onDisplayCutoutAvoidanceChanged(activity, enabled)
                                },
                                onScreenBottomCropChanged = { enabled ->
                                    viewModel.onScreenBottomCropChanged(activity, enabled)
                                },
                                onTouchscreenEnabledChanged = { enabled ->
                                    viewModel.onTouchscreenEnabledChanged(activity, enabled)
                                },
                                onGameplayFontScaleChanged = { value ->
                                    viewModel.onGameplayFontScaleChanged(activity, value)
                                },
                                onGameplayLargerUiChanged = { enabled ->
                                    viewModel.onGameplayLargerUiChanged(activity, enabled)
                                },
                            )
                        }

                        FirstRunSetupStep.INPUT -> {
                            FirstRunInputStep(
                                uiState = uiState,
                                onShowFloatingMouseWindowChanged = { enabled ->
                                    viewModel.onShowFloatingMouseWindowChanged(activity, enabled)
                                },
                                onTouchMouseNewInteractionChanged = { enabled ->
                                    viewModel.onTouchMouseNewInteractionChanged(activity, enabled)
                                },
                            )
                        }

                FirstRunSetupStep.UPDATES -> {
                    FirstRunUpdatesStep(
                        uiState = uiState,
                        onAutoCheckUpdatesChanged = { enabled ->
                            viewModel.onAutoCheckUpdatesChanged(activity, enabled)
                        },
                        onPreferredUpdateMirrorChanged = { source ->
                            viewModel.onPreferredUpdateMirrorChanged(activity, source)
                        },
                        onManualCheckUpdates = {
                            viewModel.onManualCheckUpdates(activity)
                        },
                        onOpenReleaseHistory = {
                            viewModel.onOpenReleaseHistory(activity)
                        },
                        onDismissReleaseHistoryDialog = viewModel::dismissReleaseHistoryDialog,
                    )
                }

                        FirstRunSetupStep.STEAM_CLOUD -> {
                            FirstRunSetupSteamCloudStep(
                                uiState = uiState,
                                onOpenSteamCloudLogin = { navigator.push(Route.SteamCloudLogin) },
                                onClearSteamCloudCredentials = {
                                    viewModel.onClearSteamCloudCredentials(activity)
                                },
                                onSteamCloudWattAccelerationChanged = { enabled ->
                                    viewModel.onSteamCloudWattAccelerationChanged(activity, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRunSetupHeader(
    currentStepIndex: Int,
    totalSteps: Int,
    step: FirstRunSetupStep,
) {
    val titleRes = when (step) {
        FirstRunSetupStep.APPEARANCE -> R.string.settings_first_run_step_appearance_title
        FirstRunSetupStep.RENDER -> R.string.settings_first_run_step_render_title
        FirstRunSetupStep.INPUT -> R.string.settings_first_run_step_input_title
        FirstRunSetupStep.UPDATES -> R.string.settings_first_run_step_updates_title
        FirstRunSetupStep.STEAM_CLOUD -> R.string.settings_first_run_step_steam_cloud_title
    }
    val descriptionRes = when (step) {
        FirstRunSetupStep.APPEARANCE -> R.string.settings_first_run_welcome_desc
        FirstRunSetupStep.RENDER -> R.string.settings_first_run_render_intro_desc
        FirstRunSetupStep.INPUT -> R.string.settings_first_run_input_intro_desc
        FirstRunSetupStep.UPDATES -> R.string.settings_first_run_updates_intro_desc
        FirstRunSetupStep.STEAM_CLOUD -> R.string.settings_first_run_steam_cloud_intro_desc
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(
                R.string.settings_first_run_progress,
                currentStepIndex + 1,
                totalSteps
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { (currentStepIndex + 1) / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FirstRunAppearanceStep(
    uiState: SettingsScreenViewModel.UiState,
    onThemeColorChanged: (LauncherThemeColor) -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(R.string.settings_theme_color_title)
    ) {
        FirstRunSupportingText(stringResource(R.string.settings_first_run_appearance_theme_color_desc))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherThemeColor.entries.forEach { themeColor ->
                FirstRunThemeColorOption(
                    themeColor = themeColor,
                    selected = uiState.themeColor == themeColor,
                    enabled = !uiState.busy,
                    onSelect = { onThemeColorChanged(themeColor) },
                )
            }
        }
    }
}

@Composable
private fun FirstRunThemeColorOption(
    themeColor: LauncherThemeColor,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(themeColor.seedColor)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    shape = CircleShape,
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = themeColorDisplayName(themeColor),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun FirstRunRenderStep(
    uiState: SettingsScreenViewModel.UiState,
    onDisplayCutoutAvoidanceChanged: (Boolean) -> Unit,
    onScreenBottomCropChanged: (Boolean) -> Unit,
    onTouchscreenEnabledChanged: (Boolean) -> Unit,
    onGameplayFontScaleChanged: (Float) -> Unit,
    onGameplayLargerUiChanged: (Boolean) -> Unit,
) {
    val view = LocalView.current
    var gameplayFontScaleSliderValue by remember(uiState.gameplayFontScale) {
        mutableFloatStateOf(uiState.gameplayFontScale)
    }
    var lastGameplayFontScaleStep by remember(uiState.gameplayFontScale) {
        mutableIntStateOf(gameplayFontScaleToStep(uiState.gameplayFontScale))
    }

    SettingsSectionCard(
        title = stringResource(R.string.settings_section_render)
    ) {
        SwitchSettingRow(
            checked = uiState.avoidDisplayCutout,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_display_cutout_enabled),
            disabledText = stringResource(R.string.settings_display_cutout_disabled),
            description = stringResource(R.string.settings_first_run_render_display_cutout_desc),
            onCheckedChange = onDisplayCutoutAvoidanceChanged,
        )

        SwitchSettingRow(
            checked = uiState.cropScreenBottom,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_crop_screen_bottom_enabled),
            disabledText = stringResource(R.string.settings_crop_screen_bottom_disabled),
            description = stringResource(R.string.settings_first_run_render_crop_screen_bottom_desc),
            onCheckedChange = onScreenBottomCropChanged,
        )

        SwitchSettingRow(
            checked = uiState.touchscreenEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_touchscreen_enabled),
            disabledText = stringResource(R.string.settings_touchscreen_disabled),
            description = stringResource(R.string.settings_first_run_render_touchscreen_desc),
            onCheckedChange = onTouchscreenEnabledChanged,
        )

        SwitchSettingRow(
            checked = uiState.gameplayLargerUiEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_gameplay_larger_ui_enabled),
            disabledText = stringResource(R.string.settings_gameplay_larger_ui_disabled),
            description = stringResource(R.string.settings_first_run_render_larger_ui_desc),
            onCheckedChange = onGameplayLargerUiChanged,
        )

        Text(
            text = stringResource(R.string.settings_gameplay_font_scale_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.settings_gameplay_font_scale_value,
                GameplaySettingsService.formatFontScale(gameplayFontScaleSliderValue)
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        FirstRunSupportingText(stringResource(R.string.settings_first_run_render_font_scale_desc))
        Slider(
            value = gameplayFontScaleSliderValue,
            onValueChange = { value ->
                val normalized = GameplaySettingsService.normalizeFontScale(value)
                gameplayFontScaleSliderValue = normalized
                val step = gameplayFontScaleToStep(normalized)
                if (step != lastGameplayFontScaleStep) {
                    lastGameplayFontScaleStep = step
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            },
            onValueChangeFinished = {
                onGameplayFontScaleChanged(gameplayFontScaleSliderValue)
            },
            valueRange = GameplaySettingsService.MIN_FONT_SCALE..GameplaySettingsService.MAX_FONT_SCALE,
            steps = (
                (GameplaySettingsService.MAX_FONT_SCALE - GameplaySettingsService.MIN_FONT_SCALE) /
                    GameplaySettingsService.FONT_SCALE_STEP
                ).roundToInt() - 1,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FirstRunInputStep(
    uiState: SettingsScreenViewModel.UiState,
    onShowFloatingMouseWindowChanged: (Boolean) -> Unit,
    onTouchMouseNewInteractionChanged: (Boolean) -> Unit,
) {
    SettingsSectionCard(
        title = stringResource(R.string.settings_input_floating_title)
    ) {
        SwitchSettingRow(
            checked = uiState.showFloatingMouseWindow,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_touch_mouse_floating_window_visible),
            disabledText = stringResource(R.string.settings_touch_mouse_floating_window_hidden),
            description = stringResource(R.string.settings_first_run_input_floating_window_desc),
            onCheckedChange = onShowFloatingMouseWindowChanged,
        )
        AnimatedVisibility(
            visible = uiState.showFloatingMouseWindow,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SwitchSettingRow(
                    checked = uiState.touchMouseNewInteraction,
                    enabled = !uiState.busy,
                    enabledText = stringResource(R.string.settings_touch_mouse_interaction_new),
                    disabledText = stringResource(R.string.settings_touch_mouse_interaction_legacy),
                    description = stringResource(R.string.settings_first_run_input_mouse_interaction_desc),
                    onCheckedChange = onTouchMouseNewInteractionChanged,
                )
            }
        }
    }
}

@Composable
private fun FirstRunUpdatesStep(
    uiState: SettingsScreenViewModel.UiState,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onPreferredUpdateMirrorChanged: (UpdateSource) -> Unit,
    onManualCheckUpdates: () -> Unit,
    onOpenReleaseHistory: () -> Unit,
    onDismissReleaseHistoryDialog: () -> Unit,
) {
    val controlsEnabled =
        !uiState.busy && !uiState.updateCheckInProgress && !uiState.releaseHistoryLoading

    SettingsSectionCard(
        title = stringResource(R.string.update_section_title)
    ) {
        SwitchSettingRow(
            checked = uiState.autoCheckUpdatesEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.update_auto_check_enabled),
            disabledText = stringResource(R.string.update_auto_check_disabled),
            description = stringResource(R.string.settings_first_run_updates_mirror_required_desc),
            onCheckedChange = onAutoCheckUpdatesChanged,
        )

        SettingsDropdownField(
            label = stringResource(R.string.update_mirror_title),
            valueText = uiState.preferredUpdateMirror.displayName,
            enabled = controlsEnabled,
            supportingText = stringResource(R.string.settings_first_run_updates_mirror_switch_desc),
            options = uiState.availableUpdateMirrors,
            optionLabel = { it.displayName },
            onOptionSelected = onPreferredUpdateMirrorChanged,
        )

        if (uiState.updateCheckInProgress || uiState.releaseHistoryLoading) {
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
            onClick = onManualCheckUpdates,
        )

        SettingsActionListItem(
            title = stringResource(
                if (uiState.releaseHistoryLoading) {
                    R.string.update_history_loading
                } else {
                    R.string.update_history_title
                }
            ),
            enabled = controlsEnabled,
            onClick = onOpenReleaseHistory,
        )

        Text(
            text = stringResource(R.string.update_current_version, uiState.currentVersionText),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.update_status_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer {
            Text(
                text = uiState.updateStatusSummary.ifBlank {
                    stringResource(R.string.update_status_not_checked)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    uiState.releaseHistoryDialogState?.let { dialogState ->
        AlertDialog(
            onDismissRequest = onDismissReleaseHistoryDialog,
            title = { Text(stringResource(R.string.update_history_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.update_history_dialog_source,
                            dialogState.metadataSourceDisplayName
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (dialogState.entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.update_history_dialog_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        dialogState.entries.forEach { entry ->
                            FirstRunUpdateHistoryEntryCard(entry = entry)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissReleaseHistoryDialog) {
                    Text(stringResource(R.string.common_action_close))
                }
            }
        )
    }
}

@Composable
private fun FirstRunUpdateHistoryEntryCard(
    entry: SettingsScreenViewModel.UpdateHistoryEntryState,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.update_history_dialog_entry_title, entry.version),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(
                    R.string.update_history_dialog_published_at,
                    entry.publishedAtText
                ),
                style = MaterialTheme.typography.bodySmall
            )
            SimpleMarkdownCard(
                title = stringResource(R.string.update_dialog_notes_title),
                markdown = entry.notesText
            )
        }
    }
}

@Composable
private fun FirstRunSetupSteamCloudStep(
    uiState: SettingsScreenViewModel.UiState,
    onOpenSteamCloudLogin: () -> Unit,
    onClearSteamCloudCredentials: () -> Unit,
    onSteamCloudWattAccelerationChanged: (Boolean) -> Unit,
) {
    var showLogoutConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val accountName = uiState.steamCloudAccountName.ifBlank {
        stringResource(R.string.settings_steam_cloud_account_unknown)
    }
    val accountDisplayName = uiState.steamCloudPersonaName.ifBlank { accountName }

    SettingsSectionCard(
        title = stringResource(R.string.settings_steam_cloud_title)
    ) {
        FirstRunSupportingText(stringResource(R.string.settings_first_run_steam_cloud_login_desc))
        FirstRunSteamCloudAccountCard(
            loggedIn = uiState.steamCloudRefreshTokenConfigured,
            accountName = accountDisplayName,
            busy = uiState.busy,
            onLogin = onOpenSteamCloudLogin,
            onLogout = { showLogoutConfirmDialog = true },
        )
        SwitchSettingRow(
            checked = uiState.steamCloudWattAccelerationEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_steam_cloud_watt_acceleration_enabled_title),
            disabledText = stringResource(R.string.settings_steam_cloud_watt_acceleration_disabled_title),
            description = stringResource(R.string.settings_first_run_steam_cloud_acceleration_desc),
            onCheckedChange = onSteamCloudWattAccelerationChanged,
        )
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_first_run_steam_cloud_logout_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_first_run_steam_cloud_logout_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    enabled = !uiState.busy,
                    onClick = {
                        showLogoutConfirmDialog = false
                        onClearSteamCloudCredentials()
                    }
                ) {
                    Text(stringResource(R.string.settings_steam_cloud_logout_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.busy,
                    onClick = { showLogoutConfirmDialog = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FirstRunSteamCloudAccountCard(
    loggedIn: Boolean,
    accountName: String,
    busy: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val avatarText = if (loggedIn) {
        accountName.take(1).ifBlank { "S" }
    } else {
        "S"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (loggedIn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarText,
                style = MaterialTheme.typography.titleMedium,
                color = if (loggedIn) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (loggedIn) {
                    accountName
                } else {
                    stringResource(R.string.settings_steam_cloud_account_not_signed_in)
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loggedIn) {
            TextButton(
                enabled = !busy,
                onClick = onLogout,
            ) {
                Text(stringResource(R.string.settings_steam_cloud_logout_action))
            }
        } else {
            Button(
                enabled = !busy,
                onClick = onLogin,
            ) {
                Text(stringResource(R.string.settings_steam_cloud_login_action))
            }
        }
    }
}

@Composable
private fun FirstRunSupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun themeColorDisplayName(themeColor: LauncherThemeColor): String {
    return when (themeColor) {
        LauncherThemeColor.ZHANSHIGE ->
            stringResource(R.string.settings_theme_color_zhanshige)
        LauncherThemeColor.LIEBAO ->
            stringResource(R.string.settings_theme_color_liebao)
        LauncherThemeColor.JIBAO ->
            stringResource(R.string.settings_theme_color_jibao)
        LauncherThemeColor.GUANJIE ->
            stringResource(R.string.settings_theme_color_guanjie)
        LauncherThemeColor.COLORLESS ->
            stringResource(R.string.settings_theme_color_colorless)
    }
}

private fun gameplayFontScaleToStep(value: Float): Int {
    return (
        (GameplaySettingsService.normalizeFontScale(value) - GameplaySettingsService.MIN_FONT_SCALE) /
            GameplaySettingsService.FONT_SCALE_STEP
        ).roundToInt()
}
