package io.stamethyst.backend.audio

/** The callback's initial device inventory is a baseline, not a route interruption. */
internal class AudioOutputRouteBaseline {
    private var sinks: Set<Int>? = null

    fun update(current: Set<Int>): Boolean {
        val previous = sinks
        sinks = current.toSet()
        return previous != null && previous != current
    }
}
