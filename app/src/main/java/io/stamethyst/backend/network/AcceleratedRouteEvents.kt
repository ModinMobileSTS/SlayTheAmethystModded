package io.stamethyst.backend.network

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Routing decisions made by the Watt acceleration layer.
 *
 * The acceleration code silently picks a forward node, backs off from a failing one, and can fall
 * back to the official origin. None of that was observable, so a caller could not tell a slow node
 * apart from a stalled request. These events exist so a UI can narrate the attempt it is waiting on.
 */
internal sealed interface AcceleratedRouteEvent {
    /** Logical origin host being requested, for example `steamcommunity.com`. */
    val host: String

    data class RouteDiscoveryStarted(override val host: String) : AcceleratedRouteEvent

    data class RouteDiscovered(
        override val host: String,
        val forwardTargetCount: Int,
        val preferOfficial: Boolean,
    ) : AcceleratedRouteEvent

    data class RouteDiscoveryFailed(override val host: String) : AcceleratedRouteEvent

    data class ForwardTargetAttempt(
        override val host: String,
        val target: String,
    ) : AcceleratedRouteEvent

    data class ForwardTargetFailed(
        override val host: String,
        val target: String,
        val reason: String,
    ) : AcceleratedRouteEvent

    data class ForwardTargetSucceeded(
        override val host: String,
        val target: String,
    ) : AcceleratedRouteEvent

    data class OfficialAttempt(override val host: String) : AcceleratedRouteEvent

    data class OfficialFailed(
        override val host: String,
        val reason: String,
    ) : AcceleratedRouteEvent

    data class OfficialSucceeded(override val host: String) : AcceleratedRouteEvent
}

/**
 * Process-wide fan-out for [AcceleratedRouteEvent].
 *
 * Emission happens on OkHttp's own threads while a request is in flight, so listeners must be cheap
 * and must never throw back into the transport: a broken observer must not be able to fail a
 * download. Listeners are therefore invoked defensively and their failures are discarded.
 */
internal object AcceleratedRouteEvents {
    private val listeners = CopyOnWriteArrayList<(AcceleratedRouteEvent) -> Unit>()

    fun addListener(listener: (AcceleratedRouteEvent) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (AcceleratedRouteEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(event: AcceleratedRouteEvent) {
        if (listeners.isEmpty()) {
            return
        }
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }
}
