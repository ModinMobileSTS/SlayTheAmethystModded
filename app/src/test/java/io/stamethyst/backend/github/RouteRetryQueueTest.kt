package io.stamethyst.backend.github

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteRetryQueueTest {
    private val queue = RouteRetryQueue(2, 20_000, setOf(502, 503, 504))

    @Test
    fun discoveryIsLazyAndRunsOnceAcrossRounds() {
        var discoveries = 0
        val attempts = queue.attempts(
            preferred = listOf("preferred", "official"),
            additional = { discoveries++; sequenceOf("official", "alternate", "preferred") },
            key = { it },
        ).iterator()

        assertEquals("preferred", attempts.next())
        assertEquals("official", attempts.next())
        assertEquals(0, discoveries)
        assertEquals(
            listOf("alternate", "preferred", "official", "alternate"),
            attempts.asSequence().toList(),
        )
        assertEquals(1, discoveries)
    }
}
