package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.WorkshopDownloadTaskStatus as Status

internal data class DownloadSections(
    val transfers: List<WorkshopDownloadTaskUi>,
    val queue: List<WorkshopDownloadTaskUi>,
    val attention: List<WorkshopDownloadTaskUi>,
    val history: List<WorkshopDownloadTaskUi>,
)

internal fun downloadSections(tasks: List<WorkshopDownloadTaskUi>): DownloadSections {
    val transfers = mutableListOf<WorkshopDownloadTaskUi>()
    val queue = mutableListOf<WorkshopDownloadTaskUi>()
    val attention = mutableListOf<WorkshopDownloadTaskUi>()
    val history = mutableListOf<WorkshopDownloadTaskUi>()
    for (task in tasks) when (task.status) {
        Status.Resolving, Status.Downloading, Status.Pausing, Status.Cancelling, Status.Paused -> transfers += task
        Status.Queued -> queue += task
        Status.Failed -> attention += task
        Status.Completed, Status.Cancelled -> history += task
    }
    // Progress timestamps change frequently. Never move a running card under the user's finger.
    return DownloadSections(transfers.sortedWith(compareBy({ it.status == Status.Paused }, { it.publishedFileId })), queue.sortedBy { it.updatedAtMillis }, attention, history)
}

internal data class TransferRates(val network: Long?, val disk: Long?)
internal data class TransferTrace(
    val samples: List<TransferRates> = emptyList(),
    val peak: Long? = null,
    val progressBytesPerSecond: Long? = null,
)

/** Page-scoped so scrolling a card offscreen doesn't erase its peak or its graph. */
internal class TransferSampler {
    private data class Baseline(val at: Long, val rx: Long?, val disk: Long?, val done: Long, val trace: TransferTrace)
    private val baselines = mutableMapOf<ULong, Baseline>()

    fun sample(tasks: List<WorkshopDownloadTaskUi>, now: Long): Map<ULong, TransferTrace> {
        val active = tasks.filter { it.status == Status.Downloading || it.status == Status.Pausing }
        baselines.keys.retainAll(tasks.filter { it.status == Status.Paused || it.status == Status.Downloading || it.status == Status.Pausing }.mapTo(mutableSetOf()) { it.publishedFileId })
        for (task in active) {
            val id = task.publishedFileId
            val prev = baselines[id]
            val rx = task.receivedBytes
            val disk = task.diskWriteBytes
            if (prev == null || now < prev.at || task.downloadedBytes < prev.done ||
                (rx != null && prev.rx != null && rx < prev.rx) ||
                (disk != null && prev.disk != null && disk < prev.disk)
            ) {
                baselines[id] = Baseline(now, rx, disk, task.downloadedBytes, TransferTrace())
                continue
            }
            val elapsed = now - prev.at
            if (elapsed < 1000L) continue
            fun rate(current: Long?, previous: Long?): Long? {
                if (current == null || previous == null) return null
                return ((current - previous).toDouble() * 1000 / elapsed).toLong()
            }
            val rates = TransferRates(rate(rx, prev.rx), rate(disk, prev.disk))
            val trace = TransferTrace(
                (prev.trace.samples.takeLast(35) + rates),
                rates.network?.let { maxOf(it, prev.trace.peak ?: 0L) } ?: prev.trace.peak,
                rate(task.downloadedBytes, prev.done),
            )
            baselines[id] = Baseline(now, rx, disk, task.downloadedBytes, trace)
        }
        return baselines.mapValues { it.value.trace }
    }
}
