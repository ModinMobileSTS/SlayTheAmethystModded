package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewFrameRateVoteStateTest {
    @Test fun onlySupportedForegroundFocusedContentCanVote() {
        for (sdk in listOf(30, 34, 35, 36)) {
            for (foreground in listOf(false, true)) {
                for (focused in listOf(false, true)) {
                    for (content in listOf(false, true)) {
                        assertEquals(
                            "$sdk / $foreground / $focused / $content",
                            sdk >= 35 && foreground && focused && content,
                            shouldRequestHighViewFrameRate(sdk, foreground, focused, content),
                        )
                    }
                }
            }
        }
    }

    @Test fun repeatedActiveEventsDoNotRepeatSetterCalls() {
        val fixture = Fixture(30f)
        repeat(10) { fixture.vote.setActive(true) }
        assertEquals(listOf(HIGH), fixture.writes)
        assertEquals(HIGH, fixture.rate)
    }

    @Test fun inactiveBeforeFirstVoteDoesNotEvenReadTheViewApi() {
        val vote = ViewFrameRateVoteState(HIGH, { error("unexpected read") }, { error("unexpected write") })
        repeat(3) { vote.setActive(false) }
    }

    @Test fun pauseOrSurfaceDestructionRestoresTheOriginalPreferenceOnce() {
        val fixture = Fixture(30f)
        fixture.vote.setActive(true)
        repeat(3) { fixture.vote.setActive(false) }
        assertEquals(listOf(HIGH, 30f), fixture.writes)
    }

    @Test fun originalNaNPreferenceCanBeRestoredWithoutInventingZero() {
        val fixture = Fixture(Float.NaN)
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        assertEquals(2, fixture.writes.size)
        assertTrue(fixture.rate.isNaN())
    }

    @Test fun alreadyHighPreferenceDoesNotCauseWritesEvenOnRelease() {
        val fixture = Fixture(HIGH)
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun anotherOwnersLaterPreferenceIsNotOverwrittenOnRelease() {
        val fixture = Fixture(30f)
        fixture.vote.setActive(true)
        fixture.rate = 90f
        fixture.vote.setActive(false)
        assertEquals(90f, fixture.rate)
        assertEquals(listOf(HIGH), fixture.writes)
    }

    @Test fun resumingCapturesTheNewBaselineRatherThanRestoringAStaleValue() {
        val fixture = Fixture(30f)
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        fixture.rate = 90f
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        assertEquals(listOf(HIGH, 30f, HIGH, 90f), fixture.writes)
    }

    @Test fun activeResyncPreservesAnInterveningOwnerAsTheNewBaseline() {
        val fixture = Fixture(30f)
        fixture.vote.setActive(true)
        fixture.rate = 90f
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        assertEquals(listOf(HIGH, HIGH, 90f), fixture.writes)
    }

    @Test fun setterFailureBeforeApplyCanRetryOnANewLifecycleEvent() {
        val fixture = Fixture(30f)
        fixture.failBeforeWrite = true
        assertTrue(runCatching { fixture.vote.setActive(true) }.isFailure)
        assertEquals(30f, fixture.rate)
        fixture.failBeforeWrite = false
        fixture.vote.setActive(true)
        fixture.vote.setActive(false)
        assertEquals(listOf(HIGH, 30f), fixture.writes)
    }

    @Test fun setterFailureAfterApplyStillAllowsOriginalValueToBeRestored() {
        val fixture = Fixture(30f)
        fixture.failAfterWrite = true
        assertTrue(runCatching { fixture.vote.setActive(true) }.isFailure)
        fixture.failAfterWrite = false
        fixture.vote.setActive(false)
        assertEquals(listOf(HIGH, 30f), fixture.writes)
    }

    @Test fun failedRestoreRetainsTheOriginalForTheNextCleanupAttempt() {
        val fixture = Fixture(30f)
        fixture.vote.setActive(true)
        fixture.failBeforeWrite = true
        assertTrue(runCatching { fixture.vote.setActive(false) }.isFailure)
        fixture.failBeforeWrite = false
        fixture.vote.setActive(false)
        assertEquals(listOf(HIGH, 30f), fixture.writes)
    }

    private class Fixture(var rate: Float) {
        val writes = mutableListOf<Float>()
        var failBeforeWrite = false
        var failAfterWrite = false
        val vote = ViewFrameRateVoteState(HIGH, { rate }, {
            check(!failBeforeWrite) { "setter rejected the preference" }
            rate = it
            writes += it
            check(!failAfterWrite) { "setter failed after changing the preference" }
        })
    }

    private companion object {
        // Deliberately not tied to Android's category constant: the state machine owns an opaque
        // float preference, including category values and NaN, not a physical display rate.
        const val HIGH = -4f
    }
}
