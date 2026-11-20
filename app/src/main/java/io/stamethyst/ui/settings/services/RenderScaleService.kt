package io.stamethyst.ui.settings.services

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.steamcloud.*

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.IOException

internal object RenderScaleService {
    const val DEFAULT_RENDER_SCALE = LauncherConfig.DEFAULT_RENDER_SCALE

    fun readValue(context: Context): Float {
        return LauncherConfig.readRenderScale(context)
    }

    @Throws(IOException::class)
    fun reset(context: Context) {
        LauncherConfig.resetRenderScale(context)
    }

    fun format(value: Float): String {
        return LauncherConfig.formatRenderScale(value)
    }
}

