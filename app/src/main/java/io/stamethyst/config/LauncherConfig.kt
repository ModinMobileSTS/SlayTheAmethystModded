package io.stamethyst.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.stamethyst.backend.mods.AtlasOfflineDownscaleStrategy
import io.stamethyst.backend.render.DisplayConfigSync
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererSelectionMode
import io.stamethyst.backend.steamcloud.SteamCloudSyncBlacklist
import io.stamethyst.backend.mods.ImportDownscaleMaterialPolicy
import io.stamethyst.backend.mods.RuntimeDownscaleMaterialPolicy
import io.stamethyst.backend.mods.RuntimeTextureAtlasDownscaleQuality
import io.stamethyst.backend.render.VirtualResolutionMode
import io.stamethyst.backend.steamcloud.SteamCloudOperationMutex
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Single configuration entry point for launcher/runtime settings.
 *
 * This object owns key names, defaults and normalization rules so callers do
 * not duplicate storage details.
 */
object LauncherConfig {
    private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
    private const val PREF_KEY_BACK_BEHAVIOR = "back_behavior"
    private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
    private const val PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY = "manual_dismiss_boot_overlay"
    private const val PREF_KEY_SPECIAL_KEY_INPUT_MODE = "special_key_input_mode"
    private const val PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW = "show_floating_mouse_window"
    private const val PREF_KEY_TOUCH_MOUSE_INTERACTION_MODE = "touch_mouse_interaction_mode"
    private const val PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION = "touch_mouse_new_interaction"
    private const val PREF_KEY_BUILT_IN_SOFT_KEYBOARD_ENABLED =
        "built_in_soft_keyboard_enabled"
    private const val PREF_KEY_FLOATING_TOOL_BUTTONS = "floating_tool_buttons"
    private const val PREF_KEY_HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
    private const val PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = "auto_switch_left_after_right_click"
    private const val PREF_KEY_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK =
        "touch_double_click_as_right_click"
    private const val PREF_KEY_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD =
        "ignore_long_press_right_click_while_playing_card"
    private const val PREF_KEY_TOUCHSCREEN_ENABLED = "touchscreen_enabled"
    private const val PREF_KEY_CARD_PLAY_OPTIMIZATION_MODE = "card_play_optimization_mode"
    private const val PREF_KEY_TOUCH_INDICATOR_ENABLED = "touch_indicator_enabled"
    private const val PREF_KEY_RENDER_SURFACE_BACKEND = "render_surface_backend"
    private const val PREF_KEY_RENDERER_SELECTION_MODE = "renderer_selection_mode"
    private const val PREF_KEY_MANUAL_RENDERER_BACKEND = "manual_renderer_backend"
    private const val PREF_KEY_MOBILEGLUES_ANGLE_POLICY = "mobileglues_angle_policy"
    private const val PREF_KEY_MOBILEGLUES_NO_ERROR_POLICY = "mobileglues_no_error_policy"
    private const val PREF_KEY_MOBILEGLUES_MULTIDRAW_MODE = "mobileglues_multidraw_mode"
    private const val PREF_KEY_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED =
        "mobileglues_ext_compute_shader_enabled"
    private const val LEGACY_PREF_KEY_MOBILEGLUES_EXT_GL43_ENABLED =
        "mobileglues_ext_gl43_enabled"
    private const val PREF_KEY_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED =
        "mobileglues_ext_timer_query_enabled"
    private const val PREF_KEY_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED =
        "mobileglues_ext_direct_state_access_enabled"
    private const val PREF_KEY_MOBILEGLUES_GLSL_CACHE_SIZE_MB = "mobileglues_glsl_cache_size_mb"
    private const val PREF_KEY_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE =
        "mobileglues_angle_depth_clear_fix_mode"
    private const val PREF_KEY_MOBILEGLUES_CUSTOM_GL_VERSION = "mobileglues_custom_gl_version"
    private const val PREF_KEY_MOBILEGLUES_FSR1_QUALITY_PRESET =
        "mobileglues_fsr1_quality_preset"
    private const val PREF_KEY_THEME_MODE = "theme_mode"
    private const val PREF_KEY_THEME_COLOR = "theme_color"
    private const val PREF_KEY_LAUNCHER_ICON_MODE = "launcher_icon_mode"
    private const val PREF_KEY_CHROME_BACKGROUND_OPACITY = "chrome_background_opacity"
    private const val LEGACY_PREF_KEY_HOME_CHROME_TRANSPARENCY = "home_chrome_transparency"
    private const val PREF_KEY_BOOT_OVERLAY_STYLE = "boot_overlay_style"
    private const val PREF_KEY_BOOT_OVERLAY_ANIMATION = "boot_overlay_animation"
    private const val PREF_KEY_BOOT_OVERLAY_IMAGE_MODE = "boot_overlay_image_mode"
    private const val PREF_KEY_BOOT_OVERLAY_START_IMAGE_PATH = "boot_overlay_start_image_path"
    private const val PREF_KEY_BOOT_OVERLAY_END_IMAGE_PATH = "boot_overlay_end_image_path"
    private const val PREF_KEY_BOOT_OVERLAY_START_IMAGE_VERSION =
        "boot_overlay_start_image_version"
    private const val PREF_KEY_BOOT_OVERLAY_END_IMAGE_VERSION = "boot_overlay_end_image_version"
    private const val PREF_KEY_SHOW_MOD_FILE_NAME = "show_mod_file_name"
    private const val PREF_KEY_MOBILE_HUD_ENABLED = "mobile_hud_enabled"
    private const val PREF_KEY_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED =
        "compendium_upgrade_touch_fix_enabled"
    private const val PREF_KEY_VIRTUAL_RESOLUTION_MODE = "virtual_resolution_mode"
    private const val PREF_KEY_AVOID_DISPLAY_CUTOUT = "avoid_display_cutout"
    private const val PREF_KEY_CROP_SCREEN_BOTTOM = "crop_screen_bottom"
    private const val PREF_KEY_RAM_SAVER_ENABLED = "ram_saver_enabled"
    private const val PREF_KEY_MTS_PATCH_CACHE_ENABLED = "mts_patch_cache_enabled"
    private const val PREF_KEY_SHOW_GAME_PERFORMANCE_OVERLAY = "show_game_performance_overlay"
    private const val PREF_KEY_SUSTAINED_PERFORMANCE_MODE_ENABLED =
        "sustained_performance_mode_enabled"
    private const val PREF_KEY_KEEP_SCREEN_ON_TIMEOUT_MINUTES = "keep_screen_on_timeout_minutes"
    private const val PREF_KEY_TARGET_FPS = "target_fps"
    private const val PREF_KEY_JVM_HEAP_MAX_MB = "jvm_heap_max_mb"
    private const val PREF_KEY_JVM_COMPRESSED_POINTERS_ENABLED = "jvm_compressed_pointers_enabled"
    private const val PREF_KEY_JVM_STRING_DEDUPLICATION_ENABLED =
        "jvm_string_deduplication_enabled"
    private const val PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT = "compat_global_atlas_filter_compat"
    private const val PREF_KEY_MOD_MANIFEST_ROOT_COMPAT = "compat_mod_manifest_root_compat"
    private const val PREF_KEY_FRIEREN_MOD_COMPAT = "compat_frieren_mod_compat"
    private const val PREF_KEY_DOWNFALL_IMPORT_COMPAT = "compat_downfall_import_compat"
    private const val PREF_KEY_VUPSHION_MOD_COMPAT = "compat_vupshion_mod_compat"
    private const val PREF_KEY_CHAOFANMOD_COMPAT = "compat_chaofanmod_compat"
    private const val PREF_KEY_JACKET_NO_ANO_KO_MOD_COMPAT = "compat_jacket_no_anoko_mod_compat"
    private const val PREF_KEY_FRAGMENT_SHADER_PRECISION_COMPAT =
        "compat_fragment_shader_precision_compat"
    private const val PREF_KEY_RUNTIME_TEXTURE_COMPAT = "compat_runtime_texture_compat"
    private const val PREF_KEY_MAIN_MENU_PREVIEW_REUSE_COMPAT =
        "compat_main_menu_preview_reuse"
    private const val PREF_KEY_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT =
        "compat_room_context_hand_layout_rescue"
    private const val PREF_KEY_ROOM_TRANSITION_RESCUE_COMPAT =
        "compat_room_transition_rescue"
    private const val PREF_KEY_EVENT_ROOM_RESCUE_COMPAT =
        "compat_event_room_rescue"
    private const val PREF_KEY_SHOP_ROOM_RESCUE_COMPAT =
        "compat_shop_room_rescue"
    private const val PREF_KEY_BASEMOD_SAVE_LOAD_RESCUE_COMPAT =
        "compat_basemod_save_load_rescue"
    private const val PREF_KEY_RELIC_ENTER_ROOM_RESCUE_COMPAT =
        "compat_relic_enter_room_rescue"
    private const val PREF_KEY_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT =
        "compat_dungeon_render_room_context_rescue"
    private const val PREF_KEY_POWER_ICON_RENDER_RESCUE_COMPAT =
        "compat_power_icon_render_rescue"
    private const val PREF_KEY_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT =
        "compat_basemod_custom_monster_render_rescue"
    private const val PREF_KEY_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT =
        "compat_non_combat_player_render_rescue"
    private const val PREF_KEY_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT =
        "compat_card_tooltip_keyword_rescue"
    // Keep the legacy stored key so existing users do not lose their toggle state
    // when the old relic direct-pick switch is replaced by the touchscreen allowlist.
    private const val PREF_KEY_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT =
        "compat_relic_touchscreen_obtain"
    private const val PREF_KEY_LARGE_TEXTURE_DOWNSCALE_COMPAT =
        "compat_large_texture_downscale"
    private const val PREF_KEY_TEXTURE_RESIDENCY_MANAGER_COMPAT =
        "compat_texture_residency_manager"
    private const val PREF_KEY_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR =
        "compat_texture_pressure_downscale_divisor"
    private const val PREF_KEY_GPU_RESOURCE_GUARDIAN_MODE = "compat_gpu_resource_guardian_mode"
    private const val PREF_KEY_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE =
        "compat_gpu_resource_guardian_safe_pressure_downscale"
    private const val LEGACY_GPU_RESOURCE_GUARDIAN_DIAGNOSTIC_MODE = "diagnostic"
    private const val PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER = "compat_force_linear_mipmap_filter"
    private const val PREF_KEY_HINA_CHARACTER_RENDER_COMPAT =
        "compat_hina_character_render"
    private const val PREF_KEY_NON_RENDERABLE_FBO_FORMAT_COMPAT =
        "compat_non_renderable_fbo_format_compat"
    private const val PREF_KEY_FBO_MANAGER_COMPAT = "compat_fbo_manager"
    private const val PREF_KEY_FBO_IDLE_RECLAIM_COMPAT = "compat_fbo_idle_reclaim"
    private const val PREF_KEY_FBO_PRESSURE_DOWNSCALE_COMPAT =
        "compat_fbo_pressure_downscale"
    private const val PREF_KEY_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES =
        "compat_runtime_downscale_ordinary_textures"
    private const val PREF_KEY_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES =
        "compat_runtime_downscale_texture_atlas_pages"
    private const val PREF_KEY_RUNTIME_DOWNSCALE_SPINE_TEXTURES =
        "compat_runtime_downscale_spine_textures"
    private const val PREF_KEY_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS =
        "compat_runtime_downscale_offscreen_frame_buffers"
    private const val PREF_KEY_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES =
        "compat_import_downscale_spine_atlas_pages"
    private const val PREF_KEY_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES =
        "compat_import_downscale_ordinary_atlas_pages"
    private const val PREF_KEY_LWJGL_DEBUG = "lwjgl_debug"
    private const val PREF_KEY_PRELOAD_ALL_JRE_LIBRARIES = "preload_all_jre_libraries"
    private const val PREF_KEY_LOGCAT_CAPTURE_ENABLED = "logcat_capture_enabled"
    private const val PREF_KEY_LAUNCHER_LOGCAT_CAPTURE_ENABLED = "launcher_logcat_capture_enabled"
    private const val PREF_KEY_JVM_LOGCAT_MIRROR_ENABLED = "jvm_logcat_mirror_enabled"
    private const val PREF_KEY_GPU_RESOURCE_DIAG_ENABLED = "gpu_resource_diag_enabled"
    private const val PREF_KEY_GDX_PAD_CURSOR_DEBUG = "gdx_pad_cursor_debug"
    private const val PREF_KEY_GLBRIDGE_SWAP_HEARTBEAT_DEBUG = "glbridge_swap_heartbeat_debug"
    private const val PREF_KEY_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED =
        "together_in_spire_route_lock_enabled"
    private const val PREF_KEY_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED =
        "together_in_spire_easytier_autofill_enabled"
    private const val PREF_KEY_AUTO_CHECK_UPDATES_ENABLED = "auto_check_updates_enabled"
    private const val PREF_KEY_STEAM_CLOUD_WATT_ACCELERATION_ENABLED =
        "steam_cloud_watt_acceleration_enabled"
    private const val PREF_KEY_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED =
        "steam_cloud_auto_launch_after_sync_enabled"
    private const val PREF_KEY_STEAM_CLOUD_BACKGROUND_LAUNCH_REQUESTED =
        "steam_cloud_background_launch_requested"
    private const val PREF_KEY_STEAM_GAME_PRESENCE_ENABLED =
        "steam_game_presence_enabled"
    private const val PREF_KEY_STEAM_RICH_PRESENCE_PREFIX = "steam_rich_presence_prefix"
    private const val PREF_KEY_STEAM_RICH_PRESENCE_SHOW_CHARACTER =
        "steam_rich_presence_show_character"
    private const val PREF_KEY_STEAM_RICH_PRESENCE_SHOW_FLOOR = "steam_rich_presence_show_floor"
    private const val PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ASCENSION =
        "steam_rich_presence_show_ascension"
    private const val PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ACT = "steam_rich_presence_show_act"
    private const val PREF_KEY_WORKSHOP_MAX_CONCURRENT_DOWNLOADS =
        "workshop_max_concurrent_downloads"
    private const val PREF_KEY_WORKSHOP_DOWNLOAD_THREADS = "workshop_download_threads"
    private const val PREF_KEY_WORKSHOP_WATT_ACCELERATION_ENABLED =
        "workshop_watt_acceleration_enabled"
    // Unified switch replacing the two legacy per-feature keys above.
    private const val PREF_KEY_WATT_ACCELERATION_ENABLED = "watt_acceleration_enabled"
    private const val PREF_KEY_WORKSHOP_STEAM_LANGUAGE = "workshop_steam_language"
    private const val PREF_KEY_WORKSHOP_AUTO_IMPORT_ENABLED = "workshop_auto_import_enabled"
    private const val PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED =
        "workshop_auto_import_atlas_downscale_enabled"
    private const val PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX =
        "workshop_auto_import_atlas_downscale_max_edge_px"
    private const val PREF_KEY_WORKSHOP_SUBSCRIBE_WARNING_DISMISSED =
        "workshop_subscribe_warning_dismissed"
    private const val PREF_KEY_ENABLED_MOD_SIZE_WARNING_DISMISSED =
        "enabled_mod_size_warning_dismissed"
    private const val PREF_KEY_LAST_WORKSHOP_UPDATE_CHECK_AT_MS = "last_workshop_update_check_at_ms"
    private const val PREF_KEY_STEAM_CLOUD_SAVE_MODE = "steam_cloud_save_mode"
    private const val PREF_KEY_STEAM_CLOUD_INDEPENDENT_SWITCH_PENDING =
        "steam_cloud_independent_switch_pending"
    private const val PREF_KEY_STEAM_CLOUD_PENDING_PROFILE_STEAM_ID =
        "steam_cloud_pending_profile_steam_id"
    private const val PREF_KEY_STEAM_CLOUD_SYNC_BLACKLIST_PATHS =
        "steam_cloud_sync_blacklist_paths"
    private const val PREF_KEY_PREFERRED_UPDATE_MIRROR_ID = "preferred_update_mirror_id"
    private const val PREF_KEY_LAST_UPDATE_CHECK_AT_MS = "last_update_check_at_ms"
    private const val PREF_KEY_LAST_KNOWN_REMOTE_TAG = "last_known_remote_tag"
    private const val PREF_KEY_LAST_SUCCESSFUL_METADATA_SOURCE_ID = "last_successful_metadata_source_id"
    private const val PREF_KEY_LAST_SUCCESSFUL_DOWNLOAD_SOURCE_ID = "last_successful_download_source_id"
    private const val PREF_KEY_LAST_UPDATE_ERROR_SUMMARY = "last_update_error_summary"
    private const val PREF_KEY_FIRST_RUN_SETUP_COMPLETED = "first_run_setup_completed"
    private const val PREF_KEY_BASIC_TUTORIAL_NOTICE_DISMISSED = "basic_tutorial_notice_dismissed"
    private const val PREF_KEY_DEVELOPER_SETTINGS_WARNING_DISMISSED =
        "developer_settings_warning_dismissed"
    private const val PREF_KEY_STEAM_ACHIEVEMENT_DEBUG_MODE =
        "steam_achievement_debug_mode"
    private const val PREF_KEY_STEAM_ACHIEVEMENT_SYNC_ENABLED =
        "steam_achievement_sync_enabled"
    private const val PREF_KEY_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED =
        "achievement_unlock_notification_enabled"
    private const val PREF_KEY_USE_LOCAL_TEST_CLOUD_CONTROL =
        "use_local_test_cloud_control"
    private const val PREF_KEY_LOCAL_TEST_ONLINE_SERVICE_BASE_URL =
        "local_test_online_service_base_url"
    private const val PREF_KEY_LOCAL_TEST_CONFIG_SERVER_URL =
        "local_test_config_server_url"
    private const val PREF_KEY_LOCAL_TEST_ENTRY_NODE_URL =
        "local_test_entry_node_url"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_AT_MS = "expected_back_exit_at_ms"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS = "expected_back_exit_restart_at_ms"
    private const val EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L
    const val DEFAULT_BACK_IMMEDIATE_EXIT = true
    val DEFAULT_BACK_BEHAVIOR: BackBehavior = BackBehavior.EXIT_TO_LAUNCHER
    const val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY = false
    const val DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED = true
    const val DEFAULT_TARGET_FPS = 90
    val TARGET_FPS_OPTIONS = intArrayOf(24, 30, 60, 90, 120, 240)
    const val KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES = 0
    const val DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES = KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES
    val KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS = intArrayOf(
        KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES,
        5,
        10,
        30,
        60
    )
    val DEFAULT_RENDER_SURFACE_BACKEND: RenderSurfaceBackend = RenderSurfaceBackend.SURFACE_VIEW
    val DEFAULT_RENDERER_SELECTION_MODE: RendererSelectionMode = RendererSelectionMode.AUTO
    val DEFAULT_MANUAL_RENDERER_BACKEND: RendererBackend =
        RendererBackend.OPENGL_ES_MOBILEGLUES
    val DEFAULT_MOBILEGLUES_ANGLE_POLICY: MobileGluesAnglePolicy =
        MobileGluesAnglePolicy.PREFER_DISABLED
    val DEFAULT_MOBILEGLUES_NO_ERROR_POLICY: MobileGluesNoErrorPolicy =
        MobileGluesNoErrorPolicy.AUTO
    val DEFAULT_MOBILEGLUES_MULTIDRAW_MODE: MobileGluesMultidrawMode =
        MobileGluesMultidrawMode.PREFER_MULTI_DRAW_INDIRECT
    const val DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED = false
    const val DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED = false
    const val DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED = true
    val DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET: MobileGluesGlslCacheSizePreset =
        MobileGluesGlslCacheSizePreset.MB_64
    val DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE: MobileGluesAngleDepthClearFixMode =
        MobileGluesAngleDepthClearFixMode.DISABLED
    val DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION: MobileGluesCustomGlVersion =
        MobileGluesCustomGlVersion.OPENGL_4_6
    val DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET: MobileGluesFsr1QualityPreset =
        MobileGluesFsr1QualityPreset.DISABLED
    val DEFAULT_THEME_MODE: LauncherThemeMode = LauncherThemeMode.FOLLOW_SYSTEM
    val DEFAULT_THEME_COLOR: LauncherThemeColor = LauncherThemeColor.COLORLESS
    val DEFAULT_LAUNCHER_ICON_MODE: LauncherIconMode = LauncherIconMode.AMETHYST
    const val DEFAULT_CHROME_BACKGROUND_OPACITY = 0.0f
    const val MIN_CHROME_BACKGROUND_OPACITY = 0.0f
    const val MAX_CHROME_BACKGROUND_OPACITY = 1.0f
    val DEFAULT_BOOT_OVERLAY_STYLE: BootOverlayStyle = BootOverlayStyle.MODERN
    val DEFAULT_BOOT_OVERLAY_ANIMATION: BootOverlayAnimation =
        BootOverlayAnimation.CARD_SHUFFLE
    val DEFAULT_BOOT_OVERLAY_IMAGE_MODE: BootOverlayImageMode = BootOverlayImageMode.DUAL
    val DEFAULT_SPECIAL_KEY_INPUT_MODE: SpecialKeyInputMode = SpecialKeyInputMode.BUILT_IN_MOD
    const val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW = false
    val DEFAULT_TOUCH_MOUSE_INTERACTION_MODE: TouchMouseInteractionMode =
        TouchMouseInteractionMode.OPEN_MENU_ON_TAP
    const val DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED = true
    val DEFAULT_FLOATING_TOOL_BUTTONS: Set<String> = emptySet()
    val FLOATING_TOOL_BUTTON_IDS: List<String> = listOf(
        "ctrl",
        "shift",
        "tab",
        "alt",
        "lock",
        "wheel",
    )
    const val DEFAULT_HAPTIC_FEEDBACK_ENABLED = true
    const val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = true
    const val DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK = false
    const val DEFAULT_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD = true
    const val DEFAULT_SHOW_MOD_FILE_NAME = false
    const val DEFAULT_MOBILE_HUD_ENABLED = false
    const val DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED = true
    val DEFAULT_VIRTUAL_RESOLUTION_MODE: VirtualResolutionMode =
        VirtualResolutionMode.FULLSCREEN_FILL
    const val DEFAULT_AVOID_DISPLAY_CUTOUT = false
    const val DEFAULT_CROP_SCREEN_BOTTOM = false
    const val DEFAULT_RAM_SAVER_ENABLED = true
    const val DEFAULT_MTS_PATCH_CACHE_ENABLED = false
    const val DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY = false
    const val DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED = true
    const val DEFAULT_LWJGL_DEBUG = false
    const val DEFAULT_PRELOAD_ALL_JRE_LIBRARIES = false
    const val DEFAULT_LOGCAT_CAPTURE_ENABLED = false
    const val DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED = true
    const val DEFAULT_JVM_LOGCAT_MIRROR_ENABLED = false
    const val DEFAULT_GPU_RESOURCE_DIAG_ENABLED = false
    const val DEFAULT_GDX_PAD_CURSOR_DEBUG = false
    const val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG = false
    const val DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED = true
    const val DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED = true
    const val DEFAULT_AUTO_CHECK_UPDATES_ENABLED = true
    const val DEFAULT_WATT_ACCELERATION_ENABLED = true
    const val DEFAULT_STEAM_CLOUD_WATT_ACCELERATION_ENABLED = DEFAULT_WATT_ACCELERATION_ENABLED
    const val DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED = false
    const val DEFAULT_STEAM_GAME_PRESENCE_ENABLED = false
    const val DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS = 1
    const val MIN_WORKSHOP_MAX_CONCURRENT_DOWNLOADS = 1
    const val MAX_WORKSHOP_MAX_CONCURRENT_DOWNLOADS = 4
    const val DEFAULT_WORKSHOP_DOWNLOAD_THREADS = 4
    const val MIN_WORKSHOP_DOWNLOAD_THREADS = 1
    const val MAX_WORKSHOP_DOWNLOAD_THREADS = 8
    const val DEFAULT_WORKSHOP_WATT_ACCELERATION_ENABLED = DEFAULT_WATT_ACCELERATION_ENABLED
    const val DEFAULT_WORKSHOP_STEAM_LANGUAGE = "schinese"
    const val DEFAULT_WORKSHOP_AUTO_IMPORT_ENABLED = true
    const val DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED = false
    const val DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX = 1024
    val DEFAULT_STEAM_CLOUD_SAVE_MODE: SteamCloudSaveMode = SteamCloudSaveMode.DEFAULT
    val DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS: Set<String> =
        SteamCloudSyncBlacklist.defaultLocalRelativePaths()
    const val DEFAULT_PREFERRED_UPDATE_MIRROR_ID = "gh_proxy_com"
    const val DEFAULT_FIRST_RUN_SETUP_COMPLETED = false

    const val DEFAULT_JVM_HEAP_MAX_MB = 512
    const val MIN_JVM_HEAP_MAX_MB = 256
    const val MAX_JVM_HEAP_MAX_MB = 2048
    const val JVM_HEAP_STEP_MB = 128
    const val DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED = false
    const val DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED = false
    const val DEFAULT_JACKET_NO_ANO_KO_MOD_COMPAT_ENABLED = true
    const val DEFAULT_FRAGMENT_SHADER_PRECISION_COMPAT_ENABLED = true
    const val DEFAULT_MAIN_MENU_PREVIEW_REUSE_COMPAT_ENABLED = true
    const val DEFAULT_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_ROOM_TRANSITION_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_EVENT_ROOM_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_SHOP_ROOM_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_BASEMOD_SAVE_LOAD_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_RELIC_ENTER_ROOM_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_POWER_ICON_RENDER_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT_ENABLED = true
    const val DEFAULT_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT_ENABLED = true
    val DEFAULT_TOUCHSCREEN_INPUT_MODE: TouchscreenInputMode = TouchscreenInputMode.HYBRID
    val DEFAULT_CARD_PLAY_OPTIMIZATION_MODE: CardPlayOptimizationMode =
        CardPlayOptimizationMode.RELEASE_POP_BACK
    const val DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED = false
    const val DEFAULT_TEXTURE_RESIDENCY_MANAGER_COMPAT_ENABLED = false
    const val DEFAULT_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 2
    const val MIN_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 2
    const val MAX_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 4
    val DEFAULT_GPU_RESOURCE_GUARDIAN_MODE: GpuResourceGuardianMode = GpuResourceGuardianMode.OFF
    const val DEFAULT_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE_ENABLED = false
    const val DEFAULT_HINA_CHARACTER_RENDER_COMPAT_ENABLED = true
    const val DEFAULT_FBO_MANAGER_COMPAT_ENABLED = false
    const val DEFAULT_FBO_IDLE_RECLAIM_COMPAT_ENABLED = false
    const val DEFAULT_FBO_PRESSURE_DOWNSCALE_COMPAT_ENABLED = false
    const val DEFAULT_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES_ENABLED = true
    val DEFAULT_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES_QUALITY: RuntimeTextureAtlasDownscaleQuality =
        RuntimeTextureAtlasDownscaleQuality.P1080
    const val DEFAULT_RUNTIME_DOWNSCALE_SPINE_TEXTURES_ENABLED = false
    const val DEFAULT_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS_ENABLED = true
    const val DEFAULT_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES_ENABLED = false
    const val DEFAULT_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES_ENABLED = false

    const val DEFAULT_RENDER_SCALE = 1.0f
    const val MIN_RENDER_SCALE = 0.10f
    const val MAX_RENDER_SCALE = 1.00f

    const val DEFAULT_TOUCHSCREEN_ENABLED = true
    const val DEFAULT_TOUCH_INDICATOR_ENABLED = true
    private const val DEFAULT_BIGGER_TEXT_ENABLED = false
    const val DEFAULT_GAMEPLAY_FONT_SCALE = 1.00f
    const val MIN_GAMEPLAY_FONT_SCALE = 1.00f
    const val MAX_GAMEPLAY_FONT_SCALE = 2.00f
    const val GAMEPLAY_FONT_SCALE_STEP = 0.05f
    const val DEFAULT_GAMEPLAY_UI_SCALE = 1.00f
    const val MIN_GAMEPLAY_UI_SCALE = 1.00f
    const val MAX_GAMEPLAY_UI_SCALE = 1.50f
    const val GAMEPLAY_UI_SCALE_STEP = 0.05f
    const val DEFAULT_GAMEPLAY_LARGER_UI_ENABLED = false
    const val DEFAULT_PLAYER_NAME = "player"

    private const val GAMEPLAY_SETTINGS_FILE_NAME = "STSGameplaySettings"
    private const val GAMEPLAY_SETTINGS_KEY_BIGGER_TEXT = "Bigger Text"
    private const val GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN = "Touchscreen Enabled"
    private const val GAMEPLAY_SETTINGS_KEY_FONT_SCALE = "Amethyst Font Scale"
    private const val GAMEPLAY_SETTINGS_KEY_UI_SCALE = "Amethyst UI Scale"
    private const val GAMEPLAY_SETTINGS_KEY_LARGER_UI = "Amethyst Larger UI"
    // The runtime compat mod currently treats any value above 1.00 as "use the
    // larger mobile-style layout strategy", so the smallest non-default step is
    // sufficient for the legacy numeric launch arg.
    private const val ENABLED_GAMEPLAY_UI_SCALE = 1.05f
    private const val GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSGameplaySettings"
    private const val PLAYER_SETTINGS_FILE_NAME = "STSPlayer"
    private const val PLAYER_SETTINGS_BACKUP_FILE_NAME = "STSPlayer.backUp"
    private const val PLAYER_SETTINGS_KEY_NAME = "name"
    private const val PLAYER_SETTINGS_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSPlayer"
    private const val PLAYER_SETTINGS_BACKUP_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSPlayer.backUp"
    private const val SAVE_SLOTS_SETTINGS_FILE_NAME = "STSSaveSlots"
    private const val SAVE_SLOTS_SETTINGS_BACKUP_FILE_NAME = "STSSaveSlots.backUp"
    private const val SAVE_SLOTS_SETTINGS_KEY_PROFILE_NAME = "PROFILE_NAME"
    private const val SAVE_SLOTS_SETTINGS_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSSaveSlots"
    private const val SAVE_SLOTS_SETTINGS_BACKUP_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSSaveSlots.backUp"

    fun readBackBehavior(context: Context): BackBehavior {
        val preferences = prefs(context)
        val storedBehavior = BackBehavior.fromPersistedValue(
            preferences.getString(PREF_KEY_BACK_BEHAVIOR, null)
        )
        if (storedBehavior != null) {
            return storedBehavior
        }

        if (preferences.contains(PREF_KEY_BACK_IMMEDIATE_EXIT)) {
            val legacyImmediateExit = preferences.getBoolean(
                PREF_KEY_BACK_IMMEDIATE_EXIT,
                DEFAULT_BACK_IMMEDIATE_EXIT
            )
            return if (legacyImmediateExit) {
                BackBehavior.EXIT_TO_LAUNCHER
            } else {
                BackBehavior.NONE
            }
        }

        return DEFAULT_BACK_BEHAVIOR
    }

    fun saveBackBehavior(context: Context, behavior: BackBehavior) {
        prefs(context).edit {
            putString(PREF_KEY_BACK_BEHAVIOR, behavior.persistedValue)
            // Keep legacy key synchronized for older builds that still read this boolean.
            putBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, behavior == BackBehavior.EXIT_TO_LAUNCHER)
        }
    }

    fun readBackImmediateExit(context: Context): Boolean {
        return readBackBehavior(context) == BackBehavior.EXIT_TO_LAUNCHER
    }

    fun saveBackImmediateExit(context: Context, enabled: Boolean) {
        saveBackBehavior(
            context,
            if (enabled) BackBehavior.EXIT_TO_LAUNCHER else BackBehavior.NONE
        )
    }

    fun readManualDismissBootOverlay(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY,
            DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY
        )
    }

    fun saveManualDismissBootOverlay(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY, enabled)
        }
    }

    fun readBootOverlayStyle(context: Context): BootOverlayStyle {
        val stored = prefs(context).getString(
            PREF_KEY_BOOT_OVERLAY_STYLE,
            DEFAULT_BOOT_OVERLAY_STYLE.persistedValue
        )
        return BootOverlayStyle.fromPersistedValue(stored) ?: DEFAULT_BOOT_OVERLAY_STYLE
    }

    fun saveBootOverlayStyle(context: Context, style: BootOverlayStyle) {
        prefs(context).edit {
            putString(PREF_KEY_BOOT_OVERLAY_STYLE, style.persistedValue)
        }
    }

    fun readBootOverlayAnimation(context: Context): BootOverlayAnimation {
        val stored = prefs(context).getString(
            PREF_KEY_BOOT_OVERLAY_ANIMATION,
            DEFAULT_BOOT_OVERLAY_ANIMATION.persistedValue
        )
        return BootOverlayAnimation.fromPersistedValue(stored) ?: DEFAULT_BOOT_OVERLAY_ANIMATION
    }

    fun saveBootOverlayAnimation(context: Context, animation: BootOverlayAnimation) {
        prefs(context).edit {
            putString(PREF_KEY_BOOT_OVERLAY_ANIMATION, animation.persistedValue)
        }
    }

    fun readBootOverlayImageConfig(context: Context): BootOverlayImageConfig {
        val preferences = prefs(context)
        val mode = BootOverlayImageMode.fromPersistedValue(
            preferences.getString(
                PREF_KEY_BOOT_OVERLAY_IMAGE_MODE,
                DEFAULT_BOOT_OVERLAY_IMAGE_MODE.persistedValue
            )
        ) ?: DEFAULT_BOOT_OVERLAY_IMAGE_MODE
        val startImagePath = preferences
            .getString(PREF_KEY_BOOT_OVERLAY_START_IMAGE_PATH, null)
            ?.takeIf { isReadableFilePath(it) }
        val endImagePath = preferences
            .getString(PREF_KEY_BOOT_OVERLAY_END_IMAGE_PATH, null)
            ?.takeIf { isReadableFilePath(it) }
        val startImageVersion = preferences.getLong(
            PREF_KEY_BOOT_OVERLAY_START_IMAGE_VERSION,
            0L
        )
        val endImageVersion = preferences.getLong(
            PREF_KEY_BOOT_OVERLAY_END_IMAGE_VERSION,
            0L
        )
        return BootOverlayImageConfig(
            mode = mode,
            startImagePath = startImagePath,
            endImagePath = endImagePath,
            startImageVersion = startImageVersion,
            endImageVersion = endImageVersion
        )
    }

    fun saveBootOverlayImageConfig(
        context: Context,
        config: BootOverlayImageConfig
    ) {
        prefs(context).edit {
            putString(PREF_KEY_BOOT_OVERLAY_IMAGE_MODE, config.mode.persistedValue)
            putStringOrRemove(PREF_KEY_BOOT_OVERLAY_START_IMAGE_PATH, config.startImagePath)
            putStringOrRemove(PREF_KEY_BOOT_OVERLAY_END_IMAGE_PATH, config.endImagePath)
            putLong(PREF_KEY_BOOT_OVERLAY_START_IMAGE_VERSION, config.startImageVersion)
            putLong(PREF_KEY_BOOT_OVERLAY_END_IMAGE_VERSION, config.endImageVersion)
        }
    }

    fun clearBootOverlayImageConfig(context: Context) {
        prefs(context).edit {
            remove(PREF_KEY_BOOT_OVERLAY_IMAGE_MODE)
            remove(PREF_KEY_BOOT_OVERLAY_START_IMAGE_PATH)
            remove(PREF_KEY_BOOT_OVERLAY_END_IMAGE_PATH)
            remove(PREF_KEY_BOOT_OVERLAY_START_IMAGE_VERSION)
            remove(PREF_KEY_BOOT_OVERLAY_END_IMAGE_VERSION)
        }
    }

    fun isBasicTutorialNoticeDismissed(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_BASIC_TUTORIAL_NOTICE_DISMISSED, false)
    }

    fun setBasicTutorialNoticeDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_BASIC_TUTORIAL_NOTICE_DISMISSED, dismissed)
        }
    }

    fun isDeveloperSettingsWarningDismissed(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_DEVELOPER_SETTINGS_WARNING_DISMISSED, false)
    }

    fun setDeveloperSettingsWarningDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_DEVELOPER_SETTINGS_WARNING_DISMISSED, dismissed)
        }
    }

    fun isSteamAchievementDebugModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_STEAM_ACHIEVEMENT_DEBUG_MODE, false)
    }

    fun setSteamAchievementDebugModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_STEAM_ACHIEVEMENT_DEBUG_MODE, enabled)
        }
    }

    fun isSteamAchievementSyncEnabled(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_STEAM_ACHIEVEMENT_SYNC_ENABLED,
            false,
        )
    }

    fun setSteamAchievementSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_STEAM_ACHIEVEMENT_SYNC_ENABLED, enabled)
        }
    }

    fun isAchievementUnlockNotificationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED,
            DEFAULT_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED,
        )
    }

    fun setAchievementUnlockNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_ACHIEVEMENT_UNLOCK_NOTIFICATION_ENABLED, enabled)
        }
    }

    fun isLocalTestCloudControlEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_USE_LOCAL_TEST_CLOUD_CONTROL, false)
    }

    fun setLocalTestCloudControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_USE_LOCAL_TEST_CLOUD_CONTROL, enabled)
        }
    }

    fun readLocalTestOnlineServiceBaseUrl(context: Context): String {
        return prefs(context).getString(PREF_KEY_LOCAL_TEST_ONLINE_SERVICE_BASE_URL, null)
            ?: "http://10.126.126.2:3001"
    }

    fun saveLocalTestOnlineServiceBaseUrl(context: Context, value: String) {
        prefs(context).edit { putString(PREF_KEY_LOCAL_TEST_ONLINE_SERVICE_BASE_URL, value.trim()) }
    }

    fun readLocalTestConfigServerUrl(context: Context): String {
        return prefs(context).getString(PREF_KEY_LOCAL_TEST_CONFIG_SERVER_URL, null)
            ?: "udp://10.126.126.2:22020"
    }

    fun saveLocalTestConfigServerUrl(context: Context, value: String) {
        prefs(context).edit { putString(PREF_KEY_LOCAL_TEST_CONFIG_SERVER_URL, value.trim()) }
    }

    fun readLocalTestEntryNodeUrl(context: Context): String {
        return prefs(context).getString(PREF_KEY_LOCAL_TEST_ENTRY_NODE_URL, null)
            ?: "tcp://10.126.126.2:11010"
    }

    fun saveLocalTestEntryNodeUrl(context: Context, value: String) {
        prefs(context).edit { putString(PREF_KEY_LOCAL_TEST_ENTRY_NODE_URL, value.trim()) }
    }

    fun readSpecialKeyInputMode(context: Context): SpecialKeyInputMode {
        val preferences = prefs(context)
        val stored = preferences.getString(PREF_KEY_SPECIAL_KEY_INPUT_MODE, null)
        SpecialKeyInputMode.fromPersistedValue(stored)?.let { return it }

        if (preferences.contains(PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW)) {
            return if (preferences.getBoolean(
                    PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW,
                    true
                )
            ) {
                SpecialKeyInputMode.BUILT_IN_MOD
            } else {
                SpecialKeyInputMode.DISABLED
            }
        }

        return DEFAULT_SPECIAL_KEY_INPUT_MODE
    }

    fun saveSpecialKeyInputMode(context: Context, mode: SpecialKeyInputMode) {
        prefs(context).edit {
            putString(PREF_KEY_SPECIAL_KEY_INPUT_MODE, mode.persistedValue)
            putBoolean(
                PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW,
                mode == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW
            )
        }
    }

    fun readShowFloatingMouseWindow(context: Context): Boolean {
        return readSpecialKeyInputMode(context) == SpecialKeyInputMode.LEGACY_FLOATING_WINDOW
    }

    fun saveShowFloatingMouseWindow(context: Context, enabled: Boolean) {
        saveSpecialKeyInputMode(
            context,
            if (enabled) {
                SpecialKeyInputMode.LEGACY_FLOATING_WINDOW
            } else {
                SpecialKeyInputMode.DISABLED
            }
        )
    }

    fun readTouchMouseInteractionMode(context: Context): TouchMouseInteractionMode {
        val preferences = prefs(context)
        val stored = preferences.getString(
            PREF_KEY_TOUCH_MOUSE_INTERACTION_MODE,
            null
        )
        TouchMouseInteractionMode.fromPersistedValue(stored)?.let { return it }

        if (preferences.contains(PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION)) {
            return if (preferences.getBoolean(
                    PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION,
                    true
                )
            ) {
                TouchMouseInteractionMode.OPEN_MENU_ON_TAP
            } else {
                TouchMouseInteractionMode.TOGGLE_BUTTON_ON_TAP
            }
        }

        return DEFAULT_TOUCH_MOUSE_INTERACTION_MODE
    }

    fun saveTouchMouseInteractionMode(context: Context, mode: TouchMouseInteractionMode) {
        prefs(context).edit {
            putString(PREF_KEY_TOUCH_MOUSE_INTERACTION_MODE, mode.persistedValue)
        }
    }

    fun isBuiltInSoftKeyboardEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_BUILT_IN_SOFT_KEYBOARD_ENABLED,
            DEFAULT_BUILT_IN_SOFT_KEYBOARD_ENABLED
        )
    }

    fun setBuiltInSoftKeyboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_BUILT_IN_SOFT_KEYBOARD_ENABLED, enabled)
        }
    }

    fun readFloatingToolButtons(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(PREF_KEY_FLOATING_TOOL_BUTTONS, null)
            ?: return DEFAULT_FLOATING_TOOL_BUTTONS
        return stored.intersect(FLOATING_TOOL_BUTTON_IDS.toSet())
    }

    fun saveFloatingToolButtons(context: Context, buttons: Set<String>) {
        prefs(context).edit {
            putStringSet(
                PREF_KEY_FLOATING_TOOL_BUTTONS,
                buttons.intersect(FLOATING_TOOL_BUTTON_IDS.toSet()).toSet()
            )
        }
    }

    fun isHapticFeedbackEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_HAPTIC_FEEDBACK_ENABLED,
            DEFAULT_HAPTIC_FEEDBACK_ENABLED
        )
    }

    fun setHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_HAPTIC_FEEDBACK_ENABLED, enabled)
        }
    }

    fun readAutoSwitchLeftAfterRightClick(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK,
            DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK
        )
    }

    fun saveAutoSwitchLeftAfterRightClick(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK, enabled)
        }
    }

    fun readTouchDoubleClickAsRightClick(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK,
            DEFAULT_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK
        )
    }

    fun saveTouchDoubleClickAsRightClick(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TOUCH_DOUBLE_CLICK_AS_RIGHT_CLICK, enabled)
        }
    }

    fun readIgnoreLongPressRightClickWhilePlayingCard(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD,
            DEFAULT_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD
        )
    }

    fun saveIgnoreLongPressRightClickWhilePlayingCard(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_IGNORE_LONG_PRESS_RIGHT_CLICK_WHILE_PLAYING_CARD, enabled)
        }
    }

    fun readRenderSurfaceBackend(context: Context): RenderSurfaceBackend {
        val stored = prefs(context).getString(
            PREF_KEY_RENDER_SURFACE_BACKEND,
            DEFAULT_RENDER_SURFACE_BACKEND.persistedValue
        )
        return RenderSurfaceBackend.fromPersistedValue(stored) ?: DEFAULT_RENDER_SURFACE_BACKEND
    }

    fun saveRenderSurfaceBackend(context: Context, backend: RenderSurfaceBackend) {
        prefs(context).edit {
            putString(PREF_KEY_RENDER_SURFACE_BACKEND, backend.persistedValue)
        }
    }

    fun readRendererSelectionMode(context: Context): RendererSelectionMode {
        val stored = prefs(context).getString(
            PREF_KEY_RENDERER_SELECTION_MODE,
            DEFAULT_RENDERER_SELECTION_MODE.persistedValue
        )
        return RendererSelectionMode.fromPersistedValue(stored) ?: DEFAULT_RENDERER_SELECTION_MODE
    }

    fun saveRendererSelectionMode(context: Context, mode: RendererSelectionMode) {
        prefs(context).edit {
            putString(PREF_KEY_RENDERER_SELECTION_MODE, mode.persistedValue)
        }
    }

    fun readManualRendererBackend(context: Context): RendererBackend {
        val stored = prefs(context).getString(
            PREF_KEY_MANUAL_RENDERER_BACKEND,
            DEFAULT_MANUAL_RENDERER_BACKEND.rendererId()
        )
        return RendererBackend.fromRendererId(stored) ?: DEFAULT_MANUAL_RENDERER_BACKEND
    }

    fun saveManualRendererBackend(context: Context, backend: RendererBackend) {
        prefs(context).edit {
            putString(PREF_KEY_MANUAL_RENDERER_BACKEND, backend.rendererId())
        }
    }

    fun readMobileGluesAnglePolicy(context: Context): MobileGluesAnglePolicy {
        val stored = prefs(context).getInt(
            PREF_KEY_MOBILEGLUES_ANGLE_POLICY,
            DEFAULT_MOBILEGLUES_ANGLE_POLICY.persistedValue
        )
        return MobileGluesAnglePolicy.fromPersistedValue(stored)
            ?: DEFAULT_MOBILEGLUES_ANGLE_POLICY
    }

    fun saveMobileGluesAnglePolicy(context: Context, policy: MobileGluesAnglePolicy) {
        prefs(context).edit {
            putInt(PREF_KEY_MOBILEGLUES_ANGLE_POLICY, policy.persistedValue)
        }
    }

    fun readMobileGluesSettings(context: Context): MobileGluesSettings {
        val preferences = prefs(context)
        return MobileGluesSettings(
            anglePolicy = readMobileGluesAnglePolicy(context),
            noErrorPolicy = MobileGluesNoErrorPolicy.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_NO_ERROR_POLICY,
                    DEFAULT_MOBILEGLUES_NO_ERROR_POLICY.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_NO_ERROR_POLICY,
            multidrawMode = MobileGluesMultidrawMode.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_MULTIDRAW_MODE,
                    DEFAULT_MOBILEGLUES_MULTIDRAW_MODE.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_MULTIDRAW_MODE,
            extComputeShaderEnabled = preferences.getBoolean(
                PREF_KEY_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
                DEFAULT_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED
            ),
            extTimerQueryEnabled = preferences.getBoolean(
                PREF_KEY_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
                DEFAULT_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED
            ),
            extDirectStateAccessEnabled = preferences.getBoolean(
                PREF_KEY_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
                DEFAULT_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED
            ),
            glslCacheSizePreset = MobileGluesGlslCacheSizePreset.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_GLSL_CACHE_SIZE_MB,
                    DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_GLSL_CACHE_SIZE_PRESET,
            angleDepthClearFixMode = MobileGluesAngleDepthClearFixMode.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
                    DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
            customGlVersion = MobileGluesCustomGlVersion.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_CUSTOM_GL_VERSION,
                    DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_CUSTOM_GL_VERSION,
            fsr1QualityPreset = MobileGluesFsr1QualityPreset.fromPersistedValue(
                preferences.getInt(
                    PREF_KEY_MOBILEGLUES_FSR1_QUALITY_PRESET,
                    DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET.persistedValue
                )
            ) ?: DEFAULT_MOBILEGLUES_FSR1_QUALITY_PRESET,
        )
    }

    fun saveMobileGluesSettings(context: Context, settings: MobileGluesSettings) {
        prefs(context).edit {
            putInt(PREF_KEY_MOBILEGLUES_ANGLE_POLICY, settings.anglePolicy.persistedValue)
            putInt(PREF_KEY_MOBILEGLUES_NO_ERROR_POLICY, settings.noErrorPolicy.persistedValue)
            putInt(PREF_KEY_MOBILEGLUES_MULTIDRAW_MODE, settings.multidrawMode.persistedValue)
            putBoolean(
                PREF_KEY_MOBILEGLUES_EXT_COMPUTE_SHADER_ENABLED,
                settings.extComputeShaderEnabled
            )
            remove(LEGACY_PREF_KEY_MOBILEGLUES_EXT_GL43_ENABLED)
            putBoolean(
                PREF_KEY_MOBILEGLUES_EXT_TIMER_QUERY_ENABLED,
                settings.extTimerQueryEnabled
            )
            putBoolean(
                PREF_KEY_MOBILEGLUES_EXT_DIRECT_STATE_ACCESS_ENABLED,
                settings.extDirectStateAccessEnabled
            )
            putInt(
                PREF_KEY_MOBILEGLUES_GLSL_CACHE_SIZE_MB,
                settings.glslCacheSizePreset.persistedValue
            )
            putInt(
                PREF_KEY_MOBILEGLUES_ANGLE_DEPTH_CLEAR_FIX_MODE,
                settings.angleDepthClearFixMode.persistedValue
            )
            putInt(
                PREF_KEY_MOBILEGLUES_CUSTOM_GL_VERSION,
                settings.customGlVersion.persistedValue
            )
            putInt(
                PREF_KEY_MOBILEGLUES_FSR1_QUALITY_PRESET,
                settings.fsr1QualityPreset.persistedValue
            )
        }
    }

    fun readThemeMode(context: Context): LauncherThemeMode {
        val stored = prefs(context).getString(
            PREF_KEY_THEME_MODE,
            DEFAULT_THEME_MODE.persistedValue
        )
        return LauncherThemeMode.fromPersistedValue(stored) ?: DEFAULT_THEME_MODE
    }

    fun saveThemeMode(context: Context, themeMode: LauncherThemeMode) {
        prefs(context).edit {
            putString(PREF_KEY_THEME_MODE, themeMode.persistedValue)
        }
    }

    fun readThemeColor(context: Context): LauncherThemeColor {
        val stored = prefs(context).getString(
            PREF_KEY_THEME_COLOR,
            DEFAULT_THEME_COLOR.persistedValue
        )
        return LauncherThemeColor.fromPersistedValue(stored) ?: DEFAULT_THEME_COLOR
    }

    fun saveThemeColor(context: Context, themeColor: LauncherThemeColor) {
        prefs(context).edit {
            putString(PREF_KEY_THEME_COLOR, themeColor.persistedValue)
        }
    }

    fun readLauncherIconMode(context: Context): LauncherIconMode {
        val stored = prefs(context).getString(
            PREF_KEY_LAUNCHER_ICON_MODE,
            DEFAULT_LAUNCHER_ICON_MODE.persistedValue
        )
        return LauncherIconMode.fromPersistedValue(stored) ?: DEFAULT_LAUNCHER_ICON_MODE
    }

    fun saveLauncherIconMode(context: Context, iconMode: LauncherIconMode) {
        prefs(context).edit {
            putString(PREF_KEY_LAUNCHER_ICON_MODE, iconMode.persistedValue)
        }
    }

    fun readChromeBackgroundOpacity(context: Context): Float {
        val preferences = prefs(context)
        val stored = if (preferences.contains(PREF_KEY_CHROME_BACKGROUND_OPACITY)) {
            preferences.getFloat(
                PREF_KEY_CHROME_BACKGROUND_OPACITY,
                DEFAULT_CHROME_BACKGROUND_OPACITY
            )
        } else {
            preferences.getFloat(
                LEGACY_PREF_KEY_HOME_CHROME_TRANSPARENCY,
                DEFAULT_CHROME_BACKGROUND_OPACITY
            )
        }
        return normalizeChromeBackgroundOpacity(stored)
    }

    fun saveChromeBackgroundOpacity(context: Context, opacity: Float) {
        prefs(context).edit {
            putFloat(
                PREF_KEY_CHROME_BACKGROUND_OPACITY,
                normalizeChromeBackgroundOpacity(opacity)
            )
        }
    }

    fun normalizeChromeBackgroundOpacity(opacity: Float): Float {
        return if (opacity.isFinite()) {
            opacity.coerceIn(MIN_CHROME_BACKGROUND_OPACITY, MAX_CHROME_BACKGROUND_OPACITY)
        } else {
            DEFAULT_CHROME_BACKGROUND_OPACITY
        }
    }

    fun readShowModFileName(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_SHOW_MOD_FILE_NAME,
            DEFAULT_SHOW_MOD_FILE_NAME
        )
    }

    fun saveShowModFileName(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_SHOW_MOD_FILE_NAME, enabled)
        }
    }

    fun readMobileHudEnabled(context: Context): Boolean {
        // Keep the mobile HUD disabled even if an older build persisted it as enabled.
        return false
    }

    fun saveMobileHudEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_MOBILE_HUD_ENABLED, false)
        }
    }

    fun readCompendiumUpgradeTouchFixEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED,
            DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED
        )
    }

    fun saveCompendiumUpgradeTouchFixEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED, enabled)
        }
    }

    fun isTogetherInSpireRouteLockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED,
            DEFAULT_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED
        )
    }

    fun setTogetherInSpireRouteLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TOGETHER_IN_SPIRE_ROUTE_LOCK_ENABLED, enabled)
        }
    }

    fun isTogetherInSpireEasyTierAutofillEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED,
            DEFAULT_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED
        )
    }

    fun setTogetherInSpireEasyTierAutofillEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TOGETHER_IN_SPIRE_EASYTIER_AUTOFILL_ENABLED, enabled)
        }
    }

    fun readVirtualResolutionMode(context: Context): VirtualResolutionMode {
        val stored = prefs(context).getString(
            PREF_KEY_VIRTUAL_RESOLUTION_MODE,
            DEFAULT_VIRTUAL_RESOLUTION_MODE.persistedValue
        )
        return VirtualResolutionMode.fromPersistedValue(stored) ?: DEFAULT_VIRTUAL_RESOLUTION_MODE
    }

    fun saveVirtualResolutionMode(context: Context, mode: VirtualResolutionMode) {
        prefs(context).edit {
            putString(PREF_KEY_VIRTUAL_RESOLUTION_MODE, mode.persistedValue)
        }
    }

    fun isDisplayCutoutAvoidanceEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_AVOID_DISPLAY_CUTOUT,
            DEFAULT_AVOID_DISPLAY_CUTOUT
        )
    }

    fun setDisplayCutoutAvoidanceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_AVOID_DISPLAY_CUTOUT, enabled)
        }
    }

    fun isScreenBottomCropEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_CROP_SCREEN_BOTTOM,
            DEFAULT_CROP_SCREEN_BOTTOM
        )
    }

    fun setScreenBottomCropEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_CROP_SCREEN_BOTTOM, enabled)
        }
    }

    fun isRamSaverEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_RAM_SAVER_ENABLED,
            DEFAULT_RAM_SAVER_ENABLED
        )
    }

    fun setRamSaverEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_RAM_SAVER_ENABLED, enabled)
        }
    }

    fun isMtsPatchCacheEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_MTS_PATCH_CACHE_ENABLED,
            DEFAULT_MTS_PATCH_CACHE_ENABLED
        )
    }

    fun setMtsPatchCacheEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_MTS_PATCH_CACHE_ENABLED, enabled)
        }
    }

    fun normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes: Int): Int {
        return if (KEEP_SCREEN_ON_TIMEOUT_MINUTE_OPTIONS.contains(timeoutMinutes)) {
            timeoutMinutes
        } else {
            DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES
        }
    }

    fun readKeepScreenOnTimeoutMinutes(context: Context): Int {
        return normalizeKeepScreenOnTimeoutMinutes(
            prefs(context).getInt(
                PREF_KEY_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
                DEFAULT_KEEP_SCREEN_ON_TIMEOUT_MINUTES
            )
        )
    }

    fun saveKeepScreenOnTimeoutMinutes(context: Context, timeoutMinutes: Int) {
        prefs(context).edit {
            putInt(
                PREF_KEY_KEEP_SCREEN_ON_TIMEOUT_MINUTES,
                normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes)
            )
        }
    }

    fun keepScreenOnTimeoutMs(timeoutMinutes: Int): Long? {
        val normalized = normalizeKeepScreenOnTimeoutMinutes(timeoutMinutes)
        return if (normalized == KEEP_SCREEN_ON_TIMEOUT_ALWAYS_MINUTES) {
            null
        } else {
            normalized * 60_000L
        }
    }

    fun normalizeTargetFps(targetFps: Int): Int {
        return if (TARGET_FPS_OPTIONS.contains(targetFps)) {
            targetFps
        } else {
            DEFAULT_TARGET_FPS
        }
    }

    fun readTargetFps(context: Context): Int {
        val preferences = prefs(context)
        if (preferences.contains(PREF_KEY_TARGET_FPS)) {
            return normalizeTargetFps(
                preferences.getInt(PREF_KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
            )
        }
        val migrated = normalizeTargetFps(DisplayConfigSync.readTargetFpsLimit(context))
        preferences.edit {
            putInt(PREF_KEY_TARGET_FPS, migrated)
        }
        return migrated
    }

    fun saveTargetFps(context: Context, targetFps: Int) {
        val normalizedTargetFps = normalizeTargetFps(targetFps)
        prefs(context).edit {
            putInt(PREF_KEY_TARGET_FPS, normalizedTargetFps)
        }
        DisplayConfigSync.saveTargetFpsLimit(context, normalizedTargetFps)
    }

    fun normalizeJvmHeapMaxMb(heapMaxMb: Int): Int {
        val clamped = heapMaxMb.coerceIn(MIN_JVM_HEAP_MAX_MB, MAX_JVM_HEAP_MAX_MB)
        val offset = clamped - MIN_JVM_HEAP_MAX_MB
        val snappedStepCount = (offset / JVM_HEAP_STEP_MB.toFloat()).roundToInt()
        val snapped = MIN_JVM_HEAP_MAX_MB + (snappedStepCount * JVM_HEAP_STEP_MB)
        return snapped.coerceIn(MIN_JVM_HEAP_MAX_MB, MAX_JVM_HEAP_MAX_MB)
    }

    fun resolveJvmHeapStartMb(heapMaxMb: Int): Int {
        // Xms follows Xmx so G1 commits the full heap at startup.
        // The old coerceAtMost(512) kept Xms at 512 MB regardless of the
        // user setting, causing G1 to never expand past 512 MB committed
        // even when Xmx was set to 1024 MB+.
        return normalizeJvmHeapMaxMb(heapMaxMb)
    }

    fun readJvmHeapMaxMb(context: Context): Int {
        val stored = prefs(context).getInt(PREF_KEY_JVM_HEAP_MAX_MB, DEFAULT_JVM_HEAP_MAX_MB)
        return normalizeJvmHeapMaxMb(stored)
    }

    fun readJvmHeapStartMb(context: Context): Int {
        return resolveJvmHeapStartMb(readJvmHeapMaxMb(context))
    }

    fun saveJvmHeapMaxMb(context: Context, heapMaxMb: Int) {
        prefs(context).edit {
            putInt(PREF_KEY_JVM_HEAP_MAX_MB, normalizeJvmHeapMaxMb(heapMaxMb))
        }
    }

    fun isGamePerformanceOverlayEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_SHOW_GAME_PERFORMANCE_OVERLAY,
            DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY
        )
    }

    fun setGamePerformanceOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_SHOW_GAME_PERFORMANCE_OVERLAY, enabled)
        }
    }

    fun isGamePerformanceDeepDiagnosticsEnabled(context: Context): Boolean {
        return resolveGamePerformanceDeepDiagnosticsEnabled(
            gpuResourceDiagEnabled = isGpuResourceDiagEnabled(context)
        )
    }

    fun resolveGamePerformanceDeepDiagnosticsEnabled(
        gpuResourceDiagEnabled: Boolean
    ): Boolean {
        return gpuResourceDiagEnabled
    }

    fun isSustainedPerformanceModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_SUSTAINED_PERFORMANCE_MODE_ENABLED,
            DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED
        )
    }

    fun setSustainedPerformanceModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_SUSTAINED_PERFORMANCE_MODE_ENABLED, enabled)
        }
    }

    fun isJvmCompressedPointersEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_JVM_COMPRESSED_POINTERS_ENABLED,
            DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED
        )
    }

    fun setJvmCompressedPointersEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_JVM_COMPRESSED_POINTERS_ENABLED, enabled)
        }
    }

    fun isJvmStringDeduplicationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_JVM_STRING_DEDUPLICATION_ENABLED,
            DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED
        )
    }

    fun setJvmStringDeduplicationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_JVM_STRING_DEDUPLICATION_ENABLED, enabled)
        }
    }

    fun isGlobalAtlasFilterCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT, true)
    }

    fun setGlobalAtlasFilterCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT, enabled)
        }
    }

    fun isModManifestRootCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_MOD_MANIFEST_ROOT_COMPAT, true)
    }

    fun setModManifestRootCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_MOD_MANIFEST_ROOT_COMPAT, enabled)
        }
    }

    fun isFrierenModCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_FRIEREN_MOD_COMPAT, true)
    }

    fun setFrierenModCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FRIEREN_MOD_COMPAT, enabled)
        }
    }

    fun isDownfallImportCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_DOWNFALL_IMPORT_COMPAT, true)
    }

    fun setDownfallImportCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_DOWNFALL_IMPORT_COMPAT, enabled)
        }
    }

    fun isVupShionModCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_VUPSHION_MOD_COMPAT, true)
    }

    fun setVupShionModCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_VUPSHION_MOD_COMPAT, enabled)
        }
    }

    fun isChaofanModCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_CHAOFANMOD_COMPAT, true)
    }

    fun setChaofanModCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_CHAOFANMOD_COMPAT, enabled)
        }
    }

    fun isJacketNoAnoKoModCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_JACKET_NO_ANO_KO_MOD_COMPAT,
            DEFAULT_JACKET_NO_ANO_KO_MOD_COMPAT_ENABLED
        )
    }

    fun setJacketNoAnoKoModCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_JACKET_NO_ANO_KO_MOD_COMPAT, enabled)
        }
    }

    fun isFragmentShaderPrecisionCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_FRAGMENT_SHADER_PRECISION_COMPAT,
            DEFAULT_FRAGMENT_SHADER_PRECISION_COMPAT_ENABLED
        )
    }

    fun setFragmentShaderPrecisionCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FRAGMENT_SHADER_PRECISION_COMPAT, enabled)
        }
    }

    fun isRuntimeTextureCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_RUNTIME_TEXTURE_COMPAT, false)
    }

    fun setRuntimeTextureCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_RUNTIME_TEXTURE_COMPAT, enabled)
        }
    }

    fun isMainMenuPreviewReuseCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_MAIN_MENU_PREVIEW_REUSE_COMPAT,
            DEFAULT_MAIN_MENU_PREVIEW_REUSE_COMPAT_ENABLED
        )
    }

    fun setMainMenuPreviewReuseCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_MAIN_MENU_PREVIEW_REUSE_COMPAT, enabled)
        }
    }

    fun isRoomContextHandLayoutRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT,
            DEFAULT_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT_ENABLED
        )
    }

    fun setRoomContextHandLayoutRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_ROOM_CONTEXT_HAND_LAYOUT_RESCUE_COMPAT, enabled)
        }
    }

    fun isRoomTransitionRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_ROOM_TRANSITION_RESCUE_COMPAT,
            DEFAULT_ROOM_TRANSITION_RESCUE_COMPAT_ENABLED
        )
    }

    fun setRoomTransitionRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_ROOM_TRANSITION_RESCUE_COMPAT, enabled)
        }
    }

    fun isEventRoomRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_EVENT_ROOM_RESCUE_COMPAT,
            DEFAULT_EVENT_ROOM_RESCUE_COMPAT_ENABLED
        )
    }

    fun setEventRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_EVENT_ROOM_RESCUE_COMPAT, enabled)
        }
    }

    fun isShopRoomRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_SHOP_ROOM_RESCUE_COMPAT,
            DEFAULT_SHOP_ROOM_RESCUE_COMPAT_ENABLED
        )
    }

    fun setShopRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_SHOP_ROOM_RESCUE_COMPAT, enabled)
        }
    }

    fun isBaseModSaveLoadRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_BASEMOD_SAVE_LOAD_RESCUE_COMPAT,
            DEFAULT_BASEMOD_SAVE_LOAD_RESCUE_COMPAT_ENABLED
        )
    }

    fun setBaseModSaveLoadRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_BASEMOD_SAVE_LOAD_RESCUE_COMPAT, enabled)
        }
    }

    fun isRelicEnterRoomRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_RELIC_ENTER_ROOM_RESCUE_COMPAT,
            DEFAULT_RELIC_ENTER_ROOM_RESCUE_COMPAT_ENABLED
        )
    }

    fun setRelicEnterRoomRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_RELIC_ENTER_ROOM_RESCUE_COMPAT, enabled)
        }
    }

    fun isDungeonRenderRoomContextRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT,
            DEFAULT_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT_ENABLED
        )
    }

    fun setDungeonRenderRoomContextRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_DUNGEON_RENDER_ROOM_CONTEXT_RESCUE_COMPAT, enabled)
        }
    }

    fun isPowerIconRenderRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_POWER_ICON_RENDER_RESCUE_COMPAT,
            DEFAULT_POWER_ICON_RENDER_RESCUE_COMPAT_ENABLED
        )
    }

    fun setPowerIconRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_POWER_ICON_RENDER_RESCUE_COMPAT, enabled)
        }
    }

    fun isBaseModCustomMonsterRenderRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT,
            DEFAULT_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT_ENABLED
        )
    }

    fun setBaseModCustomMonsterRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_BASEMOD_CUSTOM_MONSTER_RENDER_RESCUE_COMPAT, enabled)
        }
    }

    fun isNonCombatPlayerRenderRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT,
            DEFAULT_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT_ENABLED
        )
    }

    fun setNonCombatPlayerRenderRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_NON_COMBAT_PLAYER_RENDER_RESCUE_COMPAT, enabled)
        }
    }

    fun isCardTooltipKeywordRescueCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT,
            DEFAULT_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT_ENABLED
        )
    }

    fun setCardTooltipKeywordRescueCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_CARD_TOOLTIP_KEYWORD_RESCUE_COMPAT, enabled)
        }
    }

    fun isNativeTouchscreenAllowlistCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT,
            DEFAULT_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT_ENABLED
        )
    }

    fun setNativeTouchscreenAllowlistCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_NATIVE_TOUCHSCREEN_ALLOWLIST_COMPAT, enabled)
        }
    }

    fun isLargeTextureDownscaleCompatEnabled(context: Context): Boolean {
        return isLegacyGpuResourceModeEnabled(context) ||
            isGpuResourceGuardianPressureDownscaleActive(context)
    }

    fun setLargeTextureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LARGE_TEXTURE_DOWNSCALE_COMPAT, false)
        }
    }

    fun isTextureResidencyManagerCompatEnabled(context: Context): Boolean {
        return isLegacyGpuResourceModeEnabled(context)
    }

    fun setTextureResidencyManagerCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TEXTURE_RESIDENCY_MANAGER_COMPAT, false)
        }
    }

    fun readTexturePressureDownscaleDivisor(context: Context): Int {
        return prefs(context).getInt(
            PREF_KEY_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR,
            DEFAULT_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR
        ).coerceIn(
            MIN_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR,
            MAX_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR
        )
    }

    fun saveTexturePressureDownscaleDivisor(context: Context, divisor: Int) {
        prefs(context).edit {
            putInt(
                PREF_KEY_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR,
                divisor.coerceIn(
                    MIN_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR,
                    MAX_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR
                )
            )
        }
    }

    fun readGpuResourceGuardianMode(context: Context): GpuResourceGuardianMode {
        val persisted = prefs(context).getString(PREF_KEY_GPU_RESOURCE_GUARDIAN_MODE, null)
        if (persisted == LEGACY_GPU_RESOURCE_GUARDIAN_DIAGNOSTIC_MODE) {
            return GpuResourceGuardianMode.SAFE
        }
        return GpuResourceGuardianMode.fromPersistedValue(persisted)
            ?: resolveDefaultGpuResourceGuardianMode(context)
    }

    fun saveGpuResourceGuardianMode(context: Context, mode: GpuResourceGuardianMode) {
        prefs(context).edit {
            putString(PREF_KEY_GPU_RESOURCE_GUARDIAN_MODE, mode.persistedValue)
        }
    }

    fun resetGpuResourceGuardianMode(context: Context) {
        prefs(context).edit {
            remove(PREF_KEY_GPU_RESOURCE_GUARDIAN_MODE)
        }
    }

    fun isGpuResourceGuardianPressureDownscaleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE,
            DEFAULT_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE_ENABLED
        )
    }

    fun setGpuResourceGuardianPressureDownscaleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_GPU_RESOURCE_GUARDIAN_PRESSURE_DOWNSCALE, enabled)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveDefaultGpuResourceGuardianMode(context: Context): GpuResourceGuardianMode {
        return DEFAULT_GPU_RESOURCE_GUARDIAN_MODE
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveDefaultGpuResourceGuardianMode(totalMemoryBytes: Long): GpuResourceGuardianMode {
        return DEFAULT_GPU_RESOURCE_GUARDIAN_MODE
    }

    fun isForceLinearMipmapFilterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, true)
    }

    fun setForceLinearMipmapFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, enabled)
        }
    }

    fun isHinaCharacterRenderCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_HINA_CHARACTER_RENDER_COMPAT,
            DEFAULT_HINA_CHARACTER_RENDER_COMPAT_ENABLED
        )
    }

    fun setHinaCharacterRenderCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_HINA_CHARACTER_RENDER_COMPAT, enabled)
        }
    }

    fun isNonRenderableFboFormatCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_NON_RENDERABLE_FBO_FORMAT_COMPAT, true)
    }

    fun setNonRenderableFboFormatCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_NON_RENDERABLE_FBO_FORMAT_COMPAT, enabled)
        }
    }

    fun isFboManagerCompatEnabled(context: Context): Boolean {
        return isLegacyGpuResourceModeEnabled(context)
    }

    fun setFboManagerCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FBO_MANAGER_COMPAT, false)
        }
    }

    fun isFboIdleReclaimCompatEnabled(context: Context): Boolean {
        return isLegacyGpuResourceModeEnabled(context)
    }

    fun setFboIdleReclaimCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FBO_IDLE_RECLAIM_COMPAT, false)
        }
    }

    fun isFboPressureDownscaleCompatEnabled(context: Context): Boolean {
        return isLegacyGpuResourceModeEnabled(context) ||
            isGpuResourceGuardianPressureDownscaleActive(context)
    }

    fun setFboPressureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FBO_PRESSURE_DOWNSCALE_COMPAT, false)
        }
    }

    fun readRuntimeDownscaleMaterialPolicy(context: Context): RuntimeDownscaleMaterialPolicy {
        val prefs = prefs(context)
        return RuntimeDownscaleMaterialPolicy(
            ordinaryTextures = prefs.getBoolean(
                PREF_KEY_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES,
                DEFAULT_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES_ENABLED
            ),
            textureAtlasPages = readRuntimeDownscaleTextureAtlasPagesQuality(prefs),
            spineTextures = prefs.getBoolean(
                PREF_KEY_RUNTIME_DOWNSCALE_SPINE_TEXTURES,
                DEFAULT_RUNTIME_DOWNSCALE_SPINE_TEXTURES_ENABLED
            ),
            offscreenFrameBuffers = prefs.getBoolean(
                PREF_KEY_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS,
                DEFAULT_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS_ENABLED
            )
        )
    }

    fun setRuntimeDownscaleOrdinaryTexturesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY_RUNTIME_DOWNSCALE_ORDINARY_TEXTURES, enabled) }
    }

    private fun readRuntimeDownscaleTextureAtlasPagesQuality(
        prefs: SharedPreferences
    ): RuntimeTextureAtlasDownscaleQuality {
        return when (val rawValue = prefs.all[PREF_KEY_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES]) {
            is String -> RuntimeTextureAtlasDownscaleQuality.fromPrefValue(rawValue)
            is Boolean -> if (rawValue) {
                DEFAULT_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES_QUALITY
            } else {
                RuntimeTextureAtlasDownscaleQuality.NATIVE
            }
            else -> DEFAULT_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES_QUALITY
        }
    }

    fun setRuntimeDownscaleTextureAtlasPagesQuality(
        context: Context,
        quality: RuntimeTextureAtlasDownscaleQuality
    ) {
        prefs(context).edit {
            putString(PREF_KEY_RUNTIME_DOWNSCALE_TEXTURE_ATLAS_PAGES, quality.prefValue)
        }
    }

    fun setRuntimeDownscaleSpineTexturesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY_RUNTIME_DOWNSCALE_SPINE_TEXTURES, enabled) }
    }

    fun setRuntimeDownscaleOffscreenFrameBuffersEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY_RUNTIME_DOWNSCALE_OFFSCREEN_FRAME_BUFFERS, enabled) }
    }

    fun readImportDownscaleMaterialPolicy(context: Context): ImportDownscaleMaterialPolicy {
        val prefs = prefs(context)
        return ImportDownscaleMaterialPolicy(
            spineAtlasPages = prefs.getBoolean(
                PREF_KEY_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES,
                DEFAULT_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES_ENABLED
            ),
            ordinaryAtlasPages = prefs.getBoolean(
                PREF_KEY_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES,
                DEFAULT_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES_ENABLED
            )
        )
    }

    fun setImportDownscaleSpineAtlasPagesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY_IMPORT_DOWNSCALE_SPINE_ATLAS_PAGES, enabled) }
    }

    fun setImportDownscaleOrdinaryAtlasPagesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY_IMPORT_DOWNSCALE_ORDINARY_ATLAS_PAGES, enabled) }
    }

    private fun isLegacyGpuResourceModeEnabled(context: Context): Boolean {
        return readGpuResourceGuardianMode(context) == GpuResourceGuardianMode.LEGACY
    }

    private fun isGpuResourceGuardianPressureDownscaleActive(context: Context): Boolean {
        val mode = readGpuResourceGuardianMode(context)
        return mode != GpuResourceGuardianMode.OFF &&
            mode != GpuResourceGuardianMode.LEGACY &&
            isGpuResourceGuardianPressureDownscaleEnabled(context)
    }

    fun isLwjglDebugEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_LWJGL_DEBUG, DEFAULT_LWJGL_DEBUG)
    }

    fun setLwjglDebugEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LWJGL_DEBUG, enabled)
        }
    }

    fun isPreloadAllJreLibrariesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_PRELOAD_ALL_JRE_LIBRARIES,
            DEFAULT_PRELOAD_ALL_JRE_LIBRARIES
        )
    }

    fun setPreloadAllJreLibrariesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_PRELOAD_ALL_JRE_LIBRARIES, enabled)
        }
    }

    fun isLogcatCaptureEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_LOGCAT_CAPTURE_ENABLED,
            DEFAULT_LOGCAT_CAPTURE_ENABLED
        )
    }

    fun setLogcatCaptureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LOGCAT_CAPTURE_ENABLED, enabled)
        }
    }

    fun isLauncherLogcatCaptureEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_LAUNCHER_LOGCAT_CAPTURE_ENABLED,
            DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED
        )
    }

    fun setLauncherLogcatCaptureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LAUNCHER_LOGCAT_CAPTURE_ENABLED, enabled)
        }
    }

    fun isJvmLogcatMirrorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_JVM_LOGCAT_MIRROR_ENABLED,
            DEFAULT_JVM_LOGCAT_MIRROR_ENABLED
        )
    }

    fun setJvmLogcatMirrorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_JVM_LOGCAT_MIRROR_ENABLED, enabled)
        }
    }

    fun isGpuResourceDiagEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        val defaultValue = if (
            prefs.getString(PREF_KEY_GPU_RESOURCE_GUARDIAN_MODE, null) ==
            LEGACY_GPU_RESOURCE_GUARDIAN_DIAGNOSTIC_MODE
        ) {
            true
        } else {
            DEFAULT_GPU_RESOURCE_DIAG_ENABLED
        }
        return prefs.getBoolean(
            PREF_KEY_GPU_RESOURCE_DIAG_ENABLED,
            defaultValue
        )
    }

    fun setGpuResourceDiagEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_GPU_RESOURCE_DIAG_ENABLED, enabled)
        }
    }

    fun isGdxPadCursorDebugEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_GDX_PAD_CURSOR_DEBUG, DEFAULT_GDX_PAD_CURSOR_DEBUG)
    }

    fun setGdxPadCursorDebugEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_GDX_PAD_CURSOR_DEBUG, enabled)
        }
    }

    fun isGlBridgeSwapHeartbeatDebugEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_GLBRIDGE_SWAP_HEARTBEAT_DEBUG,
            DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG
        )
    }

    fun setGlBridgeSwapHeartbeatDebugEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_GLBRIDGE_SWAP_HEARTBEAT_DEBUG, enabled)
        }
    }

    fun isAutoCheckUpdatesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_AUTO_CHECK_UPDATES_ENABLED,
            DEFAULT_AUTO_CHECK_UPDATES_ENABLED
        )
    }

    fun setAutoCheckUpdatesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_AUTO_CHECK_UPDATES_ENABLED, enabled)
        }
    }

    fun isSteamCloudWattAccelerationEnabled(context: Context): Boolean =
        isWattAccelerationEnabled(context)

    fun setSteamCloudWattAccelerationEnabled(context: Context, enabled: Boolean) {
        setWattAccelerationEnabled(context, enabled)
    }

    /**
     * Single source of truth for the Watt acceleration switch.
     *
     * Steam Cloud and workshop acceleration used to be two independent flags in
     * two different preference modes, so turning one off left other traffic
     * (workshop preview images, avatars, profile lookups) still accelerated.
     * They are one user-facing feature, so they now share one cross-process key.
     */
    fun isWattAccelerationEnabled(context: Context): Boolean {
        val preferences = prefs(context, crossProcess = true)
        preferences.getString(PREF_KEY_WATT_ACCELERATION_ENABLED, null)?.let { stored ->
            return stored.toBooleanStrictOrNull() ?: DEFAULT_WATT_ACCELERATION_ENABLED
        }
        // Migrate whichever legacy flag exists; an explicit opt-out must survive.
        val legacyEnabled = legacyWattAccelerationEnabled(preferences)
        setWattAccelerationEnabled(context, legacyEnabled)
        return legacyEnabled
    }

    fun setWattAccelerationEnabled(context: Context, enabled: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putString(PREF_KEY_WATT_ACCELERATION_ENABLED, enabled.toString())
        }
    }

    /**
     * Treats acceleration as disabled when either legacy flag was turned off, so
     * migration never silently re-enables a path the user had opted out of.
     */
    private fun legacyWattAccelerationEnabled(preferences: SharedPreferences): Boolean {
        val legacySteamCloud = preferences.getBoolean(
            PREF_KEY_STEAM_CLOUD_WATT_ACCELERATION_ENABLED,
            DEFAULT_WATT_ACCELERATION_ENABLED,
        )
        val legacyWorkshop = preferences.getBoolean(
            PREF_KEY_WORKSHOP_WATT_ACCELERATION_ENABLED,
            DEFAULT_WATT_ACCELERATION_ENABLED,
        )
        return legacySteamCloud && legacyWorkshop
    }

    fun isSteamCloudAutoLaunchAfterSyncEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED,
            DEFAULT_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED
        )
    }

    fun setSteamCloudAutoLaunchAfterSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_STEAM_CLOUD_AUTO_LAUNCH_AFTER_SYNC_ENABLED, enabled)
        }
    }

    fun isSteamCloudBackgroundLaunchRequested(context: Context): Boolean =
        prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_STEAM_CLOUD_BACKGROUND_LAUNCH_REQUESTED,
            false,
        )

    fun setSteamCloudBackgroundLaunchRequested(context: Context, requested: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_STEAM_CLOUD_BACKGROUND_LAUNCH_REQUESTED, requested)
        }
    }

    fun isSteamGamePresenceEnabled(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_STEAM_GAME_PRESENCE_ENABLED,
            DEFAULT_STEAM_GAME_PRESENCE_ENABLED,
        )
    }

    fun setSteamGamePresenceEnabled(context: Context, enabled: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_STEAM_GAME_PRESENCE_ENABLED, enabled)
        }
    }

    fun readRichPresenceDisplayPreferences(context: Context): RichPresenceDisplayPreferences {
        val preferences = prefs(context, crossProcess = true)
        return RichPresenceDisplayPreferences(
            prefix = RichPresencePrefix.fromPersistedValue(
                preferences.getString(PREF_KEY_STEAM_RICH_PRESENCE_PREFIX, null),
            ),
            showCharacter = preferences.getBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_CHARACTER, true),
            showFloor = preferences.getBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_FLOOR, true),
            showAscension = preferences.getBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ASCENSION, false),
            showAct = preferences.getBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ACT, false),
        )
    }

    fun saveRichPresenceDisplayPreferences(
        context: Context,
        settings: RichPresenceDisplayPreferences,
    ) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putString(PREF_KEY_STEAM_RICH_PRESENCE_PREFIX, settings.prefix.persistedValue)
            putBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_CHARACTER, settings.showCharacter)
            putBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_FLOOR, settings.showFloor)
            putBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ASCENSION, settings.showAscension)
            putBoolean(PREF_KEY_STEAM_RICH_PRESENCE_SHOW_ACT, settings.showAct)
        }
    }

    fun normalizeWorkshopMaxConcurrentDownloads(value: Int): Int {
        return value.coerceIn(MIN_WORKSHOP_MAX_CONCURRENT_DOWNLOADS, MAX_WORKSHOP_MAX_CONCURRENT_DOWNLOADS)
    }

    fun readWorkshopMaxConcurrentDownloads(context: Context): Int {
        return normalizeWorkshopMaxConcurrentDownloads(
            prefs(context, crossProcess = true).getInt(
                PREF_KEY_WORKSHOP_MAX_CONCURRENT_DOWNLOADS,
                DEFAULT_WORKSHOP_MAX_CONCURRENT_DOWNLOADS
            )
        )
    }

    fun saveWorkshopMaxConcurrentDownloads(context: Context, value: Int) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putInt(PREF_KEY_WORKSHOP_MAX_CONCURRENT_DOWNLOADS, normalizeWorkshopMaxConcurrentDownloads(value))
        }
    }

    fun normalizeWorkshopDownloadThreads(value: Int): Int {
        return value.coerceIn(MIN_WORKSHOP_DOWNLOAD_THREADS, MAX_WORKSHOP_DOWNLOAD_THREADS)
    }

    fun readWorkshopDownloadThreads(context: Context): Int {
        return normalizeWorkshopDownloadThreads(
            prefs(context, crossProcess = true).getInt(PREF_KEY_WORKSHOP_DOWNLOAD_THREADS, DEFAULT_WORKSHOP_DOWNLOAD_THREADS)
        )
    }

    fun saveWorkshopDownloadThreads(context: Context, value: Int) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putInt(PREF_KEY_WORKSHOP_DOWNLOAD_THREADS, normalizeWorkshopDownloadThreads(value))
        }
    }

    fun isWorkshopWattAccelerationEnabled(context: Context): Boolean =
        isWattAccelerationEnabled(context)

    fun setWorkshopWattAccelerationEnabled(context: Context, enabled: Boolean) {
        setWattAccelerationEnabled(context, enabled)
    }

    fun readWorkshopSteamLanguage(context: Context): String {
        return prefs(context, crossProcess = true).getString(
            PREF_KEY_WORKSHOP_STEAM_LANGUAGE,
            DEFAULT_WORKSHOP_STEAM_LANGUAGE
        ) ?: DEFAULT_WORKSHOP_STEAM_LANGUAGE
    }

    fun saveWorkshopSteamLanguage(context: Context, value: String) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putString(PREF_KEY_WORKSHOP_STEAM_LANGUAGE, value.trim().ifBlank { DEFAULT_WORKSHOP_STEAM_LANGUAGE })
        }
    }

    fun isWorkshopAutoImportEnabled(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_WORKSHOP_AUTO_IMPORT_ENABLED,
            DEFAULT_WORKSHOP_AUTO_IMPORT_ENABLED
        )
    }

    fun setWorkshopAutoImportEnabled(context: Context, enabled: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_WORKSHOP_AUTO_IMPORT_ENABLED, enabled)
        }
    }

    fun isWorkshopAutoImportAtlasDownscaleEnabled(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED,
            DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED
        )
    }

    fun setWorkshopAutoImportAtlasDownscaleEnabled(context: Context, enabled: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_ENABLED, enabled)
        }
    }

    fun normalizeWorkshopAutoImportAtlasDownscaleMaxEdgePx(maxEdgePx: Int): Int {
        return AtlasOfflineDownscaleStrategy.maxEdge(maxEdgePx).value
    }

    fun readWorkshopAutoImportAtlasDownscaleMaxEdgePx(context: Context): Int {
        return normalizeWorkshopAutoImportAtlasDownscaleMaxEdgePx(
            prefs(context, crossProcess = true).getInt(
                PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX,
                DEFAULT_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX
            )
        )
    }

    fun saveWorkshopAutoImportAtlasDownscaleMaxEdgePx(context: Context, maxEdgePx: Int) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putInt(
                PREF_KEY_WORKSHOP_AUTO_IMPORT_ATLAS_DOWNSCALE_MAX_EDGE_PX,
                normalizeWorkshopAutoImportAtlasDownscaleMaxEdgePx(maxEdgePx)
            )
        }
    }

    fun isWorkshopSubscribeWarningDismissed(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_WORKSHOP_SUBSCRIBE_WARNING_DISMISSED,
            false
        )
    }

    fun setWorkshopSubscribeWarningDismissed(context: Context, dismissed: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_WORKSHOP_SUBSCRIBE_WARNING_DISMISSED, dismissed)
        }
    }

    fun isEnabledModSizeWarningDismissed(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_ENABLED_MOD_SIZE_WARNING_DISMISSED,
            false
        )
    }

    fun setEnabledModSizeWarningDismissed(context: Context, dismissed: Boolean) {
        prefs(context, crossProcess = true).edit(commit = true) {
            putBoolean(PREF_KEY_ENABLED_MOD_SIZE_WARNING_DISMISSED, dismissed)
        }
    }

    fun readLastWorkshopUpdateCheckAtMs(context: Context): Long {
        return prefs(context).getLong(PREF_KEY_LAST_WORKSHOP_UPDATE_CHECK_AT_MS, 0L)
    }

    fun saveLastWorkshopUpdateCheckAtMs(context: Context, timestampMs: Long) {
        prefs(context).edit {
            putLong(PREF_KEY_LAST_WORKSHOP_UPDATE_CHECK_AT_MS, timestampMs)
        }
    }

    fun readSteamCloudSaveMode(context: Context): SteamCloudSaveMode {
        return SteamCloudSaveMode.fromPersistedValue(
            prefs(context, crossProcess = true).getString(
                PREF_KEY_STEAM_CLOUD_SAVE_MODE,
                DEFAULT_STEAM_CLOUD_SAVE_MODE.persistedValue
            )
        )
    }

    fun saveSteamCloudSaveMode(context: Context, mode: SteamCloudSaveMode) {
        SteamCloudOperationMutex.runExclusive(context) {
            check(
                prefs(context, crossProcess = true)
                    .edit()
                    .putString(PREF_KEY_STEAM_CLOUD_SAVE_MODE, mode.persistedValue)
                    .commit()
            ) { "Failed to persist Steam Cloud save mode." }
        }
    }

    fun isSteamCloudIndependentSwitchPending(context: Context): Boolean {
        return prefs(context, crossProcess = true).getBoolean(
            PREF_KEY_STEAM_CLOUD_INDEPENDENT_SWITCH_PENDING,
            false,
        )
    }

    fun readSteamCloudPendingProfileSteamId(context: Context): String {
        return prefs(context, crossProcess = true)
            .getString(PREF_KEY_STEAM_CLOUD_PENDING_PROFILE_STEAM_ID, "")
            ?.trim()
            .orEmpty()
    }

    fun saveSteamCloudIndependentSwitchPending(
        context: Context,
        pending: Boolean,
        cloudProfileSteamId: String = "",
    ) {
        SteamCloudOperationMutex.runExclusive(context) {
            check(
                prefs(context, crossProcess = true)
                    .edit()
                    .putBoolean(PREF_KEY_STEAM_CLOUD_INDEPENDENT_SWITCH_PENDING, pending)
                    .putString(
                        PREF_KEY_STEAM_CLOUD_PENDING_PROFILE_STEAM_ID,
                        cloudProfileSteamId.trim().takeIf { pending }.orEmpty(),
                    )
                    .commit()
            ) { "Failed to persist deferred Steam Cloud profile switch state." }
        }
    }

    internal fun completeSteamCloudIndependentSwitch(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            check(
                prefs(context, crossProcess = true)
                    .edit()
                    .putString(
                        PREF_KEY_STEAM_CLOUD_SAVE_MODE,
                        SteamCloudSaveMode.INDEPENDENT.persistedValue,
                    )
                    .putBoolean(PREF_KEY_STEAM_CLOUD_INDEPENDENT_SWITCH_PENDING, false)
                    .remove(PREF_KEY_STEAM_CLOUD_PENDING_PROFILE_STEAM_ID)
                    .commit()
            ) { "Failed to persist completed Steam Cloud profile switch." }
        }
    }

    fun readSteamCloudSyncBlacklistPaths(context: Context): Set<String> {
        val preferences = prefs(context, crossProcess = true)
        if (!preferences.contains(PREF_KEY_STEAM_CLOUD_SYNC_BLACKLIST_PATHS)) {
            return LinkedHashSet(DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS)
        }
        return SteamCloudSyncBlacklist.normalizeLocalRelativePaths(
            preferences.getStringSet(PREF_KEY_STEAM_CLOUD_SYNC_BLACKLIST_PATHS, emptySet()).orEmpty()
        )
    }

    fun saveSteamCloudSyncBlacklistPaths(context: Context, localRelativePaths: Set<String>) {
        SteamCloudOperationMutex.runExclusive(context) {
            check(
                prefs(context, crossProcess = true)
                    .edit()
                    .putStringSet(
                        PREF_KEY_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
                        LinkedHashSet(
                            SteamCloudSyncBlacklist.normalizeLocalRelativePaths(localRelativePaths)
                        )
                    )
                    .commit()
            ) { "Failed to persist Steam Cloud sync blacklist." }
        }
    }

    fun readPreferredUpdateMirrorId(context: Context): String {
        return prefs(context).getString(
            PREF_KEY_PREFERRED_UPDATE_MIRROR_ID,
            DEFAULT_PREFERRED_UPDATE_MIRROR_ID
        ) ?: DEFAULT_PREFERRED_UPDATE_MIRROR_ID
    }

    fun savePreferredUpdateMirrorId(context: Context, mirrorId: String) {
        prefs(context).edit {
            putString(PREF_KEY_PREFERRED_UPDATE_MIRROR_ID, mirrorId)
        }
    }

    fun readLastUpdateCheckAtMs(context: Context): Long {
        return prefs(context).getLong(PREF_KEY_LAST_UPDATE_CHECK_AT_MS, 0L)
    }

    fun saveLastUpdateCheckAtMs(context: Context, timestampMs: Long) {
        prefs(context).edit {
            putLong(PREF_KEY_LAST_UPDATE_CHECK_AT_MS, timestampMs)
        }
    }

    fun readLastKnownRemoteTag(context: Context): String? {
        return prefs(context).getString(PREF_KEY_LAST_KNOWN_REMOTE_TAG, null)
            ?.trim()
            ?.ifEmpty { null }
    }

    fun saveLastKnownRemoteTag(context: Context, tag: String?) {
        prefs(context).edit {
            if (tag.isNullOrBlank()) {
                remove(PREF_KEY_LAST_KNOWN_REMOTE_TAG)
            } else {
                putString(PREF_KEY_LAST_KNOWN_REMOTE_TAG, tag.trim())
            }
        }
    }

    fun readLastSuccessfulMetadataSourceId(context: Context): String? {
        return prefs(context).getString(PREF_KEY_LAST_SUCCESSFUL_METADATA_SOURCE_ID, null)
            ?.trim()
            ?.ifEmpty { null }
    }

    fun saveLastSuccessfulMetadataSourceId(context: Context, sourceId: String?) {
        prefs(context).edit {
            if (sourceId.isNullOrBlank()) {
                remove(PREF_KEY_LAST_SUCCESSFUL_METADATA_SOURCE_ID)
            } else {
                putString(PREF_KEY_LAST_SUCCESSFUL_METADATA_SOURCE_ID, sourceId.trim())
            }
        }
    }

    fun readLastSuccessfulDownloadSourceId(context: Context): String? {
        return prefs(context).getString(PREF_KEY_LAST_SUCCESSFUL_DOWNLOAD_SOURCE_ID, null)
            ?.trim()
            ?.ifEmpty { null }
    }

    fun saveLastSuccessfulDownloadSourceId(context: Context, sourceId: String?) {
        prefs(context).edit {
            if (sourceId.isNullOrBlank()) {
                remove(PREF_KEY_LAST_SUCCESSFUL_DOWNLOAD_SOURCE_ID)
            } else {
                putString(PREF_KEY_LAST_SUCCESSFUL_DOWNLOAD_SOURCE_ID, sourceId.trim())
            }
        }
    }

    fun readLastUpdateErrorSummary(context: Context): String? {
        return prefs(context).getString(PREF_KEY_LAST_UPDATE_ERROR_SUMMARY, null)
            ?.trim()
            ?.ifEmpty { null }
    }

    fun saveLastUpdateErrorSummary(context: Context, summary: String?) {
        prefs(context).edit {
            if (summary.isNullOrBlank()) {
                remove(PREF_KEY_LAST_UPDATE_ERROR_SUMMARY)
            } else {
                putString(PREF_KEY_LAST_UPDATE_ERROR_SUMMARY, summary.trim())
            }
        }
    }

    fun isFirstRunSetupCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_FIRST_RUN_SETUP_COMPLETED,
            DEFAULT_FIRST_RUN_SETUP_COMPLETED
        )
    }

    fun setFirstRunSetupCompleted(context: Context, completed: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FIRST_RUN_SETUP_COMPLETED, completed)
        }
    }

    fun markExpectedBackExit(context: Context) {
        prefs(context).edit(commit = true) {
            putLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, System.currentTimeMillis())
            remove(PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS)
        }
    }

    fun isExpectedBackExitRecent(context: Context): Boolean {
        val markedAtMs = prefs(context).getLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, -1L)
        if (markedAtMs <= 0L) {
            return false
        }
        val deltaMs = System.currentTimeMillis() - markedAtMs
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS
    }

    fun markExpectedBackExitRestartScheduled(context: Context) {
        prefs(context).edit(commit = true) {
            putLong(PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS, System.currentTimeMillis())
        }
    }

    fun isExpectedBackExitRestartScheduledRecent(context: Context): Boolean {
        val scheduledAtMs = prefs(context).getLong(PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS, -1L)
        if (scheduledAtMs <= 0L) {
            return false
        }
        val deltaMs = System.currentTimeMillis() - scheduledAtMs
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS
    }

    fun consumeExpectedBackExitIfRecent(context: Context): Boolean {
        val preferences = prefs(context)
        val markedAtMs = preferences.getLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, -1L)
        preferences.edit {
            remove(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS)
            remove(PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS)
        }
        if (markedAtMs <= 0L) {
            return false
        }
        val deltaMs = System.currentTimeMillis() - markedAtMs
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS
    }

    fun readRenderScale(context: Context): Float {
        val config = renderScaleFile(context)
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE
        }
        return try {
            FileInputStream(config).use { input ->
                val bytes = ByteArray(minOf(config.length().toInt(), 64))
                val read = input.read(bytes)
                if (read <= 0) {
                    return DEFAULT_RENDER_SCALE
                }
                val value = String(bytes, 0, read, StandardCharsets.UTF_8)
                    .trim()
                    .replace(',', '.')
                if (value.isEmpty()) {
                    return DEFAULT_RENDER_SCALE
                }
                val parsed = value.toFloat()
                when {
                    parsed < MIN_RENDER_SCALE -> MIN_RENDER_SCALE
                    parsed > MAX_RENDER_SCALE -> MAX_RENDER_SCALE
                    else -> parsed
                }
            }
        } catch (_: Throwable) {
            DEFAULT_RENDER_SCALE
        }
    }

    @Throws(IOException::class)
    fun saveRenderScale(context: Context, value: Float): String {
        val config = renderScaleFile(context)
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create config directory")
        }

        val normalized = formatRenderScale(value.coerceIn(MIN_RENDER_SCALE, MAX_RENDER_SCALE))
        FileOutputStream(config, false).use { out ->
            out.write(normalized.toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        return normalized
    }

    @Throws(IOException::class)
    fun resetRenderScale(context: Context) {
        val config = renderScaleFile(context)
        if (config.exists() && !config.delete()) {
            throw IOException("Failed to reset render scale")
        }
    }

    fun formatRenderScale(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    fun readTouchscreenEnabled(context: Context): Boolean {
        val preferences = prefs(context)
        if (preferences.contains(PREF_KEY_TOUCHSCREEN_ENABLED)) {
            return preferences.getBoolean(
                PREF_KEY_TOUCHSCREEN_ENABLED,
                DEFAULT_TOUCHSCREEN_ENABLED
            )
        }
        return readLegacyTouchscreenEnabled(gameplaySettingsFile(context))
            ?: DEFAULT_TOUCHSCREEN_ENABLED
    }

    fun readTouchscreenInputMode(context: Context): TouchscreenInputMode {
        return TouchscreenInputMode.fromSettings(
            touchscreenEnabled = readTouchscreenEnabled(context),
            nativeTouchscreenAllowlistEnabled = isNativeTouchscreenAllowlistCompatEnabled(context)
        )
    }

    fun readCardPlayOptimizationMode(context: Context): CardPlayOptimizationMode {
        return CardPlayOptimizationMode.fromPersistedValue(
            prefs(context).getString(PREF_KEY_CARD_PLAY_OPTIMIZATION_MODE, null)
        ) ?: DEFAULT_CARD_PLAY_OPTIMIZATION_MODE
    }

    fun readTouchIndicatorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_TOUCH_INDICATOR_ENABLED,
            DEFAULT_TOUCH_INDICATOR_ENABLED
        )
    }

    fun setTouchIndicatorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TOUCH_INDICATOR_ENABLED, enabled)
        }
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(context: Context, enabled: Boolean) {
        val committed = prefs(context)
            .edit()
            .putBoolean(PREF_KEY_TOUCHSCREEN_ENABLED, enabled)
            .commit()
        if (!committed) {
            throw IOException("Failed to persist launcher touchscreen setting")
        }
    }

    @Throws(IOException::class)
    fun saveTouchscreenInputMode(context: Context, mode: TouchscreenInputMode) {
        saveTouchscreenEnabled(context, mode.touchscreenEnabled)
        setNativeTouchscreenAllowlistCompatEnabled(
            context,
            mode.nativeTouchscreenAllowlistEnabled
        )
    }

    @Throws(IOException::class)
    fun saveCardPlayOptimizationMode(context: Context, mode: CardPlayOptimizationMode) {
        val committed = prefs(context)
            .edit()
            .putString(PREF_KEY_CARD_PLAY_OPTIMIZATION_MODE, mode.persistedValue)
            .commit()
        if (!committed) {
            throw IOException("Failed to persist launcher card play optimization mode")
        }
    }

    fun readGameplayFontScale(context: Context): Float {
        val file = File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME)
        val customValue = readGameplaySettingsString(file, GAMEPLAY_SETTINGS_KEY_FONT_SCALE)
        if (customValue != null) {
            return normalizeGameplayFontScale(parseFloatLike(customValue, DEFAULT_GAMEPLAY_FONT_SCALE))
        }
        val biggerTextEnabled =
            readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_BIGGER_TEXT)
                ?: DEFAULT_BIGGER_TEXT_ENABLED
        return if (biggerTextEnabled) {
            DEFAULT_GAMEPLAY_FONT_SCALE
        } else {
            MIN_GAMEPLAY_FONT_SCALE
        }
    }

    @Throws(IOException::class)
    fun saveGameplayFontScale(context: Context, value: Float): String {
        val normalized = normalizeGameplayFontScale(value)
        val formatted = formatGameplayFontScale(normalized)
        writeGameplaySettingsValues(
            context,
            File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            linkedMapOf(
                GAMEPLAY_SETTINGS_KEY_FONT_SCALE to formatted,
                GAMEPLAY_SETTINGS_KEY_BIGGER_TEXT to if (normalized > MIN_GAMEPLAY_FONT_SCALE) {
                    "true"
                } else {
                    "false"
                }
            )
        )
        return formatted
    }

    fun normalizeGameplayFontScale(value: Float): Float {
        val stepped =
            (((value - MIN_GAMEPLAY_FONT_SCALE) / GAMEPLAY_FONT_SCALE_STEP).roundToInt() *
                GAMEPLAY_FONT_SCALE_STEP) + MIN_GAMEPLAY_FONT_SCALE
        return stepped.coerceIn(MIN_GAMEPLAY_FONT_SCALE, MAX_GAMEPLAY_FONT_SCALE)
    }

    fun formatGameplayFontScale(value: Float): String {
        return String.format(Locale.US, "%.2f", normalizeGameplayFontScale(value))
    }

    fun readGameplayLargerUiEnabled(context: Context): Boolean {
        val file = File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME)
        val explicitValue =
            readGameplaySettingsBoolean(
                file,
                GAMEPLAY_SETTINGS_KEY_LARGER_UI,
                DEFAULT_GAMEPLAY_LARGER_UI_ENABLED
            )
        if (explicitValue != null) {
            return explicitValue
        }
        val customValue = readGameplaySettingsString(file, GAMEPLAY_SETTINGS_KEY_UI_SCALE)
        if (customValue != null) {
            return isGameplayUiScaleEnabled(
                parseFloatLike(customValue, DEFAULT_GAMEPLAY_UI_SCALE)
            )
        }
        return DEFAULT_GAMEPLAY_LARGER_UI_ENABLED
    }

    @Throws(IOException::class)
    fun saveGameplayLargerUiEnabled(context: Context, enabled: Boolean) {
        val formatted = formatGameplayUiScale(resolveGameplayUiScale(enabled))
        writeGameplaySettingsValues(
            context,
            File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            linkedMapOf(
                GAMEPLAY_SETTINGS_KEY_LARGER_UI to if (enabled) "true" else "false",
                GAMEPLAY_SETTINGS_KEY_UI_SCALE to formatted
            )
        )
    }

    fun readGameplayUiScale(context: Context): Float {
        return resolveGameplayUiScale(readGameplayLargerUiEnabled(context))
    }

    @Throws(IOException::class)
    fun saveGameplayUiScale(context: Context, value: Float): String {
        val enabled = isGameplayUiScaleEnabled(value)
        saveGameplayLargerUiEnabled(context, enabled)
        return formatGameplayUiScale(resolveGameplayUiScale(enabled))
    }

    fun normalizeGameplayUiScale(value: Float): Float {
        val stepped =
            (((value - MIN_GAMEPLAY_UI_SCALE) / GAMEPLAY_UI_SCALE_STEP).roundToInt() *
                GAMEPLAY_UI_SCALE_STEP) + MIN_GAMEPLAY_UI_SCALE
        return stepped.coerceIn(MIN_GAMEPLAY_UI_SCALE, MAX_GAMEPLAY_UI_SCALE)
    }

    fun resolveGameplayUiScale(enabled: Boolean): Float {
        return if (enabled) {
            ENABLED_GAMEPLAY_UI_SCALE
        } else {
            DEFAULT_GAMEPLAY_UI_SCALE
        }
    }

    fun isGameplayUiScaleEnabled(value: Float): Boolean {
        return normalizeGameplayUiScale(value) > DEFAULT_GAMEPLAY_UI_SCALE
    }

    fun formatGameplayUiScale(value: Float): String {
        return String.format(Locale.US, "%.2f", normalizeGameplayUiScale(value))
    }

    fun normalizePlayerName(name: String): String {
        val sanitized = sanitizeSingleLineText(name)
        return sanitized.ifEmpty { DEFAULT_PLAYER_NAME }
    }

    fun readPlayerName(context: Context): String {
        val files = arrayOf(
            File(RuntimePaths.preferencesDir(context), SAVE_SLOTS_SETTINGS_FILE_NAME),
            File(RuntimePaths.preferencesDir(context), SAVE_SLOTS_SETTINGS_BACKUP_FILE_NAME),
            File(RuntimePaths.preferencesDir(context), PLAYER_SETTINGS_FILE_NAME),
            File(RuntimePaths.preferencesDir(context), PLAYER_SETTINGS_BACKUP_FILE_NAME)
        )
        for (file in files) {
            val key = when (file.name) {
                SAVE_SLOTS_SETTINGS_FILE_NAME, SAVE_SLOTS_SETTINGS_BACKUP_FILE_NAME ->
                    SAVE_SLOTS_SETTINGS_KEY_PROFILE_NAME
                else -> PLAYER_SETTINGS_KEY_NAME
            }
            val value = readPlayerSettingsString(file, key)
            if (value != null) {
                return normalizePlayerName(value)
            }
        }
        return DEFAULT_PLAYER_NAME
    }

    @Throws(IOException::class)
    fun savePlayerName(context: Context, name: String) {
        val normalizedName = normalizePlayerName(name)
        writePlayerSettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), PLAYER_SETTINGS_FILE_NAME),
            PLAYER_SETTINGS_KEY_NAME,
            normalizedName,
            PLAYER_SETTINGS_DEFAULT_ASSET_PATH
        )
        writePlayerSettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), PLAYER_SETTINGS_BACKUP_FILE_NAME),
            PLAYER_SETTINGS_KEY_NAME,
            normalizedName,
            PLAYER_SETTINGS_BACKUP_DEFAULT_ASSET_PATH
        )
        writePlayerSettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), SAVE_SLOTS_SETTINGS_FILE_NAME),
            SAVE_SLOTS_SETTINGS_KEY_PROFILE_NAME,
            normalizedName,
            SAVE_SLOTS_SETTINGS_DEFAULT_ASSET_PATH
        )
        writePlayerSettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), SAVE_SLOTS_SETTINGS_BACKUP_FILE_NAME),
            SAVE_SLOTS_SETTINGS_KEY_PROFILE_NAME,
            normalizedName,
            SAVE_SLOTS_SETTINGS_BACKUP_DEFAULT_ASSET_PATH
        )
    }

    private fun writeGameplaySettingsValue(context: Context, file: File, key: String, value: String) {
        writeGameplaySettingsValues(context, file, linkedMapOf(key to value))
    }

    private fun writeGameplaySettingsValues(
        context: Context,
        file: File,
        values: Map<String, String>
    ) {
        writeGameplaySettingsValues(
            file = file,
            values = values,
            bundledDefaults = readBundledGameplaySettingsDefaults(context)
        )
    }

    private fun readGameplaySettingsBoolean(
        file: File,
        key: String,
        defaultValue: Boolean = DEFAULT_TOUCHSCREEN_ENABLED
    ): Boolean? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return parseBooleanLike(objectValue.opt(key), defaultValue)
    }

    private fun readGameplaySettingsString(file: File, key: String): String? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return objectValue.opt(key)?.toString()?.let(::sanitizeSingleLineText)
    }

    private fun readBundledGameplaySettingsDefaults(context: Context): JSONObject? {
        return readBundledJsonObject(context, GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH)
    }

    private fun writePlayerSettingsValue(
        context: Context,
        file: File,
        key: String,
        name: String,
        defaultAssetPath: String
    ) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            readBundledPlayerSettingsDefaults(context, defaultAssetPath),
            readJsonObject(file)
        )
        root.put(key, name)
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.fd.sync()
        }
    }

    @JvmStatic
    fun syncLauncherPrefsToDisk(context: Context): Boolean {
        val preferences = prefs(context)
        val snapshot = LinkedHashMap(preferences.all)
        snapshot.remove(LEGACY_PREF_KEY_STEAM_CLOUD_AUTO_PULL_BEFORE_LAUNCH_ENABLED)
        snapshot.remove(LEGACY_PREF_KEY_STEAM_CLOUD_AUTO_PUSH_AFTER_CLEAN_SHUTDOWN_ENABLED)
        if (!snapshot.containsKey(PREF_KEY_TOUCHSCREEN_ENABLED)) {
            snapshot[PREF_KEY_TOUCHSCREEN_ENABLED] =
                readLegacyTouchscreenEnabled(gameplaySettingsFile(context))
                    ?: DEFAULT_TOUCHSCREEN_ENABLED
        }
        val editor = preferences.edit().clear()
        snapshot.forEach { (key, value) ->
            writePreferenceValue(editor, key, value)
        }
        if (!editor.commit()) {
            return false
        }
        return try {
            syncLauncherManagedPreferenceFiles(context, snapshot)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun readPlayerSettingsString(file: File, key: String): String? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        val value = objectValue.opt(key)?.toString() ?: return null
        return sanitizeSingleLineText(value)
    }

    private fun readBundledPlayerSettingsDefaults(context: Context, assetPath: String): JSONObject? {
        return readBundledJsonObject(context, assetPath)
    }

    private fun readBundledJsonObject(context: Context, assetPath: String): JSONObject? {
        return try {
            context.assets.open(assetPath).use { input ->
                val text = input.readBytes().toString(StandardCharsets.UTF_8).trim()
                if (text.isEmpty()) {
                    JSONObject()
                } else {
                    val parsed = JSONTokener(text).nextValue()
                    parsed as? JSONObject ?: JSONObject()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun mergeJsonObjects(base: JSONObject?, override: JSONObject?): JSONObject {
        val merged = JSONObject()
        if (base != null) {
            val keys = base.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, base.opt(key))
            }
        }
        if (override != null) {
            val keys = override.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, override.opt(key))
            }
        }
        return merged
    }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                return JSONObject()
            }
            val parsed = JSONTokener(text).nextValue()
            parsed as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseBooleanLike(value: Any?, fallback: Boolean): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                val normalized = value.trim()
                when {
                    normalized.equals("true", ignoreCase = true) || normalized == "1" -> true
                    normalized.equals("false", ignoreCase = true) || normalized == "0" -> false
                    else -> fallback
                }
            }

            else -> fallback
        }
    }

    private fun parseFloatLike(value: String, fallback: Float): Float {
        return try {
            value.trim().replace(',', '.').toFloat()
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun sanitizeSingleLineText(value: String): String {
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }

    private fun SharedPreferences.Editor.putStringOrRemove(
        key: String,
        value: String?
    ): SharedPreferences.Editor {
        return if (value.isNullOrBlank()) {
            remove(key)
        } else {
            putString(key, value)
        }
    }

    private fun isReadableFilePath(path: String): Boolean {
        return path.isNotBlank() && File(path).let { file ->
            file.isFile && file.canRead()
        }
    }

    private fun syncLauncherManagedPreferenceFiles(
        context: Context,
        snapshot: Map<String, Any?>
    ) {
        syncTouchscreenEnabledToGameplaySettingsFile(
            file = gameplaySettingsFile(context),
            enabled = parseBooleanLike(
                snapshot[PREF_KEY_TOUCHSCREEN_ENABLED],
                DEFAULT_TOUCHSCREEN_ENABLED
            ),
            bundledDefaults = readBundledGameplaySettingsDefaults(context)
        )
    }

    private fun readLegacyTouchscreenEnabled(file: File): Boolean? {
        return readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN)
    }

    @Suppress("UNCHECKED_CAST")
    private fun writePreferenceValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, LinkedHashSet(value.filterIsInstance<String>()))
            else -> throw IllegalStateException(
                "Unsupported launcher preference type for '$key': ${value.javaClass.name}"
            )
        }
    }

    private fun renderScaleFile(context: Context): File {
        return File(RuntimePaths.stsRoot(context), "render_scale.txt")
    }

    private fun gameplaySettingsFile(context: Context): File {
        return File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME)
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context, crossProcess: Boolean = false): SharedPreferences {
        val mode = if (crossProcess) {
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        } else {
            Context.MODE_PRIVATE
        }
        return context.getSharedPreferences(PREF_NAME_LAUNCHER, mode)
    }

    private const val LEGACY_PREF_KEY_STEAM_CLOUD_AUTO_PULL_BEFORE_LAUNCH_ENABLED =
        "steam_cloud_auto_pull_before_launch_enabled"
    private const val LEGACY_PREF_KEY_STEAM_CLOUD_AUTO_PUSH_AFTER_CLEAN_SHUTDOWN_ENABLED =
        "steam_cloud_auto_push_after_clean_shutdown_enabled"

    internal fun syncTouchscreenEnabledToGameplaySettingsFile(
        file: File,
        enabled: Boolean,
        bundledDefaults: JSONObject? = null
    ) {
        writeGameplaySettingsValues(
            file = file,
            values = linkedMapOf(GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN to if (enabled) "true" else "false"),
            bundledDefaults = bundledDefaults
        )
    }

    private fun writeGameplaySettingsValues(
        file: File,
        values: Map<String, String>,
        bundledDefaults: JSONObject?
    ) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            bundledDefaults,
            readJsonObject(file)
        )
        values.forEach { (key, value) ->
            root.put(key, value)
        }
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.fd.sync()
        }
    }
}
