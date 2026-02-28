package io.stamethyst.backend.mods

import android.content.Context
import android.content.SharedPreferences

object CompatibilitySettings {
    private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
    private const val PREF_KEY_ORIGINAL_FBO_PATCH = "compat_original_fbo_patch"
    private const val PREF_KEY_DOWNFALL_FBO_PATCH = "compat_downfall_fbo_patch"
    private const val PREF_KEY_VIRTUAL_FBO_POC = "compat_virtual_fbo_poc"
    private const val PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT = "compat_global_atlas_filter_compat"

    @JvmStatic
    fun isOriginalFboPatchEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_ORIGINAL_FBO_PATCH, false)
    }

    @JvmStatic
    fun setOriginalFboPatchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_ORIGINAL_FBO_PATCH, enabled).apply()
    }

    @JvmStatic
    fun isDownfallFboPatchEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_DOWNFALL_FBO_PATCH, false)
    }

    @JvmStatic
    fun setDownfallFboPatchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_DOWNFALL_FBO_PATCH, enabled).apply()
    }

    @JvmStatic
    fun isVirtualFboPocEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_VIRTUAL_FBO_POC, false)
    }

    @JvmStatic
    fun setVirtualFboPocEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_VIRTUAL_FBO_POC, enabled).apply()
    }

    @JvmStatic
    fun isGlobalAtlasFilterCompatEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT, true)
    }

    @JvmStatic
    fun setGlobalAtlasFilterCompatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEY_GLOBAL_ATLAS_FILTER_COMPAT, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
    }
}
