package io.stamethyst.ui.workshop

import io.stamethyst.backend.workshop.shouldShowOnLauncherCards

/** Each completed transfer can change installed metadata, even while the queue is still busy. */
internal fun hasSettledWorkshopDownloads(
    previousTasks: List<WorkshopDownloadTaskUi>,
    currentTasks: List<WorkshopDownloadTaskUi>,
): Boolean {
    val currentStatuses = currentTasks.associate { task ->
        (task.details.summary.appId to task.publishedFileId) to task.status
    }
    return previousTasks.any { task ->
        task.status.shouldShowOnLauncherCards() &&
            currentStatuses[task.details.summary.appId to task.publishedFileId]?.shouldShowOnLauncherCards() != true
    }
}
