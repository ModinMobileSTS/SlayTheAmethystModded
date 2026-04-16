package io.stamethyst.backend.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackIssueSyncServiceTest {
    @Test
    fun mergeSyncedSubscriptions_marksIssueUnreadWhenSummaryUpdatedAfterLastViewedTime() {
        val current = listOf(
            subscription(
                issueNumber = 42L,
                unread = false,
                followedAtMs = 50L,
                lastViewedAtMs = 500L,
                lastSyncedAtMs = 100L,
                updatedAtMs = 200L
            )
        )
        val remote = threadCache(
            issueNumber = 42L,
            updatedAtMs = 800L,
            lastEventAtMs = 500L
        )

        val merged = mergeSyncedSubscriptions(
            current = current,
            remoteByIssueNumber = mapOf(42L to remote),
            syncedAtMs = 900L
        )

        assertEquals(1, merged.size)
        assertTrue(merged.single().unread)
        assertEquals(50L, merged.single().followedAtMs)
        assertEquals(500L, merged.single().lastViewedAtMs)
        assertEquals(900L, merged.single().lastSyncedAtMs)
        assertEquals(800L, merged.single().updatedAtMs)
    }

    @Test
    fun mergeSyncedSubscriptions_keepsEntriesMissingFromFetchedSnapshot() {
        val untouched = subscription(
            issueNumber = 7L,
            unread = true,
            followedAtMs = 20L,
            lastViewedAtMs = 120L,
            lastSyncedAtMs = 300L,
            updatedAtMs = 400L
        )
        val current = listOf(
            untouched,
            subscription(
                issueNumber = 42L,
                unread = false,
                followedAtMs = 60L,
                lastViewedAtMs = 500L,
                lastSyncedAtMs = 100L,
                updatedAtMs = 200L
            )
        )
        val remote = threadCache(
            issueNumber = 42L,
            updatedAtMs = 900L,
            lastEventAtMs = 700L
        )

        val merged = mergeSyncedSubscriptions(
            current = current,
            remoteByIssueNumber = mapOf(42L to remote),
            syncedAtMs = 950L
        )
        val untouchedAfterMerge = merged.first { it.issueNumber == 7L }
        val updatedAfterMerge = merged.first { it.issueNumber == 42L }

        assertEquals(untouched, untouchedAfterMerge)
        assertEquals(950L, updatedAfterMerge.lastSyncedAtMs)
        assertEquals(500L, updatedAfterMerge.lastViewedAtMs)
    }

    @Test
    fun withRemoteState_markViewedKeepsViewedTimestampMonotonic() {
        val current = subscription(
            issueNumber = 42L,
            unread = true,
            followedAtMs = 123L,
            lastViewedAtMs = 600L,
            lastSyncedAtMs = 100L,
            updatedAtMs = 200L
        )
        val remote = threadCache(
            issueNumber = 42L,
            updatedAtMs = 700L,
            lastEventAtMs = 400L
        )

        val updated = current.withRemoteState(
            remote = remote,
            syncedAtMs = 800L,
            markViewed = true
        )

        assertFalse(updated.unread)
        assertEquals(123L, updated.followedAtMs)
        assertEquals(700L, updated.lastViewedAtMs)
        assertEquals(800L, updated.lastSyncedAtMs)
        assertEquals(700L, updated.updatedAtMs)
    }

    @Test
    fun issueThreadCache_lastEventAtMsUsesUpdatedAtWhenSummaryIsNewerThanCachedEvents() {
        val cache = FeedbackIssueThreadCache(
            issueNumber = 42L,
            issueUrl = "https://example.com/issues/42",
            title = "Issue #42",
            state = "open",
            body = "",
            updatedAtMs = 900L,
            events = listOf(
                FeedbackThreadEvent(
                    id = "comment-400",
                    type = FeedbackThreadEventType.COMMENT,
                    authorType = FeedbackThreadAuthorType.DEVELOPER,
                    authorLabel = "developer",
                    body = "",
                    createdAtMs = 400L,
                    htmlUrl = null
                )
            )
        )

        assertEquals(900L, cache.lastEventAtMs)
    }

    @Test
    fun withRemoteState_marksUnreadWhenIssueSummaryUpdatedAfterLastViewedTime() {
        val current = subscription(
            issueNumber = 42L,
            unread = false,
            followedAtMs = 123L,
            lastViewedAtMs = 500L,
            lastSyncedAtMs = 100L,
            updatedAtMs = 400L
        )
        val remote = FeedbackIssueThreadCache(
            issueNumber = 42L,
            issueUrl = "https://example.com/issues/42",
            title = "Issue #42",
            state = "open",
            body = "",
            updatedAtMs = 900L,
            events = listOf(
                FeedbackThreadEvent(
                    id = "comment-450",
                    type = FeedbackThreadEventType.COMMENT,
                    authorType = FeedbackThreadAuthorType.DEVELOPER,
                    authorLabel = "developer",
                    body = "",
                    createdAtMs = 450L,
                    htmlUrl = null
                )
            )
        )

        val updated = current.withRemoteState(remote = remote, syncedAtMs = 950L)

        assertTrue(updated.unread)
        assertEquals(900L, remote.lastEventAtMs)
        assertEquals(900L, updated.updatedAtMs)
    }

    @Test
    fun upsertSubscriptionWithLimit_removesOldestFollowedIssueWhenAddingEleventh() {
        val current = (1L..10L).map { issueNumber ->
            subscription(
                issueNumber = issueNumber,
                unread = false,
                followedAtMs = issueNumber * 10L,
                lastViewedAtMs = 0L,
                lastSyncedAtMs = issueNumber * 100L,
                updatedAtMs = issueNumber * 100L
            )
        }
        val target = subscription(
            issueNumber = 11L,
            unread = false,
            followedAtMs = 999L,
            lastViewedAtMs = 0L,
            lastSyncedAtMs = 999L,
            updatedAtMs = 999L
        )

        val result = upsertSubscriptionWithLimit(current, target)

        assertEquals(FeedbackIssueSyncService.MAX_TRACKED_SUBSCRIPTIONS, result.subscriptions.size)
        assertEquals(listOf(1L), result.displacedSubscriptions.map { it.issueNumber })
        assertFalse(result.subscriptions.any { it.issueNumber == 1L })
        assertEquals(target, result.subscriptions.first { it.issueNumber == 11L })
    }

    @Test
    fun upsertSubscriptionWithLimit_preservesExistingIssueWithoutDisplacement() {
        val current = (1L..10L).map { issueNumber ->
            subscription(
                issueNumber = issueNumber,
                unread = false,
                followedAtMs = issueNumber * 10L,
                lastViewedAtMs = 0L,
                lastSyncedAtMs = issueNumber * 100L,
                updatedAtMs = issueNumber * 100L
            )
        }
        val refreshed = subscription(
            issueNumber = 5L,
            unread = true,
            followedAtMs = 50L,
            lastViewedAtMs = 123L,
            lastSyncedAtMs = 555L,
            updatedAtMs = 777L
        )

        val result = upsertSubscriptionWithLimit(current, refreshed)

        assertEquals(FeedbackIssueSyncService.MAX_TRACKED_SUBSCRIPTIONS, result.subscriptions.size)
        assertEquals(emptyList<Long>(), result.displacedSubscriptions.map { it.issueNumber })
        assertEquals(refreshed, result.subscriptions.first { it.issueNumber == 5L })
    }

    private fun subscription(
        issueNumber: Long,
        unread: Boolean,
        followedAtMs: Long,
        lastViewedAtMs: Long,
        lastSyncedAtMs: Long,
        updatedAtMs: Long
    ): FeedbackIssueSubscription {
        return FeedbackIssueSubscription(
            issueNumber = issueNumber,
            issueUrl = "https://example.com/issues/$issueNumber",
            title = "Issue #$issueNumber",
            state = "open",
            unread = unread,
            followedAtMs = followedAtMs,
            lastSyncedAtMs = lastSyncedAtMs,
            lastViewedAtMs = lastViewedAtMs,
            updatedAtMs = updatedAtMs
        )
    }

    private fun threadCache(
        issueNumber: Long,
        updatedAtMs: Long,
        lastEventAtMs: Long
    ): FeedbackIssueThreadCache {
        return FeedbackIssueThreadCache(
            issueNumber = issueNumber,
            issueUrl = "https://example.com/issues/$issueNumber",
            title = "Issue #$issueNumber",
            state = "open",
            body = "",
            updatedAtMs = updatedAtMs,
            events = listOf(
                FeedbackThreadEvent(
                    id = "comment-$lastEventAtMs",
                    type = FeedbackThreadEventType.COMMENT,
                    authorType = FeedbackThreadAuthorType.DEVELOPER,
                    authorLabel = "developer",
                    body = "",
                    createdAtMs = lastEventAtMs,
                    htmlUrl = null
                )
            )
        )
    }
}
