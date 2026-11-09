package io.stamethyst.backend.workshop

import kotlinx.serialization.Serializable

/** IDs remain strings: Steam's unsigned 64-bit identifiers must never pass through a Double. */
@Serializable
data class WorkshopAuthor(
    val name: String,
    val profileUrl: String,
    val steamId: String? = null,
    val avatarUrl: String = "",
) {
    val identity: String get() = steamId ?: profileUrl
}

data class WorkshopAuthorPage(
    val author: WorkshopAuthor,
    val appId: UInt,
    val items: List<WorkshopItemSummary>,
    val page: Int,
    val pageSize: Int,
    val total: Int? = null,
    val hasNextPage: Boolean = false,
)

/** Not the creator Steam ID, nor the owner of a ForumTopic reply thread. */
data class WorkshopDiscussionForum(
    val owner: String,
    val feature: String,
    val forumId: String,
)

data class WorkshopDiscussionSummary(
    val id: String,
    val title: String,
    val url: String,
    val authorName: String = "",
    val replyCount: Int = 0,
    val lastPostedEpochSeconds: Long? = null,
)

data class WorkshopDiscussionPage(
    val publishedFileId: ULong,
    val forum: WorkshopDiscussionForum,
    val topics: List<WorkshopDiscussionSummary>,
    val start: Int,
    val pageSize: Int,
    val total: Int,
) {
    val page: Int get() = start / pageSize + 1
    val hasNextPage: Boolean get() = start.toLong() + pageSize < total
}

/** ForumTopic context is intentionally separate from PublishedFile_Public comments. */
data class WorkshopDiscussionReplyContext(
    val owner: String,
    val forumId: String,
    val topicId: String,
    val extendedData: String? = null,
    val oldestFirst: Boolean = true,
)

data class WorkshopDiscussionReply(
    val comment: WorkshopComment,
    val isDeveloper: Boolean = false,
)

data class WorkshopDiscussionReplyPage(
    val replies: List<WorkshopDiscussionReply>,
    val start: Int,
    val pageSize: Int,
    val total: Int,
) {
    val page: Int get() = start / pageSize + 1
    val hasNextPage: Boolean get() = start.toLong() + pageSize < total
}

data class WorkshopDiscussionDetails(
    val topic: WorkshopDiscussionSummary,
    val authorProfileUrl: String,
    val content: String,
    val postedEpochSeconds: Long?,
    val replyContext: WorkshopDiscussionReplyContext,
    val firstReplyPage: WorkshopDiscussionReplyPage,
)
