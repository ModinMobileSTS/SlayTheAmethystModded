package io.stamethyst.backend.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAudioHealthMonitorTest {
    private class Fixture {
        var allowed = true
        var mode: Int? = 0
        private var nowMs = 0L
        val pending = mutableMapOf<Runnable, Long>()
        val requests = mutableListOf<String?>()
        val monitor = ForegroundAudioHealthMonitor(
            postDelayed = { runnable, delayMs -> pending[runnable] = nowMs + delayMs },
            removeCallbacks = { pending.remove(it) },
            canCheckHealth = { allowed },
            readAudioMode = { mode },
            requestHealthCheck = { requests += it }
        )

        fun advanceBy(delayMs: Long) {
            val targetMs = nowMs + delayMs
            while (true) {
                val next = pending.minByOrNull { it.value } ?: break
                if (next.value > targetMs) break
                nowMs = next.value
                pending.remove(next.key)
                next.key.run()
            }
            nowMs = targetMs
        }
    }

    @Test
    fun focusOnlyReturnProbesImmediatelyWithoutActivityResumeOrRouteEvent() {
        val fixture = Fixture()

        fixture.monitor.checkNow("window_focus_gained")

        assertEquals(listOf("window_focus_gained"), fixture.requests)
        assertEquals(1, fixture.pending.size)
        fixture.advanceBy(999)
        assertEquals(1, fixture.requests.size)
        fixture.advanceBy(1)
        assertEquals(listOf("window_focus_gained", null), fixture.requests)
    }

    @Test
    fun periodicChecksKeepRunningAfterRecoveryWithoutRenewingRetryBudget() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.advanceBy(100_000)

        assertEquals("foreground", fixture.requests.first())
        assertEquals(101, fixture.requests.size)
        assertTrue(fixture.requests.drop(1).all { it == null })
        assertEquals(1, fixture.pending.size)
    }

    @Test
    fun repeatedStartsAndFocusEventsNeverCreateMultipleTimers() {
        val fixture = Fixture()
        repeat(10) { fixture.monitor.start() }
        repeat(10) { fixture.monitor.checkNow("window_focus_gained") }
        fixture.advanceBy(1000)

        assertEquals(12, fixture.requests.size)
        assertEquals(1, fixture.pending.size)
    }

    @Test
    fun audioModeTransitionRenewsBudgetWithoutFocusOrRouteCallbacks() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.mode = 3 // MODE_IN_COMMUNICATION, e.g. a WeChat call.
        fixture.advanceBy(1000)
        fixture.advanceBy(1000)
        fixture.mode = 0
        fixture.advanceBy(1000)

        assertEquals(
            listOf("foreground", "audio_mode_changed", null, "audio_mode_changed"),
            fixture.requests
        )
    }

    @Test
    fun unavailableAudioManagerDoesNotDisableDisconnectedDeviceChecks() {
        val fixture = Fixture()
        fixture.mode = null
        fixture.monitor.start()
        fixture.advanceBy(1000)

        assertEquals(listOf("foreground", null), fixture.requests)
    }

    @Test
    fun stopCancelsProbesAndForegroundRestartRenewsBudget() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.monitor.stop()
        fixture.advanceBy(10_000)

        assertEquals(listOf("foreground"), fixture.requests)
        assertTrue(fixture.pending.isEmpty())

        fixture.monitor.start()
        assertEquals(listOf("foreground", "foreground"), fixture.requests)
        assertEquals(1, fixture.pending.size)
    }

    @Test
    fun backgroundBootOverlayAndExitGuardPreventAnyProbeAndCancelTheTimer() {
        val fixture = Fixture()
        fixture.allowed = false
        fixture.monitor.start()
        fixture.monitor.checkNow("window_focus_gained")
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.pending.isEmpty())

        fixture.allowed = true
        fixture.monitor.start()
        fixture.allowed = false
        fixture.advanceBy(1000)
        assertEquals(listOf("foreground"), fixture.requests)
        assertTrue(fixture.pending.isEmpty())
    }

    @Test
    fun staleCallbackAfterStopDoesNotEnqueueAProbe() {
        val fixture = Fixture()
        fixture.monitor.start()
        val staleCallback = fixture.pending.keys.single()
        fixture.monitor.stop()
        staleCallback.run()

        assertEquals(listOf("foreground"), fixture.requests)
        assertTrue(fixture.pending.isEmpty())
    }
}
