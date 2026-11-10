package io.stamethyst.backend.workshop

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.stamethyst.R
import io.stamethyst.backend.steam.SteamCmAccountEpoch
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.workshop.WorkshopDownloadCenterStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-local launcher adapter; it owns no timer, Activity, or background scan. */
internal class WorkshopSubscriptionSyncController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val service by lazy { WorkshopService(context) }
    private val tasks by lazy { WorkshopDownloadTaskStore(context) }
    private val metadata by lazy { WorkshopMetadataStore(context) }
    @Volatile private var foreground = false
    @Volatile private var ready = false
    @Volatile private var identity: WorkshopSubscriptionAccountIdentity? = null
    private var authRequest = 0L
    private val coordinator = WorkshopSubscriptionSyncCoordinator(
        scope = scope,
        appId = APP_ID,
        monotonicMillis = SystemClock::elapsedRealtime,
        loadPage = { account, page ->
            withContext(Dispatchers.IO) {
                requireCurrent(account)
                service.browseSubscriptions(appId = APP_ID, page = page).also { requireCurrent(account) }
            }
        },
        shouldEnqueue = { account, _ -> withContext(Dispatchers.IO) { isCurrent(account) } },
        enqueue = { account, item ->
            withContext(Dispatchers.IO) {
                WorkshopSubscriptionQueue(
                    taskStore = tasks,
                    itemDirectory = { RuntimePaths.workshopItemDir(context, APP_ID, it) },
                    installedRecord = { metadata.findByPublishedFileId(APP_ID, it) },
                    getDetails = { id ->
                        requireCurrent(account)
                        service.getDetails(APP_ID, id, includeCommunityData = false, includeDependencyData = false)
                    },
                    isBlocked = WorkshopDownloadBlocklist::isBlocked,
                    isCurrent = { isCurrent(account) },
                    queuedMessage = { context.getString(R.string.workshop_download_task_message_waiting) },
                    onQueued = {
                        withContext(Dispatchers.Main.immediate) { WorkshopDownloadCenterStore.initialize(context) }
                        val loaded = WorkshopDownloadCenterStore.loadTasks()
                        withContext(Dispatchers.Main.immediate) { WorkshopDownloadCenterStore.replaceInMemory(loaded) }
                        WorkshopDownloadProcessService.startNextQueued(context)
                    },
                ).enqueue(item)
            }
        },
        onScanFailure = { Log.w(TAG, "Subscription scan did not complete", it) },
    )

    fun setForeground(value: Boolean) {
        foreground = value
        coordinator.update(foreground, ready, identity)
        if (value) refreshAuth()
    }

    fun onStartupResourcesReady() {
        ready = true
        refreshAuth()
    }

    fun refreshAuth() {
        val request = ++authRequest
        scope.launch {
            val current = withContext(Dispatchers.IO) { readIdentity() }
            if (request != authRequest) return@launch
            identity = current
            coordinator.update(foreground, ready, current)
        }
    }

    suspend fun captureAccount(): WorkshopSubscriptionAccountIdentity? = withContext(Dispatchers.IO) { readIdentity() }

    suspend fun enqueueSubscribed(account: WorkshopSubscriptionAccountIdentity?, item: WorkshopItemSummary): WorkshopSubscriptionEnqueueResult {
        if (account == null) return WorkshopSubscriptionEnqueueResult.Skipped
        return withContext(Dispatchers.Main.immediate) { coordinator.enqueueSubscribed(account, item) }
    }

    private fun readIdentity(): WorkshopSubscriptionAccountIdentity? {
        val epoch = SteamCmAccountEpoch.read(context)
        val material = SteamCloudAuthStore.readAuthMaterial(context) ?: return null
        if (material.refreshToken.isBlank() || material.steamId64.isBlank() || epoch != SteamCmAccountEpoch.read(context)) return null
        return WorkshopSubscriptionAccountIdentity("${material.steamId64}:$epoch", material.credentialRevision)
    }

    private fun isCurrent(account: WorkshopSubscriptionAccountIdentity): Boolean =
        foreground && ready && identity == account && readIdentity() == account

    private fun requireCurrent(account: WorkshopSubscriptionAccountIdentity) {
        if (!isCurrent(account)) throw CancellationException("Subscription account is no longer current")
    }

    companion object {
        private const val APP_ID = 646570u
        private const val TAG = "WorkshopSubscriptionSync"
        private var instance: WorkshopSubscriptionSyncController? = null

        fun get(context: Context): WorkshopSubscriptionSyncController = instance ?: synchronized(this) {
            instance ?: WorkshopSubscriptionSyncController(context.applicationContext).also { instance = it }
        }
    }
}
