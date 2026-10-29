package io.stamethyst.backend.mods

import io.stamethyst.backend.network.AcceleratedRouteEvent
import io.stamethyst.backend.network.AcceleratedRouteEvents
import io.stamethyst.backend.progress.LoadNarrationSession
import io.stamethyst.backend.progress.LoadNarrationUpdate
import io.stamethyst.backend.progress.NarratedLoadPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-perceivable stage of an AI patch catalogue load.
 *
 * The catalogue is a single raw.githubusercontent.com JSON index, so the stages mirror what the
 * accelerated transport actually does: resolve a route, probe nodes, connect, retry a failing node,
 * fall back to the official origin, then read and cache the index.
 */
internal enum class CatalogueLoadPhase : NarratedLoadPhase {
    /** Reading the on-disk cache and assembling the request. */
    Preparing,

    /** Choosing an acceleration route for the catalogue host. */
    ResolvingRoute,

    /** Measuring candidate acceleration nodes. */
    ProbingNodes,

    /** Downloading the index from a chosen node. */
    Connecting,

    /** A node failed; backing off to another node. */
    FailingOver,

    /** All accelerated nodes are unusable; using the official origin. */
    FallingBack,

    /** Response received: parsing the index and refreshing the on-disk cache. */
    Parsing,

    /** Terminal success. */
    Completed,

    /** Terminal failure. */
    Failed,
    ;

    override val isTerminal: Boolean
        get() = this == Completed || this == Failed

    override val isRecovery: Boolean
        get() = this == FailingOver || this == FallingBack
}

/** A progress snapshot for one catalogue fetch attempt. */
internal data class CatalogueLoadProgress(
    val sessionId: Long,
    val phase: CatalogueLoadPhase,
    /** Node currently being used or retried, when the phase concerns a specific node. */
    val target: String? = null,
    /** How many distinct nodes have failed during this attempt. */
    val failedTargetCount: Int = 0,
    /** Short technical reason for the latest failure, for diagnostics text. */
    val detail: String? = null,
)

/**
 * Publishes [CatalogueLoadProgress] for the AI patch catalogue screen.
 *
 * Routing events arrive on OkHttp threads while the fetch is in flight, so the narration is exposed
 * as a [StateFlow] rather than through a raw callback: the screen collects it on the main dispatcher
 * instead of mutating Compose state from a transport thread. The last snapshot is kept after a
 * session ends so the bar can collapse with its final text intact.
 */
internal object CatalogueLoadProgressReporter {
    private val session = LoadNarrationSession<CatalogueLoadPhase>(
        initialPhase = CatalogueLoadPhase.Preparing,
        recoveryFloor = CatalogueLoadPhase.Connecting,
    )

    private val mutableProgress = MutableStateFlow<CatalogueLoadProgress?>(null)
    val progress: StateFlow<CatalogueLoadProgress?> = mutableProgress.asStateFlow()

    private val lock = Any()
    private var activeSessionId: Long = 0L
    private var observeRouteEvents = true
    private var routeListenerInstalled = false

    private val routeListener: (AcceleratedRouteEvent) -> Unit = ::onRouteEvent

    init {
        session.setListener { update -> mutableProgress.value = update.toCatalogueProgress() }
    }

    /** Opens a new attempt and makes it the only one whose updates are forwarded. */
    fun beginSession(observeRoutes: Boolean = true): Long {
        installRouteListener()
        // Drop the previous attempt's final snapshot before the new one narrates, so the bar never
        // opens on the last run's "loaded, 100%" frame.
        mutableProgress.value = null
        val sessionId = session.beginSession()
        synchronized(lock) {
            activeSessionId = sessionId
            observeRouteEvents = observeRoutes
        }
        return sessionId
    }

    fun report(
        sessionId: Long,
        phase: CatalogueLoadPhase,
        target: String? = null,
        detail: String? = null,
    ) {
        session.report(sessionId = sessionId, phase = phase, target = target, detail = detail)
    }

    /** Ends the attempt so late routing events from a finished fetch are ignored. */
    fun endSession(sessionId: Long) {
        synchronized(lock) {
            if (sessionId == activeSessionId) activeSessionId = 0L
        }
        session.endSession(sessionId)
    }

    private fun installRouteListener() {
        synchronized(lock) {
            if (routeListenerInstalled) return
            // Kept for the process lifetime: the listener is inert unless a session is active, and
            // an add/remove pair per fetch would race a fetch that outlives its screen.
            AcceleratedRouteEvents.addListener(routeListener)
            routeListenerInstalled = true
        }
    }

    private fun LoadNarrationUpdate<CatalogueLoadPhase>.toCatalogueProgress() = CatalogueLoadProgress(
        sessionId = sessionId,
        phase = phase,
        target = target,
        failedTargetCount = failedTargetCount,
        detail = detail,
    )

    private fun onRouteEvent(event: AcceleratedRouteEvent) {
        // The catalogue is the only thing this screen waits on, so a routing event for any other
        // host (a workshop preview, an avatar) must not narrate this bar.
        if (!event.host.equals(CATALOGUE_HOST, ignoreCase = true)) return
        val sessionId = synchronized(lock) { if (observeRouteEvents) activeSessionId else 0L }
        if (sessionId == 0L) return
        when (event) {
            is AcceleratedRouteEvent.RouteDiscoveryStarted ->
                report(sessionId, CatalogueLoadPhase.ResolvingRoute)

            is AcceleratedRouteEvent.RouteDiscovered ->
                report(
                    sessionId = sessionId,
                    phase = CatalogueLoadPhase.ProbingNodes,
                    detail = "nodes=${event.forwardTargetCount}",
                )

            is AcceleratedRouteEvent.RouteDiscoveryFailed ->
                report(sessionId, CatalogueLoadPhase.FallingBack, detail = "route unavailable")

            is AcceleratedRouteEvent.ForwardTargetAttempt ->
                report(sessionId, CatalogueLoadPhase.Connecting, target = event.target)

            is AcceleratedRouteEvent.ForwardTargetFailed ->
                report(
                    sessionId = sessionId,
                    phase = CatalogueLoadPhase.FailingOver,
                    target = event.target,
                    detail = event.reason,
                )

            is AcceleratedRouteEvent.ForwardTargetSucceeded ->
                report(sessionId, CatalogueLoadPhase.Parsing, target = event.target)

            is AcceleratedRouteEvent.OfficialAttempt ->
                report(sessionId, CatalogueLoadPhase.FallingBack)

            is AcceleratedRouteEvent.OfficialFailed ->
                report(sessionId, CatalogueLoadPhase.FallingBack, detail = event.reason)

            is AcceleratedRouteEvent.OfficialSucceeded ->
                report(sessionId, CatalogueLoadPhase.Parsing)
        }
    }

    /** Logical host of the catalogue index; only routing events for it are narrated. */
    private const val CATALOGUE_HOST = "raw.githubusercontent.com"
}
