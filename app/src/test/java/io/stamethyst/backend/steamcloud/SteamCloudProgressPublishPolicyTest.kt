package io.stamethyst.backend.steamcloud

import org.junit.Assert.*
import org.junit.Test

class SteamCloudProgressPublishPolicyTest {
    private fun progress(count: Int, total: Int = 100, phase: SteamCloudSyncPhase = SteamCloudSyncPhase.DOWNLOADING) =
        SteamCloudSyncProgress(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL, phase, count, total, "saves/$count")

    @Test fun burstOfFilesIsThrottledButFinalCountIsImmediate() {
        val policy = SteamCloudProgressPublishPolicy()
        assertTrue(policy.shouldPublish(progress(0), 0))
        (1..99).forEach { assertFalse(policy.shouldPublish(progress(it), it.toLong())) }
        assertTrue(policy.shouldPublish(progress(100), 100))
        assertFalse(policy.shouldPublish(progress(100), 500))
    }

    @Test fun regularUpdatesResumeAfterInterval() {
        val policy = SteamCloudProgressPublishPolicy()
        assertTrue(policy.shouldPublish(progress(0), 50))
        assertFalse(policy.shouldPublish(progress(1), 249))
        assertTrue(policy.shouldPublish(progress(2), 250))
        assertFalse(policy.shouldPublish(progress(3), 251))
    }

    @Test fun phaseDirectionAndTotalChangesAreNeverDelayed() {
        val policy = SteamCloudProgressPublishPolicy()
        assertTrue(policy.shouldPublish(progress(1), 1000))
        assertTrue(policy.shouldPublish(progress(0, phase = SteamCloudSyncPhase.UPLOADING), 1001))
        assertTrue(policy.shouldPublish(progress(1, phase = SteamCloudSyncPhase.UPLOADING)
            .copy(direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD), 1002))
        assertTrue(policy.shouldPublish(progress(1, total = 200, phase = SteamCloudSyncPhase.UPLOADING), 1003))
        assertTrue(policy.shouldPublish(progress(0, total = 0, phase = SteamCloudSyncPhase.VERIFYING_REMOTE), 1004))
    }
}
