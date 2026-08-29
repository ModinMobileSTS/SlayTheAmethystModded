package io.stamethyst.config

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIconController {
    private const val AMETHYST_ALIAS = "LauncherActivityIconAmethyst"
    private const val WATCHER_ALIAS = "LauncherActivityIconWatcher"

    @JvmStatic
    fun applySavedIconMode(context: Context) {
        apply(context, LauncherConfig.readLauncherIconMode(context))
    }

    @JvmStatic
    fun apply(context: Context, iconMode: LauncherIconMode) {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val selectedComponent = componentFor(appContext, iconMode)

        setComponentState(
            packageManager = packageManager,
            component = selectedComponent,
            state = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        )
        LauncherIconMode.entries
            .map { componentFor(appContext, it) }
            .filterNot { it == selectedComponent }
            .forEach { component ->
                setComponentState(
                    packageManager = packageManager,
                    component = component,
                    state = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                )
            }
    }

    private fun componentFor(context: Context, iconMode: LauncherIconMode): ComponentName {
        val aliasName = when (iconMode) {
            LauncherIconMode.AMETHYST -> AMETHYST_ALIAS
            LauncherIconMode.WATCHER -> WATCHER_ALIAS
        }
        return ComponentName(context.packageName, "${context.packageName}.$aliasName")
    }

    private fun setComponentState(
        packageManager: PackageManager,
        component: ComponentName,
        state: Int
    ) {
        try {
            if (packageManager.getComponentEnabledSetting(component) == state) {
                return
            }
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: IllegalArgumentException) {
            // The launcher-icon alias is not declared in this package build.
            // This happens when an APK is repackaged for coexistence (e.g. via
            // MT Manager) and the repackager drops or mangles the activity-alias
            // components. Icon switching is best-effort — never let it crash
            // the app at startup.
        }
    }
}
