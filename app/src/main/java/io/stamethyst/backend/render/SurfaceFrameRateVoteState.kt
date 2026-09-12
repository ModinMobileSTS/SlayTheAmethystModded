package io.stamethyst.backend.render

/** A SurfaceView can reuse its Java Surface after its native layer has been recreated. */
internal class SurfaceFrameRateVoteState {
    private var lastSurface: Any? = null
    private var lastGeneration = -1
    private var lastRefreshRateHz = Float.NaN

    fun shouldApply(surface: Any, generation: Int, refreshRateHz: Float): Boolean =
        lastSurface !== surface || lastGeneration != generation ||
            kotlin.math.abs(lastRefreshRateHz - refreshRateHz) >= 0.01f ||
            lastRefreshRateHz.isNaN()

    fun recordApplied(surface: Any, generation: Int, refreshRateHz: Float) {
        lastSurface = surface
        lastGeneration = generation
        lastRefreshRateHz = refreshRateHz
    }

    fun clear() {
        lastSurface = null
        lastGeneration = -1
        lastRefreshRateHz = Float.NaN
    }
}
