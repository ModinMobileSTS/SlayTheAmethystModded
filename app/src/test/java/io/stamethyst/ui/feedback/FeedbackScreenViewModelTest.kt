package io.stamethyst.ui.feedback

import io.stamethyst.backend.feedback.FeedbackCategory
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
    fun startsInCategorySelectionStep() {
        val viewModel = FeedbackScreenViewModel()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.CATEGORY_SELECTION,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun continueAfterCategorySelected_movesToFormStep() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onCategorySelected(FeedbackCategory.LAUNCHER_BUG)
        viewModel.onContinueAfterCategorySelected()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.FORM,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun continueWithoutCategory_keepsCategorySelectionStep() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onContinueAfterCategorySelected()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.CATEGORY_SELECTION,
            viewModel.uiState.submissionStep
        )
    }

    @Test
    fun returnToCategorySelection_movesBackToFirstStep() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onCategorySelected(FeedbackCategory.GAME_BUG)
        viewModel.onContinueAfterCategorySelected()
        viewModel.onReturnToCategorySelection()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.CATEGORY_SELECTION,
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
    fun returnToForm_setsFormStep() {
        val viewModel = FeedbackScreenViewModel()

        viewModel.onReturnToForm()

        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.FORM,
            viewModel.uiState.submissionStep
        )
        assertFalse(viewModel.uiState.showBriefFeedbackConfirmation)
    }

    @Test
    fun submissionStepPreviousStep_matchesExpectedFlow() {
        assertEquals(
            null,
            FeedbackScreenViewModel.SubmissionStep.CATEGORY_SELECTION.previousStep()
        )
        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.CATEGORY_SELECTION,
            FeedbackScreenViewModel.SubmissionStep.FORM.previousStep()
        )
        assertEquals(
            FeedbackScreenViewModel.SubmissionStep.FORM,
            FeedbackScreenViewModel.SubmissionStep.SUBMISSION_CONFIRMATION.previousStep()
        )
    }
}
