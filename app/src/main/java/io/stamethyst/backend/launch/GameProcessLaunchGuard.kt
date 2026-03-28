package io.stamethyst.backend.launch

import java.util.concurrent.atomic.AtomicReference

internal object GameProcessLaunchGuard {
    private val activeOwnerToken = AtomicReference<String?>(null)

    fun tryAcquire(ownerToken: String): Boolean {
        if (ownerToken.isBlank()) {
            return false
        }
        return activeOwnerToken.compareAndSet(null, ownerToken) ||
            activeOwnerToken.get() == ownerToken
    }

    fun release(ownerToken: String) {
        if (ownerToken.isBlank()) {
            return
        }
        activeOwnerToken.compareAndSet(ownerToken, null)
    }

    internal fun resetForTest() {
        activeOwnerToken.set(null)
    }
}
