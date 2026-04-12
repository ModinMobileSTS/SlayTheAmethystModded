package io.stamethyst.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
import io.stamethyst.backend.render.VirtualResolutionMode
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
    private const val PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW = "show_floating_mouse_window"
    private const val PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION = "touch_mouse_new_interaction"
    private const val PREF_KEY_LONG_PRESS_MOUSE_SHOWS_KEYBOARD =
        "long_press_mouse_shows_keyboard"
    private const val PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = "auto_switch_left_after_right_click"
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
    private const val PREF_KEY_SHOW_MOD_FILE_NAME = "show_mod_file_name"
    private const val PREF_KEY_MOBILE_HUD_ENABLED = "mobile_hud_enabled"
    private const val PREF_KEY_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED =
        "compendium_upgrade_touch_fix_enabled"
    private const val PREF_KEY_VIRTUAL_RESOLUTION_MODE = "virtual_resolution_mode"
    private const val PREF_KEY_AVOID_DISPLAY_CUTOUT = "avoid_display_cutout"
    private const val PREF_KEY_CROP_SCREEN_BOTTOM = "crop_screen_bottom"
    private const val PREF_KEY_SHOW_GAME_PERFORMANCE_OVERLAY = "show_game_performance_overlay"
    private const val PREF_KEY_SUSTAINED_PERFORMANCE_MODE_ENABLED =
        "sustained_performance_mode_enabled"
    private const val PREF_KEY_TARGET_FPS = "target_fps"
    private const val PREF_KEY_JVM_HEAP_MAX_MB = "jvm_heap_max_mb"
    private const val PREF_KEY_JVM_COMPRESSED_POINTERS_ENABLED = "jvm_compressed_pointers_enabled"
    private const val PREF_KEY_JVM_STRING_DEDUPLICATION_ENABLED =
        "jvm_string_deduplication_enabled"
    private const val PREF_KEY_VIRTUAL_FBO_POC = "compat_virtual_fbo_poc"
    private const val PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT = "compat_global_atlas_filter_compat"
    private const val PREF_KEY_MOD_MANIFEST_ROOT_COMPAT = "compat_mod_manifest_root_compat"
    private const val PREF_KEY_FRIEREN_MOD_COMPAT = "compat_frieren_mod_compat"
    private const val PREF_KEY_DOWNFALL_IMPORT_COMPAT = "compat_downfall_import_compat"
    private const val PREF_KEY_VUPSHION_MOD_COMPAT = "compat_vupshion_mod_compat"
    private const val PREF_KEY_FRAGMENT_SHADER_PRECISION_COMPAT =
        "compat_fragment_shader_precision_compat"
    private const val PREF_KEY_RUNTIME_TEXTURE_COMPAT = "compat_runtime_texture_compat"
    private const val PREF_KEY_LARGE_TEXTURE_DOWNSCALE_COMPAT =
        "compat_large_texture_downscale"
    private const val PREF_KEY_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR =
        "compat_texture_pressure_downscale_divisor"
    private const val PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER = "compat_force_linear_mipmap_filter"
    private const val PREF_KEY_NON_RENDERABLE_FBO_FORMAT_COMPAT =
        "compat_non_renderable_fbo_format_compat"
    private const val PREF_KEY_FBO_IDLE_RECLAIM_COMPAT = "compat_fbo_idle_reclaim"
    private const val PREF_KEY_FBO_PRESSURE_DOWNSCALE_COMPAT =
        "compat_fbo_pressure_downscale"
    private const val PREF_KEY_LWJGL_DEBUG = "lwjgl_debug"
    private const val PREF_KEY_PRELOAD_ALL_JRE_LIBRARIES = "preload_all_jre_libraries"
    private const val PREF_KEY_LOGCAT_CAPTURE_ENABLED = "logcat_capture_enabled"
    private const val PREF_KEY_LAUNCHER_LOGCAT_CAPTURE_ENABLED = "launcher_logcat_capture_enabled"
    private const val PREF_KEY_JVM_LOGCAT_MIRROR_ENABLED = "jvm_logcat_mirror_enabled"
    private const val PREF_KEY_GPU_RESOURCE_DIAG_ENABLED = "gpu_resource_diag_enabled"
    private const val PREF_KEY_GDX_PAD_CURSOR_DEBUG = "gdx_pad_cursor_debug"
    private const val PREF_KEY_GLBRIDGE_SWAP_HEARTBEAT_DEBUG = "glbridge_swap_heartbeat_debug"
    private const val PREF_KEY_AUTO_CHECK_UPDATES_ENABLED = "auto_check_updates_enabled"
    private const val PREF_KEY_PREFERRED_UPDATE_MIRROR_ID = "preferred_update_mirror_id"
    private const val PREF_KEY_LAST_UPDATE_CHECK_AT_MS = "last_update_check_at_ms"
    private const val PREF_KEY_LAST_KNOWN_REMOTE_TAG = "last_known_remote_tag"
    private const val PREF_KEY_LAST_SUCCESSFUL_METADATA_SOURCE_ID = "last_successful_metadata_source_id"
    private const val PREF_KEY_LAST_SUCCESSFUL_DOWNLOAD_SOURCE_ID = "last_successful_download_source_id"
    private const val PREF_KEY_LAST_UPDATE_ERROR_SUMMARY = "last_update_error_summary"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_AT_MS = "expected_back_exit_at_ms"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_RESTART_AT_MS = "expected_back_exit_restart_at_ms"
    private const val EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L

    const val DEFAULT_BACK_IMMEDIATE_EXIT = true
    val DEFAULT_BACK_BEHAVIOR: BackBehavior = BackBehavior.EXIT_TO_LAUNCHER
    const val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY = false
    const val DEFAULT_TARGET_FPS = 60
    val TARGET_FPS_OPTIONS = intArrayOf(24, 30, 60, 120, 240)
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
    const val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW = true
    const val DEFAULT_TOUCH_MOUSE_NEW_INTERACTION = true
    const val DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD = true
    const val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = true
    const val DEFAULT_SHOW_MOD_FILE_NAME = false
    const val DEFAULT_MOBILE_HUD_ENABLED = false
    const val DEFAULT_COMPENDIUM_UPGRADE_TOUCH_FIX_ENABLED = true
    val DEFAULT_VIRTUAL_RESOLUTION_MODE: VirtualResolutionMode =
        VirtualResolutionMode.FULLSCREEN_FILL
    const val DEFAULT_AVOID_DISPLAY_CUTOUT = false
    const val DEFAULT_CROP_SCREEN_BOTTOM = false
    const val DEFAULT_SHOW_GAME_PERFORMANCE_OVERLAY = false
    const val DEFAULT_SUSTAINED_PERFORMANCE_MODE_ENABLED = true
    const val DEFAULT_LWJGL_DEBUG = false
    const val DEFAULT_PRELOAD_ALL_JRE_LIBRARIES = false
    const val DEFAULT_LOGCAT_CAPTURE_ENABLED = true
    const val DEFAULT_LAUNCHER_LOGCAT_CAPTURE_ENABLED = true
    const val DEFAULT_JVM_LOGCAT_MIRROR_ENABLED = false
    const val DEFAULT_GPU_RESOURCE_DIAG_ENABLED = false
    const val DEFAULT_GDX_PAD_CURSOR_DEBUG = false
    const val DEFAULT_GLBRIDGE_SWAP_HEARTBEAT_DEBUG = false
    const val DEFAULT_AUTO_CHECK_UPDATES_ENABLED = true
    const val DEFAULT_PREFERRED_UPDATE_MIRROR_ID = "gh_proxy_com"

    const val DEFAULT_JVM_HEAP_MAX_MB = 512
    const val MIN_JVM_HEAP_MAX_MB = 256
    const val MAX_JVM_HEAP_MAX_MB = 2048
    const val JVM_HEAP_STEP_MB = 128
    const val DEFAULT_JVM_COMPRESSED_POINTERS_ENABLED = false
    const val DEFAULT_JVM_STRING_DEDUPLICATION_ENABLED = false
    const val DEFAULT_FRAGMENT_SHADER_PRECISION_COMPAT_ENABLED = true
    const val DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED = true
    const val DEFAULT_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 2
    const val MIN_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 2
    const val MAX_TEXTURE_PRESSURE_DOWNSCALE_DIVISOR = 4
    const val DEFAULT_FBO_IDLE_RECLAIM_COMPAT_ENABLED = true
    const val DEFAULT_FBO_PRESSURE_DOWNSCALE_COMPAT_ENABLED = true

    const val DEFAULT_RENDER_SCALE = 1.0f
    const val MIN_RENDER_SCALE = 0.10f
    const val MAX_RENDER_SCALE = 1.00f

    const val DEFAULT_TOUCHSCREEN_ENABLED = true
    private const val DEFAULT_BIGGER_TEXT_ENABLED = true
    const val DEFAULT_GAMEPLAY_FONT_SCALE = 1.50f
    const val MIN_GAMEPLAY_FONT_SCALE = 1.00f
    const val MAX_GAMEPLAY_FONT_SCALE = 2.00f
    const val GAMEPLAY_FONT_SCALE_STEP = 0.05f
    const val DEFAULT_PLAYER_NAME = "player"

    private const val GAMEPLAY_SETTINGS_FILE_NAME = "STSGameplaySettings"
    private const val GAMEPLAY_SETTINGS_KEY_BIGGER_TEXT = "Bigger Text"
    private const val GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN = "Touchscreen Enabled"
    private const val GAMEPLAY_SETTINGS_KEY_FONT_SCALE = "Amethyst Font Scale"
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

    fun readShowFloatingMouseWindow(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW,
            DEFAULT_SHOW_FLOATING_MOUSE_WINDOW
        )
    }

    fun saveShowFloatingMouseWindow(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW, enabled)
        }
    }

    fun readTouchMouseNewInteraction(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION,
            DEFAULT_TOUCH_MOUSE_NEW_INTERACTION
        )
    }

    fun saveTouchMouseNewInteraction(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_TOUCH_MOUSE_NEW_INTERACTION, enabled)
        }
    }

    fun readLongPressMouseShowsKeyboard(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_LONG_PRESS_MOUSE_SHOWS_KEYBOARD,
            DEFAULT_LONG_PRESS_MOUSE_SHOWS_KEYBOARD
        )
    }

    fun saveLongPressMouseShowsKeyboard(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LONG_PRESS_MOUSE_SHOWS_KEYBOARD, enabled)
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
        val normalizedMax = normalizeJvmHeapMaxMb(heapMaxMb)
        return normalizedMax.coerceAtMost(DEFAULT_JVM_HEAP_MAX_MB)
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

    fun isVirtualFboPocEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_VIRTUAL_FBO_POC, false)
    }

    fun setVirtualFboPocEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_VIRTUAL_FBO_POC, enabled)
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

    fun isLargeTextureDownscaleCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_LARGE_TEXTURE_DOWNSCALE_COMPAT,
            DEFAULT_LARGE_TEXTURE_DOWNSCALE_COMPAT_ENABLED
        )
    }

    fun setLargeTextureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_LARGE_TEXTURE_DOWNSCALE_COMPAT, enabled)
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

    fun isForceLinearMipmapFilterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, true)
    }

    fun setForceLinearMipmapFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, enabled)
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

    fun isFboIdleReclaimCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_FBO_IDLE_RECLAIM_COMPAT,
            DEFAULT_FBO_IDLE_RECLAIM_COMPAT_ENABLED
        )
    }

    fun setFboIdleReclaimCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FBO_IDLE_RECLAIM_COMPAT, enabled)
        }
    }

    fun isFboPressureDownscaleCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(
            PREF_KEY_FBO_PRESSURE_DOWNSCALE_COMPAT,
            DEFAULT_FBO_PRESSURE_DOWNSCALE_COMPAT_ENABLED
        )
    }

    fun setFboPressureDownscaleCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FBO_PRESSURE_DOWNSCALE_COMPAT, enabled)
        }
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
        return prefs(context).getBoolean(
            PREF_KEY_GPU_RESOURCE_DIAG_ENABLED,
            DEFAULT_GPU_RESOURCE_DIAG_ENABLED
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
        val file = File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME)
        val value = readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN)
        if (value != null) {
            return value
        }
        return DEFAULT_TOUCHSCREEN_ENABLED
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(context: Context, enabled: Boolean) {
        val value = if (enabled) "true" else "false"
        writeGameplaySettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
            value
        )
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
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            readBundledGameplaySettingsDefaults(context),
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

    private fun readGameplaySettingsBoolean(file: File, key: String): Boolean? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return parseBooleanLike(objectValue.opt(key), DEFAULT_TOUCHSCREEN_ENABLED)
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
        val editor = preferences.edit().clear()
        snapshot.forEach { (key, value) ->
            writePreferenceValue(editor, key, value)
        }
        return editor.commit()
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
}
