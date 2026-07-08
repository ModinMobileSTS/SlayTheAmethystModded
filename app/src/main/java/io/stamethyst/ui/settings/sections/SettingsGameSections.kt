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

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.config.BackBehavior
import io.stamethyst.config.CardPlayOptimizationMode
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.config.TouchMouseInteractionMode
import io.stamethyst.config.TouchscreenInputMode
import io.stamethyst.ui.preferences.LauncherPreferences
import kotlin.math.roundToInt


internal data class PerformanceSettingsActions(
    val onRenderScaleSelected: (Float) -> Unit,
    val onTargetFpsSelected: (Int) -> Unit,
    val onVirtualResolutionModeChanged: (VirtualResolutionMode) -> Unit,
    val onDisplayCutoutAvoidanceChanged: (Boolean) -> Unit,
    val onScreenBottomCropChanged: (Boolean) -> Unit,
    val onRamSaverEnabledChanged: (Boolean) -> Unit,
    val onMtsPatchCacheEnabledChanged: (Boolean) -> Unit,
    val onGameplayFontScaleChanged: (Float) -> Unit,
    val onGameplayLargerUiChanged: (Boolean) -> Unit,
)


internal data class InputSettingsActions(
    val onPlayerNameChanged: (String) -> Boolean,
    val onBackBehaviorChanged: (BackBehavior) -> Unit,
    val onTouchscreenInputModeChanged: (TouchscreenInputMode) -> Unit,
    val onCardPlayOptimizationModeChanged: (CardPlayOptimizationMode) -> Unit,
    val onTouchIndicatorEnabledChanged: (Boolean) -> Unit,
    val onSpecialKeyInputModeChanged: (SpecialKeyInputMode) -> Unit,
    val onTouchMouseInteractionModeChanged: (TouchMouseInteractionMode) -> Unit,
    val onTouchDoubleClickAsRightClickChanged: (Boolean) -> Unit,
    val onIgnoreLongPressRightClickWhilePlayingCardChanged: (Boolean) -> Unit,
    val onBuiltInSoftKeyboardChanged: (Boolean) -> Unit,
    val onHapticFeedbackChanged: (Boolean) -> Unit,
    val onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit,
    val onKeepScreenOnTimeoutSelected: (Int) -> Unit,
    val onGamePerformanceOverlayChanged: (Boolean) -> Unit,
)


internal data class InputBasicsSettingsActions(
    val onPlayerNameChanged: (String) -> Boolean,
    val onBackBehaviorChanged: (BackBehavior) -> Unit,
    val onTouchscreenInputModeChanged: (TouchscreenInputMode) -> Unit,
    val onCardPlayOptimizationModeChanged: (CardPlayOptimizationMode) -> Unit,
    val onTouchIndicatorEnabledChanged: (Boolean) -> Unit,
    val onTouchDoubleClickAsRightClickChanged: (Boolean) -> Unit,
    val onIgnoreLongPressRightClickWhilePlayingCardChanged: (Boolean) -> Unit,
    val onHapticFeedbackChanged: (Boolean) -> Unit,
    val onKeepScreenOnTimeoutSelected: (Int) -> Unit,
    val onGamePerformanceOverlayChanged: (Boolean) -> Unit,
)


internal data class FloatingMouseSettingsActions(
    val onSpecialKeyInputModeChanged: (SpecialKeyInputMode) -> Unit,
    val onTouchMouseInteractionModeChanged: (TouchMouseInteractionMode) -> Unit,
    val onBuiltInSoftKeyboardChanged: (Boolean) -> Unit,
    val onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit,
)


@Composable
internal fun SettingsPerformanceSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: PerformanceSettingsActions,
) {
    val view = LocalView.current
    var renderScaleSliderValue by remember(uiState.selectedRenderScale) {
        mutableFloatStateOf(uiState.selectedRenderScale)
    }
    var lastRenderScaleStep by remember(uiState.selectedRenderScale) {
        mutableIntStateOf(renderScaleToStep(uiState.selectedRenderScale))
    }
    var gameplayFontScaleSliderValue by remember(uiState.gameplayFontScale) {
        mutableFloatStateOf(uiState.gameplayFontScale)
    }
    var lastGameplayFontScaleStep by remember(uiState.gameplayFontScale) {
        mutableIntStateOf(gameplayFontScaleToStep(uiState.gameplayFontScale))
    }

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.ramSaverEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_ram_saver_title),
            disabledText = stringResource(R.string.settings_ram_saver_title),
            description = stringResource(R.string.settings_ram_saver_desc),
            onCheckedChange = actions.onRamSaverEnabledChanged,
            chipText = stringResource(R.string.settings_ram_saver_experimental_chip),
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.mtsPatchCacheEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_mts_patch_cache_title),
            disabledText = stringResource(R.string.settings_mts_patch_cache_title),
            description = stringResource(R.string.settings_mts_patch_cache_desc),
            onCheckedChange = actions.onMtsPatchCacheEnabledChanged,
            chipText = stringResource(R.string.settings_ram_saver_experimental_chip),
        )
    )

    Text(
        text = stringResource(R.string.settings_render_scale_title),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = RenderScaleService.format(renderScaleSliderValue),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = stringResource(R.string.settings_render_scale_desc),
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = renderScaleSliderValue,
        onValueChange = { value ->
            renderScaleSliderValue = value
            val step = renderScaleToStep(value)
            if (step != lastRenderScaleStep) {
                lastRenderScaleStep = step
                performHapticFeedback(view, HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = { actions.onRenderScaleSelected(renderScaleSliderValue) },
        valueRange = RenderScaleService.MIN_RENDER_SCALE..RenderScaleService.MAX_RENDER_SCALE,
        steps = ((RenderScaleService.MAX_RENDER_SCALE - RenderScaleService.MIN_RENDER_SCALE) / 0.01f)
            .roundToInt() - 1,
        enabled = !uiState.busy,
        modifier = Modifier.fillMaxWidth()
    )

    SettingsChoiceDialogItem(
        SettingsChoiceSpec(
            title = stringResource(R.string.settings_target_fps_title),
            valueText = stringResource(
                R.string.settings_target_fps_option,
                uiState.selectedTargetFps
            ),
            enabled = !uiState.busy,
            selectedValue = uiState.selectedTargetFps,
            options = uiState.targetFpsOptions,
            optionLabel = { fps -> stringResource(R.string.settings_target_fps_option, fps) },
            onOptionSelected = actions.onTargetFpsSelected,
        ),
    )

    SettingsChoiceDialogItem(
        SettingsChoiceSpec(
            title = stringResource(R.string.settings_virtual_resolution_mode_title),
            valueText = virtualResolutionModeDisplayName(uiState.virtualResolutionMode),
            enabled = !uiState.busy,
            selectedValue = uiState.virtualResolutionMode,
            options = VirtualResolutionMode.entries,
            optionLabel = { mode -> virtualResolutionModeDisplayName(mode) },
            onOptionSelected = actions.onVirtualResolutionModeChanged,
            description = virtualResolutionModeDescription(uiState.virtualResolutionMode),
            dialogDescription = virtualResolutionModeDescription(uiState.virtualResolutionMode),
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.avoidDisplayCutout,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_display_cutout_enabled),
            disabledText = stringResource(R.string.settings_display_cutout_disabled),
            description = stringResource(R.string.settings_display_cutout_desc),
            onCheckedChange = actions.onDisplayCutoutAvoidanceChanged
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.cropScreenBottom,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_crop_screen_bottom_enabled),
            disabledText = stringResource(R.string.settings_crop_screen_bottom_disabled),
            description = stringResource(R.string.settings_crop_screen_bottom_desc),
            onCheckedChange = actions.onScreenBottomCropChanged
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.gameplayLargerUiEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_gameplay_larger_ui_enabled),
            disabledText = stringResource(R.string.settings_gameplay_larger_ui_disabled),
            description = stringResource(R.string.settings_gameplay_larger_ui_desc),
            onCheckedChange = actions.onGameplayLargerUiChanged
        )
    )

    Text(
        text = stringResource(R.string.settings_gameplay_font_scale_title),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = stringResource(
            R.string.settings_gameplay_font_scale_value,
            GameplaySettingsService.formatFontScale(gameplayFontScaleSliderValue)
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        text = stringResource(R.string.settings_gameplay_font_scale_desc),
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = gameplayFontScaleSliderValue,
        onValueChange = { value ->
            val normalized = GameplaySettingsService.normalizeFontScale(value)
            gameplayFontScaleSliderValue = normalized
            val step = gameplayFontScaleToStep(normalized)
            if (step != lastGameplayFontScaleStep) {
                lastGameplayFontScaleStep = step
                performHapticFeedback(view, HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        onValueChangeFinished = { actions.onGameplayFontScaleChanged(gameplayFontScaleSliderValue) },
        valueRange = GameplaySettingsService.MIN_FONT_SCALE..GameplaySettingsService.MAX_FONT_SCALE,
        steps = (
            (GameplaySettingsService.MAX_FONT_SCALE - GameplaySettingsService.MIN_FONT_SCALE) /
                GameplaySettingsService.FONT_SCALE_STEP
            ).roundToInt() - 1,
        enabled = !uiState.busy,
        modifier = Modifier.fillMaxWidth()
    )

}


private fun renderScaleToStep(value: Float): Int {
    return ((value - RenderScaleService.MIN_RENDER_SCALE) / 0.01f).roundToInt()
}


private fun gameplayFontScaleToStep(value: Float): Int {
    return (
        (GameplaySettingsService.normalizeFontScale(value) - GameplaySettingsService.MIN_FONT_SCALE) /
            GameplaySettingsService.FONT_SCALE_STEP
        ).roundToInt()
}


@Composable
internal fun SettingsInputSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: InputSettingsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.settings_input_basic_title),
            style = MaterialTheme.typography.titleSmall
        )
        SettingsInputBasicsSection(
            uiState = uiState,
            actions = InputBasicsSettingsActions(
                onPlayerNameChanged = actions.onPlayerNameChanged,
                onBackBehaviorChanged = actions.onBackBehaviorChanged,
                onTouchscreenInputModeChanged = actions.onTouchscreenInputModeChanged,
                onCardPlayOptimizationModeChanged = actions.onCardPlayOptimizationModeChanged,
                onTouchIndicatorEnabledChanged = actions.onTouchIndicatorEnabledChanged,
                onTouchDoubleClickAsRightClickChanged = actions.onTouchDoubleClickAsRightClickChanged,
                onIgnoreLongPressRightClickWhilePlayingCardChanged =
                    actions.onIgnoreLongPressRightClickWhilePlayingCardChanged,
                onHapticFeedbackChanged = actions.onHapticFeedbackChanged,
                onKeepScreenOnTimeoutSelected = actions.onKeepScreenOnTimeoutSelected,
                onGamePerformanceOverlayChanged = actions.onGamePerformanceOverlayChanged,
            ),
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_special_key_input_mode_title),
            style = MaterialTheme.typography.titleSmall
        )
        SettingsFloatingMouseSection(
            uiState = uiState,
            actions = FloatingMouseSettingsActions(
                onSpecialKeyInputModeChanged = actions.onSpecialKeyInputModeChanged,
                onTouchMouseInteractionModeChanged = actions.onTouchMouseInteractionModeChanged,
                onBuiltInSoftKeyboardChanged = actions.onBuiltInSoftKeyboardChanged,
                onAutoSwitchLeftAfterRightClickChanged = actions.onAutoSwitchLeftAfterRightClickChanged,
            ),
        )
    }

}


@Composable
internal fun SettingsInputBasicsSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: InputBasicsSettingsActions,
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
//    Text(
//        text = stringResource(R.string.settings_player_name_desc),
//        style = MaterialTheme.typography.bodySmall
//    )

    SettingsChoiceDialogItem(
        SettingsChoiceSpec(
            title = stringResource(R.string.settings_back_behavior_title),
            valueText = backBehaviorDisplayName(uiState.backBehavior),
            enabled = !uiState.busy,
            selectedValue = uiState.backBehavior,
            options = BackBehavior.entries,
            optionLabel = { behavior -> backBehaviorDisplayName(behavior) },
            onOptionSelected = actions.onBackBehaviorChanged,
            description = stringResource(R.string.settings_back_behavior_desc),
            dialogDescription = stringResource(R.string.settings_back_behavior_desc),
        )
    )

    SettingsDropdownField(
        label = stringResource(R.string.settings_touchscreen_mode_title),
        valueText = uiState.touchscreenInputMode.displayName(),
        enabled = !uiState.busy,
        supportingText = uiState.touchscreenInputMode.description(),
        options = TouchscreenInputMode.entries,
        optionLabel = { mode -> mode.displayName() },
        optionDescription = { mode -> mode.description() },
        onOptionSelected = actions.onTouchscreenInputModeChanged
    )

    SettingsDropdownField(
        label = stringResource(R.string.settings_card_play_optimization_title),
        valueText = uiState.cardPlayOptimizationMode.displayName(),
        enabled = !uiState.busy && uiState.touchscreenInputMode.touchscreenEnabled,
        supportingText = uiState.cardPlayOptimizationMode.description(),
        options = CardPlayOptimizationMode.entries,
        optionLabel = { mode -> mode.displayName() },
        optionDescription = { mode -> mode.description() },
        onOptionSelected = actions.onCardPlayOptimizationModeChanged
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.touchDoubleClickAsRightClick,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_touch_double_click_as_right_click_enabled),
            disabledText = stringResource(R.string.settings_touch_double_click_as_right_click_disabled),
            description = stringResource(R.string.settings_touch_double_click_as_right_click_desc),
            onCheckedChange = actions.onTouchDoubleClickAsRightClickChanged
        )
    )

    if (uiState.touchDoubleClickAsRightClick) {
        SettingsSwitchItem(
            SettingsSwitchSpec(
                checked = uiState.ignoreLongPressRightClickWhilePlayingCard,
                enabled = !uiState.busy,
                enabledText = stringResource(R.string.settings_ignore_long_press_right_click_while_playing_card_enabled),
                disabledText = stringResource(R.string.settings_ignore_long_press_right_click_while_playing_card_disabled),
                description = stringResource(R.string.settings_ignore_long_press_right_click_while_playing_card_desc),
                onCheckedChange = actions.onIgnoreLongPressRightClickWhilePlayingCardChanged
            )
        )
    }

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.touchIndicatorEnabled,
            enabled = !uiState.busy && uiState.touchscreenInputMode.touchscreenEnabled,
            enabledText = stringResource(R.string.settings_touch_indicator_enabled),
            disabledText = stringResource(R.string.settings_touch_indicator_disabled),
            description = stringResource(R.string.settings_touch_indicator_desc),
            onCheckedChange = actions.onTouchIndicatorEnabledChanged
        )
    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.hapticFeedbackEnabled,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_haptic_feedback_enabled),
            disabledText = stringResource(R.string.settings_haptic_feedback_disabled),
            description = stringResource(R.string.settings_haptic_feedback_desc),
            onCheckedChange = actions.onHapticFeedbackChanged
        )
    )

    SettingsDropdownField(
        label = stringResource(R.string.settings_keep_screen_on_timeout_title),
        valueText = keepScreenOnTimeoutDisplayName(uiState.keepScreenOnTimeoutMinutes),
        enabled = !uiState.busy,
        supportingText = stringResource(R.string.settings_keep_screen_on_timeout_desc),
        options = uiState.keepScreenOnTimeoutMinuteOptions,
        optionLabel = { timeoutMinutes -> keepScreenOnTimeoutDisplayName(timeoutMinutes) },
        onOptionSelected = actions.onKeepScreenOnTimeoutSelected
    )

//    SwitchSettingRow(
//        checked = uiState.mobileHudEnabled,
//        enabled = !uiState.busy,
//        enabledText = stringResource(R.string.settings_mobile_hud_enabled),
//        disabledText = stringResource(R.string.settings_mobile_hud_disabled),
//        description = stringResource(R.string.settings_mobile_hud_desc),
//        onCheckedChange = onMobileHudEnabledChanged
//    )

    SettingsSwitchItem(
        SettingsSwitchSpec(
            checked = uiState.showGamePerformanceOverlay,
            enabled = !uiState.busy,
            enabledText = stringResource(R.string.settings_performance_overlay_enabled),
            disabledText = stringResource(R.string.settings_performance_overlay_disabled),
            description = stringResource(R.string.settings_performance_overlay_desc),
            onCheckedChange = actions.onGamePerformanceOverlayChanged
        )
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
                        if (actions.onPlayerNameChanged(pendingPlayerName)) {
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
internal fun SettingsFloatingMouseSection(
    uiState: SettingsScreenViewModel.UiState,
    actions: FloatingMouseSettingsActions,
) {
    SettingsDropdownField(
        label = stringResource(R.string.settings_special_key_input_mode_title),
        valueText = uiState.specialKeyInputMode.displayName(),
        enabled = !uiState.busy,
        supportingText = stringResource(R.string.settings_special_key_input_mode_desc),
        options = SpecialKeyInputMode.entries,
        optionLabel = { mode -> mode.displayName() },
        optionDescription = { mode -> mode.description() },
        onOptionSelected = actions.onSpecialKeyInputModeChanged
    )

    val useLegacyFloatingWindow =
        uiState.specialKeyInputMode == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW

    if (useLegacyFloatingWindow) {
        SettingsDropdownField(
            label = stringResource(R.string.settings_touch_mouse_interaction_label),
            valueText = uiState.touchMouseInteractionMode.displayName(),
            enabled = !uiState.busy,
            supportingText = stringResource(R.string.settings_touch_mouse_interaction_desc),
            options = TouchMouseInteractionMode.entries,
            optionLabel = { mode -> mode.displayName() },
            optionDescription = { mode -> mode.description() },
            onOptionSelected = actions.onTouchMouseInteractionModeChanged
        )
    }

    if (useLegacyFloatingWindow) {
        SettingsSwitchItem(
            SettingsSwitchSpec(
                checked = uiState.builtInSoftKeyboardEnabled,
                enabled = !uiState.busy,
                enabledText = stringResource(R.string.settings_built_in_soft_keyboard_enabled),
                disabledText = stringResource(R.string.settings_built_in_soft_keyboard_disabled),
                description = stringResource(R.string.settings_built_in_soft_keyboard_desc),
                onCheckedChange = actions.onBuiltInSoftKeyboardChanged
            )
        )

        SettingsSwitchItem(
            SettingsSwitchSpec(
                checked = uiState.autoSwitchLeftAfterRightClick,
                enabled = !uiState.busy,
                enabledText = stringResource(R.string.settings_auto_switch_left_enabled),
                disabledText = stringResource(R.string.settings_auto_switch_left_disabled),
                description = stringResource(R.string.settings_auto_switch_left_desc),
                onCheckedChange = actions.onAutoSwitchLeftAfterRightClickChanged
            )
        )
    }
}


@Composable
private fun virtualResolutionModeDisplayName(mode: VirtualResolutionMode): String {
    return when (mode) {
        VirtualResolutionMode.FULLSCREEN_FILL ->
            stringResource(R.string.settings_virtual_resolution_mode_fullscreen_fill)
        VirtualResolutionMode.RESOLUTION_1080P ->
            stringResource(R.string.settings_virtual_resolution_mode_1080p)
        VirtualResolutionMode.RESOLUTION_720P ->
            stringResource(R.string.settings_virtual_resolution_mode_720p)
        VirtualResolutionMode.RATIO_4_3 ->
            stringResource(R.string.settings_virtual_resolution_mode_4_3)
        VirtualResolutionMode.RATIO_16_9 ->
            stringResource(R.string.settings_virtual_resolution_mode_16_9)
    }
}


@Composable
private fun virtualResolutionModeDescription(mode: VirtualResolutionMode): String {
    return when (mode) {
        VirtualResolutionMode.FULLSCREEN_FILL ->
            stringResource(R.string.settings_virtual_resolution_mode_desc_fullscreen_fill)
        VirtualResolutionMode.RESOLUTION_1080P ->
            stringResource(R.string.settings_virtual_resolution_mode_desc_1080p)
        VirtualResolutionMode.RESOLUTION_720P ->
            stringResource(R.string.settings_virtual_resolution_mode_desc_720p)
        VirtualResolutionMode.RATIO_4_3 ->
            stringResource(R.string.settings_virtual_resolution_mode_desc_4_3)
        VirtualResolutionMode.RATIO_16_9 ->
            stringResource(R.string.settings_virtual_resolution_mode_desc_16_9)
    }
}


@Composable
private fun backBehaviorDisplayName(behavior: BackBehavior): String {
    return when (behavior) {
        BackBehavior.EXIT_TO_LAUNCHER ->
            stringResource(R.string.settings_back_behavior_exit)
        BackBehavior.SEND_ESCAPE ->
            stringResource(R.string.settings_back_behavior_escape)
        BackBehavior.NONE ->
            stringResource(R.string.settings_back_behavior_none)
    }
}


@Composable
private fun TouchscreenInputMode.displayName(): String {
    return stringResource(
        when (this) {
            TouchscreenInputMode.DESKTOP -> R.string.settings_touchscreen_mode_desktop
            TouchscreenInputMode.HYBRID -> R.string.settings_touchscreen_mode_hybrid
            TouchscreenInputMode.MOBILE -> R.string.settings_touchscreen_mode_mobile
        }
    )
}


@Composable
private fun TouchscreenInputMode.description(): String {
    return stringResource(
        when (this) {
            TouchscreenInputMode.DESKTOP -> R.string.settings_touchscreen_mode_desktop_desc
            TouchscreenInputMode.HYBRID -> R.string.settings_touchscreen_mode_hybrid_desc
            TouchscreenInputMode.MOBILE -> R.string.settings_touchscreen_mode_mobile_desc
        }
    )
}


@Composable
private fun CardPlayOptimizationMode.displayName(): String {
    return stringResource(
        when (this) {
            CardPlayOptimizationMode.RELEASE_POP_BACK ->
                R.string.settings_card_play_optimization_release_pop_back
            CardPlayOptimizationMode.RELEASE_KEEP_OPEN ->
                R.string.settings_card_play_optimization_release_keep_open
            CardPlayOptimizationMode.TAP_CARD_THEN_TARGET ->
                R.string.settings_card_play_optimization_tap_then_target
            CardPlayOptimizationMode.VANILLA ->
                R.string.settings_card_play_optimization_vanilla
        }
    )
}


@Composable
private fun CardPlayOptimizationMode.description(): String {
    return stringResource(
        when (this) {
            CardPlayOptimizationMode.RELEASE_POP_BACK ->
                R.string.settings_card_play_optimization_release_pop_back_desc
            CardPlayOptimizationMode.RELEASE_KEEP_OPEN ->
                R.string.settings_card_play_optimization_release_keep_open_desc
            CardPlayOptimizationMode.TAP_CARD_THEN_TARGET ->
                R.string.settings_card_play_optimization_tap_then_target_desc
            CardPlayOptimizationMode.VANILLA ->
                R.string.settings_card_play_optimization_vanilla_desc
        }
    )
}


@Composable
private fun keepScreenOnTimeoutDisplayName(timeoutMinutes: Int): String {
    return if (timeoutMinutes == LauncherPreferences.KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES) {
        stringResource(R.string.settings_keep_screen_on_timeout_always)
    } else {
        stringResource(R.string.settings_keep_screen_on_timeout_minutes, timeoutMinutes)
    }
}


@Composable
private fun TouchMouseInteractionMode.displayName(): String {
    return stringResource(
        when (this) {
            TouchMouseInteractionMode.OPEN_MENU_ON_TAP ->
                R.string.settings_touch_mouse_interaction_mode_open_menu
            TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP ->
                R.string.settings_touch_mouse_interaction_mode_toggle_button
        }
    )
}


@Composable
private fun TouchMouseInteractionMode.description(): String {
    return stringResource(
        when (this) {
            TouchMouseInteractionMode.OPEN_MENU_ON_TAP ->
                R.string.settings_touch_mouse_interaction_mode_open_menu_desc
            TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP ->
                R.string.settings_touch_mouse_interaction_mode_toggle_button_desc
        }
    )
}


@Composable
private fun SpecialKeyInputMode.displayName(): String {
    return stringResource(
        when (this) {
            SpecialKeyInputMode.LEGACY_FLOATING_WINDOW ->
                R.string.settings_special_key_input_mode_legacy_floating_window
            SpecialKeyInputMode.BUILT_IN_MOD ->
                R.string.settings_special_key_input_mode_built_in_mod
            SpecialKeyInputMode.DISABLED ->
                R.string.settings_special_key_input_mode_disabled
        }
    )
}


@Composable
private fun SpecialKeyInputMode.description(): String {
    return stringResource(
        when (this) {
            SpecialKeyInputMode.LEGACY_FLOATING_WINDOW ->
                R.string.settings_special_key_input_mode_legacy_floating_window_desc
            SpecialKeyInputMode.BUILT_IN_MOD ->
                R.string.settings_special_key_input_mode_built_in_mod_desc
            SpecialKeyInputMode.DISABLED ->
                R.string.settings_special_key_input_mode_disabled_desc
        }
    )
}


