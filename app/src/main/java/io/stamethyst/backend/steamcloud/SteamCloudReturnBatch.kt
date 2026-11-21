package io.stamethyst.backend.steamcloud

internal data class SteamCloudServicePass(
    val steamId: String,
    val mode: SteamCloudSyncMode = SteamCloudSyncMode.MERGE,
    val expectedPlan: SteamCloudUploadPlan? = null,
    val userInitiated: Boolean = false,
    val allowBackgroundUpload: Boolean = true,
    val returnedLaunch: Long? = null,
    val returnScope: String? = null,
)

/**
 * One logical service operation, possibly spanning two or more physical snapshots. The launch
 * marker identifies the game session, not the Activity observing it. Commands and completions
 * are serialized: either a return joins before finish, or it starts an entirely new operation.
 */
internal class SteamCloudReturnBatch(val steamId: String, initialReturn: Long?, private var returnScope: String? = null) {
    private val seenReturns = mutableSetOf<Long>().apply { initialReturn?.let(::add) }
    private var checking = initialReturn
    private var requested = initialReturn

    fun attach(returnedLaunch: Long?, scope: String? = null) {
        if (returnedLaunch != null && seenReturns.add(returnedLaunch)) {
            requested = returnedLaunch
            returnScope = scope
        }
    }

    /** Never swallow failures/conflicts or replay their operation/override. Only a new return
     * can consume another pass; ordinary attach/query does not change [requested]. */
    fun finish(code: Int, plan: SteamCloudUploadPlan?, cancelled: Boolean,
        mergeAgain: (SteamCloudServicePass) -> Unit, publish: (Int) -> Unit) {
        val canRecheck = when (code) {
            SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED,
            SteamCloudSyncProcessService.RESULT_LOCAL_OVERRIDE_COMPLETED,
            SteamCloudSyncProcessService.RESULT_CLOUD_OVERRIDE_COMPLETED,
            SteamCloudSyncProcessService.RESULT_UP_TO_DATE,
            SteamCloudSyncProcessService.RESULT_DEFERRED -> true
            SteamCloudSyncProcessService.RESULT_PLAN_READY -> plan != null && plan.conflicts.isEmpty()
            else -> false
        }
        if (checking != requested && canRecheck) {
            if (cancelled) publish(SteamCloudSyncProcessService.RESULT_CANCELLED)
            else {
                checking = requested
                mergeAgain(SteamCloudServicePass(steamId, returnedLaunch = checking, returnScope = returnScope))
            }
        } else publish(code)
    }
}
