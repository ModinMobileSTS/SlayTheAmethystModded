package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.LauncherConfig

object BackExitNotice {
    @JvmStatic
    fun markExpectedBackExit(context: Context) {
        LauncherConfig.markExpectedBackExit(context)
    }

    @JvmStatic
    fun consumeExpectedBackExitIfRecent(context: Context): Boolean {
        return LauncherConfig.consumeExpectedBackExitIfRecent(context)
    }
}
