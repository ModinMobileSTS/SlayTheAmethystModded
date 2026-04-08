package io.stamethyst.backend.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedbackIssueSyncServiceTest {
    @Test
    fun mergeSyncedSubscriptions_preservesLatestViewedState() {
        val current = listOf(
            subscription(
                issueNumber = 42L,
                unread = false,
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
        assertFalse(merged.single().unread)
        assertEquals(500L, merged.single().lastViewedAtMs)
        assertEquals(900L, merged.single().lastSyncedAtMs)
        assertEquals(800L, merged.single().updatedAtMs)
    }

    @Test
    fun mergeSyncedSubscriptions_keepsEntriesMissingFromFetchedSnapshot() {
        val untouched = subscription(
            issueNumber = 7L,
            unread = true,
            lastViewedAtMs = 120L,
            lastSyncedAtMs = 300L,
            updatedAtMs = 400L
        )
        val current = listOf(
            untouched,
            subscription(
                issueNumber = 42L,
                unread = false,
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
        assertEquals(600L, updated.lastViewedAtMs)
        assertEquals(800L, updated.lastSyncedAtMs)
        assertEquals(700L, updated.updatedAtMs)
    }

    private fun subscription(
        issueNumber: Long,
        unread: Boolean,
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
