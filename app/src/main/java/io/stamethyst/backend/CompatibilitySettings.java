package io.stamethyst.backend;

import android.content.Context;
import android.content.SharedPreferences;

public final class CompatibilitySettings {
    private static final String PREF_NAME_LAUNCHER = "sts_launcher_prefs";
    private static final String PREF_KEY_ORIGINAL_FBO_PATCH = "compat_original_fbo_patch";
    private static final String PREF_KEY_DOWNFALL_FBO_PATCH = "compat_downfall_fbo_patch";
    private static final String PREF_KEY_VIRTUAL_FBO_POC = "compat_virtual_fbo_poc";

    private CompatibilitySettings() {
    }

    public static boolean isOriginalFboPatchEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_ORIGINAL_FBO_PATCH, true);
    }

    public static void setOriginalFboPatchEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_ORIGINAL_FBO_PATCH, enabled).apply();
    }

    public static boolean isDownfallFboPatchEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_DOWNFALL_FBO_PATCH, true);
    }

    public static void setDownfallFboPatchEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_DOWNFALL_FBO_PATCH, enabled).apply();
    }

    public static boolean isVirtualFboPocEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY_VIRTUAL_FBO_POC, false);
    }

    public static void setVirtualFboPocEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_KEY_VIRTUAL_FBO_POC, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE);
    }
}
