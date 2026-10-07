package io.stamethyst.ui.main

/** A service restart can reset its sequence; an operation ID cannot belong to two UI requests. */
internal class SteamCloudOperationEventGate {
    private var operationId: String? = null
    private var sequence = 0L
    fun begin(id: String) { operationId = id; sequence = 0L }
    fun clear() { operationId = null; sequence = 0L }
    fun canAttach(requestedId: String? = null): Boolean = operationId == null || operationId == requestedId
    fun attach(id: String, requestedId: String? = null): Boolean {
        if (!canAttach(requestedId)) return false
        begin(id)
        return true
    }
    fun canAccept(id: String?, next: Long?): Boolean =
        id != null && id == operationId && next != null && next > sequence
    fun accept(id: String?, next: Long?): Boolean {
        if (!canAccept(id, next)) return false
        sequence = requireNotNull(next)
        return true
    }
}
