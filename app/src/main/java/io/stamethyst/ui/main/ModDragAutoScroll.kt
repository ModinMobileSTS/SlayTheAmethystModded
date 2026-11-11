package io.stamethyst.ui.main

import kotlin.math.abs

/** Density-aware edge zones never overlap, including in short list viewports. */
internal fun modDragAutoScrollDelta(
    pointerX: Float,
    pointerY: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    density: Float,
): Float {
    if (density <= 0f || bottom <= top || pointerX < left || pointerX > right) return 0f
    val edge = minOf(96f * density, (bottom - top) / 3f)
    val ratio = when {
        pointerY < top + edge -> -((top + edge - pointerY) / edge).coerceIn(0f, 1f)
        pointerY > bottom - edge -> ((pointerY - bottom + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
    return 20f * density * ratio * abs(ratio)
}
