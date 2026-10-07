package io.stamethyst.backend.steamcloud

/** Only presentation is throttled; verification, transactions and terminal events are unchanged. */
internal class SteamCloudProgressPublishPolicy(private val intervalMs: Long = 200L) {
    private var previous: SteamCloudSyncProgress? = null
    private var publishedAtMs = 0L

    fun shouldPublish(progress: SteamCloudSyncProgress, nowMs: Long): Boolean {
        val last = previous
        if (progress == last) return false
        val boundary = last == null || last.phase != progress.phase || last.direction != progress.direction ||
            last.totalFiles != progress.totalFiles || progress.totalFiles > 0 && progress.completedFiles >= progress.totalFiles
        if (!boundary && nowMs - publishedAtMs < intervalMs) return false
        previous = progress
        publishedAtMs = nowMs
        return true
    }
}
