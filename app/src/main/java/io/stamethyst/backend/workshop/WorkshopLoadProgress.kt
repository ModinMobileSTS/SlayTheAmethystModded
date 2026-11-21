package io.stamethyst.backend.workshop

import io.stamethyst.backend.network.AcceleratedRouteEvent
import io.stamethyst.backend.network.AcceleratedRouteEvents
import io.stamethyst.backend.progress.LoadNarrationSession
import io.stamethyst.backend.progress.LoadNarrationUpdate
import io.stamethyst.backend.progress.NarratedLoadPhase

/**
 * A user-perceivable stage of a Workshop market load.
 *
 * Ordinal order is the order the pipeline normally advances through, so the UI can render a
 * monotonic bar. [FailingOver] and [FallingBack] are exceptions: they are recoveries that can occur
 * during [Connecting], and are reported so a slow load reads as "retrying another node" rather than
 * as a freeze.
 */
internal enum class WorkshopLoadPhase : NarratedLoadPhase {
    /** Preparing the request: service wiring, query assembly. */
    Preparing,

    /** Priming the Steam web session (token + login cookies). */
    Authenticating,

    /** Resolving which acceleration route to use. */
    ResolvingRoute,

    /** Measuring candidate acceleration nodes. */
    ProbingNodes,

    /** Talking to a chosen node. */
    Connecting,

    /** A node failed; backing off to another node. */
    FailingOver,

    /** All accelerated nodes are unusable; using the official origin. */
    FallingBack,

    /** Response received, extracting entries. */
    Parsing,

    /**
     * Detail page: waiting on the steamcommunity.com filedetails HTML, which carries the
     * description, preview media and comment thread context. This is the dominant slow
     * request in a detail load, so it gets its own stage instead of hiding inside [Parsing].
     */
    LoadingCommunityDetail,

    /** Detail page: resolving dependency metadata for the required-item ids. */
    LoadingDependencies,

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

/**
 * A progress snapshot for one market load attempt.
 *
 * [sessionId] lets the UI drop updates from a superseded load, which matters because the market
 * screen cancels and restarts loads on every filter change.
 */
internal data class WorkshopLoadProgress(
    val sessionId: Long,
    val phase: WorkshopLoadPhase,
    /** Node currently being used or retried, when the phase concerns a specific node. */
    val target: String? = null,
    /** How many accelerated nodes have failed during this attempt. */
    val failedTargetCount: Int = 0,
    /** Short technical reason for the latest failure, for diagnostics text. */
    val detail: String? = null,
)

/**
 * Publishes [WorkshopLoadProgress] for the market screen.
 *
 * This bridges two very different sources into one ordered stream: explicit stage calls from
 * [WorkshopService]/[WorkshopSteamWebSession], and routing events that the acceleration layer emits
 * from OkHttp threads. Only the currently active session is forwarded, so a stale in-flight request
 * cannot rewind the bar of a newer one.
 */
internal object WorkshopLoadProgressReporter {
    private val session = LoadNarrationSession<WorkshopLoadPhase>(
        initialPhase = WorkshopLoadPhase.Preparing,
        recoveryFloor = WorkshopLoadPhase.Connecting,
    )

    private val lock = Any()
    private var activeSessionId: Long = 0L
    private var routeListenerInstalled = false

    private val routeListener: (AcceleratedRouteEvent) -> Unit = ::onRouteEvent

    /** Registers the single consumer, normally the market ViewModel. */
    fun setListener(listener: ((WorkshopLoadProgress) -> Unit)?) {
        synchronized(lock) {
            if (listener == null) {
                if (routeListenerInstalled) {
                    AcceleratedRouteEvents.removeListener(routeListener)
                    routeListenerInstalled = false
                }
                activeSessionId = 0L
            } else if (!routeListenerInstalled) {
                AcceleratedRouteEvents.addListener(routeListener)
                routeListenerInstalled = true
            }
        }
        session.setListener(listener?.let { consumer -> { update ->
            consumer(update.toWorkshopProgress())
        } })
    }

    /** Opens a new attempt and makes it the only one whose updates are forwarded. */
    fun beginSession(): Long {
        val sessionId = session.beginSession()
        synchronized(lock) { activeSessionId = sessionId }
        return sessionId
    }

    fun report(
        sessionId: Long,
        phase: WorkshopLoadPhase,
        target: String? = null,
        detail: String? = null,
    ) {
        session.report(sessionId = sessionId, phase = phase, target = target, detail = detail)
    }

    /** Ends the attempt so late routing events from finished calls are ignored. */
    fun endSession(sessionId: Long) {
        synchronized(lock) {
            if (sessionId == activeSessionId) activeSessionId = 0L
        }
        session.endSession(sessionId)
    }

    private fun LoadNarrationUpdate<WorkshopLoadPhase>.toWorkshopProgress() = WorkshopLoadProgress(
        sessionId = sessionId,
        phase = phase,
        target = target,
        failedTargetCount = failedTargetCount,
        detail = detail,
    )

    private fun onRouteEvent(event: AcceleratedRouteEvent) {
        // Only the market's own origin is interesting here. Image and CDN hosts are loaded lazily by
        // list items and would otherwise reopen the progress bar after the list is already visible.
        if (!event.host.equals(STEAM_COMMUNITY_HOST, ignoreCase = true) &&
            !event.host.equals(STEAM_API_HOST, ignoreCase = true)
        ) {
            return
        }
        val sessionId = synchronized(lock) { activeSessionId }
        if (sessionId == 0L) {
            return
        }
        when (event) {
            is AcceleratedRouteEvent.RouteDiscoveryStarted ->
                report(
                    sessionId,
                    if (event.retrying) WorkshopLoadPhase.FailingOver else WorkshopLoadPhase.ResolvingRoute,
                )

            is AcceleratedRouteEvent.RouteDiscovered ->
                report(
                    sessionId = sessionId,
                    phase = WorkshopLoadPhase.ProbingNodes,
                    detail = "nodes=${event.forwardTargetCount}",
                )

            is AcceleratedRouteEvent.RouteDiscoveryFailed ->
                report(sessionId, WorkshopLoadPhase.FallingBack, detail = "route unavailable")

            is AcceleratedRouteEvent.ForwardTargetAttempt ->
                report(sessionId, WorkshopLoadPhase.Connecting, target = event.target)

            is AcceleratedRouteEvent.ForwardTargetFailed ->
                report(
                    sessionId = sessionId,
                    phase = WorkshopLoadPhase.FailingOver,
                    target = event.target,
                    detail = event.reason,
                )

            is AcceleratedRouteEvent.ForwardTargetSucceeded ->
                report(sessionId, WorkshopLoadPhase.Parsing, target = event.target)

            is AcceleratedRouteEvent.OfficialAttempt ->
                report(sessionId, WorkshopLoadPhase.FallingBack)

            is AcceleratedRouteEvent.OfficialFailed ->
                report(sessionId, WorkshopLoadPhase.FallingBack, detail = event.reason)

            is AcceleratedRouteEvent.OfficialSucceeded ->
                report(sessionId, WorkshopLoadPhase.Parsing)
        }
    }

    private const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
    private const val STEAM_API_HOST = "api.steampowered.com"
}
