package io.stamethyst.backend.render

internal fun shouldRequestHighViewFrameRate(
    sdkInt: Int,
    foreground: Boolean,
    focused: Boolean,
    contentAvailable: Boolean = true,
): Boolean = sdkInt >= 35 && foreground && focused && contentAvailable

/** Owns only our View preference, not a Window/Surface vote or the system's selected rate. */
internal class ViewFrameRateVoteState(
    private val highRate: Float,
    private val readRate: () -> Float,
    private val writeRate: (Float) -> Unit,
) {
    private var originalRate: Float? = null
    private var appliedRate: Float? = null

    fun setActive(active: Boolean) {
        if (!active && originalRate == null) return
        val current = readRate()
        if (!active) {
            val original = originalRate ?: return
            // Another owner (e.g. TextureView's SurfaceTexture forwarding) may have changed it.
            // Never overwrite that preference when releasing our own vote.
            if (appliedRate?.let { sameRate(current, it) } == true && !sameRate(current, original)) {
                writeRate(original)
            }
            originalRate = null
            appliedRate = null
            return
        }

        if (originalRate == null || appliedRate?.let { !sameRate(current, it) } == true) {
            originalRate = current
        }
        // Record before calling the setter so even a setter that throws after applying can be
        // restored. A failed write is retried only on the next lifecycle event, never a timer.
        appliedRate = highRate
        if (!sameRate(current, highRate)) writeRate(highRate)
    }

    private fun sameRate(first: Float, second: Float): Boolean =
        first == second || (first.isNaN() && second.isNaN())
}
