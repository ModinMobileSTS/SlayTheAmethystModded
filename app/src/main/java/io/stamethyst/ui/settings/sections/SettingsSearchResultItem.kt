package io.stamethyst.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.SpecialKeyInputMode
import io.stamethyst.navigation.Route
import io.stamethyst.ui.settings.common.SettingsActionListItem
import io.stamethyst.ui.settings.common.SettingsSearchEntry
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

internal data class SettingsSearchActions(
    val appearance: AppearanceSettingsActions,
    val update: UpdateSettingsActions,
    val input: InputSettingsActions,
    val display: GameplayDisplaySettingsActions,
    val performance: PerformanceSettingsActions,
    val market: MarketSettingsActions,
    val acceleration: AccelerationSettingsActions,
    val advancedRender: AdvancedRenderSettingsActions,
    val onPlayerNameChanged: (String) -> Boolean,
    val onApplyModFileNameAliases: () -> Unit,
    val onResetLauncherSettingsToDefaults: () -> Unit,
    val onImportMods: () -> Unit,
    val onExportMods: () -> Unit,
    val onImportSaves: () -> Unit,
    val onExportSaves: () -> Unit,
    val onExportLogs: () -> Unit,
    val onExportLogsToFile: () -> Unit,
)

/** Render the original control so search and category pages share dialogs and validation. */
@Composable
internal fun SettingsSearchResultItem(
    entry: SettingsSearchEntry,
    uiState: SettingsScreenViewModel.UiState,
    actions: SettingsSearchActions?,
    onOpenSettingsRoute: (Route) -> Unit,
) {
    val manualRendererDialog = entry.titleResId == R.string.settings_renderer_backend_title &&
        uiState.rendererSelectionMode == RendererSelectionMode.MANUAL
    if (actions != null && (entry.titleResId in developerDialogTitles || manualRendererDialog)) {
        SettingsSearchDeveloperWarning {
            SettingsSearchResultControl(entry, uiState, actions, onOpenSettingsRoute)
        }
    } else {
        SettingsSearchResultControl(entry, uiState, actions, onOpenSettingsRoute)
    }
}

@Composable
private fun SettingsSearchResultControl(
    entry: SettingsSearchEntry,
    uiState: SettingsScreenViewModel.UiState,
    actions: SettingsSearchActions?,
    onOpenSettingsRoute: (Route) -> Unit,
) {
    val title = entry.titleResId
    val appearanceAvailable = when (title) {
        R.string.settings_boot_overlay_custom_image_title -> uiState.bootOverlayStyle == BootOverlayStyle.MODERN
        R.string.settings_loading_animation_title -> uiState.bootOverlayStyle.supportsLoadingAnimation
        else -> true
    }
    val inputAvailable = title != R.string.settings_touch_mouse_interaction_label ||
        uiState.specialKeyInputMode == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW

    when {
        actions == null || !appearanceAvailable || !inputAvailable -> SettingsActionListItem(
            title = stringResource(title),
            supportingText = entry.subtitleResId?.let { stringResource(it) },
            enabled = !uiState.busyOperation.locksInteraction(uiState.busy),
            onClick = { onOpenSettingsRoute(entry.route) },
        )
        title in appearanceDialogTitles -> SettingsAppearanceSection(uiState, actions.appearance, title)
        title == R.string.update_mirror_title -> SettingsUpdateMirrorSetting(uiState, actions.update)
        title == R.string.settings_mod_alias_apply_file_names_action -> SettingsApplyModFileNameAliasesAction(
            uiState.busy, actions.onApplyModFileNameAliases,
        )
        title == R.string.settings_player_name_title -> SettingsPlayerNameAction(
            uiState, actions.onPlayerNameChanged,
        )
        title == R.string.settings_back_behavior_title ->
            SettingsBackBehaviorSetting(uiState, actions.input.onBackBehaviorChanged)
        title == R.string.settings_touchscreen_mode_title ->
            SettingsTouchscreenInputModeSetting(uiState, actions.input.onTouchscreenInputModeChanged)
        title == R.string.settings_card_play_optimization_title ->
            SettingsCardPlayOptimizationSetting(uiState, actions.input)
        title == R.string.settings_special_key_input_mode_title ->
            SettingsSpecialKeyInputModeSetting(uiState, actions.input.onSpecialKeyInputModeChanged)
        title == R.string.settings_touch_mouse_interaction_label ->
            SettingsTouchMouseInteractionSetting(uiState, actions.input.onTouchMouseInteractionModeChanged)
        title == R.string.settings_game_display_size_title ->
            GameDisplayRegionSetting(uiState, actions.display.onGameDisplayRegionChanged)
        title == R.string.settings_keep_screen_on_timeout_title ->
            SettingsKeepScreenOnTimeoutSetting(uiState, actions.display)
        title == R.string.settings_virtual_resolution_mode_title ->
            SettingsVirtualResolutionModeSetting(uiState, actions.performance)
        title == R.string.settings_market_workshop_language_title ->
            SettingsWorkshopLanguageSetting(uiState, actions.market)
        title == R.string.settings_system_game_mode_title ->
            SettingsSystemGameModeSetting(uiState)
        title == R.string.settings_developer_acceleration_strategy_title ->
            SettingsAccelerationSection(uiState, actions.acceleration)
        title == R.string.settings_renderer_backend_title &&
            uiState.rendererSelectionMode == RendererSelectionMode.MANUAL ->
            SettingsManualRendererSetting(uiState, actions.advancedRender)
        title == R.string.settings_render_surface_backend_title ->
            SettingsRenderSurfaceBackendSetting(uiState, actions.advancedRender)
        title == R.string.settings_gpu_resource_guardian_title ->
            SettingsGpuResourceGuardianSetting(uiState, actions.advancedRender)
        title == R.string.settings_jvm_tiered_stop_at_level_title ->
            SettingsJvmTieredStopAtLevelSetting(uiState, actions.advancedRender)
        title in statusDialogTitles -> SettingsStatusDialogActions(uiState, title)
        title == R.string.settings_reset_defaults_title -> SettingsResetDefaultsSection(
            uiState.busy, actions.onResetLauncherSettingsToDefaults,
        )
        title in resourceDialogTitles -> SettingsImportSection(
            busy = uiState.busy,
            onImportJar = {},
            onReinstallResourcePack = {},
            onImportMods = actions.onImportMods,
            onExportMods = actions.onExportMods,
            onImportSaves = actions.onImportSaves,
            onExportSaves = actions.onExportSaves,
            onExportLogs = actions.onExportLogs,
            onExportLogsToFile = actions.onExportLogsToFile,
            settingTitleResId = title,
        )
        else -> SettingsActionListItem(
            title = stringResource(title),
            supportingText = entry.subtitleResId?.let { stringResource(it) },
            enabled = !uiState.busyOperation.locksInteraction(uiState.busy),
            onClick = { onOpenSettingsRoute(entry.route) },
        )
    }
}

private val appearanceDialogTitles = setOf(
    R.string.settings_theme_mode_title,
    R.string.settings_app_icon_title,
    R.string.settings_theme_color_title,
    R.string.settings_boot_overlay_style_title,
    R.string.settings_boot_overlay_custom_image_title,
    R.string.settings_loading_animation_title,
)

private val statusDialogTitles = setOf(
    R.string.settings_view_full_status,
    R.string.settings_view_log_paths,
    R.string.settings_unplayable_mods_entry_title,
)

private val developerDialogTitles = statusDialogTitles + setOf(
    R.string.settings_system_game_mode_title,
    R.string.settings_developer_acceleration_strategy_title,
    R.string.settings_render_surface_backend_title,
    R.string.settings_gpu_resource_guardian_title,
    R.string.settings_jvm_tiered_stop_at_level_title,
    R.string.settings_reset_defaults_title,
)

private val resourceDialogTitles = setOf(
    R.string.settings_mod_operations,
    R.string.settings_save_operations,
    R.string.settings_log_operations,
)
