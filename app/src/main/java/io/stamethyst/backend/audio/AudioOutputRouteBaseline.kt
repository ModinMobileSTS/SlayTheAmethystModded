package io.stamethyst.backend.audio

/** The callback's initial device inventory is a baseline, not a route interruption. */
internal class AudioOutputRouteBaseline {
    private var sinks: Set<Int>? = null
    var hasPendingChange = false
        private set

    fun update(current: Set<Int>): Boolean {
        val previous = sinks
        sinks = current.toSet()
        val changed = previous != null && previous != current
        if (changed) markChanged()
        return changed
    }

    fun markChanged() {
        hasPendingChange = true
    }

    fun consumePendingChange(): Boolean {
        val pending = hasPendingChange
        hasPendingChange = false
        return pending
    }
}
