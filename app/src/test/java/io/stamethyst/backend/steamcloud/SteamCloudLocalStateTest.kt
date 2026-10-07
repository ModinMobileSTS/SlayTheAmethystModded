package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCloudLocalStateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(values: MutableMap<String, Any> = linkedMapOf()): Context {
        val internal = temp.newFolder()
        val external = temp.newFolder()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0])
                "getString", "getStringSet", "getBoolean", "getLong", "getInt", "getFloat" -> values[args!![0]] ?: args[1]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
        return object : ContextWrapper(Application()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = internal
            override fun getNoBackupFilesDir() = File(internal, "no_backup")
            override fun getExternalFilesDir(type: String?) = external
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
    }
    private fun put(root: File, path: String, text: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test fun migratesLegacyControlsOnceAndNeverUsesStalePreferenceValuesAgain() {
        val legacy = linkedMapOf<String, Any>("steam_cloud_sync_disabled" to true,
            "steam_cloud_save_mode" to SteamCloudSaveMode.STEAM_CLOUD.persistedValue,
            "steam_cloud_independent_switch_pending" to true, "steam_cloud_pending_profile_steam_id" to "123",
            "steam_cloud_sync_blacklist_paths" to setOf("preferences/STSPlayer"))
        val host = context(legacy)
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
        assertEquals(SteamCloudSaveMode.STEAM_CLOUD, LauncherConfig.readSteamCloudSaveMode(host))
        assertEquals("123", LauncherConfig.readSteamCloudPendingProfileSteamId(host))
        assertEquals(setOf("preferences/STSPlayer"), LauncherConfig.readSteamCloudSyncBlacklistPaths(host))
        legacy["steam_cloud_sync_disabled"] = false
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
        LauncherConfig.setSteamCloudSyncDisabled(host, false)
        assertFalse(LauncherConfig.isSteamCloudSyncDisabled(host))
        assertTrue(LauncherConfig.isSteamCloudIndependentSwitchPending(host))
    }

    @Test fun controlUpdatesReadFreshDiskStateWithoutACache() {
        val host = context()
        LauncherConfig.setSteamCloudSyncDisabled(host, false)
        SteamCloudControlStore.write(SteamCloudControlStore.file(host), SteamCloudControlStore.State(disabled = true))
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
        LauncherConfig.setSteamCloudBackgroundLaunchRequested(host, true)
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
        assertTrue(LauncherConfig.isSteamCloudBackgroundLaunchRequested(host))
    }

    @Test fun disableDoesNotWaitForNetworkOperationLock() {
        val host = context()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val updated = CountDownLatch(1)
        val network = Thread {
            SteamCloudOperationMutex.runExclusive(host) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        network.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val disable = Thread { LauncherConfig.setSteamCloudSyncDisabled(host, true); updated.countDown() }
            disable.start()
            assertTrue(updated.await(2, TimeUnit.SECONDS)); disable.join(2000)
        } finally { release.countDown(); network.join(2000) }
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
    }

    @Test fun switchingProfilesCommitsModeAndPreservesBlacklistedLivePreferences() {
        val host = context(); val live = RuntimePaths.stsRoot(host)
        put(live, "saves/save", "independent"); put(live, "preferences/STSGameplaySettings", "device")
        SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.INDEPENDENT)
        put(live, "saves/save", "cloud")
        SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.STEAM_CLOUD)
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        SteamCloudSaveProfileManager.switchMode(host, SteamCloudSaveMode.STEAM_CLOUD, SteamCloudSaveMode.INDEPENDENT)
        assertEquals("independent", File(live, "saves/save").readText())
        assertEquals("device", File(live, "preferences/STSGameplaySettings").readText())
        assertEquals(SteamCloudSaveMode.INDEPENDENT, LauncherConfig.readSteamCloudSaveMode(host))
        SteamCloudSaveProfileManager.switchMode(host, SteamCloudSaveMode.INDEPENDENT, SteamCloudSaveMode.STEAM_CLOUD)
        assertEquals("cloud", File(live, "saves/save").readText())
        assertEquals(SteamCloudSaveMode.STEAM_CLOUD, LauncherConfig.readSteamCloudSaveMode(host))
    }

    @Test fun unknownProfileCannotChangeLiveContentsOrMode() {
        val host = context(); val live = RuntimePaths.stsRoot(host)
        put(live, "saves/save", "independent")
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.INDEPENDENT)
        try {
            SteamCloudSaveProfileManager.switchMode(host, SteamCloudSaveMode.INDEPENDENT, SteamCloudSaveMode.STEAM_CLOUD)
            fail()
        } catch (_: java.io.IOException) { }
        assertEquals("independent", File(live, "saves/save").readText())
        assertEquals(SteamCloudSaveMode.INDEPENDENT, LauncherConfig.readSteamCloudSaveMode(host))
    }

    @Test fun staleModeSwitchCannotSaveLiveFilesIntoTheWrongProfile() {
        val host = context(); val live = RuntimePaths.stsRoot(host)
        put(live, "saves/save", "independent")
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.INDEPENDENT)
        try {
            SteamCloudSaveProfileManager.switchMode(host, SteamCloudSaveMode.STEAM_CLOUD, SteamCloudSaveMode.INDEPENDENT)
            fail()
        } catch (_: SteamCloudStalePlanException) { }
        assertEquals("independent", File(live, "saves/save").readText())
        assertFalse(SteamCloudSaveProfileManager.profileIsInitialized(host, SteamCloudSaveMode.STEAM_CLOUD))
    }

    @Test fun startupRecoveryRestoresFilesAndModeFromOneInterruptedTransaction() {
        val host = context(); val live = RuntimePaths.stsRoot(host); val work = temp.newFolder()
        val save = put(live, "saves/save", "old")
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.INDEPENDENT)
        val staged = File(work, "control")
        SteamCloudControlStore.write(staged, SteamCloudControlStore.read(host).copy(mode = SteamCloudSaveMode.STEAM_CLOUD.persistedValue))
        SteamCloudFileTransaction.prepare(File(SteamCloudManifestStore.outputDir(host), "transactions-v2"),
            RuntimePaths.storageRoot(host), listOf(SteamCloudPathReplacement(put(work, "save", "new"), save),
                SteamCloudControlStore.modeReplacement(host, staged))).apply()
        LauncherConfig.setSteamCloudSyncDisabled(host, true)
        SteamCloudSyncRepository.recoverOnStartup(host)
        assertEquals("old", save.readText())
        assertEquals(SteamCloudSaveMode.INDEPENDENT, LauncherConfig.readSteamCloudSaveMode(host))
        assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
    }

    @Test fun gameLeaseBlocksStartupMutationButMetadataLockIsReentrant() {
        val host = context()
        SteamCloudLocalStateMutex.runExclusive(host) {
            SteamCloudLocalStateMutex.runExclusive(host) { SteamCloudSyncRepository.recoverLocal(host) }
        }
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        val game = Thread { SteamCloudLiveSaveLease.acquireForGame(host).use { ready.countDown(); release.await(5, TimeUnit.SECONDS) } }
        game.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            try { SteamCloudSyncRepository.recoverOnStartup(host); fail() } catch (_: SteamCloudLiveSaveInUseException) { }
        } finally { release.countDown(); game.join(2000) }
    }
}
