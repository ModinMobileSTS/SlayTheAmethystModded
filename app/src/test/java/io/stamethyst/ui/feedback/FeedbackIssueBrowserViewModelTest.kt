package io.stamethyst.ui.feedback

import io.stamethyst.backend.feedback.FeedbackIssueBrowseItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackIssueBrowserViewModelTest {
    @Test
    fun uiStateVisibleIssues_sortedNewestFirst() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
                issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
                issue(issueNumber = 21, updatedAtMs = 500L, isClosed = false),
                issue(issueNumber = 5, updatedAtMs = 100L, isClosed = true)
            ),
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.ALL
        )

        assertEquals(listOf(21L, 18L, 12L, 5L), uiState.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_defaultsToOpenOnly() {
        val uiState = FeedbackIssueBrowserViewModel.UiState(
            issues = listOf(
                issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
                issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
                issue(issueNumber = 21, updatedAtMs = 300L, isClosed = false)
            )
        )

        assertEquals(listOf(21L, 12L), uiState.visibleIssues.map { it.issueNumber })
    }

    @Test
    fun uiStateVisibleIssues_appliesIssueStateFilter() {
        val issues = listOf(
            issue(issueNumber = 12, updatedAtMs = 200L, isClosed = false),
            issue(issueNumber = 18, updatedAtMs = 500L, isClosed = true),
            issue(issueNumber = 21, updatedAtMs = 300L, isClosed = false)
        )

        val openOnly = FeedbackIssueBrowserViewModel.UiState(
            issues = issues,
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.OPEN_ONLY
        )
        val closedOnly = FeedbackIssueBrowserViewModel.UiState(
            issues = issues,
            issueStateFilter = FeedbackIssueBrowserViewModel.IssueStateFilter.CLOSED_ONLY
        )

        assertEquals(listOf(21L, 12L), openOnly.visibleIssues.map { it.issueNumber })
        assertEquals(listOf(18L), closedOnly.visibleIssues.map { it.issueNumber })
    }

    private fun issue(
        issueNumber: Long,
        updatedAtMs: Long,
        isClosed: Boolean
    ): FeedbackIssueBrowseItem {
        return FeedbackIssueBrowseItem(
            issueNumber = issueNumber,
            issueUrl = "https://example.com/issues/$issueNumber",
            title = "Issue #$issueNumber",
            bodyPreview = "",
            state = if (isClosed) "closed" else "open",
            commentCount = 0,
            authorLabel = "tester",
            updatedAtMs = updatedAtMs
        )
    }
}
