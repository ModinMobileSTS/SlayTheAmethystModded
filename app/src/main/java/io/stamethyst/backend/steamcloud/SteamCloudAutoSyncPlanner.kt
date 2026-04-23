package io.stamethyst.backend.steamcloud

internal object SteamCloudAutoSyncPlanner {
    enum class LaunchBlockReason {
        CONFLICTS_PRESENT,
        LOCAL_CHANGES_PRESENT,
    }

    data class PreLaunchPullDecision(
        val shouldPull: Boolean,
        val shouldBlockLaunch: Boolean,
        val blockReason: LaunchBlockReason? = null,
    )

    data class CleanShutdownPushDecision(
        val shouldPush: Boolean,
        val blockedByConflicts: Boolean,
    )

    fun planPreLaunchPull(uploadPlan: SteamCloudUploadPlan): PreLaunchPullDecision {
        if (uploadPlan.conflicts.isNotEmpty()) {
            return PreLaunchPullDecision(
                shouldPull = false,
                shouldBlockLaunch = true,
                blockReason = LaunchBlockReason.CONFLICTS_PRESENT,
            )
        }
        if (uploadPlan.remoteOnlyChanges.isEmpty()) {
            return PreLaunchPullDecision(
                shouldPull = false,
                shouldBlockLaunch = false,
            )
        }
        if (uploadPlan.uploadCandidates.isNotEmpty()) {
            return PreLaunchPullDecision(
                shouldPull = false,
                shouldBlockLaunch = true,
                blockReason = LaunchBlockReason.LOCAL_CHANGES_PRESENT,
            )
        }
        return PreLaunchPullDecision(
            shouldPull = true,
            shouldBlockLaunch = false,
        )
    }

    fun planCleanShutdownPush(uploadPlan: SteamCloudUploadPlan): CleanShutdownPushDecision {
        if (uploadPlan.conflicts.isNotEmpty()) {
            return CleanShutdownPushDecision(
                shouldPush = false,
                blockedByConflicts = true,
            )
        }
        return CleanShutdownPushDecision(
            shouldPush = uploadPlan.uploadCandidates.isNotEmpty(),
            blockedByConflicts = false,
        )
    }
}
