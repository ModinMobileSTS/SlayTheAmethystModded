package io.stamethyst.ui.settings.sections

import android.app.Activity
import android.graphics.Matrix
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import io.stamethyst.backend.render.DisplayGeometryRotation
import io.stamethyst.backend.render.DisplayPixelRect
import io.stamethyst.backend.render.GameDisplayRegion

/** Full display coordinates, never the dialog's or the launcher's inset content bounds. */
internal data class DeviceDisplayGeometry(
    val width: Int,
    val height: Int,
    val cutoutBounds: List<DisplayPixelRect> = emptyList(),
    val cornerRadii: List<Float> = List(4) { 0f }, // TL, TR, BR, BL
    val cutoutPath: Path? = null,
    val cutoutSafeRegion: GameDisplayRegion? = null,
) {
    fun legacyRegion(avoidCutout: Boolean, cropOppositeEdge: Boolean): GameDisplayRegion {
        // Punch holes need not touch an edge. Android's safe insets include their entire depth.
        val safe = cutoutSafeRegion?.toPixels(width, height)
        val leftInset = safe?.left ?: cutoutBounds.filter { it.left <= 0 }.maxOfOrNull { it.right } ?: 0
        val rightInset = safe?.let { width - it.right }
            ?: cutoutBounds.filter { it.right >= width }.maxOfOrNull { width - it.left } ?: 0
        val topInset = safe?.top ?: cutoutBounds.filter { it.top <= 0 }.maxOfOrNull { it.bottom } ?: 0
        val bottomInset = safe?.let { height - it.bottom }
            ?: cutoutBounds.filter { it.bottom >= height }.maxOfOrNull { height - it.top } ?: 0
        val opposite = maxOf(leftInset, rightInset)
        return GameDisplayRegion(
            left = (if (avoidCutout) leftInset else 0).toFloat() / width +
                (if (cropOppositeEdge && rightInset > leftInset) opposite.toFloat() / width else 0f),
            top = (if (avoidCutout) topInset else 0).toFloat() / height,
            right = 1f - (if (avoidCutout) rightInset else 0).toFloat() / width -
                (if (cropOppositeEdge && leftInset >= rightInset) opposite.toFloat() / width else 0f),
            bottom = 1f - (if (avoidCutout) bottomInset else 0).toFloat() / height,
        ).normalized()
    }

    companion object {
        @Suppress("DEPRECATION")
        fun read(activity: Activity, hostView: View): DeviceDisplayGeometry {
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display?.getRealMetrics(metrics)
            val width: Int
            val height: Int
            val insets: WindowInsets?
            if (Build.VERSION.SDK_INT >= 30) {
                val windowMetrics = activity.windowManager.maximumWindowMetrics
                width = windowMetrics.bounds.width().coerceAtLeast(1)
                height = windowMetrics.bounds.height().coerceAtLeast(1)
                insets = windowMetrics.windowInsets
            } else {
                width = metrics.widthPixels.coerceAtLeast(1)
                height = metrics.heightPixels.coerceAtLeast(1)
                insets = hostView.rootWindowInsets
            }
            val rotation = display?.rotation ?: 0
            val target = DisplayGeometryRotation.canonicalLandscapeRotation(width, height, rotation)
            val turns = (target - rotation + 4) % 4
            val cutout = when {
                Build.VERSION.SDK_INT >= 30 -> insets?.displayCutout
                Build.VERSION.SDK_INT >= 29 -> display?.cutout ?: insets?.displayCutout
                Build.VERSION.SDK_INT >= 28 -> insets?.displayCutout
                else -> null
            }
            val bounds = if (Build.VERSION.SDK_INT >= 28) cutout?.boundingRects.orEmpty().filter { !it.isEmpty }.map {
                DisplayGeometryRotation.rotateRect(
                    DisplayPixelRect(it.left, it.top, it.right, it.bottom), width, height, turns
                )
            } else emptyList()
            val safeRect = if (Build.VERSION.SDK_INT >= 28) cutout?.let {
                DisplayGeometryRotation.rotateRect(DisplayPixelRect(
                    it.safeInsetLeft, it.safeInsetTop, width - it.safeInsetRight, height - it.safeInsetBottom
                ), width, height, turns)
            } else null
            val landscapeWidth = if (turns % 2 == 0) width else height
            val landscapeHeight = if (turns % 2 == 0) height else width
            val radii = if (Build.VERSION.SDK_INT >= 31) {
                listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT).map {
                    insets?.getRoundedCorner(it)?.radius?.toFloat() ?: 0f
                }
            } else List(4) { 0f }
            val path = if (Build.VERSION.SDK_INT >= 31) cutout?.cutoutPath?.let { source ->
                Path(source).apply {
                    val matrix = Matrix()
                    matrix.setValues(when (turns) {
                        1 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
                        2 -> floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
                        3 -> floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
                        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                    })
                    transform(matrix)
                }
            } else null
            return DeviceDisplayGeometry(
                width = landscapeWidth,
                height = landscapeHeight,
                cutoutBounds = bounds,
                cornerRadii = List(4) { radii[(it + turns) % 4] },
                cutoutPath = path,
                cutoutSafeRegion = safeRect?.let {
                    GameDisplayRegion(it.left.toFloat() / landscapeWidth, it.top.toFloat() / landscapeHeight,
                        it.right.toFloat() / landscapeWidth, it.bottom.toFloat() / landscapeHeight).normalized()
                },
            )
        }
    }
}
