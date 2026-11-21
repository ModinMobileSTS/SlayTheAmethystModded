package io.stamethyst.backend.steamcloud

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.backend.launch.GameLaunchReturnTracker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

    @Test fun oldReturnMergeCannotClearSecondImmediateLaunchFenceBeforeGameLease() {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        val first = SteamCloudLaunchFence.token()
        SteamCloudLaunchFence.acquire(host, first)
        GameLaunchReturnTracker.markGameLaunchStarted(host, 1, first)
        val batch = SteamCloudReturnBatch("1", null)
        batch.attach(GameLaunchReturnTracker.cleanReturn(host, 1)!!.launch)
        val second = SteamCloudLaunchFence.token()
        SteamCloudLaunchFence.acquire(host, second)
        GameLaunchReturnTracker.markGameLaunchStarted(host, 2, second)
        // Late cleanup of launch one (UI/service failure, destruction or return analysis).
        SteamCloudLaunchFence.release(host, first)
        GameLaunchReturnTracker.clearPendingGameLaunch(host, 1)
        assertEquals(2L, GameLaunchReturnTracker.readPendingGameLaunchStartedAt(host))
        val frozen = temp.newFolder()
        val live = RuntimePaths.stsRoot(host)
        val remote = SteamCloudSyncEngineTest.FakeRemote().apply { files["saves/new"] = "from cloud" }
        put(live, "saves/save", "new game")
        SteamCloudFileTransaction.copyPath(live, frozen)
        batch.finish(SteamCloudSyncProcessService.RESULT_AUTO_SYNC_COMPLETED, null, false, mergeAgain = { pass ->
            try {
                // The new JVM does NOT hold a lease. Only its launch fence can stop this merge.
                SteamCloudOperationMutex.runExclusive(host) {
                    SteamCloudSyncRepository.synchronizeFrozen(host, remote, frozen, "1", pass.mode, null, null,
                        { true }, null, false, SteamCloudSyncTrace(), {}, {})
                }
                fail("installed cloud data before second game acquired its lease")
            } catch (_: SteamCloudLiveSaveInUseException) { }
        }, publish = { fail("published stale success") })
        assertEquals(0, remote.downloadCount)
        assertEquals(0, remote.beginCount)
        assertFalse(File(live, "saves/new").exists())
        assertEquals("new game", File(live, "saves/save").readText())
        GameLaunchReturnTracker.clearPendingGameLaunch(host, 2)
        assertFalse(SteamCloudLaunchFence.active(host))
    }

    @Test fun unreadableAccountKeepsCleanReturnUntilSameAccountServiceReceiptAcrossRecreation() = runBlocking {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        GameLaunchReturnTracker.markGameLaunchStarted(host, 100)
        val pending = GameLaunchReturnTracker.cleanReturn(host, 100)!!
        GameLaunchReturnTracker.clearPendingGameLaunch(host, 100)
        val unavailable = SteamCloudAuthStore.AuthMaterialRead(null, true, "")
        val valid = SteamCloudAuthStore.AuthMaterialRead(SteamCloudAuthStore.SavedAuthMaterial("A", "", "", "1", 0), false, "")
        var attempts = 0
        assertFalse(SteamCloudGameReturn.dispatch(host, pending, { ++attempts; unavailable }, {}) { fail("unverified dispatch") })
        assertEquals(4, attempts)
        val recreated = SteamCloudGameReturn.pending(host)!!
        val sent = mutableListOf<String>()
        assertTrue(SteamCloudGameReturn.dispatch(host, recreated, { valid }, {}, sent::add))
        assertEquals(listOf("1"), sent)
        assertEquals(recreated, SteamCloudGameReturn.pending(host)) // startService isn't receipt
        SteamCloudGameReturn.consume(host, 100)
        assertNull(SteamCloudGameReturn.pending(host))
    }

    @Test fun loginChangeWhileReturnWaitsForKeyStoreCannotRetargetTheNewAccount() = runBlocking {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        GameLaunchReturnTracker.markGameLaunchStarted(host, 1)
        val pending = GameLaunchReturnTracker.cleanReturn(host, 1)!!
        var attempts = 0
        assertFalse(SteamCloudGameReturn.dispatch(host, pending, {
            ++attempts
            SteamCloudAuthStore.AuthMaterialRead(null, true, "")
        }, { SteamCloudGameReturn.newLogin(host) }) { fail("retargeted after login") })
        assertEquals(1, attempts)
        assertNull(SteamCloudGameReturn.pending(host))
    }

    @Test fun conclusiveSignOutDiscardsReturnWithoutWaitingOrCallingService() = runBlocking {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        GameLaunchReturnTracker.markGameLaunchStarted(host, 123)
        val pending = GameLaunchReturnTracker.cleanReturn(host, 123)!!
        assertFalse(SteamCloudGameReturn.dispatch(host, pending,
            { SteamCloudAuthStore.AuthMaterialRead(null, false, "") }, { fail("retried a signed-out account") },
            { fail("dispatched for a signed-out account") }))
        assertNull(SteamCloudGameReturn.pending(host))
    }

    @Test fun frozenAdapterValidatesTheRealAccountSlotAndRepairsOnlyViaCheckedCommit() {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        val path = "saves/char"
        val excluded = SteamCloudSyncBlacklist.DEFAULT_LOCAL_RELATIVE_PATH
        val frozen = temp.newFolder()
        put(frozen, path, "good"); put(frozen, excluded, "device-only")
        val remote = SteamCloudSyncEngineTest.FakeRemote().apply {
            files[path] = "good"; files[excluded] = "pc-only"
        }
        val state = SteamCloudManifestStore.outputDir(host)
        val slot = SteamCloudSaveProfileManager.cloudSlot(host, "1")
        val otherSlot = SteamCloudSaveProfileManager.cloudSlot(host, "2")
        put(otherSlot, path, "different"); put(slot, "runs/history", "keep")
        fun pass(expectedReads: Int, installing: SteamCloudSaveMode? = null) {
            val reads = remote.manifestCount
            val trace = SteamCloudSyncTrace()
            SteamCloudOperationMutex.runExclusive(host) {
                trace.record(state, SteamCloudSyncMode.MERGE, "1") {
                    SteamCloudSyncRepository.synchronizeFrozen(host, remote, frozen, "1", SteamCloudSyncMode.MERGE,
                        null, null, { true }, installing, false, trace, {}, {})
                }
            }
            assertEquals(expectedReads, remote.manifestCount - reads)
            assertEquals(0, remote.beginCount); assertEquals(0, remote.downloadCount)
            assertEquals("good", File(slot, path).readText())
            assertEquals("keep", File(slot, "runs/history").readText())
            assertEquals("different", File(otherSlot, path).readText())
            assertFalse(File(slot, excluded).exists()); assertEquals("pc-only", remote.files[excluded])
            val text = File(state, SteamCloudSyncTrace.FILE_NAME).readText()
            for (line in listOf("phaseMs.SNAPSHOT=", "phaseMs.LIST=", "phaseMs.PLAN=", "enteredWrite=false",
                    "pendingNow=false", "outcome=COMPLETED")) assertTrue(line, text.contains(line))
        }
        pass(3)
        // Remove empty transaction/work parents: the ordinary pass must not even recreate them.
        val work = File(state, "work-v2"); val tx = File(state, "transactions-v2")
        assertTrue(work.delete()); assertTrue(tx.delete())
        val baseline = SteamCloudBaselineStore.baselineFile(host)
        val saved = baseline.readText()
        pass(1)
        assertFalse(work.exists()); assertFalse(tx.exists()); assertEquals(saved, baseline.readText())
        for (damage in listOf<() -> Unit>(
                { File(slot, path).delete() },
                { put(slot, path, "evil").setLastModified(File(frozen, path).lastModified()) }, // same length + mtime
                { put(slot, excluded, "leaked") },
                { File(slot, excluded).mkdirs() },
                { File(slot, ".initialized").delete() },
                { File(slot, ".initialized").writeText("bad") },
                { baseline.writeText("{}") },
                { baseline.delete() },
                { SteamCloudManifestStore.manifestFile(host).writeText("{broken") },
                { SteamCloudManifestStore.manifestFile(host).delete() },
            )) {
            damage(); pass(3); pass(1)
        }
        // Protect a newly excluded path even if someone already removed it from the slot:
        // filtered planner inputs alone conceal stale persisted baseline/cache entries.
        val added = "preferences/mod"
        put(frozen, added, "both"); remote.files[added] = "both"
        pass(3); assertTrue(File(slot, added).delete())
        LauncherConfig.saveSteamCloudSyncBlacklistPaths(host, setOf(added, excluded))
        pass(3); assertFalse(baseline.readText().contains(added)); pass(1)
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.INDEPENDENT)
        pass(3, SteamCloudSaveMode.STEAM_CLOUD)
        assertEquals(SteamCloudSaveMode.STEAM_CLOUD, LauncherConfig.readSteamCloudSaveMode(host))
    }
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
        assertFalse(SteamCloudControlStore.file(host).exists())
        assertTrue(SteamCloudControlStore.read(host).disabled)
        // Migration is persisted by the first mutation, never by a main-thread read.
        LauncherConfig.setSteamCloudBackgroundLaunchRequested(host, true)
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

    @Test fun launcherStatusReadDoesNotWaitForControlTransactionLock() {
        val host = context()
        LauncherConfig.setSteamCloudSyncDisabled(host, true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val transaction = pool.submit {
                SteamCloudControlStore.locked(host) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(pool.submit<Boolean> { LauncherConfig.isSteamCloudSyncDisabled(host) }.get(1, TimeUnit.SECONDS))
            release.countDown()
            transaction.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun controlWriteStillSerializesWithTransactionAndPreservesOtherFields() {
        val host = context()
        LauncherConfig.setSteamCloudSyncDisabled(host, true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val writeStarted = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val transaction = pool.submit {
                SteamCloudControlStore.locked(host) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val launchRequest = pool.submit {
                writeStarted.countDown()
                LauncherConfig.setSteamCloudBackgroundLaunchRequested(host, true)
            }
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(launchRequest.isDone)
            release.countDown()
            transaction.get(2, TimeUnit.SECONDS)
            launchRequest.get(2, TimeUnit.SECONDS)
            assertEquals(SteamCloudSaveMode.STEAM_CLOUD, LauncherConfig.readSteamCloudSaveMode(host))
            assertTrue(LauncherConfig.isSteamCloudSyncDisabled(host))
            assertTrue(LauncherConfig.isSteamCloudBackgroundLaunchRequested(host))
        } finally { release.countDown(); pool.shutdownNow() }
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

    @Test fun queuedServiceRequestsCannotFreezeTheIndependentSlotAfterAProfileSwitch() {
        val host = context()
        val live = RuntimePaths.stsRoot(host)
        put(live, "saves/save", "independent")
        SteamCloudSaveProfileManager.saveActiveProfile(host, SteamCloudSaveMode.INDEPENDENT)
        put(live, "saves/save", "cloud")
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        val modes = listOf(SteamCloudSyncMode.MERGE, SteamCloudSyncMode.LOCAL_WINS, SteamCloudSyncMode.CLOUD_WINS)
        val readCloud = CountDownLatch(modes.size)
        val pool = Executors.newFixedThreadPool(modes.size)
        val workers = mutableListOf<Future<*>>()
        val auth = SteamCloudAuthStore.SavedAuthMaterial("test", "unused", "", "1", 0L)
        try {
            SteamCloudOperationMutex.runExclusive(host) {
                for (mode in modes) workers += pool.submit {
                    assertEquals(SteamCloudSaveMode.STEAM_CLOUD, LauncherConfig.readSteamCloudSaveMode(host))
                    readCloud.countDown()
                    try {
                        SteamCloudSyncRepository.synchronize(host, auth, mode, expectedCloudAccount = auth.steamId64,
                            shouldContinue = { throw AssertionError("$mode started freezing the switched slot") })
                        fail("Expected source rejection for $mode")
                    } catch (_: CancellationException) { }
                }
                assertTrue(readCloud.await(5, TimeUnit.SECONDS))
                SteamCloudSaveProfileManager.switchMode(host, SteamCloudSaveMode.STEAM_CLOUD, SteamCloudSaveMode.INDEPENDENT)
            }
            workers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals("independent", File(live, "saves/save").readText())
            assertFalse(SteamCloudManifestStore.manifestFile(host).exists())
            assertFalse(SteamCloudRemoteCommitStore(SteamCloudManifestStore.outputDir(host), "1").hasPending())

            // Settings deliberately pulls INTO an independent slot; it passes through to freeze.
            // Cancel there so this test can never construct a real Steam client.
            var reachedFreeze = false
            try {
                SteamCloudSyncRepository.pullAll(host, auth, saveModeAfterPull = SteamCloudSaveMode.STEAM_CLOUD,
                    shouldContinue = { reachedFreeze = true; false })
                fail("Expected cancellation at freeze")
            } catch (_: CancellationException) { }
            assertTrue(reachedFreeze)
        } finally { pool.shutdownNow() }
    }

    @Test fun revokedOrDifferentServiceAccountIsRejectedBeforeCopyEvenInCloudMode() {
        val host = context()
        LauncherConfig.saveSteamCloudSaveMode(host, SteamCloudSaveMode.STEAM_CLOUD)
        val stale = SteamCloudAuthStore.SavedAuthMaterial("old", "unused", "", "1", 0L)
        for (account in listOf("1", "2")) {
            try {
                SteamCloudSyncRepository.synchronize(host, stale, SteamCloudSyncMode.LOCAL_WINS, expectedCloudAccount = account,
                    shouldContinue = { throw AssertionError("started freezing without current account $account") })
                fail("Expected account rejection")
            } catch (_: CancellationException) { }
        }
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
