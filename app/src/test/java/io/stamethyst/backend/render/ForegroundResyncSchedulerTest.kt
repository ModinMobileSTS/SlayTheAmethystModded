package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundResyncSchedulerTest {
    @Test
    fun request_coalescesPendingReasons_untilDrain() {
        val scheduler = ForegroundResyncScheduler()

        assertTrue(scheduler.request("resume"))
        assertFalse(scheduler.request("focus"))
        assertFalse(scheduler.request("layout"))

        assertEquals(
            linkedSetOf("resume", "focus", "layout"),
            scheduler.drain()
        )
        assertTrue(scheduler.request("surface_changed"))
    }
}
