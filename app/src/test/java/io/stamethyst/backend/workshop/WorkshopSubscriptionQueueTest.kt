package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkshopSubscriptionQueueTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(
        File(generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "gradlew").isFile }, "agent-tmp")
            .apply { check(isDirectory || mkdirs()) },
    )

    @Test
    fun queuesMissingAndCompletedButProtectsEveryOtherStatusAndDeletedMarker() = runBlocking {
        for (status in listOf(null) + WorkshopDownloadTaskStatus.entries) {
            val f = Fixture()
            if (status != null) f.store.upsert(f.task(status))
            val expected = status == null || status == WorkshopDownloadTaskStatus.Completed
            assertEquals("status=$status", if (expected) WorkshopSubscriptionEnqueueResult.Queued
                else WorkshopSubscriptionEnqueueResult.Skipped, f.queue.enqueue(f.item))
            assertEquals(if (expected) WorkshopDownloadTaskStatus.Queued else status, f.store.find(1uL)?.status)
            assertEquals(if (expected) 1 else 0, f.notifications)
        }
        val f = Fixture()
        f.store.removeAndMarkDeleted(1uL)
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, f.queue.enqueue(f.item))
        assertTrue(f.store.isMarkedDeleted(1uL))
        assertNull(f.store.find(1uL))
    }

    @Test
    fun rechecksDeletionManualTaskAndAccountAfterSuspendedDetails() = runBlocking {
        for (change in listOf("deleted", "manual", "account", "blocked")) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.details = {
                entered.complete(Unit)
                release.await()
                WorkshopItemDetails(f.item.copy(title = if (change == "blocked") "blocked" else "allowed"))
            }
            val pending = async(Dispatchers.Unconfined) { f.queue.enqueue(f.item) }
            entered.await()
            when (change) {
                "deleted" -> f.store.removeAndMarkDeleted(1uL)
                "manual" -> f.store.upsert(f.task(WorkshopDownloadTaskStatus.Cancelled))
                "account" -> f.current = false
            }
            release.complete(Unit)
            assertEquals(change, WorkshopSubscriptionEnqueueResult.Skipped, pending.await())
            assertEquals(0, f.notifications)
            if (change == "manual") assertEquals(WorkshopDownloadTaskStatus.Cancelled, f.store.find(1uL)?.status)
            else assertNull(f.store.find(1uL))
            if (change == "deleted") assertTrue(f.store.isMarkedDeleted(1uL))
        }
    }

    @Test
    fun guardedStoreCommitRejectsChangedExpectedStatusAndNeverClearsDeletion() {
        val f = Fixture()
        f.store.upsert(f.task(WorkshopDownloadTaskStatus.Completed))
        f.store.upsert(f.task(WorkshopDownloadTaskStatus.Downloading))
        assertFalse(f.store.enqueueSubscriptionIfEligible(f.task(WorkshopDownloadTaskStatus.Queued), WorkshopDownloadTaskStatus.Completed))
        assertEquals(WorkshopDownloadTaskStatus.Downloading, f.store.find(1uL)?.status)
        f.store.removeAndMarkDeleted(1uL)
        assertFalse(f.store.enqueueSubscriptionIfEligible(f.task(WorkshopDownloadTaskStatus.Queued), null))
        assertTrue(f.store.isMarkedDeleted(1uL))
        assertNull(f.store.find(1uL))
        val clean = Fixture()
        assertTrue(clean.store.enqueueSubscriptionIfEligible(clean.task(WorkshopDownloadTaskStatus.Queued), null))
        assertFalse(clean.store.enqueueSubscriptionIfEligible(clean.task(WorkshopDownloadTaskStatus.Queued), null))
    }

    @Test
    fun relativeDeclaredJarsResolveAgainstItemDirectoryAndAbsoluteImportsRemainAbsolute() = runBlocking {
        for (absolute in listOf(false, true)) {
            val f = Fixture()
            val jar = if (absolute) temporaryFolder.newFile("imported.jar") else File(f.itemDir, "mod.jar").apply { writeText("jar") }
            f.metadata = f.record(if (absolute) jar.absolutePath else "mod.jar")
            assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, f.queue.enqueue(f.item))
            assertNull(f.store.find(1uL))
            assertEquals(0, f.detailsCalls)
        }
    }

    @Test
    fun nonemptyTextureDirectoryCountsButPartialCacheAndMetadataDoNot() = runBlocking {
        val texture = Fixture()
        val pack = File(texture.itemDir, "texture").apply { mkdirs() }
        File(pack, "image.png").writeText("texture")
        texture.metadata = texture.record("").copy(contentKind = WorkshopInstalledContentKind.TexturePack, texturePackPath = pack.absolutePath)
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, texture.queue.enqueue(texture.item))
        val incomplete = Fixture()
        listOf("mod.jar.part", "mod.tmp", "metadata.json", "cache/data.bin").forEach { path ->
            File(incomplete.itemDir, path).apply { parentFile?.mkdirs(); writeText("not finished") }
        }
        incomplete.store.upsert(incomplete.task(WorkshopDownloadTaskStatus.Completed))
        assertEquals(WorkshopSubscriptionEnqueueResult.Queued, incomplete.queue.enqueue(incomplete.item))
    }

    @Test
    fun preparationFailureDoesNotPersistOrNotifyAndInactiveAccountDoesNotFetch() = runBlocking {
        val f = Fixture()
        f.current = false
        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, f.queue.enqueue(f.item))
        assertEquals(0, f.detailsCalls)
        f.current = true
        f.details = { throw IllegalStateException("details unavailable") }
        assertEquals(WorkshopSubscriptionEnqueueResult.PreparationFailed, f.queue.enqueue(f.item))
        assertNull(f.store.find(1uL))
        assertEquals(0, f.notifications)
    }

    @Test
    fun paddedRelativeLegacyJarIsAlreadyPresent() = runBlocking {
        assertPaddedDeclaredJarIsAlreadyPresent(absolute = false, declaredList = false)
    }

    @Test
    fun paddedRelativeDeclaredJarIsAlreadyPresent() = runBlocking {
        assertPaddedDeclaredJarIsAlreadyPresent(absolute = false, declaredList = true)
    }

    @Test
    fun paddedAbsoluteLegacyImportIsAlreadyPresent() = runBlocking {
        assertPaddedDeclaredJarIsAlreadyPresent(absolute = true, declaredList = false)
    }

    @Test
    fun paddedAbsoluteDeclaredImportIsAlreadyPresent() = runBlocking {
        assertPaddedDeclaredJarIsAlreadyPresent(absolute = true, declaredList = true)
    }

    private suspend fun assertPaddedDeclaredJarIsAlreadyPresent(absolute: Boolean, declaredList: Boolean) {
        val f = Fixture()
        // Neither a nested artifact nor an external import is covered by root-JAR fallback.
        val jar = if (absolute) temporaryFolder.newFile("external-import.jar") else
            File(f.itemDir, "nested/mod.jar").apply { parentFile.mkdirs(); writeText("jar") }
        val path = "  ${if (absolute) jar.absolutePath else "nested/mod.jar"}  "
        f.metadata = if (declaredList) f.record("").copy(localJarPaths = listOf(path)) else f.record(path)

        assertTrue(jar.isFile)
        assertEquals("Padded declared content must not be downloaded again",
            WorkshopSubscriptionEnqueueResult.Skipped, f.queue.enqueue(f.item))
        assertNull(f.store.find(1uL))
        assertEquals(0, f.detailsCalls)
        assertEquals(0, f.notifications)
    }

    private inner class Fixture {
        val root = temporaryFolder.newFolder()
        val files = File(root, "files").apply { mkdirs() }
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = files
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "io.stamethyst.test"
        }
        val store = WorkshopDownloadTaskStore(context)
        val itemDir = File(root, "item").apply { mkdirs() }
        val item = WorkshopItemSummary(1uL, 646570u, "allowed", "", "")
        var current = true
        var metadata: WorkshopInstalledModRecord? = null
        var detailsCalls = 0
        var notifications = 0
        var details: suspend () -> WorkshopItemDetails = { WorkshopItemDetails(item) }
        val queue = WorkshopSubscriptionQueue(
            taskStore = store,
            itemDirectory = { _: ULong -> itemDir },
            installedRecord = { _: ULong -> metadata },
            getDetails = { _: ULong -> detailsCalls++; details() },
            isBlocked = { summary: WorkshopItemSummary -> summary.title == "blocked" },
            isCurrent = { current },
            onQueued = { notifications++ },
        )
        fun task(status: WorkshopDownloadTaskStatus) = WorkshopDownloadTaskRecord(1uL, "allowed", status, "existing", details = WorkshopItemDetails(item))
        fun record(path: String) = WorkshopInstalledModRecord(646570u, 1uL, "allowed", "", "", "", 0L, 0L, path)
    }
}
