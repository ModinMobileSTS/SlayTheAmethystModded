package io.stamethyst.backend.workshop

import android.content.Context

internal class WorkshopUpdateChecker(context: Context) {
    private val service = WorkshopService(context)
    private val store = WorkshopMetadataStore(context)

    suspend fun checkInstalledMods(publishedFileIds: Set<ULong>? = null): WorkshopUpdateCheckReport {
        val results = ArrayList<WorkshopUpdateCheckResult>()
        var failedCount = 0
        store.list().filter { publishedFileIds == null || it.publishedFileId in publishedFileIds }.forEach { record ->
            runCatching {
                val details = service.getDetails(
                    appId = record.appId,
                    publishedFileId = record.publishedFileId,
                    includeCommunityData = false,
                    includeDependencyData = false,
                )
                WorkshopUpdateCheckResult(
                    appId = record.appId,
                    publishedFileId = record.publishedFileId,
                    hasUpdate = WorkshopUpdatePolicy.hasUpdate(
                        localUpdatedAtMillis = record.updatedAtMillis,
                        remoteUpdatedAtMillis = details.summary.updatedAtMillis,
                    ),
                    remoteUpdatedAtMillis = details.summary.updatedAtMillis,
                    localUpdatedAtMillis = record.updatedAtMillis,
                    remoteVersionText = details.summary.updatedAtMillis.toString(),
                )
            }.onSuccess { result ->
                results.add(result)
            }.onFailure {
                failedCount++
            }
        }
        store.applyUpdateCheckResults(results)
        store.markMissingFiles()
        return WorkshopUpdateCheckReport(results = results, failedCount = failedCount)
    }
}
