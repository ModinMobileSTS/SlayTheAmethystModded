package io.stamethyst.backend.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the shared task-status -> display-kind mapping.
 *
 * The mod page and the market list used to fold Queued/Cancelling into Downloading with
 * independent copies of this logic, so a queued item rendered an animating progress bar as if
 * bytes were moving. Both surfaces now route through [WorkshopModStateResolver.resolveTaskKind];
 * these assertions keep them from drifting apart again.
 */
class WorkshopModStateResolverTaskKindTest {
    @Test
    fun queuedStaysDistinctFromDownloading() {
        assertEquals(
            WorkshopResolvedModStateKind.Queued,
            WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Queued),
        )
    }

    @Test
    fun cancellingStaysDistinctFromDownloading() {
        assertEquals(
            WorkshopResolvedModStateKind.Cancelling,
            WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Cancelling),
        )
    }

    @Test
    fun activeTransferStatusesReportDownloading() {
        // Pausing still drains the current transfer, so it deliberately reports Downloading
        // until the service settles on Paused.
        listOf(
            WorkshopDownloadTaskStatus.Resolving,
            WorkshopDownloadTaskStatus.Downloading,
            WorkshopDownloadTaskStatus.Pausing,
        ).forEach { status ->
            assertEquals(
                "expected $status to map to Downloading",
                WorkshopResolvedModStateKind.Downloading,
                WorkshopModStateResolver.resolveTaskKind(status),
            )
        }
    }

    @Test
    fun pausedAndFailedKeepTheirOwnKinds() {
        assertEquals(
            WorkshopResolvedModStateKind.DownloadPaused,
            WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Paused),
        )
        assertEquals(
            WorkshopResolvedModStateKind.DownloadFailed,
            WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Failed),
        )
    }

    @Test
    fun terminalStatusesYieldNoTaskKind() {
        // Completed/Cancelled must fall through to the persisted record state instead of
        // pinning the card to a stale in-flight status.
        assertNull(WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Completed))
        assertNull(WorkshopModStateResolver.resolveTaskKind(WorkshopDownloadTaskStatus.Cancelled))
        assertNull(WorkshopModStateResolver.resolveTaskKind(null))
    }

    @Test
    fun everyTaskStatusIsMapped() {
        // Guards against a new WorkshopDownloadTaskStatus member silently falling into a
        // catch-all branch on one page but not the other.
        val unmapped = WorkshopDownloadTaskStatus.entries.filter { status ->
            WorkshopModStateResolver.resolveTaskKind(status) == null &&
                status != WorkshopDownloadTaskStatus.Completed &&
                status != WorkshopDownloadTaskStatus.Cancelled
        }
        assertEquals(emptyList<WorkshopDownloadTaskStatus>(), unmapped)
    }

    @Test
    fun resolveKeepsQueuedStatusTextSeparateFromDownloading() {
        val queued = WorkshopModStateResolver.resolve(
            record = null,
            taskStatus = WorkshopDownloadTaskStatus.Queued,
        )
        assertEquals(WorkshopResolvedModStateKind.Queued, queued.kind)
        assertEquals("等待下载", queued.statusText)

        val cancelling = WorkshopModStateResolver.resolve(
            record = null,
            taskStatus = WorkshopDownloadTaskStatus.Cancelling,
        )
        assertEquals(WorkshopResolvedModStateKind.Cancelling, cancelling.kind)
        assertEquals("正在取消", cancelling.statusText)
    }

    @Test
    fun explicitTaskMessageWinsOverFallbackText() {
        val resolved = WorkshopModStateResolver.resolve(
            record = null,
            taskStatus = WorkshopDownloadTaskStatus.Downloading,
            taskMessage = "正在下载 3/8",
        )
        assertEquals(WorkshopResolvedModStateKind.Downloading, resolved.kind)
        assertEquals("正在下载 3/8", resolved.statusText)
    }

    @Test
    fun missingRecordWithoutTaskReportsNotDownloaded() {
        val resolved = WorkshopModStateResolver.resolve(record = null)
        assertEquals(WorkshopResolvedModStateKind.NotDownloaded, resolved.kind)
    }
}
