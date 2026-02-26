package io.stamethyst.backend;

import android.content.Context;
import android.content.SharedPreferences;

public final class BackExitNotice {
    private static final String PREF_NAME_LAUNCHER = "sts_launcher_prefs";
    private static final String PREF_KEY_EXPECTED_BACK_EXIT_AT_MS = "expected_back_exit_at_ms";
    private static final long EXPECTED_BACK_EXIT_VALID_WINDOW_MS = 30_000L;

    private BackExitNotice() {
    }

    public static void markExpectedBackExit(Context context) {
        prefs(context)
                .edit()
                .putLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, System.currentTimeMillis())
                .apply();
    }

    public static boolean consumeExpectedBackExitIfRecent(Context context) {
        SharedPreferences preferences = prefs(context);
        long markedAtMs = preferences.getLong(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS, -1L);
        preferences.edit().remove(PREF_KEY_EXPECTED_BACK_EXIT_AT_MS).apply();
        if (markedAtMs <= 0L) {
            return false;
        }
        long deltaMs = System.currentTimeMillis() - markedAtMs;
        return deltaMs >= 0L && deltaMs <= EXPECTED_BACK_EXIT_VALID_WINDOW_MS;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE);
    }
}
