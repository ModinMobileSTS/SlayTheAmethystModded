package io.stamethyst.input

internal data class WindowCoords(
    val x: Float,
    val y: Float
)

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

    var mappedX = (viewX * windowWidth) / viewWidth.toFloat()
    var mappedY = (viewY * windowHeight) / viewHeight.toFloat()

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
