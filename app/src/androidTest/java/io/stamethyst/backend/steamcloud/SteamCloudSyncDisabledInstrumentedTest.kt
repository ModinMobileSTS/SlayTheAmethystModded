package io.stamethyst.backend.steamcloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SteamCloudSyncDisabledInstrumentedTest {
    @Test
    fun disabledSync_blocksTransfersWithoutChangingSaveModeOrCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalDisabled = LauncherConfig.isSteamCloudSyncDisabled(context)
        val originalMode = LauncherConfig.readSteamCloudSaveMode(context)
        val originalAuth = SteamCloudAuthStore.readSnapshot(context)
        val auth = SteamCloudAuthStore.SavedAuthMaterial("test", "unused", "", "0", 0L)
        val plan = SteamCloudUploadPlan(
            plannedAtMs = 0L,
            baselineConfigured = false,
            uploadCandidates = emptyList(),
            conflicts = emptyList(),
            remoteOnlyChanges = emptyList(),
            remoteDeleteCandidates = emptyList(),
            warnings = emptyList(),
        )
        val unusedRoot = File(context.cacheDir, "disabled-steam-cloud-${System.nanoTime()}")
        try {
            LauncherConfig.setSteamCloudSyncDisabled(context, true)
            assertTrue(LauncherConfig.isSteamCloudSyncDisabled(context))
            assertCancelled { SteamCloudSyncRepository.pullAll(context, auth) }
            assertCancelled {
                SteamCloudSyncRepository.downloadAllToDirectory(context, auth, unusedRoot)
            }
            assertCancelled { SteamCloudSyncRepository.synchronize(context, auth) }
            assertCancelled { SteamCloudSyncRepository.pushLocalChanges(context, auth, plan) }
            assertCancelled {
                SteamCloudSyncRepository.overwriteRemoteWithLocal(context, auth, unusedRoot)
            }
            SteamCloudClient(context, SteamCloudClient.DownloadLimits.defaults(), false).use { client ->
                assertCancelled { client.downloadFile(STEAM_CLOUD_APP_ID, "preferences/STSPlayer", unusedRoot) }
                assertCancelled { client.beginUploadBatch(STEAM_CLOUD_APP_ID, emptyList()) }
                assertCancelled { client.uploadFile(STEAM_CLOUD_APP_ID, "preferences/STSPlayer", unusedRoot, 1L) }
                assertCancelled { client.deleteFile(STEAM_CLOUD_APP_ID, "preferences/STSPlayer", 1L) }
            }
            assertFalse(unusedRoot.exists())
            assertFalse(SteamCloudSyncProcessService.startCheckAndSync(context, userInitiated = false))
            assertFalse(SteamCloudSyncProcessService.startUseLocal(context))
            assertFalse(SteamCloudSyncProcessService.startUseCloud(context))
            assertEquals(originalMode, LauncherConfig.readSteamCloudSaveMode(context))
            assertEquals(originalAuth, SteamCloudAuthStore.readSnapshot(context))

            LauncherConfig.setSteamCloudSyncDisabled(context, false)
            assertFalse(LauncherConfig.isSteamCloudSyncDisabled(context))
            SteamCloudSyncPolicy.requireSyncEnabled(context)
        } finally {
            LauncherConfig.setSteamCloudSyncDisabled(context, originalDisabled)
        }
    }

    private fun assertCancelled(block: () -> Unit) {
        try {
            block()
            fail("Disabled cloud sync must stop before network or save changes")
        } catch (_: CancellationException) {
            // Expected: the policy rejects the operation before validating a transfer plan.
        }
    }
}
