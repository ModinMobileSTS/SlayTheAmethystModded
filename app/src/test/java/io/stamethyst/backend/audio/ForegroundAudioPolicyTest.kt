package io.stamethyst.backend.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAudioPolicyTest {
    @Test
    fun shouldRestoreForegroundAudio_requiresResumeAndRuntimeReady() {
        val policy = ForegroundAudioPolicy()

        assertFalse(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = true,
                backExitRequested = false,
                runtimeAudioSuppressed = false
            )
        )

        policy.markActivityResumed(true)
        assertTrue(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = true,
                backExitRequested = false,
                runtimeAudioSuppressed = false
            )
        )
    }

    @Test
    fun shouldRestoreForegroundAudio_rejectsBackgroundBackExitAndNotReadyStates() {
        val policy = ForegroundAudioPolicy()
        policy.markActivityResumed(true)

        assertFalse(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = false,
                backExitRequested = false,
                runtimeAudioSuppressed = false
            )
        )
        assertFalse(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = true,
                backExitRequested = true,
                runtimeAudioSuppressed = false
            )
        )
        assertFalse(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = true,
                backExitRequested = false,
                runtimeAudioSuppressed = true
            )
        )

        policy.markActivityResumed(false)
        assertFalse(
            policy.shouldRestoreForegroundAudio(
                runtimeLifecycleReady = true,
                backExitRequested = false,
                runtimeAudioSuppressed = false
            )
        )
    }
}
