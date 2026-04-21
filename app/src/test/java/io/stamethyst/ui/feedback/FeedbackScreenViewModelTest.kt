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

    @Test
    fun uiStateShouldWarnAboutMissingEmail_whenBlankAndNotConfirmed() {
        val uiState = FeedbackScreenViewModel.UiState(email = "   ")

        assertTrue(uiState.shouldWarnAboutMissingEmail)
    }

    @Test
    fun uiStateShouldNotWarnAboutMissingEmail_whenBlankButAlreadyConfirmed() {
        val uiState = FeedbackScreenViewModel.UiState(
            email = "",
            missingEmailWarningConfirmed = true
        )

        assertFalse(uiState.shouldWarnAboutMissingEmail)
    }

    @Test
    fun uiStateShouldNotWarnAboutMissingEmail_whenEmailIsFilled() {
        val uiState = FeedbackScreenViewModel.UiState(email = "name@example.com")

        assertFalse(uiState.shouldWarnAboutMissingEmail)
    }

    @Test
    fun startsInAcknowledgementStep() {
        val viewModel = FeedbackScreenViewModel()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun continueAfterAcknowledgementsConfirmed_movesToFormStep() {
        val viewModel = FeedbackScreenViewModel()
        requiredAcknowledgements().forEach { acknowledgement ->
            viewModel.onSubmissionAcknowledgementChanged(acknowledgement, true)
        }

        viewModel.onContinueAfterAcknowledgementsConfirmed()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.FORM,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun continueWithoutAllAcknowledgements_keepsAcknowledgementStep() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onContinueAfterAcknowledgementsConfirmed()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun returnToAcknowledgements_movesBackToFirstStep() {
        val viewModel = FeedbackScreenViewModel()
        requiredAcknowledgements().forEach { acknowledgement ->
            viewModel.onSubmissionAcknowledgementChanged(acknowledgement, true)
        }

        viewModel.onContinueAfterAcknowledgementsConfirmed()
        viewModel.onReturnToAcknowledgements()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT,
            viewModel.uiState.submissionStep
        )
        assertFalse(viewModel.uiState.showBriefFeedbackConfirmation)
    }

    @Test
    fun uiStateAllSubmissionAcknowledgementsChecked_onlyTrueWhenAllChecked() {
        val requiredAcknowledgements = FeedbackScreenViewModel.SubmissionAcknowledgement.entries
            .filter { it.requiredForSubmission }
            .toSet()
        val incompleteState = FeedbackScreenViewModel.UiState(
            checkedSubmissionAcknowledgements = requiredAcknowledgements -
                FeedbackScreenViewModel.SubmissionAcknowledgement.DEVELOPER_IS_NOT_CUSTOMER_SUPPORT
        )
        val completeState = incompleteState.copy(
            checkedSubmissionAcknowledgements = requiredAcknowledgements
        )

        assertFalse(incompleteState.allSubmissionAcknowledgementsChecked)
        assertTrue(completeState.allSubmissionAcknowledgementsChecked)
        assertFalse(completeState.hasSubmissionInterceptionAcknowledgementChecked)
    }

    @Test
    fun acknowledgementToggles_updateAllSubmissionAcknowledgementsChecked() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.UNCLEAR_DESCRIPTION_DELAYS_RESOLUTION,
            true
        )
        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.MOD_CONFLICT_NOT_SUPPORTED,
            true
        )
        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.TRIED_FIXING_BEFORE_SUBMITTING,
            true
        )
        assertFalse(viewModel.uiState.allSubmissionAcknowledgementsChecked)

        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.DEVELOPER_IS_NOT_CUSTOMER_SUPPORT,
            true
        )
        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement
                .DESCRIPTION_IS_CLEAR_TO_UNFAMILIAR_DEVELOPERS,
            true
        )

        assertTrue(viewModel.uiState.allSubmissionAcknowledgementsChecked)
        assertFalse(viewModel.uiState.hasSubmissionInterceptionAcknowledgementChecked)
        assertEquals(null, viewModel.uiState.submissionStatus)
    }

    @Test
    fun interceptionAcknowledgement_isTrackedWithoutBreakingRequiredChecks() {
        val requiredAcknowledgements = FeedbackScreenViewModel.SubmissionAcknowledgement.entries
            .filter { it.requiredForSubmission }
            .toSet()
        val uiState = FeedbackScreenViewModel.UiState(
            checkedSubmissionAcknowledgements = requiredAcknowledgements +
                FeedbackScreenViewModel.SubmissionAcknowledgement.SHOULD_NOT_CHECK_THIS_BOX
        )

        assertTrue(uiState.allSubmissionAcknowledgementsChecked)
        assertTrue(uiState.hasSubmissionInterceptionAcknowledgementChecked)
    }

    @Test
    fun interceptionAcknowledgement_blocksProgressAndShowsWarning() {
        val viewModel = FeedbackScreenViewModel()
        requiredAcknowledgements().forEach { acknowledgement ->
            viewModel.onSubmissionAcknowledgementChanged(acknowledgement, true)
        }
        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.SHOULD_NOT_CHECK_THIS_BOX,
            true
        )

        viewModel.onContinueAfterAcknowledgementsConfirmed()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT,
            viewModel.uiState.submissionStep
        )
        assertTrue(viewModel.uiState.showSubmissionAttentionWarning)
        assertEquals(FEEDBACK_SUBMISSION_WARNING_STATUS, viewModel.uiState.submissionStatus)
    }

    @Test
    fun interceptionAcknowledgementStatus_clearsWhenUnchecked() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.SHOULD_NOT_CHECK_THIS_BOX,
            true
        )
        assertEquals(FEEDBACK_SUBMISSION_WARNING_STATUS, viewModel.uiState.submissionStatus)

        viewModel.onSubmissionAcknowledgementChanged(
            FeedbackScreenViewModel.SubmissionAcknowledgement.SHOULD_NOT_CHECK_THIS_BOX,
            false
        )

        assertEquals(null, viewModel.uiState.submissionStatus)
    }

    @Test
    fun submissionStepPreviousStep_matchesExpectedFlow() {
        assertEquals(
            null,
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT.previousStep()
        )
        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.ACKNOWLEDGEMENT,
            FeedbackScreenViewModel.SubmissionStep.FORM.previousStep()
        )
    }

    private fun requiredAcknowledgements(): Set<FeedbackScreenViewModel.SubmissionAcknowledgement> {
        return FeedbackScreenViewModel.SubmissionAcknowledgement.entries
            .filter { it.requiredForSubmission }
            .toSet()
    }
}
