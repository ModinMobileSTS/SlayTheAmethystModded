package io.stamethyst.backend.steamcloud

internal data class SteamCloudPullPlan(
    val entries: List<SteamCloudManifestEntry>,
    val replaceRoots: Set<SteamCloudRootKind>,
)

internal object SteamCloudPullPlanner {
    fun buildPlan(snapshot: SteamCloudManifestSnapshot): SteamCloudPullPlan {
        return SteamCloudPullPlan(
            entries = snapshot.entries,
            replaceRoots = linkedSetOf(
                SteamCloudRootKind.PREFERENCES,
                SteamCloudRootKind.SAVES,
            ),
        )
    }
}
