package io.stamethyst.backend.render

import io.stamethyst.config.LauncherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HighRefreshRateSessionTest {
    @Test fun experimentalWorkaroundIsOptIn() {
        assertFalse(LauncherConfig.DEFAULT_FORCE_HIGH_REFRESH_RATE)
    }

    @Test fun disabledOrBackgroundHostDoesNotStart() {
        val events = mutableListOf<String>()
        val session = session(events)
        session.update(enabled = false, resumed = true)
        session.update(enabled = true, resumed = false)
        session.update(enabled = false, resumed = false)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun enablingIsImmediateAndRepeatedUpdatesNeverDuplicateWindows() {
        val events = mutableListOf<String>()
        val session = session(events)
        repeat(3) { session.update(enabled = true, resumed = true) }
        repeat(3) { session.update(enabled = false, resumed = true) }
        session.update(enabled = true, resumed = true)
        assertEquals(listOf("start", "stop", "start"), events)
    }

    @Test fun pauseReleasesWindowAndResumeRestartsOnlyIfStillEnabled() {
        val events = mutableListOf<String>()
        val session = session(events)
        session.update(enabled = true, resumed = true)
        repeat(3) { session.update(enabled = true, resumed = false) }
        session.update(enabled = false, resumed = true)
        assertEquals(listOf("start", "stop"), events)
        session.update(enabled = true, resumed = true)
        assertEquals(listOf("start", "stop", "start"), events)
    }

    @Test fun failedAttachmentCanRetryAndIsNotTreatedAsALiveWindow() {
        val events = mutableListOf<String>()
        var available = false
        val session = HighRefreshRateSession(
            start = { events += "attempt"; available },
            stop = { events += "stop" },
        )
        session.update(enabled = true, resumed = true)
        session.update(enabled = true, resumed = false)
        assertEquals(listOf("attempt"), events)
        available = true
        session.update(enabled = true, resumed = true)
        session.update(enabled = false, resumed = false)
        assertEquals(listOf("attempt", "attempt", "stop"), events)
    }

    private fun session(events: MutableList<String>) = HighRefreshRateSession(
        start = { events += "start"; true },
        stop = { events += "stop" },
    )
}
