package io.stamethyst.backend.steamcloud

import androidx.annotation.StringRes
import io.stamethyst.R

@StringRes
internal fun steamCloudPhaseLabel(phase: SteamCloudSyncPhase): Int = when (phase) {
    SteamCloudSyncPhase.DOWNLOADING -> R.string.cloud_card_downloading
    SteamCloudSyncPhase.UPLOADING -> R.string.cloud_card_uploading
    SteamCloudSyncPhase.DELETING_REMOTE -> R.string.cloud_card_deleting
    SteamCloudSyncPhase.VERIFYING_REMOTE -> R.string.cloud_card_verifying
    SteamCloudSyncPhase.BACKING_UP_LOCAL, SteamCloudSyncPhase.APPLYING_TO_LOCAL -> R.string.cloud_card_installing
    SteamCloudSyncPhase.FINALIZING -> R.string.cloud_card_finalizing
    else -> R.string.cloud_card_preparing
}
