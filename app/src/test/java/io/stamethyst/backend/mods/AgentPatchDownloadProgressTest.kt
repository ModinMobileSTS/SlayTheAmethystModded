package io.stamethyst.backend.mods

import io.stamethyst.backend.network.AcceleratedRouteEvent
import io.stamethyst.backend.network.AcceleratedRouteEvents
import io.stamethyst.backend.update.UpdateSource
import org.junit.Assert.*
import org.junit.Test

class AgentPatchDownloadProgressTest {
    @Test fun acceleratedSourceSelectsNodesWhilePlainSourcesConnectDirectly() {
        val updates = mutableListOf<AgentPatchInstallProgress>()
        val reporter = AgentPatchDownloadProgressReporter(updates::add)
        reporter.attemptingSource(UpdateSource.ACCELERATED_DIRECT)
        reporter.attemptingSource(UpdateSource.GH_PROXY_VIP)
        reporter.attemptingSource(UpdateSource.OFFICIAL)
        assertEquals(
            listOf(AgentPatchRoutePhase.SELECTING, AgentPatchRoutePhase.CONNECTING, AgentPatchRoutePhase.CONNECTING),
            updates.map { it.routePhase },
        )
        assertEquals(listOf("加速直连", "ghproxy.vip", "GitHub"), updates.map { it.routeTarget })
        assertTrue(updates.all { it.totalBytes == 0L })
    }

    @Test fun choosingAndConnectingDoNotPretendToHaveDownloadedBytes() {
        val updates = mutableListOf<AgentPatchInstallProgress>()
        val reporter = AgentPatchDownloadProgressReporter(updates::add)
        reporter.selecting("加速直连")
        reporter.onRouteEvent(AcceleratedRouteEvent.ForwardTargetAttempt("raw.githubusercontent.com", "node.test"))

        assertEquals(listOf(AgentPatchRoutePhase.SELECTING, AgentPatchRoutePhase.CONNECTING), updates.map { it.routePhase })
        assertEquals(listOf("加速直连", "node.test"), updates.map { it.routeTarget })
        assertTrue(updates.all { it.stage == AgentPatchInstallStage.SELECTING_ROUTE && it.totalBytes == 0L })
    }

    @Test fun failedNodeAndOfficialFallbackStayInRouteSelectionUntilDownloadStarts() {
        val updates = mutableListOf<AgentPatchInstallProgress>()
        val reporter = AgentPatchDownloadProgressReporter(updates::add)
        val host = "raw.githubusercontent.com"
        reporter.onRouteEvent(AcceleratedRouteEvent.ForwardTargetFailed(host, "node.test", "timeout"))
        reporter.onRouteEvent(AcceleratedRouteEvent.OfficialAttempt(host))
        reporter.onRouteEvent(AcceleratedRouteEvent.OfficialSucceeded(host))

        assertEquals(listOf(AgentPatchRoutePhase.SWITCHING, AgentPatchRoutePhase.CONNECTING), updates.map { it.routePhase })
        assertEquals(host, updates.last().routeTarget)
        reporter.downloading(0, 1_000)
        assertEquals(AgentPatchInstallStage.DOWNLOADING, updates.last().stage)
        assertEquals(1_000L, updates.last().totalBytes)
        assertNull(updates.last().routePhase)
    }

    @Test fun sourceRetryImmediatelyClearsOldBytesAndRestartsRealDownloadProgress() {
        val updates = mutableListOf<AgentPatchInstallProgress>()
        val reporter = AgentPatchDownloadProgressReporter(updates::add)
        reporter.downloading(700, 1_000)
        assertEquals(0.496f, updates.last().fraction, 0.0001f)
        reporter.switching("mirror.test")
        reporter.selecting("other.test")
        assertEquals(0L, updates.last().downloadedBytes)
        assertEquals(0L, updates.last().totalBytes)
        reporter.downloading(0, 1_000)
        assertEquals(0.02f, updates.last().fraction, 0.0001f)
        reporter.downloading(1_000, 1_000)
        assertEquals(0.70f, updates.last().fraction, 0.0001f)
    }

    @Test fun unrelatedGlobalTrafficCannotOverwriteRequestLocalProgress() {
        val updates = mutableListOf<AgentPatchInstallProgress>()
        val reporter = AgentPatchDownloadProgressReporter(updates::add)
        reporter.selecting("加速直连")
        // Even same-host catalogue refreshes are unrelated requests.
        AcceleratedRouteEvents.emit(AcceleratedRouteEvent.OfficialAttempt("raw.githubusercontent.com"))
        AcceleratedRouteEvents.emit(AcceleratedRouteEvent.ForwardTargetAttempt("api.github.com", "other.test"))
        assertEquals(1, updates.size)
        assertEquals(AgentPatchRoutePhase.SELECTING, updates.single().routePhase)
    }
}
