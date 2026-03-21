package io.stamethyst.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackScreenViewModelTest {
    @Test
    fun calculateDetailedFeedbackLength_ignoresWhitespaceAndCombinesBothFields() {
        assertEquals(
            8,
            calculateDetailedFeedbackLength(
                detail = " ab \ncd ",
                reproductionSteps = " e f\tgh "
            )
        )
    }

    @Test
    fun uiStateShouldWarnAboutBriefFeedback_whenCombinedLengthIsExactlyThreshold() {
        val uiState = FeedbackScreenViewModel.UiState(
            detail = "a".repeat(40),
            reproductionSteps = "b".repeat(60)
        )

        assertEquals(100, uiState.detailedFeedbackLength)
        assertTrue(uiState.shouldWarnAboutBriefFeedback)
    }

    @Test
    fun uiStateShouldNotWarnAboutBriefFeedback_whenCombinedLengthExceedsThreshold() {
        val uiState = FeedbackScreenViewModel.UiState(
            detail = "a".repeat(80),
            reproductionSteps = "b".repeat(21)
        )

        assertEquals(101, uiState.detailedFeedbackLength)
        assertFalse(uiState.shouldWarnAboutBriefFeedback)
    }
}
