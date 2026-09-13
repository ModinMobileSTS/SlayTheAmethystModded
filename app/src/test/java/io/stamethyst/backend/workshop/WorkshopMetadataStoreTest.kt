package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopMetadataStoreTest {
    @Test
    fun upsertReplacesRecordForSamePublishedFileId() {
        val roots = TestRoots.create("workshop-metadata-store")
        val store = WorkshopMetadataStore(roots.context)
        val first = record(title = "Old", updatedAtMillis = 100L)
        val second = record(title = "New", updatedAtMillis = 200L)

        store.upsert(first)
        store.upsert(second)

        val records = store.list()
        assertEquals(1, records.size)
        assertEquals("New", records.single().title)
        assertEquals(200L, records.single().updatedAtMillis)
    }

    @Test
    fun saveSortsByRemoteUpdatedTimeDescending() {
        val roots = TestRoots.create("workshop-metadata-store-sort")
        val store = WorkshopMetadataStore(roots.context)

        store.save(
            listOf(
                record(title = "Older", publishedFileId = 1u, updatedAtMillis = 100L),
                record(title = "Newer", publishedFileId = 2u, updatedAtMillis = 300L),
            )
        )

        assertEquals(listOf("Newer", "Older"), store.list().map { it.title })
    }

    @Test
    fun removeByLocalJarPathsRemovesOnlyMatchingWorkshopRecords() {
        val roots = TestRoots.create("workshop-metadata-store-remove-paths")
        val store = WorkshopMetadataStore(roots.context)
        val replacedPath = File(roots.rootDir, "optional/Old Workshop.jar").absolutePath
        store.save(
            listOf(
                record(title = "Old Workshop", publishedFileId = 1u, updatedAtMillis = 100L).copy(
                    localJarPath = replacedPath.replace('\\', '/')
                ),
                record(title = "Other Workshop", publishedFileId = 2u, updatedAtMillis = 200L).copy(
                    localJarPath = File(roots.rootDir, "optional/Other.jar").absolutePath
                ),
            )
        )

        val removedCount = store.removeByLocalJarPaths(listOf(replacedPath))

        assertEquals(1, removedCount)
        assertEquals(listOf("Other Workshop"), store.list().map { it.title })
    }

    @Test
    fun removeByLocalJarPathsMatchesAnyInstalledJarPath() {
        val roots = TestRoots.create("workshop-metadata-store-remove-multi-paths")
        val store = WorkshopMetadataStore(roots.context)
        val firstPath = File(roots.rootDir, "optional/First.jar").absolutePath
        val secondPath = File(roots.rootDir, "optional/Second.jar").absolutePath
        store.save(
            listOf(
                record(title = "Multi Jar Workshop", publishedFileId = 1u, updatedAtMillis = 100L).copy(
                    localJarPath = firstPath,
                    localJarPaths = listOf(firstPath, secondPath),
                ),
                record(title = "Other Workshop", publishedFileId = 2u, updatedAtMillis = 200L).copy(
                    localJarPath = File(roots.rootDir, "optional/Other.jar").absolutePath
                ),
            )
        )

        val removedCount = store.removeByLocalJarPaths(listOf(secondPath))

        assertEquals(1, removedCount)
        assertEquals(listOf("Other Workshop"), store.list().map { it.title })
    }

    @Test
    fun rebindLocalJarPathsPreservesWorkshopAssociationForReplacementJar() {
        val roots = TestRoots.create("workshop-metadata-store-rebind")
        val store = WorkshopMetadataStore(roots.context)
        val oldPath = File(roots.rootDir, "optional/Old.jar").absolutePath
        val newPath = File(roots.rootDir, "optional/New.jar").absolutePath
        store.upsert(
            record(title = "Workshop Mod", publishedFileId = 3u, updatedAtMillis = 100L).copy(
                localJarPath = oldPath,
                localJarPaths = listOf(oldPath),
            )
        )

        assertEquals(1, store.rebindLocalJarPaths(listOf(oldPath), newPath))

        val rebound = store.findByPublishedFileId(646570u, 3uL)
        assertEquals(newPath, rebound?.localJarPath)
        assertEquals(listOf(newPath), rebound?.localJarPaths)
    }

    @Test
    fun recoverFinishedTransferRestoresDownloadedJarAsUnpatched() {
        val roots = TestRoots.create("workshop-metadata-store-recover-complete")
        val metadataStore = WorkshopMetadataStore(roots.context)
        val taskStore = WorkshopDownloadTaskStore(roots.context)
        val details = details(publishedFileId = 123uL, title = "Recovered Mod")
        val outputDir = File(roots.context.filesDir, "workshop/646570/123").apply { mkdirs() }
        File(outputDir, "Recovered Mod.jar").writeText("jar-bytes")
        val task = task(details).copy(
            status = WorkshopDownloadTaskStatus.Downloading,
            progressPercent = 100,
            downloadedBytes = 9L,
            totalBytes = 9L,
            completedFiles = 1,
            totalFiles = 1,
        )
        taskStore.upsert(task)

        val recovered = WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
            context = roots.context,
            metadataStore = metadataStore,
            taskStore = taskStore,
            task = task,
        )

        assertTrue(recovered)
        val record = metadataStore.findByPublishedFileId(646570u, 123uL)
        assertEquals(WorkshopModCardState.ImportedUnpatched, record?.cardState)
        assertEquals("Recovered Mod.jar", record?.localJarPath)
        assertEquals(WorkshopDownloadTaskStatus.Completed, taskStore.find(123uL)?.status)
    }

    @Test
    fun recoverFinishedTransferPreservesCompletedAutoImportFailureRecord() {
        val roots = TestRoots.create("workshop-metadata-store-recover-auto-import-failed")
        val metadataStore = WorkshopMetadataStore(roots.context)
        val taskStore = WorkshopDownloadTaskStore(roots.context)
        val details = details(publishedFileId = 127uL, title = "Downfall")
        val outputDir = File(roots.context.filesDir, "workshop/646570/127").apply { mkdirs() }
        File(outputDir, "Downfall.jar").writeText("downloaded-jar")
        val autoImportFailedMessage = "下载完成，自动导入失败：自动导入未产生已安装模组"
        metadataStore.upsert(
            record(title = "Downfall", publishedFileId = 127uL, updatedAtMillis = 200L).copy(
                localJarPath = "Downfall.jar",
                cardState = WorkshopModCardState.ImportedUnpatched,
                statusText = "自动导入失败，请手动导入",
            )
        )
        val task = task(details).copy(
            status = WorkshopDownloadTaskStatus.Completed,
            message = autoImportFailedMessage,
            progressPercent = 100,
            completedFiles = 1,
            totalFiles = 1,
        )
        taskStore.upsert(task)

        val recovered = WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
            context = roots.context,
            metadataStore = metadataStore,
            taskStore = taskStore,
            task = task,
        )

        assertFalse(recovered)
        val record = metadataStore.findByPublishedFileId(646570u, 127uL)
        assertEquals(WorkshopModCardState.ImportedUnpatched, record?.cardState)
        assertEquals("自动导入失败，请手动导入", record?.statusText)
        assertEquals(autoImportFailedMessage, taskStore.find(127uL)?.message)
    }

    @Test
    fun recoverFinishedTransferIgnoresPartialDownloadEvenWhenJarExists() {
        val roots = TestRoots.create("workshop-metadata-store-recover-partial")
        val metadataStore = WorkshopMetadataStore(roots.context)
        val taskStore = WorkshopDownloadTaskStore(roots.context)
        val details = details(publishedFileId = 124uL, title = "Partial Mod")
        val outputDir = File(roots.context.filesDir, "workshop/646570/124").apply { mkdirs() }
        File(outputDir, "Partial Mod.jar").writeText("partial")
        val task = task(details).copy(
            status = WorkshopDownloadTaskStatus.Downloading,
            progressPercent = 50,
            downloadedBytes = 4L,
            totalBytes = 8L,
        )
        taskStore.upsert(task)

        val recovered = WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
            context = roots.context,
            metadataStore = metadataStore,
            taskStore = taskStore,
            task = task,
        )

        assertFalse(recovered)
        assertEquals(null, metadataStore.findByPublishedFileId(646570u, 124uL))
        assertEquals(WorkshopDownloadTaskStatus.Downloading, taskStore.find(124uL)?.status)
    }

    @Test
    fun recoverFinishedTransferKeepsExistingInstalledJarDuringInterruptedUpdate() {
        val roots = TestRoots.create("workshop-metadata-store-recover-update")
        val metadataStore = WorkshopMetadataStore(roots.context)
        val taskStore = WorkshopDownloadTaskStore(roots.context)
        val details = details(publishedFileId = 125uL, title = "Updated Mod")
        val installedJar = File(roots.rootDir, "optional/Updated Mod.jar").apply {
            parentFile?.mkdirs()
            writeText("old-installed-jar")
        }
        metadataStore.upsert(
            record(title = "Updated Mod", publishedFileId = 125uL, updatedAtMillis = 100L).copy(
                localJarPath = installedJar.absolutePath,
                cardState = WorkshopModCardState.Downloading,
                statusText = "正在更新",
            )
        )
        val outputDir = File(roots.context.filesDir, "workshop/646570/125").apply { mkdirs() }
        File(outputDir, "Updated Mod.jar").writeText("new-downloaded-jar")
        val task = task(details).copy(
            status = WorkshopDownloadTaskStatus.Completed,
            progressPercent = 100,
            completedFiles = 1,
            totalFiles = 1,
        )
        taskStore.upsert(task)

        val recovered = WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
            context = roots.context,
            metadataStore = metadataStore,
            taskStore = taskStore,
            task = task,
        )

        assertTrue(recovered)
        val record = metadataStore.findByPublishedFileId(646570u, 125uL)
        assertEquals(WorkshopModCardState.UpdateAvailable, record?.cardState)
        assertEquals(installedJar.absolutePath, record?.localJarPath)
        assertEquals(WorkshopDownloadTaskStatus.Completed, taskStore.find(125uL)?.status)
    }

    @Test
    fun recoverFinishedTransferDoesNotRestoreQueuedUpdateBeforeItStarts() {
        val roots = TestRoots.create("workshop-metadata-store-recover-queued-update")
        val metadataStore = WorkshopMetadataStore(roots.context)
        val taskStore = WorkshopDownloadTaskStore(roots.context)
        val details = details(publishedFileId = 126uL, title = "Queued Update")
        val installedJar = File(roots.rootDir, "optional/Queued Update.jar").apply {
            parentFile?.mkdirs()
            writeText("old-installed-jar")
        }
        metadataStore.upsert(
            record(title = "Queued Update", publishedFileId = 126uL, updatedAtMillis = 100L).copy(
                localJarPath = installedJar.absolutePath,
                cardState = WorkshopModCardState.Downloading,
                statusText = "等待更新",
            )
        )
        val task = task(details).copy(
            status = WorkshopDownloadTaskStatus.Queued,
            message = "等待更新",
        )
        taskStore.upsert(task)

        val recovered = WorkshopInterruptedDownloadRecovery.recoverFinishedTransferIfPossible(
            context = roots.context,
            metadataStore = metadataStore,
            taskStore = taskStore,
            task = task,
        )

        assertFalse(recovered)
        val record = metadataStore.findByPublishedFileId(646570u, 126uL)
        assertEquals(WorkshopModCardState.Downloading, record?.cardState)
        assertEquals("等待更新", record?.statusText)
        assertEquals(WorkshopDownloadTaskStatus.Queued, taskStore.find(126uL)?.status)
    }

    private fun record(
        title: String,
        publishedFileId: ULong = 1605060445u,
        updatedAtMillis: Long,
    ): WorkshopInstalledModRecord = WorkshopInstalledModRecord(
        appId = 646570u,
        publishedFileId = publishedFileId,
        title = title,
        description = "description",
        previewUrl = "https://cdn.example/$publishedFileId.jpg",
        versionText = updatedAtMillis.toString(),
        updatedAtMillis = updatedAtMillis,
        installedAtMillis = updatedAtMillis + 10L,
        localJarPath = "mods/$title.jar",
    )

    private fun details(publishedFileId: ULong, title: String): WorkshopItemDetails = WorkshopItemDetails(
        summary = WorkshopItemSummary(
            appId = 646570u,
            publishedFileId = publishedFileId,
            title = title,
            previewUrl = "https://cdn.example/$publishedFileId.jpg",
            description = "description",
            fileSizeBytes = 9L,
            updatedAtMillis = 200L,
        )
    )

    private fun task(details: WorkshopItemDetails): WorkshopDownloadTaskRecord = WorkshopDownloadTaskRecord(
        publishedFileId = details.summary.publishedFileId,
        title = details.summary.title,
        status = WorkshopDownloadTaskStatus.Downloading,
        message = "正在下载",
        details = details,
    )

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
