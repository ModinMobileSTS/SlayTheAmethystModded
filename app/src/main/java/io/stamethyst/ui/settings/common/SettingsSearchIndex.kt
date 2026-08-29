package io.stamethyst.ui.settings.common

import androidx.annotation.StringRes
import io.stamethyst.R
import io.stamethyst.navigation.Route

internal data class SettingsSearchEntry(
    @param:StringRes val titleResId: Int,
    @param:StringRes val subtitleResId: Int? = null,
    @param:StringRes val categoryTitleResId: Int,
    val route: Route,
)

internal val SettingsSearchEntries: List<SettingsSearchEntry> = listOf(
    // Launcher
    entry(
        titleResId = R.string.settings_basic_tutorial_action,
        subtitleResId = R.string.settings_basic_tutorial_desc,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_theme_mode_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_app_icon_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_theme_color_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_chrome_background_opacity_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_boot_overlay_style_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_boot_overlay_custom_image_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_loading_animation_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.update_section_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.update_auto_check_enabled,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.update_mirror_title,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_first_run_reopen_action,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),
    entry(
        titleResId = R.string.settings_mod_alias_apply_file_names_action,
        subtitleResId = R.string.settings_mod_alias_apply_file_names_desc,
        categoryTitleResId = R.string.settings_category_launcher_title,
        route = Route.SettingsLauncher,
    ),

    // Game
    entry(
        titleResId = R.string.settings_player_name_title,
        subtitleResId = R.string.settings_player_name_desc,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_back_behavior_title,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_touchscreen_mode_title,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_card_play_optimization_title,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_touch_double_click_as_right_click_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_ignore_long_press_right_click_while_playing_card_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_touch_indicator_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_haptic_feedback_enabled,
        subtitleResId = R.string.settings_haptic_feedback_desc,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_special_key_input_mode_title,
        subtitleResId = R.string.settings_special_key_input_mode_desc,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_touch_mouse_interaction_label,
        subtitleResId = R.string.settings_touch_mouse_interaction_desc,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_built_in_soft_keyboard_enabled,
        subtitleResId = R.string.settings_built_in_soft_keyboard_desc,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_auto_switch_left_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_display_cutout_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_crop_screen_bottom_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_gameplay_larger_ui_enabled,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_gameplay_font_scale_title,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),
    entry(
        titleResId = R.string.settings_keep_screen_on_timeout_title,
        categoryTitleResId = R.string.settings_category_game_title,
        route = Route.SettingsGame,
    ),

    // Performance
    entry(
        titleResId = R.string.settings_ram_saver_title,
        subtitleResId = R.string.settings_ram_saver_desc,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),
    entry(
        titleResId = R.string.settings_mts_patch_cache_title,
        subtitleResId = R.string.settings_mts_patch_cache_desc,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),
    entry(
        titleResId = R.string.settings_render_scale_title,
        subtitleResId = R.string.settings_render_scale_desc,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),
    entry(
        titleResId = R.string.settings_target_fps_title,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),
    entry(
        titleResId = R.string.settings_virtual_resolution_mode_title,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),
    entry(
        titleResId = R.string.settings_performance_overlay_enabled,
        categoryTitleResId = R.string.settings_category_performance_title,
        route = Route.SettingsPerformance,
    ),

    // Market / cloud
    entry(
        titleResId = R.string.settings_steam_cloud_title,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_steam_cloud_save_settings_title,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_steam_cloud_watt_acceleration_enabled_title,
        subtitleResId = R.string.settings_steam_cloud_watt_acceleration_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_steam_cloud_auto_launch_after_sync_title,
        subtitleResId = R.string.settings_steam_cloud_auto_launch_after_sync_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_steam_cloud_clear_network_cache_title,
        subtitleResId = R.string.settings_steam_cloud_clear_network_cache_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_concurrent_downloads_title,
        subtitleResId = R.string.settings_market_concurrent_downloads_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_download_threads_title,
        subtitleResId = R.string.settings_market_download_threads_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_workshop_acceleration_enabled_title,
        subtitleResId = R.string.settings_market_workshop_acceleration_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_workshop_language_title,
        subtitleResId = R.string.settings_market_workshop_language_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_workshop_auto_import_enabled_title,
        subtitleResId = R.string.settings_market_workshop_auto_import_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_workshop_auto_import_defaults_title,
        subtitleResId = R.string.settings_workshop_auto_import_defaults_subtitle,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsWorkshopAutoImportDefaults,
    ),
    entry(
        titleResId = R.string.settings_baidu_translation_credentials_title,
        subtitleResId = R.string.settings_baidu_translation_credentials_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),
    entry(
        titleResId = R.string.settings_market_clear_preview_cache_title,
        subtitleResId = R.string.settings_market_clear_preview_cache_desc,
        categoryTitleResId = R.string.settings_category_market_cloud_title,
        route = Route.SettingsMarketCloud,
    ),

    // Feedback / files
    entry(
        titleResId = R.string.settings_feedback_entry_new,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_feedback_entry_subscriptions,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_feedback_entry_issue_browser,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_mod_operations,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_save_operations,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_log_operations,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_reimport_sts_jar_title,
        subtitleResId = R.string.settings_reimport_sts_jar_desc,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_export_all_mods,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_import_saves,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_export_saves,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),
    entry(
        titleResId = R.string.settings_export_error_logs,
        categoryTitleResId = R.string.settings_feedback_logs_title,
        route = Route.SettingsFeedback,
    ),

    // Native library market
    entry(
        titleResId = R.string.settings_native_library_market_title,
        subtitleResId = R.string.settings_native_library_market_desc,
        categoryTitleResId = R.string.settings_native_library_market_title,
        route = Route.NativeLibraryMarket,
    ),

    // Developer
    entry(
        titleResId = R.string.settings_developer_title,
        subtitleResId = R.string.settings_developer_summary,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_together_in_spire_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_easytier_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_cloud_control_test_enabled,
        subtitleResId = R.string.settings_cloud_control_test_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_sustained_performance_enabled,
        subtitleResId = R.string.settings_sustained_performance_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_system_game_mode_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_boot_overlay_manual_enabled,
        subtitleResId = R.string.settings_boot_overlay_manual_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_compendium_upgrade_touch_fix_enabled,
        subtitleResId = R.string.settings_compendium_upgrade_touch_fix_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_renderer_backend_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_render_surface_backend_title,
        subtitleResId = R.string.settings_render_surface_backend_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_mobileglues_entry_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_gpu_resource_guardian_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_jvm_compressed_pointers_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_jvm_string_dedup_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.compat_settings_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_lwjgl_debug_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_preload_all_jre_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_logcat_capture_enabled,
        subtitleResId = R.string.settings_logcat_capture_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_launcher_logcat_capture_enabled,
        subtitleResId = R.string.settings_launcher_logcat_capture_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_jvm_logcat_mirror_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_gpu_resource_diag_enabled,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_view_full_status,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_view_log_paths,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_unplayable_mods_entry_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_developer_clear_junk_files_title,
        subtitleResId = R.string.settings_developer_clear_junk_files_desc,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),
    entry(
        titleResId = R.string.settings_reset_defaults_title,
        categoryTitleResId = R.string.settings_developer_title,
        route = Route.DeveloperSettings,
    ),

    // About
    entry(
        titleResId = R.string.settings_author_info_title,
        categoryTitleResId = R.string.settings_category_about_title,
        route = Route.SettingsAbout,
    ),
)

private fun entry(
    @StringRes titleResId: Int,
    @StringRes subtitleResId: Int? = null,
    @StringRes categoryTitleResId: Int,
    route: Route,
): SettingsSearchEntry {
    return SettingsSearchEntry(
        titleResId = titleResId,
        subtitleResId = subtitleResId,
        categoryTitleResId = categoryTitleResId,
        route = route,
    )
}
