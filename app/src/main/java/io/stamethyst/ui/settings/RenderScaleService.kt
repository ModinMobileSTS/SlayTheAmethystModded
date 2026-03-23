package io.stamethyst.ui.settings

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object RenderScaleService {
    const val DEFAULT_RENDER_SCALE = LauncherConfig.DEFAULT_RENDER_SCALE
    const val MIN_RENDER_SCALE = LauncherConfig.MIN_RENDER_SCALE
    const val MAX_RENDER_SCALE = LauncherConfig.MAX_RENDER_SCALE

    fun readValue(context: Context): Float {
        return LauncherConfig.readRenderScale(context)
    }

    @Throws(IOException::class)
    fun reset(context: Context) {
        LauncherConfig.resetRenderScale(context)
    }

    @Throws(IOException::class)
    fun save(context: Context, value: Float): String {
        return LauncherConfig.saveRenderScale(context, value)
    }

    fun format(value: Float): String {
        return LauncherConfig.formatRenderScale(value)
    }
}
