package io.stamethyst.backend.feedback

enum class FeedbackThreadEventType {
    COMMENT,
    STATE_CHANGE
}

enum class FeedbackThreadAuthorType {
    ME,
    DEVELOPER,
    OTHER,
    SYSTEM
}

data class FeedbackThreadAttachment(
    val name: String,
    val url: String,
    val mimeType: String
)

data class FeedbackThreadEvent(
    val id: String,
    val type: FeedbackThreadEventType,
    val authorType: FeedbackThreadAuthorType,
    val authorLabel: String,
    val body: String,
    val createdAtMs: Long,
    val htmlUrl: String?,
    val attachments: List<FeedbackThreadAttachment> = emptyList(),
    val state: String? = null
)

data class FeedbackIssueThreadCache(
    val issueNumber: Long,
    val issueUrl: String,
    val title: String,
    val state: String,
    val body: String,
    val updatedAtMs: Long,
    val events: List<FeedbackThreadEvent>
) {
    val isClosed: Boolean
        get() = state.equals("closed", ignoreCase = true)

    val lastEventAtMs: Long
        get() = events.maxOfOrNull { it.createdAtMs } ?: updatedAtMs
}

data class FeedbackIssueSubscription(
    val issueNumber: Long,
    val issueUrl: String,
    val title: String,
    val state: String,
    val unread: Boolean,
    val lastSyncedAtMs: Long,
    val lastViewedAtMs: Long,
    val updatedAtMs: Long
) {
    val isClosed: Boolean
        get() = state.equals("closed", ignoreCase = true)
}

data class FeedbackIssueBrowseItem(
    val issueNumber: Long,
    val issueUrl: String,
    val title: String,
    val bodyPreview: String,
    val state: String,
    val commentCount: Int,
    val authorLabel: String,
    val updatedAtMs: Long
) {
    val isClosed: Boolean
        get() = state.equals("closed", ignoreCase = true)
}

data class FeedbackIssueBrowsePage(
    val issues: List<FeedbackIssueBrowseItem>,
    val nextPage: Int,
    val hasMore: Boolean
)

data class FeedbackUnreadNotice(
    val unreadIssueNumbers: List<Long>
) {
    val unreadIssueCount: Int
        get() = unreadIssueNumbers.size
}

data class FeedbackInboxUiState(
    val subscriptions: List<FeedbackIssueSubscription> = emptyList(),
    val unreadIssueCount: Int = 0,
    val syncing: Boolean = false,
    val pendingNotice: FeedbackUnreadNotice? = null
)

data class FeedbackSyncResult(
    val subscriptions: List<FeedbackIssueSubscription>,
    val unreadIssueNumbers: List<Long>,
    val syncedAtMs: Long
)

data class FeedbackPostedComment(
    val commentId: Long,
    val commentUrl: String?,
    val createdAtMs: Long,
    val body: String,
    val attachments: List<FeedbackThreadAttachment>,
    val playerName: String
)

data class FeedbackIssueStateUpdateResult(
    val issueNumber: Long,
    val issueUrl: String?,
    val state: String,
    val updatedAtMs: Long
)
