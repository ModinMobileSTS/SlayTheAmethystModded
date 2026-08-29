package io.stamethyst.backend.workshop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopLoadProgressReporterTest {
    private val received = mutableListOf<WorkshopLoadProgress>()

    @Before
    fun setUp() {
        WorkshopLoadProgressReporter.setListener { progress -> received += progress }
    }

    @After
    fun tearDown() {
        WorkshopLoadProgressReporter.setListener(null)
        received.clear()
    }

    @Test
    fun backwardRouteEventsFromLaterRequestsAreDropped() {
        val sessionId = WorkshopLoadProgressReporter.beginSession()
        report(sessionId, WorkshopLoadPhase.Connecting)
        report(sessionId, WorkshopLoadPhase.Parsing)
        // A second request under the same session starts over at Connecting; it must not rewind.
        report(sessionId, WorkshopLoadPhase.Connecting)
        // Its completion is still narrated, but the bar stays put because Parsing == Parsing.
        report(sessionId, WorkshopLoadPhase.Parsing)

        assertEquals(
            listOf(
                WorkshopLoadPhase.Preparing,
                WorkshopLoadPhase.Connecting,
                WorkshopLoadPhase.Parsing,
                WorkshopLoadPhase.Parsing,
            ),
            phases(),
        )
    }

    @Test
    fun recoveryRewindsFloorSoRetriedNodeNarratesConnectingAgain() {
        val sessionId = WorkshopLoadProgressReporter.beginSession()
        report(sessionId, WorkshopLoadPhase.Connecting)
        report(sessionId, WorkshopLoadPhase.Parsing)
        report(
            sessionId = sessionId,
            phase = WorkshopLoadPhase.FailingOver,
            target = "node-a",
        )
        report(sessionId, WorkshopLoadPhase.Connecting, target = "node-b")
        report(sessionId, WorkshopLoadPhase.Parsing)

        assertEquals(
            listOf(
                WorkshopLoadPhase.Preparing,
                WorkshopLoadPhase.Connecting,
                WorkshopLoadPhase.Parsing,
                WorkshopLoadPhase.FailingOver,
                WorkshopLoadPhase.Connecting,
                WorkshopLoadPhase.Parsing,
            ),
            phases(),
        )
        assertTrue(received.any { progress ->
            progress.phase == WorkshopLoadPhase.FailingOver && progress.failedTargetCount == 1
        })
    }

    @Test
    fun lateRouteDiscoveryAfterParsingIsDropped() {
        val sessionId = WorkshopLoadProgressReporter.beginSession()
        report(sessionId, WorkshopLoadPhase.ResolvingRoute)
        report(sessionId, WorkshopLoadPhase.ProbingNodes)
        report(sessionId, WorkshopLoadPhase.Connecting)
        report(sessionId, WorkshopLoadPhase.Parsing)
        // A follow-up request triggering route discovery again must not rewind the bar.
        report(sessionId, WorkshopLoadPhase.ResolvingRoute)
        report(sessionId, WorkshopLoadPhase.ProbingNodes)

        assertEquals(
            listOf(
                WorkshopLoadPhase.Preparing,
                WorkshopLoadPhase.ResolvingRoute,
                WorkshopLoadPhase.ProbingNodes,
                WorkshopLoadPhase.Connecting,
                WorkshopLoadPhase.Parsing,
            ),
            phases(),
        )
    }

    @Test
    fun nonTerminalPhasesStayBlockedAfterTerminalOne() {
        val sessionId = WorkshopLoadProgressReporter.beginSession()
        report(sessionId, WorkshopLoadPhase.Completed)
        report(sessionId, WorkshopLoadPhase.Parsing)

        assertEquals(
            listOf(WorkshopLoadPhase.Preparing, WorkshopLoadPhase.Completed),
            phases(),
        )
    }

    @Test
    fun staleSessionUpdatesAreIgnoredAndNewSessionResetsFloor() {
        val supersededSessionId = WorkshopLoadProgressReporter.beginSession()
        val activeSessionId = WorkshopLoadProgressReporter.beginSession()
        report(supersededSessionId, WorkshopLoadPhase.Completed)
        report(activeSessionId, WorkshopLoadPhase.Connecting)

        assertEquals(
            listOf(WorkshopLoadPhase.Preparing, WorkshopLoadPhase.Preparing, WorkshopLoadPhase.Connecting),
            phases(),
        )
    }

    private fun phases(): List<WorkshopLoadPhase> = received.map { progress -> progress.phase }

    private fun report(
        sessionId: Long,
        phase: WorkshopLoadPhase,
        target: String? = null,
    ) {
        WorkshopLoadProgressReporter.report(
            sessionId = sessionId,
            phase = phase,
            target = target,
        )
    }
}
