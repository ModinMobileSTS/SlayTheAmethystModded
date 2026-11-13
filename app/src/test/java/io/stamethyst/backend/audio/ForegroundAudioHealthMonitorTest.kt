package io.stamethyst.backend.audio

import org.junit.Assert.*
import org.junit.Test

class ForegroundAudioHealthMonitorTest {
    private class Fixture {
        var allowed = true
        var mode: Int? = 0
        var nowMs = 0L
        val pending = mutableMapOf<Runnable, Long>()
        val requests = mutableListOf<String?>()
        var recoveryAllowed = false
        val monitor = ForegroundAudioHealthMonitor(
            postDelayed = { task, delay -> pending[task] = nowMs + delay },
            removeCallbacks = { pending.remove(it) },
            canCheckHealth = { allowed },
            readAudioMode = { mode },
            requestHealthCheck = { requests += it },
            setRecoveryAllowed = { recoveryAllowed = it },
            nowMs = { nowMs }
        )

        fun advanceBy(delayMs: Long) {
            val target = nowMs + delayMs
            while (true) {
                val next = pending.minByOrNull { it.value } ?: break
                if (next.value > target) break
                nowMs = next.value
                pending.remove(next.key)
                next.key.run()
            }
            nowMs = target
        }
    }

    @Test fun ordinaryForegroundAndRepeatedFocusNeverRenewBudget() {
        val f = Fixture()
        repeat(10) { f.monitor.start() }
        repeat(10) { f.monitor.checkNow("window_focus_gained") }
        f.advanceBy(100_000)
        assertEquals(111, f.requests.size)
        assertTrue(f.requests.all { it == null })
        assertEquals(1, f.pending.size)
        assertTrue(f.recoveryAllowed)
    }

    @Test fun communicationGatesAllRequestsUntilNormalModeIsStable() {
        val f = Fixture()
        f.mode = 3
        f.monitor.start()
        f.monitor.checkNow("window_focus_gained")
        f.monitor.checkNow("output_route_changed")
        f.advanceBy(10_000)
        assertTrue(f.requests.isEmpty())
        assertFalse(f.recoveryAllowed)
        f.mode = 0
        f.advanceBy(1000)
        assertTrue(f.requests.isEmpty())
        f.advanceBy(1000)
        assertEquals(listOf("output_route_changed"), f.requests)
        assertTrue(f.recoveryAllowed)
        f.advanceBy(1000)
        assertEquals(listOf("output_route_changed", null), f.requests)
    }

    @Test fun communicationEndRenewsExactlyOnceWithoutFocusOrRouteCallback() {
        val f = Fixture()
        f.monitor.start()
        f.mode = 2
        f.advanceBy(1000)
        f.mode = 0
        f.advanceBy(3000)
        assertEquals(listOf(null, "communication_ended", null), f.requests)
    }

    @Test fun modeFlappingRestartsTheStableInterval() {
        val f = Fixture()
        f.mode = 3
        f.monitor.start()
        f.mode = 0
        f.advanceBy(1000)
        f.mode = 3
        f.advanceBy(1000)
        f.mode = 0
        f.advanceBy(1000)
        assertTrue(f.requests.isEmpty())
        f.advanceBy(1000)
        assertEquals(listOf("communication_ended"), f.requests)
    }

    @Test fun unavailableManagerStillChecksButDoesNotCertifyAnObservedCallEnded() {
        val f = Fixture()
        f.mode = null
        f.monitor.start()
        assertEquals(listOf<String?>(null), f.requests)
        f.mode = 3
        f.advanceBy(1000)
        f.mode = null
        f.advanceBy(10_000)
        assertEquals(1, f.requests.size)
        assertFalse(f.recoveryAllowed)
    }

    @Test fun staleCallbackCannotRunAfterStopAndRestart() {
        val f = Fixture()
        f.monitor.start()
        val stale = f.pending.keys.single()
        f.monitor.stop()
        assertFalse(f.recoveryAllowed)
        f.monitor.start()
        stale.run()
        assertEquals(listOf(null, null), f.requests)
        assertEquals(1, f.pending.size)
    }

    @Test fun backgroundOrExitCancelsTimersAndPreventsRouteRequests() {
        val f = Fixture()
        f.allowed = false
        f.monitor.start()
        f.monitor.checkNow("output_route_changed")
        assertTrue(f.requests.isEmpty())
        assertTrue(f.pending.isEmpty())
        f.allowed = true
        f.monitor.start()
        f.allowed = false
        f.advanceBy(1000)
        assertEquals(1, f.requests.size)
        assertTrue(f.pending.isEmpty())
        assertFalse(f.recoveryAllowed)
    }

    @Test fun callStateSurvivesActivityPauseAndOnlyRealRouteEventsRenewBudget() {
        val f = Fixture()
        f.mode = 3
        f.monitor.start()
        f.monitor.stop()
        f.mode = 0
        f.monitor.start()
        assertTrue(f.requests.isEmpty())
        f.advanceBy(1000)
        f.monitor.checkNow("window_focus_gained")
        f.monitor.checkNow("output_route_changed")
        assertEquals(listOf("communication_ended", null, "output_route_changed"), f.requests)
    }
}
