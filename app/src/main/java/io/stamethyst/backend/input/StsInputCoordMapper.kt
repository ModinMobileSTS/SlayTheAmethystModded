package io.stamethyst.backend.input

internal fun mapViewToWindowCoords(
    viewX: Float,
    viewY: Float,
    rawViewWidth: Int,
    rawViewHeight: Int,
    windowWidthRaw: Int,
    windowHeightRaw: Int
): FloatArray {
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
    return floatArrayOf(mappedX, mappedY)
}
