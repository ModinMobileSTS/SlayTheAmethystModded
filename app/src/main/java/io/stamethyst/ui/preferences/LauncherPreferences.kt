package io.stamethyst.ui.preferences

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToInt

object LauncherPreferences {
    private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
    private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
    private const val PREF_KEY_TARGET_FPS = "target_fps"
    private const val PREF_KEY_MANUAL_DISMISS_BOOT_OVERLAY = "manual_dismiss_boot_overlay"
    private const val PREF_KEY_SHOW_FLOATING_MOUSE_WINDOW = "show_floating_mouse_window"
    private const val PREF_KEY_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = "auto_switch_left_after_right_click"
    private const val PREF_KEY_JVM_HEAP_MAX_MB = "jvm_heap_max_mb"

    const val DEFAULT_BACK_IMMEDIATE_EXIT = true
    const val DEFAULT_MANUAL_DISMISS_BOOT_OVERLAY = false
    const val DEFAULT_TARGET_FPS = 120
    val TARGET_FPS_OPTIONS = intArrayOf(60, 90, 120, 240)
    const val DEFAULT_SHOW_FLOATING_MOUSE_WINDOW = true
    const val DEFAULT_AUTO_SWITCH_LEFT_AFTER_RIGHT_CLICK = true

    const val DEFAULT_JVM_HEAP_MAX_MB = 1024
    const val MIN_JVM_HEAP_MAX_MB = 1024
    const val MAX_JVM_HEAP_MAX_MB = 2048
    const val JVM_HEAP_STEP_MB = 128

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
}
