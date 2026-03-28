package io.stamethyst.backend.launch

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProcessLaunchGuardTest {
    @After
    fun tearDown() {
        GameProcessLaunchGuard.resetForTest()
    }

    @Test
    fun tryAcquire_rejectsSecondOwner_untilFirstReleases() {
        assertTrue(GameProcessLaunchGuard.tryAcquire("owner-a"))
        assertFalse(GameProcessLaunchGuard.tryAcquire("owner-b"))

        GameProcessLaunchGuard.release("owner-a")

        assertTrue(GameProcessLaunchGuard.tryAcquire("owner-b"))
    }

    @Test
    fun release_ignoresNonOwnerToken() {
        assertTrue(GameProcessLaunchGuard.tryAcquire("owner-a"))

        GameProcessLaunchGuard.release("owner-b")

        assertFalse(GameProcessLaunchGuard.tryAcquire("owner-b"))
        assertTrue(GameProcessLaunchGuard.tryAcquire("owner-a"))
    }
}
