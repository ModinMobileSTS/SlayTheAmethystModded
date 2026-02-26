package io.stamethyst.backend.launch

import android.content.Context
import android.content.SharedPreferences

object BackExitNotice {
    private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
    private const val PREF_KEY_EXPECTED_BACK_EXIT_AT_MS = "expected_back_exit_at_ms"
    private const val EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L

    @JvmStatic
    fun markExpectedBackExit(context: Context) {
        prefs(context)
            .edit()
            .putLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun consumeExpectedBackExitIfRecent(context: Context): Boolean {
        val preferences: SharedPreferences = prefs(context)
        val markedAtMs = preferences.getLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, -1L)
        preferences.edit().remove(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS).apply()
        if (markedAtMs <= 0L) {
            return false
        }
        val deltaMs = System.currentTimeMillis() - markedAtMs
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
    }
}
