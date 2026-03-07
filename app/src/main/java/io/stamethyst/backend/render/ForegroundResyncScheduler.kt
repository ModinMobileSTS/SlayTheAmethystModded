package io.stamethyst.backend.render

import java.util.LinkedHashSet

internal class ForegroundResyncScheduler {
    private val pendingReasons = LinkedHashSet<String>()
    private var scheduled = false

    fun request(reason: String): Boolean {
        if (reason.isNotBlank()) {
            pendingReasons += reason
        }
        if (scheduled) {
            return false
        }
        scheduled = true
        return true
    }

    fun drain(): Set<String> {
        scheduled = false
        if (pendingReasons.isEmpty()) {
            return emptySet()
        }
        val reasons = LinkedHashSet(pendingReasons)
        pendingReasons.clear()
        return reasons
    }
}
