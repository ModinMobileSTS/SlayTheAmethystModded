package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/** Android boundary: all UI/service entry points share locks, recovery, snapshots and engine. */
internal object SteamCloudSyncRepository {
    @JvmStatic
    fun recoverOnStartup(host: Context) = SteamCloudLiveSaveLease.runMutation(host) { recoverLocal(host) }

    fun recoverLocal(host: Context) = SteamCloudLocalStateMutex.runExclusive(host) {
        SteamCloudControlStore.locked(host) {
            SteamCloudFileTransaction.recoverAll(File(SteamCloudManifestStore.outputDir(host), "transactions-v2"), RuntimePaths.storageRoot(host))
        }
    }

    fun refreshManifest(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial): SteamCloudManifestSnapshot =
        SteamCloudOperationMutex.runExclusive(host) {
            SteamCloudSyncPolicy.requireSyncEnabled(host)
            requireCurrentAuth(host, authMaterial)
            SteamCloudSteamTransport(host, authMaterial).use { remote ->
                remote.manifest().also {
                    SteamCloudManifestStore.writeSnapshot(host, it)
                    recordStatus { SteamCloudAuthStore.recordManifestSuccess(host, it.fetchedAtMs) }
                }
            }
        }

    fun buildUploadPlan(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        shouldContinue: () -> Boolean = { true }): SteamCloudUploadPlan = SteamCloudOperationMutex.runExclusive(host) {
        withFrozen(host, shouldContinue) { frozen ->
            requireCurrentAuth(host, authMaterial)
            SteamCloudSteamTransport(host, authMaterial).use { transport ->
                engine(host, transport, authMaterial.steamId64, shouldContinue).inspect(frozen, baseline(host, authMaterial)).also {
                    recordStatus { SteamCloudAuthStore.recordManifestSuccess(host, it.remoteManifestFetchedAtMs) }
                }
            }
        }
    }

    fun synchronize(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial?,
        mode: SteamCloudSyncMode = SteamCloudSyncMode.MERGE, expectedPlan: SteamCloudUploadPlan? = null,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true }, saveModeAfterPull: SteamCloudSaveMode? = null,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        expectedCloudAccount: String? = null,
        expectedReturnScope: String? = null,
        trace: SteamCloudSyncTrace = SteamCloudSyncTrace(),
        onPlan: (SteamCloudUploadPlan) -> Unit = {}): SteamCloudSyncEngine.Result = SteamCloudOperationMutex.runExclusive(host) {
        trace.record(SteamCloudManifestStore.outputDir(host), mode, expectedCloudAccount ?: authMaterial?.steamId64) {
            if (expectedCloudAccount != null && LauncherConfig.readSteamCloudSaveMode(host) != SteamCloudSaveMode.STEAM_CLOUD)
                throw CancellationException("Independent saves are not automatically synchronized")
            val auth = authMaterial
                ?: throw SteamCloudCredentialsMissingException(host.getString(io.stamethyst.R.string.settings_steam_cloud_credentials_missing))
            require(sourceRoot.canonicalFile == RuntimePaths.stsRoot(host).canonicalFile || mode == SteamCloudSyncMode.LOCAL_WINS)
            val installSource = sourceRoot.canonicalFile != RuntimePaths.stsRoot(host).canonicalFile
            trace.phase(SteamCloudSyncTrace.Phase.FREEZE)
            withFrozen(host, shouldContinue, sourceRoot, beforeCopy = {
                if (expectedCloudAccount != null) {
                    // Recovery can itself restore the previous mode. Check the recovered slot,
                    // with the same operation mutex used by settings/profile switches still held.
                    val controls = LauncherConfig.readSteamCloudControls(host)
                    if (controls.mode != SteamCloudSaveMode.STEAM_CLOUD.persistedValue || controls.independentSwitchPending ||
                        (expectedReturnScope != null && SteamCloudGameReturn.scope(host) != expectedReturnScope) ||
                        expectedCloudAccount != auth.steamId64 || expectedCloudAccount.isBlank()) {
                        throw CancellationException("Steam Cloud source profile changed before synchronization")
                    }
                    requireCurrentAuth(host, auth)
                }
            }) { frozen ->
                trace.frozen(UUID.fromString(frozen.name))
                requireCurrentAuth(host, auth)
                trace.phase(SteamCloudSyncTrace.Phase.CONNECT)
                SteamCloudSteamTransport(host, auth).use { transport ->
                    synchronizeFrozen(host, transport, frozen, auth.steamId64, mode, expectedPlan,
                        progressCallback, shouldContinue, saveModeAfterPull, installSource, trace, onPlan,
                        verifyAccount = { requireCurrentAuth(host, auth) })
                }.also { result ->
                    recordStatus {
                        requireCurrentAuth(host, auth)
                        recordResult(host, result, mode, trace.operation, frozen.name)
                    }
                }
            }
        }
    }

    private fun recordResult(host: Context, result: SteamCloudSyncEngine.Result, mode: SteamCloudSyncMode,
        operation: UUID, freeze: String) {
        SteamCloudAuthStore.recordManifestSuccess(host, result.plan.remoteManifestFetchedAtMs)
        if (result.plan.conflicts.isNotEmpty()) return
        val completedAt = System.currentTimeMillis()
        val header = "engine=v2\noperationId=$operation\nfreezeId=$freeze\ncompletedAtMs=$completedAt\n"
        if (result.uploaded + result.deleted > 0 || mode == SteamCloudSyncMode.LOCAL_WINS) {
            SteamCloudAuthStore.recordPushSuccess(host, completedAt)
            SteamCloudAtomicFileStore.writeTextWithoutBackup(SteamCloudManifestStore.pushSummaryFile(host),
                header + "uploaded=${result.uploaded}\ndeleted=${result.deleted}\n")
        }
        if (result.plan.remoteOnlyChanges.isNotEmpty() && mode != SteamCloudSyncMode.UPLOAD_ONLY ||
            mode == SteamCloudSyncMode.CLOUD_WINS) {
            SteamCloudAuthStore.recordPullSuccess(host, completedAt)
            SteamCloudAtomicFileStore.writeTextWithoutBackup(SteamCloudManifestStore.pullSummaryFile(host),
                header + "downloaded=${result.downloaded}\nlocalDeletions=${result.plan.remoteOnlyChanges.count { it.currentRemote?.isLive != true }}\n")
        }
    }

    /** Caller holds the operation lock; mode, baseline, blacklist and account slot belong to this pass. */
    internal fun synchronizeFrozen(host: Context, transport: SteamCloudSyncTransport, frozen: File, steamId: String,
        mode: SteamCloudSyncMode, expectedPlan: SteamCloudUploadPlan?, progressCallback: ((SteamCloudSyncProgress) -> Unit)?,
        shouldContinue: () -> Boolean, saveModeAfterPull: SteamCloudSaveMode?, installSource: Boolean,
        trace: SteamCloudSyncTrace, onPlan: (SteamCloudUploadPlan) -> Unit, verifyAccount: () -> Unit): SteamCloudSyncEngine.Result {
        val blacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
        val slot = if (installSource || saveModeAfterPull != null ||
            LauncherConfig.readSteamCloudSaveMode(host) == SteamCloudSaveMode.STEAM_CLOUD)
            SteamCloudSaveProfileManager.cloudSlot(host, steamId) else null
        return engine(host, transport, steamId, shouldContinue, progressCallback, blacklist, trace).run(frozen,
            SteamCloudBaselineStore.readSnapshot(host, steamId), mode, expectedPlan,
            metadata = if (slot == null) null else { work, preparedRoot ->
                buildList {
                    if (installSource) addAll(SteamCloudSaveProfileManager.stageLiveRoot(host, work, preparedRoot))
                    addAll(SteamCloudSaveProfileManager.stageCloudProfile(slot, blacklist, work, preparedRoot))
                    saveModeAfterPull?.let { targetMode ->
                        val stagedControl = File(work, "control.json")
                        SteamCloudControlStore.write(stagedControl, SteamCloudControlStore.read(host).copy(mode = targetMode.persistedValue))
                        add(SteamCloudControlStore.modeReplacement(host, stagedControl))
                    }
                }
            }, metadataRequiresLiveLease = saveModeAfterPull != null, requiresLocalInstall = installSource,
            onPlan = onPlan, shouldDeferLocalChanges = {
                mode == SteamCloudSyncMode.MERGE && SteamCloudLaunchFence.active(host)
            }, profileIsCurrent = { local ->
                (slot == null || SteamCloudSaveProfileManager.cloudSlotMatches(slot, local)).also { verifyAccount() }
            })
    }

    fun pushLocalChanges(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial, plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null, shouldContinue: () -> Boolean = { true }): SteamCloudPushResult {
        val result = synchronize(host, authMaterial, SteamCloudSyncMode.UPLOAD_ONLY, plan, progressCallback, shouldContinue)
        check(result.plan.conflicts.isEmpty()) { "Cloud conflicts require resolution" }
        return pushResult(host, result)
    }

    fun overwriteRemoteWithLocal(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null, shouldContinue: () -> Boolean = { true }): SteamCloudPushResult =
        pushResult(host, synchronize(host, authMaterial, SteamCloudSyncMode.LOCAL_WINS,
            progressCallback = progressCallback, shouldContinue = shouldContinue, sourceRoot = sourceRoot))

    fun pullAll(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null, shouldContinue: () -> Boolean = { true },
        saveModeAfterPull: SteamCloudSaveMode? = null): SteamCloudPullResult {
        val result = synchronize(host, authMaterial, SteamCloudSyncMode.CLOUD_WINS,
            progressCallback = progressCallback, shouldContinue = shouldContinue, saveModeAfterPull = saveModeAfterPull)
        return SteamCloudPullResult(result.downloaded,
            File(SteamCloudManifestStore.outputDir(host), "history-v2").absolutePath.takeIf { result.plan.remoteOnlyChanges.isNotEmpty() }, System.currentTimeMillis(),
            SteamCloudManifestStore.manifestFile(host).absolutePath, result.plan.warnings)
    }

    fun downloadAllToDirectory(host: Context, authMaterial: SteamCloudAuthStore.SavedAuthMaterial, outputRoot: File,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null, shouldContinue: () -> Boolean = { true }): SteamCloudManifestSnapshot =
        SteamCloudOperationMutex.runExclusive(host) {
            SteamCloudSyncPolicy.requireSyncEnabled(host)
            requireCurrentAuth(host, authMaterial)
            SteamCloudSteamTransport(host, authMaterial).use { remote ->
                val snapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(remote.manifest(), LauncherConfig.readSteamCloudSyncBlacklistPaths(host))
                snapshot.entries.forEachIndexed { index, entry ->
                    if (!shouldContinue() || Thread.currentThread().isInterrupted) throw CancellationException()
                    SteamCloudSyncPolicy.requireSyncEnabled(host)
                    val target = File(outputRoot, entry.localRelativePath)
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                    remote.download(entry, target)
                    val downloaded = SteamCloudLocalSnapshotCollector.collectFile(outputRoot, entry.localRelativePath)
                    check(SteamCloudSyncPlanner.matches(downloaded, entry)) { "Cloud backup failed verification" }
                    progressCallback?.invoke(SteamCloudSyncProgress(SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        SteamCloudSyncPhase.DOWNLOADING, index + 1, snapshot.entries.size, entry.localRelativePath))
                }
                SteamCloudSyncPolicy.requireSyncEnabled(host)
                if (SteamCloudManifestIdentity.compute(snapshot) != SteamCloudManifestIdentity.compute(
                        SteamCloudSyncBlacklist.filterManifestSnapshot(remote.manifest(), LauncherConfig.readSteamCloudSyncBlacklistPaths(host)))) {
                    throw SteamCloudStalePlanException("Cloud changed during backup download")
                }
                snapshot
            }
        }

    private fun <T> withFrozen(host: Context, shouldContinue: () -> Boolean,
        sourceRoot: File = RuntimePaths.stsRoot(host), beforeCopy: () -> Unit = {}, block: (File) -> T): T {
        SteamCloudSyncPolicy.requireSyncEnabled(host)
        val frozen = File(SteamCloudManifestStore.outputDir(host), "frozen-v2/${UUID.randomUUID()}")
        check(frozen.mkdirs())
        try {
            SteamCloudLiveSaveLease.runMutation(host) {
                recoverLocal(host)
                beforeCopy()
                SteamCloudRootKind.entries.forEach { root ->
                    if (!shouldContinue()) throw CancellationException()
                    val source = File(sourceRoot, root.directoryName)
                    if (source.exists()) SteamCloudFileTransaction.copyPath(source, File(frozen, root.directoryName))
                }
            }
            return block(frozen)
        } finally { frozen.deleteRecursively() }
    }
    private fun engine(host: Context, transport: SteamCloudSyncTransport, steamId: String, shouldContinue: () -> Boolean,
        progress: ((SteamCloudSyncProgress) -> Unit)? = null,
        blacklist: Set<String> = LauncherConfig.readSteamCloudSyncBlacklistPaths(host),
        trace: SteamCloudSyncTrace? = null) = SteamCloudSyncEngine(
        RuntimePaths.storageRoot(host), RuntimePaths.stsRoot(host), SteamCloudManifestStore.outputDir(host), transport,
        SteamCloudRemoteCommitStore(SteamCloudManifestStore.outputDir(host), steamId), blacklist,
        { shouldContinue() && !LauncherConfig.isSteamCloudSyncDisabled(host) }, { progress?.invoke(it) },
        { apply -> SteamCloudLiveSaveLease.runMutation(host) { apply() } },
        { apply -> SteamCloudLocalStateMutex.runExclusive(host) { SteamCloudControlStore.locked(host) { apply() } } }, trace)
    private fun baseline(host: Context, auth: SteamCloudAuthStore.SavedAuthMaterial) = SteamCloudSyncBlacklist.filterBaseline(
        SteamCloudBaselineStore.readSnapshot(host, auth.steamId64), LauncherConfig.readSteamCloudSyncBlacklistPaths(host))
    private fun requireCurrentAuth(host: Context, expected: SteamCloudAuthStore.SavedAuthMaterial) {
        val actual = SteamCloudAuthStore.readAuthMaterial(host)
        if (actual?.steamId64 != expected.steamId64 || actual.refreshToken != expected.refreshToken || actual.accountName != expected.accountName) {
            throw CancellationException("Steam account changed before synchronization")
        }
    }
    private fun pushResult(host: Context, result: SteamCloudSyncEngine.Result) = SteamCloudPushResult(result.uploaded,
        result.plan.uploadBytes, result.deleted, System.currentTimeMillis(), SteamCloudManifestStore.manifestFile(host).absolutePath, result.plan.warnings)
    private fun recordStatus(block: () -> Unit) {
        // Status/diagnostics are not authoritative recovery data. Their failure cannot undo or
        // misreport an already verified save commit.
        runCatching(block).onFailure { android.util.Log.w("SteamCloudStatus", "Cannot update synchronization status", it) }
    }
}
