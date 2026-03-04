package io.stamethyst

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import io.stamethyst.config.LauncherConfig

object LauncherIconManager {
    fun applySelection(context: Context, icon: LauncherIcon): LauncherIcon {
        setAliasState(context, icon)
        saveSelection(context, icon)
        return effectiveSelection(context, icon)
    }

    fun syncSelection(context: Context): LauncherIcon {
        val selected = readSelection(context)
        setAliasState(context, selected)
        return effectiveSelection(context, selected)
    }

    fun readSelection(context: Context): LauncherIcon {
        return LauncherConfig.readLauncherIcon(context)
    }

    fun readEffectiveSelection(context: Context): LauncherIcon {
        return effectiveSelection(context, readSelection(context))
    }

    private fun saveSelection(context: Context, icon: LauncherIcon) {
        LauncherConfig.saveLauncherIcon(context, icon)
    }

    private fun setAliasState(context: Context, selected: LauncherIcon) {
        val debugBuild = isDebugBuild(context)
        val effectiveSelected = if (debugBuild) LauncherIcon.AMBER else selected
        val packageManager = context.packageManager
        LauncherIcon.entries.forEach { candidate ->
            val desiredState = if (candidate == effectiveSelected) {
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
            }
        }
    }

    private fun effectiveSelection(context: Context, selected: LauncherIcon): LauncherIcon {
        return if (isDebugBuild(context)) {
            LauncherIcon.AMBER
        } else {
            selected
        }
    }

    private fun isDebugBuild(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
