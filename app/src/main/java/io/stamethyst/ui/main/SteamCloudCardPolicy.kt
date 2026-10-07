package io.stamethyst.ui.main

import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorState
import io.stamethyst.ui.main.MainScreenViewModel.SteamCloudIndicatorUi
import io.stamethyst.backend.steamcloud.SteamCloudSyncPhase

internal fun steamCloudFailureState(cancelled: Boolean, requiresResolution: Boolean): SteamCloudIndicatorState = when {
    cancelled -> SteamCloudIndicatorState.CANCELLED
    requiresResolution -> SteamCloudIndicatorState.RECOVERY_REQUIRED
    else -> SteamCloudIndicatorState.CONNECTION_FAILED
}

internal fun steamCloudCardAvailability(current: SteamCloudIndicatorUi, disabled: Boolean,
    cloudMode: Boolean, authenticated: Boolean): SteamCloudIndicatorUi = when {
    disabled -> SteamCloudIndicatorUi(state = SteamCloudIndicatorState.DISABLED, syncDisabled = true)
    !cloudMode -> SteamCloudIndicatorUi(state = SteamCloudIndicatorState.INDEPENDENT)
    !authenticated -> SteamCloudIndicatorUi(state = SteamCloudIndicatorState.SIGNED_OUT)
    current.state in setOf(SteamCloudIndicatorState.DISABLED, SteamCloudIndicatorState.INDEPENDENT,
        SteamCloudIndicatorState.SIGNED_OUT) -> SteamCloudIndicatorUi(visible = true)
    else -> current.copy(visible = true, syncDisabled = false)
}

internal fun canRefreshSteamCloudCard(state: SteamCloudIndicatorUi): Boolean = !state.operationInFlight &&
    state.state !in setOf(SteamCloudIndicatorState.DISABLED, SteamCloudIndicatorState.INDEPENDENT,
        SteamCloudIndicatorState.SIGNED_OUT, SteamCloudIndicatorState.DEFERRED)

internal fun canResolveSteamCloudCard(state: SteamCloudIndicatorUi): Boolean =
    state.state == SteamCloudIndicatorState.RECOVERY_REQUIRED ||
        (state.state == SteamCloudIndicatorState.CONFLICT && state.plan != null)

/** Presentation only: do not erase recovery evidence or change the service's state. */
internal fun steamCloudCardDisplayState(state: SteamCloudIndicatorUi): SteamCloudIndicatorState = when (state.state) {
    SteamCloudIndicatorState.DEFERRED -> SteamCloudIndicatorState.HIDDEN
    SteamCloudIndicatorState.RECOVERY_REQUIRED -> SteamCloudIndicatorState.CONFLICT
    else -> state.state
}

internal fun canLaunchSteamCloudCard(state: SteamCloudIndicatorUi): Boolean = !state.operationInFlight &&
    state.state in setOf(SteamCloudIndicatorState.UP_TO_DATE, SteamCloudIndicatorState.DISABLED,
        SteamCloudIndicatorState.INDEPENDENT, SteamCloudIndicatorState.SIGNED_OUT)

/** Explicit background launch does not wait for the network; live-save writes stay guarded. */
internal fun shouldShowSteamCloudBackgroundSyncAction(state: SteamCloudIndicatorUi): Boolean =
    state.state in setOf(SteamCloudIndicatorState.HIDDEN, SteamCloudIndicatorState.CHECKING,
        SteamCloudIndicatorState.SYNCING, SteamCloudIndicatorState.CANCELLING, SteamCloudIndicatorState.CANCELLED)

internal enum class SteamCloudBackgroundLaunchAction { REJECT, START_SYNC, LAUNCH }

internal fun steamCloudBackgroundLaunchAction(state: SteamCloudIndicatorUi): SteamCloudBackgroundLaunchAction = when {
    !shouldShowSteamCloudBackgroundSyncAction(state) || state.state == SteamCloudIndicatorState.CANCELLING ->
        SteamCloudBackgroundLaunchAction.REJECT
    state.operationInFlight -> SteamCloudBackgroundLaunchAction.LAUNCH
    else -> SteamCloudBackgroundLaunchAction.START_SYNC
}

/** Starting/enqueuing the worker is not waiting for its completion; even rejection cannot gate launch. */
internal fun performSteamCloudBackgroundLaunch(action: SteamCloudBackgroundLaunchAction,
    startSync: () -> Unit, launch: () -> Boolean): Boolean {
    if (action == SteamCloudBackgroundLaunchAction.REJECT) return false
    if (action == SteamCloudBackgroundLaunchAction.START_SYNC) startSync()
    return launch()
}

internal fun shouldHonorSteamCloudLaunchRequest(pending: Boolean, manual: Boolean, autoLaunchEnabled: Boolean): Boolean =
    pending && (manual || autoLaunchEnabled)

/** File counters are explicitly per stage, never an overall synchronization percentage. */
internal fun steamCloudStageFraction(state: SteamCloudIndicatorUi): Float? =
    if (state.state == SteamCloudIndicatorState.SYNCING && state.totalFiles > 0 &&
        state.phase in setOf(SteamCloudSyncPhase.DOWNLOADING, SteamCloudSyncPhase.UPLOADING, SteamCloudSyncPhase.DELETING_REMOTE)) {
        state.completedFiles.coerceIn(0, state.totalFiles).toFloat() / state.totalFiles
    } else null
