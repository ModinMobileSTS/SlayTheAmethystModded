package io.stamethyst.backend.render

import kotlin.math.roundToInt

internal class RenderSurfaceState(
    private val renderScale: Float
) {
    internal data class ApplyPlan(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val windowWidth: Int,
        val windowHeight: Int,
        val shouldApplyBufferSize: Boolean,
        val shouldDispatchWindowSize: Boolean
    )

    var surfaceBufferWidth = 0
        private set
    var surfaceBufferHeight = 0
        private set
    var bridgeSurfaceReady = false
        private set
    var surfaceGeneration = 0
        private set
    var lastTextureFrameTimestampNs = 0L
        private set
    var isForeground = false
        private set
    var hasWindowFocus = false
        private set
    var holderResizeCount = 0
        private set
    var bufferApplyCount = 0
        private set
    var bufferSkipCount = 0
        private set
    var windowSizeDispatchCount = 0
        private set
    var windowSizeSkipCount = 0
        private set

    private var hasActiveSurface = false
    private var lastPhysicalWidth = 0
    private var lastPhysicalHeight = 0
    private var lastAppliedBufferWidth = 0
    private var lastAppliedBufferHeight = 0
    private var lastAppliedBufferGeneration = -1
    private var lastDispatchedWindowWidth = 0
    private var lastDispatchedWindowHeight = 0

    fun rememberPhysicalSize(width: Int, height: Int): Boolean {
        var changed = false
        if (width > 0 && width != lastPhysicalWidth) {
            lastPhysicalWidth = width
            changed = true
        }
        if (height > 0 && height != lastPhysicalHeight) {
            lastPhysicalHeight = height
            changed = true
        }
        return changed
    }

    fun markSurfaceAvailable(generation: Int, width: Int, height: Int) {
        hasActiveSurface = true
        bridgeSurfaceReady = false
        surfaceGeneration = generation
        rememberPhysicalSize(width, height)
    }

    fun markSurfaceDestroyed() {
        hasActiveSurface = false
        bridgeSurfaceReady = false
        surfaceGeneration = 0
        surfaceBufferWidth = 0
        surfaceBufferHeight = 0
        lastPhysicalWidth = 0
        lastPhysicalHeight = 0
        lastAppliedBufferWidth = 0
        lastAppliedBufferHeight = 0
        lastAppliedBufferGeneration = -1
    }

    fun markBridgeSurfaceReady(ready: Boolean) {
        bridgeSurfaceReady = ready
    }

    fun updateTextureFrameTimestamp(timestampNs: Long) {
        lastTextureFrameTimestampNs = timestampNs
    }

    fun markForeground(foreground: Boolean) {
        isForeground = foreground
    }

    fun markWindowFocus(hasFocus: Boolean) {
        hasWindowFocus = hasFocus
    }

    fun resolvePhysicalWidth(viewWidth: Int = 0): Int {
        return when {
            viewWidth > 0 -> viewWidth
            lastPhysicalWidth > 0 -> lastPhysicalWidth
            else -> 1
        }
    }

    fun resolvePhysicalHeight(viewHeight: Int = 0): Int {
        return when {
            viewHeight > 0 -> viewHeight
            lastPhysicalHeight > 0 -> lastPhysicalHeight
            else -> 1
        }
    }

    fun buildApplyPlan(viewWidth: Int = 0, viewHeight: Int = 0): ApplyPlan {
        val physicalWidth = resolvePhysicalWidth(viewWidth)
        val physicalHeight = resolvePhysicalHeight(viewHeight)
        val bufferWidth = (physicalWidth * renderScale).roundToInt().coerceAtLeast(1)
        val bufferHeight = (physicalHeight * renderScale).roundToInt().coerceAtLeast(1)
        surfaceBufferWidth = bufferWidth
        surfaceBufferHeight = bufferHeight
        val shouldApplyBufferSize = hasActiveSurface &&
            (bufferWidth != lastAppliedBufferWidth ||
                bufferHeight != lastAppliedBufferHeight ||
                surfaceGeneration != lastAppliedBufferGeneration)
        val shouldDispatchWindowSize =
            bufferWidth != lastDispatchedWindowWidth || bufferHeight != lastDispatchedWindowHeight
        return ApplyPlan(
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            windowWidth = bufferWidth,
            windowHeight = bufferHeight,
            shouldApplyBufferSize = shouldApplyBufferSize,
            shouldDispatchWindowSize = shouldDispatchWindowSize
        )
    }

    fun recordBufferApply(plan: ApplyPlan, applied: Boolean, incrementsHolderResize: Boolean) {
        if (applied) {
            lastAppliedBufferWidth = plan.bufferWidth
            lastAppliedBufferHeight = plan.bufferHeight
            lastAppliedBufferGeneration = surfaceGeneration
            bufferApplyCount++
            if (incrementsHolderResize) {
                holderResizeCount++
            }
        } else {
            bufferSkipCount++
        }
    }

    fun recordWindowSizeDispatch(plan: ApplyPlan, dispatched: Boolean) {
        if (dispatched) {
            lastDispatchedWindowWidth = plan.windowWidth
            lastDispatchedWindowHeight = plan.windowHeight
            windowSizeDispatchCount++
        } else {
            windowSizeSkipCount++
        }
    }
}
