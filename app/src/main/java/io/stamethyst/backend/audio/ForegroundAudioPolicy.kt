package io.stamethyst.backend.audio

internal class ForegroundAudioPolicy {
    private var activityResumed = false

    fun markActivityResumed(resumed: Boolean) {
        activityResumed = resumed
    }

    fun shouldRestoreForegroundAudio(
        runtimeLifecycleReady: Boolean,
        backExitRequested: Boolean
    ): Boolean {
        return activityResumed &&
            runtimeLifecycleReady &&
            !backExitRequested
    }
}
