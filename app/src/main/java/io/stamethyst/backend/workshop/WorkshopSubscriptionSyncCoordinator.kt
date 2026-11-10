package io.stamethyst.backend.workshop

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class WorkshopSubscriptionAccountIdentity(val accountId: String, val credentialRevision: Long)

internal enum class WorkshopSubscriptionEnqueueResult { Queued, Skipped, PreparationFailed }

/** Lifecycle state and calls are owned by the supplied scope's dispatcher (Main in the app). */
internal class WorkshopSubscriptionSyncCoordinator(
    private val scope: CoroutineScope,
    private val appId: UInt,
    private val monotonicMillis: () -> Long,
    private val loadPage: suspend (WorkshopSubscriptionAccountIdentity, Int) -> WorkshopBrowseResult,
    private val shouldEnqueue: suspend (WorkshopSubscriptionAccountIdentity, WorkshopItemSummary) -> Boolean,
    private val enqueue: suspend (WorkshopSubscriptionAccountIdentity, WorkshopItemSummary) -> WorkshopSubscriptionEnqueueResult,
    private val onScanFailure: (Exception) -> Unit = {},
) {
    private val engine = WorkshopSubscriptionSyncEngine(appId)
    private var candidates = Mutex()
    private var account: WorkshopSubscriptionAccountIdentity? = null
    private var active = false
    private var generation = 0L
    private var scan: Job? = null
    private var lastSuccess: Long? = null
    var lastScanFailure: Exception? = null
        private set

    fun update(foreground: Boolean, startupReady: Boolean, account: WorkshopSubscriptionAccountIdentity?) {
        val changed = this.account != account
        val enabled = foreground && startupReady && account != null
        if (changed || !enabled) {
            generation++
            scan?.cancel()
            scan = null
            // A revoked callback may ignore cancellation. It must not hold the new account's
            // serialization lane hostage; the generation fence still prevents its commit.
            candidates = Mutex()
        }
        if (changed) lastSuccess = null
        this.account = account
        active = enabled
        if (!enabled || scan?.isActive == true) return
        val identity = account
        val token = generation
        val now = monotonicMillis()
        if (lastSuccess?.let { now - it < 600_000L } == true) return
        scan = scope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.sync(
                    loadPage = { page ->
                        checkCurrent(identity, token)
                        loadPage(identity, page).also { checkCurrent(identity, token) }
                    },
                    shouldEnqueue = { checkCurrent(identity, token); true },
                    enqueue = { item -> enqueueCandidate(identity, token, item) },
                )
                checkCurrent(identity, token)
                lastSuccess = monotonicMillis()
                lastScanFailure = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(identity, token)) {
                    lastScanFailure = failure
                    onScanFailure(failure)
                }
            }
        }.also { it.start() }
    }

    suspend fun enqueueSubscribed(account: WorkshopSubscriptionAccountIdentity, item: WorkshopItemSummary): WorkshopSubscriptionEnqueueResult =
        enqueueCandidate(account, generation, item)

    private suspend fun enqueueCandidate(identity: WorkshopSubscriptionAccountIdentity, token: Long, item: WorkshopItemSummary): WorkshopSubscriptionEnqueueResult = candidates.withLock {
        if (!isCurrent(identity, token) || item.appId != appId) return@withLock WorkshopSubscriptionEnqueueResult.Skipped
        try {
            currentCoroutineContext().ensureActive()
            val eligible = shouldEnqueue(identity, item)
            currentCoroutineContext().ensureActive()
            if (!eligible || !isCurrent(identity, token)) return@withLock WorkshopSubscriptionEnqueueResult.Skipped
            enqueue(identity, item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            WorkshopSubscriptionEnqueueResult.PreparationFailed
        }
    }

    private fun isCurrent(identity: WorkshopSubscriptionAccountIdentity, token: Long) = active && account == identity && generation == token

    private suspend fun checkCurrent(identity: WorkshopSubscriptionAccountIdentity, token: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(identity, token)) throw CancellationException("Subscription scan is no longer current")
    }
}

/** Runs on IO in the app. Re-reads user decisions and artifacts after details preparation. */
internal class WorkshopSubscriptionQueue(
    private val taskStore: WorkshopDownloadTaskStore,
    private val itemDirectory: (ULong) -> File,
    private val installedRecord: (ULong) -> WorkshopInstalledModRecord?,
    private val getDetails: suspend (ULong) -> WorkshopItemDetails,
    private val isBlocked: (WorkshopItemSummary) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val onQueued: suspend () -> Unit,
    private val queuedMessage: () -> String = { "Waiting to download" },
) {
    suspend fun enqueue(item: WorkshopItemSummary): WorkshopSubscriptionEnqueueResult {
        try {
            currentCoroutineContext().ensureActive()
            val expected = taskStore.find(item.publishedFileId)?.status
            if (!eligible(item)) return WorkshopSubscriptionEnqueueResult.Skipped
            val details = getDetails(item.publishedFileId)
            currentCoroutineContext().ensureActive()
            if (details.summary.appId != item.appId || details.summary.publishedFileId != item.publishedFileId ||
                !eligible(details.summary)
            ) return WorkshopSubscriptionEnqueueResult.Skipped
            val task = WorkshopDownloadTaskRecord(
                publishedFileId = item.publishedFileId,
                title = details.summary.title,
                status = WorkshopDownloadTaskStatus.Queued,
                message = queuedMessage(),
                details = details,
            )
            // The adapter's fence reads both the cross-process epoch and credential revision here.
            if (!isCurrent() || !taskStore.enqueueSubscriptionIfEligible(task, expected, isCurrent)) {
                return WorkshopSubscriptionEnqueueResult.Skipped
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return WorkshopSubscriptionEnqueueResult.PreparationFailed
        }
        onQueued()
        return WorkshopSubscriptionEnqueueResult.Queued
    }

    private fun eligible(item: WorkshopItemSummary): Boolean {
        if (!isCurrent()) return false
        val directory = itemDirectory(item.publishedFileId)
        fun resolve(path: String): String {
            val trimmed = path.trim()
            return if (trimmed.isBlank()) "" else
                File(trimmed).let { if (it.isAbsolute) it else File(directory, trimmed) }.absolutePath
        }
        val record = installedRecord(item.publishedFileId)?.let {
            it.copy(localJarPath = resolve(it.localJarPath), localJarPaths = it.localJarPaths.map(::resolve),
                texturePackPath = resolve(it.texturePackPath))
        }
        // Direct downloads and root manifest jars are final files in the item directory. Do not
        // recurse into caches/chunks or infer completeness from metadata, .part or .tmp files.
        val rootJar = directory.listFiles()?.any { it.isFile && it.extension.equals("jar", ignoreCase = true) } == true
        return WorkshopSubscriptionSyncPolicy.shouldEnqueue(
            blocked = isBlocked(item),
            markedDeleted = taskStore.isMarkedDeleted(item.publishedFileId),
            taskStatus = taskStore.find(item.publishedFileId)?.status,
            hasLocalContent = WorkshopSubscriptionSyncPolicy.hasLocalContent(record) || rootJar,
        )
    }
}
