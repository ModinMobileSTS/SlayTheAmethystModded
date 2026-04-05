package io.stamethyst.backend.audio

internal class ForegroundAudioPolicy(
    initialAudioFocusGranted: Boolean = true
) {
    private var activityResumed = false
    private var audioFocusGranted = initialAudioFocusGranted

    fun markActivityResumed(resumed: Boolean) {
        activityResumed = resumed
    }

    fun markAudioFocusGranted(granted: Boolean) {
        audioFocusGranted = granted
    }

    fun shouldRestoreForegroundAudio(
        runtimeLifecycleReady: Boolean,
        backExitRequested: Boolean
    ): Boolean {
        return activityResumed &&
            audioFocusGranted &&
            runtimeLifecycleReady &&
            !backExitRequested
    }
}
