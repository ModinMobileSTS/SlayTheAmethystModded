package io.stamethyst.input

import kotlin.math.roundToInt

internal data class WindowCoords(
    val x: Float,
    val y: Float
)

internal data class ContentBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

internal fun resolveContentBounds(
    rawViewWidth: Int,
    rawViewHeight: Int,
    windowWidthRaw: Int,
    windowHeightRaw: Int
): ContentBounds {
    val viewWidth = maxOf(1, rawViewWidth)
    val viewHeight = maxOf(1, rawViewHeight)
    val windowWidth = maxOf(1, windowWidthRaw)
    val windowHeight = maxOf(1, windowHeightRaw)
    val scale = minOf(
        viewWidth.toFloat() / windowWidth.toFloat(),
        viewHeight.toFloat() / windowHeight.toFloat()
    )
    // Keep this inverse transform aligned with the Android compositor's centered fit scaling.
    val width = maxOf(1, (windowWidth * scale).roundToInt())
    val height = maxOf(1, (windowHeight * scale).roundToInt())
    return ContentBounds(
        left = ((viewWidth - width) / 2).toFloat(),
        top = ((viewHeight - height) / 2).toFloat(),
        width = width.toFloat(),
        height = height.toFloat()
    )
}

internal fun glfwCursorYFromMappedViewY(mappedY: Float, windowHeight: Int): Float {
    // Android view and GLFW cursor coordinates both use a top-left origin.
    val height = maxOf(1, windowHeight)
    return mappedY.coerceIn(0f, height - 1f)
}

internal inline fun mapViewToWindowCoords(
    viewX: Float,
    viewY: Float,
    rawViewWidth: Int,
    rawViewHeight: Int,
    windowWidthRaw: Int,
    windowHeightRaw: Int,
    onMapped: (x: Float, y: Float, windowHeight: Int) -> Unit
) {
    val viewWidth = maxOf(1, rawViewWidth)
    val viewHeight = maxOf(1, rawViewHeight)
    val windowWidth = maxOf(1, windowWidthRaw)
    val windowHeight = maxOf(1, windowHeightRaw)

    val content = resolveContentBounds(viewWidth, viewHeight, windowWidth, windowHeight)
    var mappedX = ((viewX - content.left) * windowWidth) / content.width
    var mappedY = ((viewY - content.top) * windowHeight) / content.height

    if (mappedX < 0f) {
        mappedX = 0f
    } else if (mappedX > windowWidth - 1f) {
        mappedX = windowWidth - 1f
    }
    if (mappedY < 0f) {
        mappedY = 0f
    } else if (mappedY > windowHeight - 1f) {
        mappedY = windowHeight - 1f
    }
    onMapped(mappedX, mappedY, windowHeight)
}

internal fun mapViewToWindowCoords(
    viewX: Float,
    viewY: Float,
    rawViewWidth: Int,
    rawViewHeight: Int,
    windowWidthRaw: Int,
    windowHeightRaw: Int
): WindowCoords {
    var resultX = 0f
    var resultY = 0f
    mapViewToWindowCoords(
        viewX = viewX,
        viewY = viewY,
        rawViewWidth = rawViewWidth,
        rawViewHeight = rawViewHeight,
        windowWidthRaw = windowWidthRaw,
        windowHeightRaw = windowHeightRaw
    ) { mappedX, mappedY, _ ->
        resultX = mappedX
        resultY = mappedY
    }
    return WindowCoords(resultX, resultY)
}
