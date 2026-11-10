package io.stamethyst.backend.workshop

import java.io.File

internal object WorkshopSubscriptionSyncPolicy {
    fun shouldEnqueue(
        blocked: Boolean,
        markedDeleted: Boolean,
        taskStatus: WorkshopDownloadTaskStatus?,
        hasLocalContent: Boolean,
    ): Boolean = !blocked && !markedDeleted && !hasLocalContent &&
        (taskStatus == null || taskStatus == WorkshopDownloadTaskStatus.Completed)

    fun hasLocalContent(record: WorkshopInstalledModRecord?): Boolean {
        if (record == null) return false
        return when (record.contentKind) {
            WorkshopInstalledContentKind.JarMod,
            WorkshopInstalledContentKind.NonStandard -> record.allLocalJarPaths().any { File(it).isFile }
            WorkshopInstalledContentKind.TexturePack -> {
                if (record.texturePackPath.isBlank()) return false
                val directory = File(record.texturePackPath)
                directory.isDirectory && directory.walkTopDown().any { it.isFile }
            }
        }
    }
}
