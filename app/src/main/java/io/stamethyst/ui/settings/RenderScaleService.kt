package io.stamethyst.ui.settings

import android.app.Activity
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object RenderScaleService {
    const val DEFAULT_RENDER_SCALE = LauncherConfig.DEFAULT_RENDER_SCALE
    const val MIN_RENDER_SCALE = LauncherConfig.MIN_RENDER_SCALE
    const val MAX_RENDER_SCALE = LauncherConfig.MAX_RENDER_SCALE

    fun readValue(host: Activity): Float {
        return LauncherConfig.readRenderScale(host)
    }

    @Throws(IOException::class)
    fun reset(host: Activity) {
        LauncherConfig.resetRenderScale(host)
    }

    @Throws(IOException::class)
    fun save(host: Activity, value: Float): String {
        return LauncherConfig.saveRenderScale(host, value)
    }

    fun format(value: Float): String {
        return LauncherConfig.formatRenderScale(value)
    }
}
