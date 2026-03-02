package io.stamethyst.config

import android.content.Context
import androidx.core.content.edit
import io.stamethyst.LauncherIcon
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
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
    private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
    private const val PREF_KEY_TARGET_FPS = "target_fps"
    private const val PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY = "manual_dismiss_boot_overlay"
    private const val PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW = "show_floating_mouse_window"
    private const val PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = "auto_switch_left_after_right_click"
    private const val PREF_KEY_JVM_HEAP_MAX_MB = "jvm_heap_max_mb"
    private const val PREF_KEY_LAUNCHER_ICON = "launcher_icon"
    private const val PREF_KEY_VIRTUAL_FBO_POC = "compat_virtual_fbo_poc"
    private const val PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT = "compat_global_atlas_filter_compat"
    private const val PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER = "compat_force_linear_mipmap_filter"
    private const val PREF_KEY_LWJGL_DEBUG = "lwjgl_debug"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_AT_MS = "expected_back_exit_at_ms"
    private const val EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L

    const val DEFAULT_BACK_IMMEDIATE_EXIT = true
    const val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY = false
    const val DEFAULT_TARGET_FPS = 120
    val TARGET_FPS_OPTIONS = intArrayOf(60, 90, 120, 240)
    const val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW = true
    const val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = true
    const val DEFAULT_LWJGL_DEBUG = false

    const val DEFAULT_JVM_HEAP_MAX_MB = 1024
    const val MIN_JVM_HEAP_MAX_MB = 1024
    const val MAX_JVM_HEAP_MAX_MB = 2048
    const val JVM_HEAP_STEP_MB = 128

    const val DEFAULT_RENDER_SCALE = 1.0f
    const val MIN_RENDER_SCALE = 0.50f
    const val MAX_RENDER_SCALE = 1.00f

    const val DEFAULT_TOUCHSCREEN_ENABLED = true

    private const val GAMEPLAY_SETTINGS_FILE_NAME = "STSGameplaySettings"
    private const val GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN = "Touchscreen Enabled"
    private const val GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSGameplaySettings"
    private val DEFAULT_LAUNCHER_ICON = LauncherIcon.AMBER

    fun readBackImmediateExit(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, DEFAULT_BACK_IMMEDIATE_EXIT)
    }

    fun saveBackImmediateExit(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, enabled)
        }
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

    fun normalizeTargetFps(targetFps: Int): Int {
        return if (TARGET_FPS_OPTIONS.contains(targetFps)) {
            targetFps
        } else {
            DEFAULT_TARGET_FPS
        }
    }

    fun readTargetFps(context: Context): Int {
        val stored = prefs(context).getInt(PREF_KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
        return normalizeTargetFps(stored)
    }

    fun saveTargetFps(context: Context, targetFps: Int) {
        prefs(context).edit {
            putInt(PREF_KEY_TARGET_FPS, normalizeTargetFps(targetFps))
        }
    }

    fun normalizeJvmHeapMaxMb(heapMaxMb: Int): Int {
        val clamped = heapMaxMb.coerceIn(MIN_JVM_HEAP_MAX_MB, MAX_JVM_HEAP_MAX_MB)
        val offset = clamped - MIN_JVM_HEAP_MAX_MB
        val snappedStepCount = (offset / JVM_HEAP_STEP_MB.toFloat()).roundToInt()
        val snapped = MIN_JVM_HEAP_MAX_MB + (snappedStepCount * JVM_HEAP_STEP_MB)
        return snapped.coerceIn(MIN_JVM_HEAP_MAX_MB, MAX_JVM_HEAP_MAX_MB)
    }

    fun readJvmHeapMaxMb(context: Context): Int {
        val stored = prefs(context).getInt(PREF_KEY_JVM_HEAP_MAX_MB, DEFAULT_JVM_HEAP_MAX_MB)
        return normalizeJvmHeapMaxMb(stored)
    }

    fun saveJvmHeapMaxMb(context: Context, heapMaxMb: Int) {
        prefs(context).edit {
            putInt(PREF_KEY_JVM_HEAP_MAX_MB, normalizeJvmHeapMaxMb(heapMaxMb))
        }
    }

    fun readLauncherIcon(context: Context): LauncherIcon {
        val rawValue = prefs(context).getString(PREF_KEY_LAUNCHER_ICON, DEFAULT_LAUNCHER_ICON.name)
        return runCatching { LauncherIcon.valueOf(rawValue ?: "") }
            .getOrDefault(DEFAULT_LAUNCHER_ICON)
    }

    fun saveLauncherIcon(context: Context, icon: LauncherIcon) {
        prefs(context).edit {
            putString(PREF_KEY_LAUNCHER_ICON, icon.name)
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

    fun isForceLinearMipmapFilterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, true)
    }

    fun setForceLinearMipmapFilterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(PREF_KEY_FORCE_LINEAR_MIPMAP_FILTER, enabled)
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

    fun markExpectedBackExit(context: Context) {
        prefs(context).edit {
            putLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, System.currentTimeMillis())
        }
    }

    fun consumeExpectedBackExitIfRecent(context: Context): Boolean {
        val preferences = prefs(context)
        val markedAtMs = preferences.getLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, -1L)
        preferences.edit {
            remove(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS)
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
        val files = arrayOf(
            File(RuntimePaths.betaPreferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME)
        )
        for (file in files) {
            val value = readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN)
            if (value != null) {
                return value
            }
        }
        return DEFAULT_TOUCHSCREEN_ENABLED
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(context: Context, enabled: Boolean) {
        val value = if (enabled) "true" else "false"
        writeGameplaySettingsValue(
            context,
            File(RuntimePaths.betaPreferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
            value
        )
        writeGameplaySettingsValue(
            context,
            File(RuntimePaths.preferencesDir(context), GAMEPLAY_SETTINGS_FILE_NAME),
            GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
            value
        )
    }

    private fun writeGameplaySettingsValue(context: Context, file: File, key: String, value: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            readBundledGameplaySettingsDefaults(context),
            readJsonObject(file)
        )
        root.put(key, value)
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
        }
    }

    private fun readGameplaySettingsBoolean(file: File, key: String): Boolean? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return parseBooleanLike(objectValue.opt(key), DEFAULT_TOUCHSCREEN_ENABLED)
    }

    private fun readBundledGameplaySettingsDefaults(context: Context): JSONObject? {
        return try {
            context.assets.open(GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH).use { input ->
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

    private fun renderScaleFile(context: Context): File {
        return File(RuntimePaths.stsRoot(context), "render_scale.txt")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
}
