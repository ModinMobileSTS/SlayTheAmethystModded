package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

class WorkshopSubscriptionSyncEligibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(
        File(generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "gradlew").isFile }, "agent-tmp")
            .apply { check(isDirectory || mkdirs()) },
    )

    @Test
    fun confirmedSubscriptionQueuesWithAutomaticScanningDisabled() = runBlocking {
        val f = Fixture()
        val coordinator = f.coordinator(this)

        assertFalse(f.eligibility.foreground)
        assertFalse(f.eligibility.ready)
        assertFalse(f.eligibility.canScan(f.account))
        assertEquals(WorkshopSubscriptionEnqueueResult.Queued, coordinator.enqueueSubscribed(f.account, f.item))
        assertEquals(WorkshopDownloadTaskStatus.Queued, f.store.find(f.item.publishedFileId)?.status)
        assertEquals(1, f.detailsCalls)
        assertEquals(1, f.notifications)
        assertEquals(0, f.pageLoads)
    }

    @Test
    fun scanStillRequiresForegroundAndReadyWhileExplicitEligibilityOnlyRequiresCurrentAccount() {
        val f = Fixture()
        for (foreground in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                f.eligibility.foreground = foreground
                f.eligibility.ready = ready
                assertEquals(foreground && ready, f.eligibility.canScan(f.account))
                assertTrue(f.eligibility.canEnqueue(f.account))
            }
        }
        f.liveIdentity = null
        assertFalse(f.eligibility.canScan(f.account))
        assertFalse(f.eligibility.canEnqueue(f.account))
    }

    @Test
    fun accountEpochRevisionAndRevocationFenceCapturedSubscriptionsBeforeDetails() = runBlocking {
        for (refreshObservedIdentity in listOf(false, true)) {
            for (next in invalidatedIdentities) {
                val f = Fixture()
                val coordinator = f.coordinator(this)
                f.liveIdentity = next
                if (refreshObservedIdentity) f.eligibility.identity = next

                assertEquals("next=$next refreshObservedIdentity=$refreshObservedIdentity",
                    WorkshopSubscriptionEnqueueResult.Skipped, coordinator.enqueueSubscribed(f.account, f.item))
                assertEquals(0, f.detailsCalls)
                assertEquals(0, f.notifications)
                assertEquals(0, f.pageLoads)
                assertNull(f.store.find(f.item.publishedFileId))
            }
        }
    }

    @Test
    fun observedAccountMustAlsoMatchEvenIfPersistentIdentityStillMatchesCapture() = runBlocking {
        val f = Fixture()
        val coordinator = f.coordinator(this)
        f.eligibility.identity = null

        assertEquals(WorkshopSubscriptionEnqueueResult.Skipped, coordinator.enqueueSubscribed(f.account, f.item))
        assertEquals(0, f.detailsCalls)
        assertNull(f.store.find(f.item.publishedFileId))
    }

    @Test
    fun accountEpochRevisionAndRevocationDuringDetailsPreventCommitWithoutAuthRefresh() = runBlocking {
        for (next in invalidatedIdentities) {
            val f = Fixture()
            val coordinator = f.coordinator(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.details = {
                entered.complete(Unit)
                release.await()
                WorkshopItemDetails(f.item)
            }
            val pending = async(Dispatchers.Unconfined) { coordinator.enqueueSubscribed(f.account, f.item) }
            entered.await()
            // Simulate a cross-process change before the launcher's auth observer refreshes.
            f.liveIdentity = next
            release.complete(Unit)

            assertEquals("next=$next", WorkshopSubscriptionEnqueueResult.Skipped, pending.await())
            assertEquals(1, f.detailsCalls)
            assertEquals(0, f.notifications)
            assertEquals(0, f.pageLoads)
            assertNull(f.store.find(f.item.publishedFileId))
        }
    }

    @Test
    fun explicitSubscriptionStillRespectsAllExistingTaskStatuses() = runBlocking {
        for (status in listOf(null) + WorkshopDownloadTaskStatus.entries) {
            val f = Fixture()
            if (status != null) f.store.upsert(f.task(status))
            val coordinator = f.coordinator(this)
            val eligible = status == null || status == WorkshopDownloadTaskStatus.Completed

            assertEquals("status=$status", if (eligible) WorkshopSubscriptionEnqueueResult.Queued
                else WorkshopSubscriptionEnqueueResult.Skipped, coordinator.enqueueSubscribed(f.account, f.item))
            assertEquals(if (eligible) WorkshopDownloadTaskStatus.Queued else status, f.store.find(f.item.publishedFileId)?.status)
            assertEquals(if (eligible) 1 else 0, f.notifications)
            assertEquals(0, f.pageLoads)
        }
    }

    @Test
    fun explicitSubscriptionStillRespectsBlocklistDeletionAndLocalContent() = runBlocking {
        for (reason in listOf("blocked", "deleted", "local")) {
            val f = Fixture()
            when (reason) {
                "blocked" -> f.blocked = true
                "deleted" -> f.store.removeAndMarkDeleted(f.item.publishedFileId)
                "local" -> File(f.itemDir, "installed.jar").writeText("local content")
            }
            val coordinator = f.coordinator(this)

            assertEquals(reason, WorkshopSubscriptionEnqueueResult.Skipped, coordinator.enqueueSubscribed(f.account, f.item))
            assertNull(f.store.find(f.item.publishedFileId))
            assertEquals(reason == "deleted", f.store.isMarkedDeleted(f.item.publishedFileId))
            assertEquals(0, f.detailsCalls)
            assertEquals(0, f.notifications)
            assertEquals(0, f.pageLoads)
        }
    }

    private inner class Fixture {
        val account = capturedAccount
        var liveIdentity: WorkshopSubscriptionAccountIdentity? = account
        // This is the production controller's strategy, not a coordinator callback returning true.
        val eligibility = WorkshopSubscriptionSyncEligibility { liveIdentity }.apply { identity = account }
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
        var blocked = false
        var detailsCalls = 0
        var notifications = 0
        var pageLoads = 0
        var details: suspend () -> WorkshopItemDetails = { WorkshopItemDetails(item) }

        fun coordinator(scope: CoroutineScope) = WorkshopSubscriptionSyncCoordinator(
            scope = scope,
            appId = item.appId,
            monotonicMillis = { 0L },
            loadPage = { _, _ ->
                pageLoads++
                error("Paused automatic scans must not load subscription pages")
            },
            shouldEnqueue = eligibility::shouldEnqueue,
            enqueue = { account, candidate ->
                WorkshopSubscriptionQueue(
                    taskStore = store,
                    itemDirectory = { itemDir },
                    installedRecord = { null },
                    getDetails = { detailsCalls++; details() },
                    isBlocked = { blocked },
                    isCurrent = { eligibility.canEnqueue(account) },
                    onQueued = { notifications++ },
                ).enqueue(candidate)
            },
        ).apply { update(foreground = false, startupReady = false, account = this@Fixture.account) }

        fun task(status: WorkshopDownloadTaskStatus) = WorkshopDownloadTaskRecord(
            item.publishedFileId, item.title, status, "existing", details = WorkshopItemDetails(item),
        )
    }

    companion object {
        private val capturedAccount = WorkshopSubscriptionAccountIdentity("account-a:epoch-1", 1L)
        private val invalidatedIdentities = listOf(
            WorkshopSubscriptionAccountIdentity("account-b:epoch-1", 1L),
            capturedAccount.copy(accountId = "account-a:epoch-2"),
            capturedAccount.copy(credentialRevision = 2L),
            null,
        )
    }
}
