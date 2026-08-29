package io.stamethyst.backend.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.DisplayMetrics

internal data class FullscreenCanvasSize(
    val width: Int,
    val height: Int
)

/**
 * Resolves the fixed game canvas from the physical display rather than the current freeform
 * window. The game always runs in landscape, so normalize the panel dimensions accordingly.
 */
internal object FullscreenCanvasResolution {
    @Suppress("DEPRECATION")
    fun resolve(context: Context): FullscreenCanvasSize {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        val width = metrics.widthPixels.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val height = metrics.heightPixels.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return normalizeLandscape(width, height)
    }

    @JvmStatic
    fun normalizeLandscape(width: Int, height: Int): FullscreenCanvasSize {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return FullscreenCanvasSize(
            width = maxOf(safeWidth, safeHeight),
            height = minOf(safeWidth, safeHeight)
        )
    }
}
