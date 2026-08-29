package io.stamethyst.backend.steamcloud

internal class RichPresenceDispatchState(initialPresence: Map<String, String>) {
    private var lastUploadedPresence: Map<String, String>? = null
    private var pendingPresence = initialPresence

    val shouldUpload: Boolean
        get() = pendingPresence != lastUploadedPresence

    fun pending(): Map<String, String> = pendingPresence

    fun update(presence: Map<String, String>) {
        pendingPresence = presence
    }

    fun markUploaded() {
        lastUploadedPresence = pendingPresence
    }
}
