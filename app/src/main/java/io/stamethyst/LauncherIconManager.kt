package io.stamethyst

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object LauncherIconManager {
    private const val TAG = "LauncherIconManager"
    private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
    private const val PREF_KEY_LAUNCHER_ICON = "launcher_icon"
    private val defaultIcon = LauncherIcon.AMBER

    fun applySelection(context: Context, icon: LauncherIcon) {
        setAliasState(context, icon)
        saveSelection(context, icon)
    }

    fun syncSelection(context: Context): LauncherIcon {
        val selected = readSelection(context)
        setAliasState(context, selected)
        return selected
    }

    fun readSelection(context: Context): LauncherIcon {
        val rawValue = context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .getString(PREF_KEY_LAUNCHER_ICON, defaultIcon.name)
        return runCatching { LauncherIcon.valueOf(rawValue ?: "") }
            .getOrDefault(defaultIcon)
    }

    private fun saveSelection(context: Context, icon: LauncherIcon) {
        context.getSharedPreferences(PREF_NAME_LAUNCHER, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_LAUNCHER_ICON, icon.name)
            .apply()
    }

    private fun setAliasState(context: Context, selected: LauncherIcon) {
        val packageManager = context.packageManager
        LauncherIcon.entries.forEach { candidate ->
            val desiredState = if (candidate == selected) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val aliasClassName = candidate.resolveAliasClassName(context.packageName)
            val componentName = ComponentName(context.packageName, aliasClassName)
            runCatching {
                packageManager.setComponentEnabledSetting(
                    componentName,
                    desiredState,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure { error ->
                Log.w(TAG, "set launcher alias failed: $aliasClassName state=$desiredState", error)
            }
        }
    }
}
