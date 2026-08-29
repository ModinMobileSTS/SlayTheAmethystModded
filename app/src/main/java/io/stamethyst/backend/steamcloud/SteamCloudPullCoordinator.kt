package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import io.stamethyst.ui.settings.files.SettingsSaveBackupService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class SteamCloudReconciliationException(
    val recoveryRoot: File,
    val recoveryDataPreserved: Boolean,
    cause: Throwable,
) : IOException(
    "Steam Cloud reconciliation failed: " +
        (cause.message?.trim().orEmpty().ifBlank { cause.javaClass.simpleName }) +
        if (recoveryDataPreserved) "; recovery data: ${recoveryRoot.absolutePath}" else "",
    cause,
)

internal data class SteamCloudRollbackResult(
    val failures: List<Throwable>,
)

internal class SteamCloudApplyTransaction(
    private val rollbackAction: () -> List<Throwable>,
) {
    private var rollbackResult: SteamCloudRollbackResult? = null

    @Synchronized
    fun rollback(): SteamCloudRollbackResult {
        rollbackResult?.let { return it }
        return SteamCloudRollbackResult(rollbackAction()).also { rollbackResult = it }
    }
}

internal object SteamCloudPullCoordinator {
    private const val PULL_DOWNLOAD_CONCURRENCY = 4
    private val downloadThreadIds = AtomicInteger(1)

    data class MergeRemoteChangesResult(
        val downloadedFileCount: Int,
        val deletedLocalFileCount: Int,
        val completedAtMs: Long,
    )

    private data class RemoteMergePlanState(
        val filteredSnapshot: SteamCloudManifestSnapshot,
        val plan: SteamCloudUploadPlan,
    )

    @Throws(Exception::class)
    fun refreshManifest(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        allowReconnectRetry: Boolean = true,
    ): SteamCloudManifestSnapshot {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "refresh_manifest",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                client.start()
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                val rawEntries = client.listFiles(STEAM_CLOUD_APP_ID)
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = rawEntries,
                    steamId64 = authMaterial.steamId64,
                )
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "refresh_manifest",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = listOf(
                        "Manifest files: ${snapshot.fileCount}",
                        "preferences/: ${snapshot.preferencesCount}",
                        "saves/: ${snapshot.savesCount}",
                        "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    ),
                )
                return snapshot
            }
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry && isManifestReconnectRetryCandidate(error, failureDiagnostics)) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                return refreshManifest(
                    host = host,
                    authMaterial = authMaterial,
                    allowReconnectRetry = false,
                )
            }
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "refresh_manifest",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = listOf(
                        "Existing guard data provided: ${if (authMaterial.guardData.isBlank()) "no" else "yes"}",
                    ),
                )
            }
            throw error
        }
    }

    @Throws(Exception::class)
    fun downloadAllToDirectory(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        outputRoot: File,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        allowReconnectRetry: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): SteamCloudManifestSnapshot {
        val startedAtMs = System.currentTimeMillis()
        ensureNotCancelled(shouldContinue)
        if (!outputRoot.isDirectory && !outputRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud backup staging directory: ${outputRoot.absolutePath}")
        }
        ensureNotCancelled(shouldContinue)

        val client = SteamCloudClient(host)
        val downloadResults = mutableListOf<SteamCloudClient.DownloadResult>()
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "backup_remote",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.CONNECTING,
                        progressPercent = 5,
                    )
                )
                ensureNotCancelled(shouldContinue)
                client.start()
                ensureNotCancelled(shouldContinue)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                ensureNotCancelled(shouldContinue)
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                ensureNotCancelled(shouldContinue)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                        progressPercent = 20,
                    )
                )
                ensureNotCancelled(shouldContinue)
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                    steamId64 = authMaterial.steamId64,
                )
                ensureNotCancelled(shouldContinue)
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                ensureNotCancelled(shouldContinue)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
                ensureNotCancelled(shouldContinue)
                downloadResults += downloadEntries(
                    client = client,
                    appId = STEAM_CLOUD_APP_ID,
                    entries = SteamCloudPullPlanner.buildPlan(snapshot).entries,
                    stagingRoot = outputRoot,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                )
                ensureNotCancelled(shouldContinue)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "backup_remote",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = listOf(
                        "Manifest files: ${snapshot.fileCount}",
                        "Downloaded files: ${downloadResults.size}",
                        "Downloaded raw bytes: ${downloadResults.sumOf { it.rawBytes }}",
                        "Output root: ${outputRoot.absolutePath}",
                    ) + snapshot.warnings.map { "Warning: $it" },
                )
                return snapshot
            }
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                downloadResults.isEmpty() &&
                shouldContinue() &&
                isManifestReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                return downloadAllToDirectory(
                    host = host,
                    authMaterial = authMaterial,
                    outputRoot = outputRoot,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error), authMaterial)
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "backup_remote",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = listOf(
                        "Downloaded files before failure: ${downloadResults.size}",
                        "Output root: ${outputRoot.absolutePath}",
                    ),
                )
            }
            throw error
        }
    }

    @Throws(Exception::class)
    fun pullAll(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        allowReconnectRetry: Boolean = true,
        shouldContinue: () -> Boolean = { true },
        saveModeAfterPull: SteamCloudSaveMode? = null,
    ): SteamCloudPullResult {
        val startedAtMs = System.currentTimeMillis()
        ensureNotCancelled(shouldContinue)
        val outputDir = SteamCloudManifestStore.outputDir(host)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${outputDir.absolutePath}")
        }
        ensureNotCancelled(shouldContinue)

        val stagingRoot = File(outputDir, "pull-staging-${System.currentTimeMillis()}-${System.nanoTime()}")
        val rollbackRoot = File(outputDir, "pull-rollback-${System.currentTimeMillis()}-${System.nanoTime()}")
        var snapshot: SteamCloudManifestSnapshot? = null
        val downloadResults = mutableListOf<SteamCloudClient.DownloadResult>()
        val client = SteamCloudClient(host)
        val priorBaseline = SteamCloudBaselineStore.readSnapshot(host, authMaterial.steamId64)
        val priorSaveMode = saveModeAfterPull?.let { LauncherConfig.readSteamCloudSaveMode(host) }
        var appliedTransaction: SteamCloudApplyTransaction? = null
        var preserveRecoveryData = false
        var liveSaveLease: SteamCloudLiveSaveLease.Lease? = null
        var saveModeCommitAttempted = false

        try {
            client.use {
                client.beginOperationDiagnostics(
                    "full_pull",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.CONNECTING,
                        progressPercent = 5,
                    )
                )
                ensureNotCancelled(shouldContinue)
                val connectStartedAtNs = System.nanoTime()
                client.start()
                ensureNotCancelled(shouldContinue)
                val connectMs = elapsedMs(connectStartedAtNs)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                ensureNotCancelled(shouldContinue)
                val logOnStartedAtNs = System.nanoTime()
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                ensureNotCancelled(shouldContinue)
                val logOnMs = elapsedMs(logOnStartedAtNs)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                        progressPercent = 20,
                    )
                )
                ensureNotCancelled(shouldContinue)
                val manifestStartedAtNs = System.nanoTime()
                snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                    steamId64 = authMaterial.steamId64,
                )
                ensureNotCancelled(shouldContinue)
                val manifestMs = elapsedMs(manifestStartedAtNs)
                ensureNotCancelled(shouldContinue)
                SteamCloudManifestStore.writeSnapshot(host, requireNotNull(snapshot))
                ensureNotCancelled(shouldContinue)
                SteamCloudAuthStore.recordManifestSuccess(host, requireNotNull(snapshot).fetchedAtMs)

                val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
                val filteredSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                    snapshot = requireNotNull(snapshot),
                    configuredBlacklist = syncBlacklist,
                )
                val plan = SteamCloudPullPlanner.buildPlan(filteredSnapshot)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.DOWNLOADING,
                        completedFiles = 0,
                        totalFiles = plan.entries.size,
                        progressPercent = 28,
                    )
                )
                val downloadStartedAtNs = System.nanoTime()
                ensureNotCancelled(shouldContinue)
                downloadResults += downloadEntries(
                    client = client,
                    appId = STEAM_CLOUD_APP_ID,
                    entries = plan.entries,
                    stagingRoot = stagingRoot,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                )
                ensureNotCancelled(shouldContinue)
                val downloadMs = elapsedMs(downloadStartedAtNs)
                ensureNotCancelled(shouldContinue)
                val downloadDetailsPath = writePullDownloadDetails(host, downloadResults)
                ensureNotCancelled(shouldContinue)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.BACKING_UP_LOCAL,
                        completedFiles = plan.entries.size,
                        totalFiles = plan.entries.size,
                        progressPercent = 82,
                    )
                )
                val backupStartedAtNs = System.nanoTime()
                ensureNotCancelled(shouldContinue)
                liveSaveLease = SteamCloudLiveSaveLease.acquireForMutation(host)
                ensureNotCancelled(shouldContinue)
                val backupLabel = SettingsSaveBackupService.backupExistingSavesToDownloads(
                    host,
                    RuntimePaths.stsRoot(host)
                )
                ensureNotCancelled(shouldContinue)
                val backupMs = elapsedMs(backupStartedAtNs)
                val pullPlan = SteamCloudPullPlanner.buildPlan(filteredSnapshot)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.APPLYING_TO_LOCAL,
                        completedFiles = pullPlan.entries.size,
                        totalFiles = pullPlan.entries.size,
                        progressPercent = 92,
                    )
                )
                val applyStartedAtNs = System.nanoTime()
                ensureNotCancelled(shouldContinue)
                appliedTransaction = applyStaging(
                    stagingRoot = stagingRoot,
                    stsRoot = RuntimePaths.stsRoot(host),
                    replaceRoots = pullPlan.replaceRoots,
                    rollbackRoot = rollbackRoot,
                    preserveLocalRelativePaths = syncBlacklist,
                    shouldContinue = shouldContinue,
                )
                ensureNotCancelled(shouldContinue)
                val applyMs = elapsedMs(applyStartedAtNs)

                val result = SteamCloudPullResult(
                    appliedFileCount = pullPlan.entries.size,
                    backupLabel = backupLabel,
                    completedAtMs = System.currentTimeMillis(),
                    summaryPath = SteamCloudManifestStore.pullSummaryFile(host).absolutePath,
                    warnings = filteredSnapshot.warnings,
                )
                val baselineStartedAtNs = System.nanoTime()
                ensureNotCancelled(shouldContinue)
                SteamCloudBaselineStore.writeSnapshot(
                    host,
                    SteamCloudSyncBaseline(
                        syncedAtMs = result.completedAtMs,
                        localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                            entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                            configuredBlacklist = syncBlacklist,
                        ),
                        remoteEntries = filteredSnapshot.entriesForPlanning,
                        steamId64 = authMaterial.steamId64,
                    )
                )
                ensureNotCancelled(shouldContinue)
                val baselineMs = elapsedMs(baselineStartedAtNs)
                if (saveModeAfterPull == SteamCloudSaveMode.STEAM_CLOUD) {
                    SteamCloudSaveProfileManager.saveActiveProfile(
                        host,
                        SteamCloudSaveMode.STEAM_CLOUD,
                    )
                }
                saveModeAfterPull?.let { targetMode ->
                    saveModeCommitAttempted = true
                    LauncherConfig.saveSteamCloudSaveMode(host, targetMode)
                }
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.FINALIZING,
                        completedFiles = pullPlan.entries.size,
                        totalFiles = pullPlan.entries.size,
                        progressPercent = 98,
                    )
                )
                val telemetry = PullExecutionTelemetry(
                    connectMs = connectMs,
                    logOnMs = logOnMs,
                    manifestMs = manifestMs,
                    downloadMs = downloadMs,
                    backupMs = backupMs,
                    applyMs = applyMs,
                    baselineMs = baselineMs,
                    downloadConcurrency = if (plan.entries.isEmpty()) 0 else minOf(PULL_DOWNLOAD_CONCURRENCY, plan.entries.size),
                    downloadDetailsPath = downloadDetailsPath,
                    downloadResults = downloadResults,
                )
                ensureNotCancelled(shouldContinue)
                writePullSummary(host, requireNotNull(snapshot), result, telemetry)
                ensureNotCancelled(shouldContinue)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "full_pull",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = result.completedAtMs,
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = listOf(
                        "Manifest files: ${requireNotNull(snapshot).fileCount}",
                        "Downloaded files: ${pullPlan.entries.size}",
                        "Downloaded bytes: ${telemetry.totalRawBytes}",
                        "Download details: ${telemetry.downloadDetailsPath}",
                        "Connect ms: ${telemetry.connectMs}",
                        "Logon ms: ${telemetry.logOnMs}",
                        "Manifest ms: ${telemetry.manifestMs}",
                        "Download total ms: ${telemetry.downloadMs}",
                        "Backup ms: ${telemetry.backupMs}",
                        "Apply staging ms: ${telemetry.applyMs}",
                        "Baseline write ms: ${telemetry.baselineMs}",
                        "Download concurrency: ${telemetry.downloadConcurrency}",
                        "Applied files: ${result.appliedFileCount}",
                        "Backup label: ${result.backupLabel ?: "<none>"}",
                        "Pull summary: ${result.summaryPath}",
                    ) + telemetry.slowestDownloads.mapIndexed { index, item ->
                        "Slow download #${index + 1}: ${item.remotePath} totalMs=${item.totalMs} rpcMs=${item.rpcMs} httpMs=${item.httpMs} rawBytes=${item.rawBytes}"
                    } + requireNotNull(snapshot).warnings.map { "Warning: $it" },
                )
                SteamCloudAuthStore.recordPullSuccess(host, result.completedAtMs)
                return result
            }
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            appliedTransaction?.rollback()?.failures?.let(rollbackFailures::addAll)
            if (appliedTransaction != null) {
                runCatching {
                    restoreBaseline(host, priorBaseline)
                }.onFailure(rollbackFailures::add)
                runCatching {
                    val summaryFile = SteamCloudManifestStore.pullSummaryFile(host)
                    if (summaryFile.exists() && !summaryFile.delete()) {
                        throw IOException("Failed to remove incomplete Steam Cloud pull summary: ${summaryFile.absolutePath}")
                    }
                }.onFailure(rollbackFailures::add)
            }
            if (saveModeCommitAttempted && priorSaveMode != null) {
                runCatching {
                    LauncherConfig.saveSteamCloudSaveMode(host, priorSaveMode)
                }.onFailure(rollbackFailures::add)
            }
            val reportedError = if (error is SteamCloudReconciliationException) {
                error
            } else if (appliedTransaction != null) {
                reconciliationFailure(
                    recoveryRoot = rollbackRoot,
                    original = error,
                    rollbackFailures = rollbackFailures,
                )
            } else {
                error
            }
            preserveRecoveryData = when (reportedError) {
                is SteamCloudReconciliationException -> reportedError.recoveryDataPreserved
                else -> false
            }
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                snapshot == null &&
                downloadResults.isEmpty() &&
                shouldContinue() &&
                isManifestReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                return pullAll(
                    host = host,
                    authMaterial = authMaterial,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                    saveModeAfterPull = saveModeAfterPull,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(reportedError), authMaterial)
            runCatching {
                val downloadDetailsPath = if (downloadResults.isNotEmpty()) {
                    writePullDownloadDetails(host, downloadResults)
                } else {
                    ""
                }
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "full_pull",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(reportedError),
                    error = reportedError,
                    extraLines = buildList {
                        add("Existing guard data provided: ${if (authMaterial.guardData.isBlank()) "no" else "yes"}")
                        if (downloadResults.isNotEmpty()) {
                            add("Downloaded files before failure: ${downloadResults.size}")
                            add("Downloaded bytes before failure: ${downloadResults.sumOf { it.rawBytes }}")
                            add("Download details: $downloadDetailsPath")
                        }
                        snapshot?.let {
                            add("Manifest files before failure: ${it.fileCount}")
                            add("Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
                            it.warnings.forEach { warning -> add("Warning: $warning") }
                        }
                        if (preserveRecoveryData) {
                            add("Recovery data preserved: ${rollbackRoot.absolutePath}")
                        }
                    },
                )
            }
            throw reportedError
        } finally {
            stagingRoot.deleteRecursively()
            if (!preserveRecoveryData) {
                rollbackRoot.deleteRecursively()
            }
            liveSaveLease?.close()
        }
    }

    @Throws(Exception::class)
    fun mergeRemoteOnlyChanges(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
    ): MergeRemoteChangesResult {
        require(plan.conflicts.isEmpty()) {
            "Steam Cloud remote merge was requested with unresolved conflicts."
        }
        require(plan.remoteOnlyChanges.isNotEmpty()) {
            "Steam Cloud remote merge was requested with no remote-only changes."
        }

        val startedAtMs = System.currentTimeMillis()
        ensureNotCancelled(shouldContinue)
        val outputDir = SteamCloudManifestStore.outputDir(host)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${outputDir.absolutePath}")
        }
        ensureNotCancelled(shouldContinue)

        val stagingRoot = File(outputDir, "merge-pull-staging-${System.currentTimeMillis()}-${System.nanoTime()}")
        val rollbackRoot = File(outputDir, "merge-pull-rollback-${System.currentTimeMillis()}-${System.nanoTime()}")
        val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
        val priorBaseline = SteamCloudBaselineStore.readSnapshot(host, authMaterial.steamId64)
        val filteredBaseline = SteamCloudSyncBlacklist.filterBaseline(priorBaseline, syncBlacklist)
        var downloads = emptyList<SteamCloudManifestEntry>()
        var deletions = emptyList<SteamCloudRemoteOnlyChange>()
        var appliedPlan = plan
        var appliedSnapshot: SteamCloudManifestSnapshot? = null
        val downloadResults = mutableListOf<SteamCloudClient.DownloadResult>()
        val client = SteamCloudClient(host)
        var appliedTransaction: SteamCloudApplyTransaction? = null
        var preserveRecoveryData = false
        var liveSaveLease: SteamCloudLiveSaveLease.Lease? = null

        try {
            client.use {
                client.beginOperationDiagnostics(
                    "merge_pull",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.CONNECTING,
                        progressPercent = 5,
                    )
                )
                ensureNotCancelled(shouldContinue)
                client.start()
                ensureNotCancelled(shouldContinue)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                ensureNotCancelled(shouldContinue)
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                ensureNotCancelled(shouldContinue)
                val preDownloadState = refreshRemoteMergePlan(
                    host = host,
                    client = client,
                    authMaterial = authMaterial,
                    syncBlacklist = syncBlacklist,
                    baseline = filteredBaseline,
                    shouldContinue = shouldContinue,
                )
                validateRemoteMergePlan(
                    expectedPlan = plan,
                    currentPlan = preDownloadState.plan,
                    currentSnapshot = preDownloadState.filteredSnapshot,
                )
                downloads = remoteMergeDownloads(preDownloadState.plan)
                deletions = remoteMergeDeletions(preDownloadState.plan)
                if (downloads.isNotEmpty()) {
                    reportProgress(
                        progressCallback,
                        SteamCloudSyncProgress(
                            direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                            phase = SteamCloudSyncPhase.DOWNLOADING,
                            completedFiles = 0,
                            totalFiles = downloads.size,
                            progressPercent = 25,
                        )
                    )
                    downloadResults += downloadEntries(
                        client = client,
                        appId = STEAM_CLOUD_APP_ID,
                        entries = downloads,
                        stagingRoot = stagingRoot,
                        progressCallback = progressCallback,
                        shouldContinue = shouldContinue,
                    )
                }
                ensureNotCancelled(shouldContinue)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.APPLYING_TO_LOCAL,
                        completedFiles = downloads.size,
                        totalFiles = downloads.size,
                        progressPercent = 90,
                    )
                )
                liveSaveLease = SteamCloudLiveSaveLease.acquireForMutation(host)
                ensureNotCancelled(shouldContinue)
                val leaseState = refreshRemoteMergePlan(
                    host = host,
                    client = client,
                    authMaterial = authMaterial,
                    syncBlacklist = syncBlacklist,
                    baseline = filteredBaseline,
                    shouldContinue = shouldContinue,
                )
                validateRemoteMergePlan(
                    expectedPlan = preDownloadState.plan,
                    currentPlan = leaseState.plan,
                    currentSnapshot = leaseState.filteredSnapshot,
                )
                downloads = remoteMergeDownloads(leaseState.plan)
                deletions = remoteMergeDeletions(leaseState.plan)
                appliedPlan = leaseState.plan
                appliedSnapshot = leaseState.filteredSnapshot
                appliedTransaction = applyRemoteOnlyChanges(
                    stagingRoot = stagingRoot,
                    stsRoot = RuntimePaths.stsRoot(host),
                    downloadedEntries = downloads,
                    deletedEntries = deletions,
                    rollbackRoot = rollbackRoot,
                    shouldContinue = shouldContinue,
                )
            }

            ensureNotCancelled(shouldContinue)
            val snapshot = appliedSnapshot
                ?: throw IOException("Steam Cloud manifest is missing after remote merge validation.")
            ensureNotCancelled(shouldContinue)
            val completedAtMs = System.currentTimeMillis()
            writeRemoteOnlyBaselineChanges(
                host = host,
                plan = appliedPlan,
                snapshot = snapshot,
                priorBaseline = priorBaseline,
                completedAtMs = completedAtMs,
                shouldContinue = shouldContinue,
            )
            ensureNotCancelled(shouldContinue)
            SteamCloudDiagnosticsStore.writeSummary(
                context = host,
                operation = "merge_pull",
                outcome = "SUCCESS",
                accountName = authMaterial.accountName,
                startedAtMs = startedAtMs,
                completedAtMs = completedAtMs,
                diagnostics = client.snapshotDiagnostics(),
                extraLines = listOf(
                    "Downloaded files: ${downloads.size}",
                    "Deleted local files: ${deletions.size}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                ),
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = downloads.size,
                    totalFiles = downloads.size,
                    progressPercent = 98,
                )
            )
            return MergeRemoteChangesResult(
                downloadedFileCount = downloads.size,
                deletedLocalFileCount = deletions.size,
                completedAtMs = completedAtMs,
            )
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            appliedTransaction?.rollback()?.failures?.let(rollbackFailures::addAll)
            if (appliedTransaction != null) {
                runCatching {
                    restoreBaseline(host, priorBaseline)
                }.onFailure(rollbackFailures::add)
            }
            val reportedError = if (error is SteamCloudReconciliationException) {
                error
            } else if (appliedTransaction != null) {
                reconciliationFailure(
                    recoveryRoot = rollbackRoot,
                    original = error,
                    rollbackFailures = rollbackFailures,
                )
            } else {
                error
            }
            preserveRecoveryData = reportedError is SteamCloudReconciliationException &&
                reportedError.recoveryDataPreserved
            SteamCloudAuthStore.recordFailure(host, summarizeError(reportedError), authMaterial)
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "merge_pull",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = summarizeError(reportedError),
                    error = reportedError,
                    extraLines = buildList {
                        add("Remote-only changes: ${plan.remoteOnlyChanges.size}")
                        add("Downloads planned: ${downloads.size}")
                        add("Deletes planned: ${deletions.size}")
                        if (preserveRecoveryData) {
                            add("Recovery data preserved: ${rollbackRoot.absolutePath}")
                        }
                    },
                )
            }
            throw reportedError
        } finally {
            stagingRoot.deleteRecursively()
            if (!preserveRecoveryData) {
                rollbackRoot.deleteRecursively()
            }
            liveSaveLease?.close()
        }
    }

    private fun refreshRemoteMergePlan(
        host: Context,
        client: SteamCloudClient,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        syncBlacklist: Set<String>,
        baseline: SteamCloudSyncBaseline?,
        shouldContinue: () -> Boolean,
    ): RemoteMergePlanState {
        ensureNotCancelled(shouldContinue)
        val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
            fetchedAtMs = System.currentTimeMillis(),
            remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            steamId64 = authMaterial.steamId64,
        )
        SteamCloudManifestStore.writeSnapshot(host, snapshot)
        SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
        val filteredSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
            snapshot = snapshot,
            configuredBlacklist = syncBlacklist,
        )
        ensureNotCancelled(shouldContinue)
        val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
            entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
            configuredBlacklist = syncBlacklist,
        )
        ensureNotCancelled(shouldContinue)
        return RemoteMergePlanState(
            filteredSnapshot = filteredSnapshot,
            plan = SteamCloudDiffPlanner.buildUploadPlan(
                plannedAtMs = System.currentTimeMillis(),
                currentLocalEntries = localEntries,
                currentRemoteSnapshot = filteredSnapshot,
                baseline = baseline,
            ),
        )
    }

    internal fun validateRemoteMergePlan(
        expectedPlan: SteamCloudUploadPlan,
        currentPlan: SteamCloudUploadPlan,
        currentSnapshot: SteamCloudManifestSnapshot,
    ) {
        val expectedIdentity = expectedPlan.plannedRemoteManifestIdentity.trim()
        if (expectedIdentity.isEmpty() ||
            expectedIdentity != SteamCloudManifestIdentity.compute(currentSnapshot)
        ) {
            throw SteamCloudStalePlanException(
                "Steam Cloud remote merge plan is stale: the remote manifest changed."
            )
        }
        if (currentPlan.conflicts.isNotEmpty()) {
            throw SteamCloudStalePlanException(
                "Steam Cloud remote merge plan is stale: local and remote changes now conflict."
            )
        }
        val expectedChanges = expectedPlan.remoteOnlyChanges.associateBy { it.localRelativePath }
        val currentChanges = currentPlan.remoteOnlyChanges.associateBy { it.localRelativePath }
        if (expectedChanges.size != expectedPlan.remoteOnlyChanges.size ||
            currentChanges.size != currentPlan.remoteOnlyChanges.size ||
            expectedChanges.keys != currentChanges.keys ||
            expectedChanges.any { (path, expected) ->
                currentChanges[path]?.let { current ->
                    remoteOnlyChangesHaveSameIdentity(expected, current)
                } != true
            }
        ) {
            throw SteamCloudStalePlanException(
                "Steam Cloud remote merge plan is stale: the changes to apply are no longer the same."
            )
        }
        currentPlan.remoteOnlyChanges.mapNotNull { it.currentRemote }.forEach { entry ->
            if (!entry.isLive || entry.sha1.isBlank()) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud remote merge requires a live SHA-1 for ${entry.localRelativePath}."
                )
            }
        }
    }

    private fun remoteMergeDownloads(plan: SteamCloudUploadPlan): List<SteamCloudManifestEntry> =
        plan.remoteOnlyChanges.mapNotNull { it.currentRemote }
            .sortedWith(
                compareBy<SteamCloudManifestEntry>(
                    { it.localRelativePath.lowercase(Locale.ROOT) },
                    { it.localRelativePath },
                )
            )

    private fun remoteMergeDeletions(plan: SteamCloudUploadPlan): List<SteamCloudRemoteOnlyChange> =
        plan.remoteOnlyChanges
            .filter { it.kind == SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED }
            .sortedWith(
                compareBy<SteamCloudRemoteOnlyChange>(
                    { it.localRelativePath.lowercase(Locale.ROOT) },
                    { it.localRelativePath },
                )
            )

    private fun remoteOnlyChangesHaveSameIdentity(
        left: SteamCloudRemoteOnlyChange,
        right: SteamCloudRemoteOnlyChange,
    ): Boolean = left.localRelativePath == right.localRelativePath &&
        left.rootKind == right.rootKind &&
        left.kind == right.kind &&
        manifestEntriesHaveSameIdentity(left.currentRemote, right.currentRemote) &&
        manifestEntriesHaveSameIdentity(left.baselineRemote, right.baselineRemote)

    private fun manifestEntriesHaveSameIdentity(
        left: SteamCloudManifestEntry?,
        right: SteamCloudManifestEntry?,
    ): Boolean {
        if (left == null || right == null) {
            return left == right
        }
        return left.remotePath.replace('\\', '/').equals(
            right.remotePath.replace('\\', '/'),
            ignoreCase = true,
        ) && left.localRelativePath == right.localRelativePath &&
            left.rootKind == right.rootKind &&
            left.rawSize == right.rawSize &&
            left.timestamp == right.timestamp &&
            left.machineName.trim().equals(right.machineName.trim(), ignoreCase = true) &&
            steamCloudPersistStatesMatch(left.persistState, right.persistState) &&
            left.sha1.trim().equals(right.sha1.trim(), ignoreCase = true)
    }

    private fun writePullSummary(
        host: Context,
        snapshot: SteamCloudManifestSnapshot,
        result: SteamCloudPullResult,
        telemetry: PullExecutionTelemetry,
    ) {
        val summaryFile = SteamCloudManifestStore.pullSummaryFile(host)
        val parent = summaryFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud pull summary")
            add("")
            add("Completed At: ${formatTimestamp(result.completedAtMs)}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Applied Files: ${result.appliedFileCount}")
            add("Manifest Files: ${snapshot.fileCount}")
            add("preferences/: ${snapshot.preferencesCount}")
            add("saves/: ${snapshot.savesCount}")
            add("Downloaded Bytes: ${telemetry.totalRawBytes}")
            add("Download Workers: ${telemetry.downloadConcurrency}")
            add("Backup: ${result.backupLabel ?: "<none>"}")
            add("Manifest: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
            add("Download Details: ${telemetry.downloadDetailsPath}")
            add("")
            add("Timings:")
            add(" - connect: ${telemetry.connectMs} ms")
            add(" - logon: ${telemetry.logOnMs} ms")
            add(" - manifest: ${telemetry.manifestMs} ms")
            add(" - download: ${telemetry.downloadMs} ms")
            add(" - backup: ${telemetry.backupMs} ms")
            add(" - apply staging: ${telemetry.applyMs} ms")
            add(" - baseline write: ${telemetry.baselineMs} ms")
            if (telemetry.slowestDownloads.isNotEmpty()) {
                add("")
                add("Slowest Downloads:")
                telemetry.slowestDownloads.forEach { item ->
                    add(
                        " - ${item.remotePath} | total=${item.totalMs} ms | rpc=${item.rpcMs} ms | http=${item.httpMs} ms | unzip=${item.unzipMs} ms | write=${item.writeMs} ms | raw=${item.rawBytes}"
                    )
                }
            }
            if (result.warnings.isNotEmpty()) {
                add("")
                add("Warnings:")
                result.warnings.forEach { add(" - $it") }
            }
        }
        summaryFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun writePullDownloadDetails(
        host: Context,
        downloadResults: List<SteamCloudClient.DownloadResult>,
    ): String {
        val file = SteamCloudManifestStore.pullDownloadDetailsFile(host)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }
        val lines = buildList {
            add("index\tremotePath\trawBytes\tcompressedBytes\tdecompressed\trpcMs\thttpMs\tunzipMs\twriteMs\ttotalMs\toutputPath")
            downloadResults.forEachIndexed { index, item ->
                add(
                    listOf(
                        (index + 1).toString(),
                        item.remotePath,
                        item.rawBytes.toString(),
                        item.compressedBytes.toString(),
                        item.decompressed.toString(),
                        item.rpcMs.toString(),
                        item.httpMs.toString(),
                        item.unzipMs.toString(),
                        item.writeMs.toString(),
                        item.totalMs.toString(),
                        item.outputPath,
                    ).joinToString("\t")
                )
            }
        }
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return file.absolutePath
    }

    private fun writeRemoteOnlyBaselineChanges(
        host: Context,
        plan: SteamCloudUploadPlan,
        snapshot: SteamCloudManifestSnapshot,
        priorBaseline: SteamCloudSyncBaseline?,
        completedAtMs: Long,
        shouldContinue: () -> Boolean,
    ) {
        ensureNotCancelled(shouldContinue)
        val changedPaths = plan.remoteOnlyChanges.mapTo(linkedSetOf()) { it.localRelativePath }
        val remoteByPath = snapshot.entriesForPlanning.associateBy { it.localRelativePath }
        val previousLocalByPath = priorBaseline?.localEntries.orEmpty().associateBy { it.localRelativePath }
        val currentLocalByPath = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host))
            .associateBy { it.localRelativePath }
        changedPaths.forEach { path ->
            val currentLocal = currentLocalByPath[path]
            val currentRemote = remoteByPath[path]
            when {
                currentRemote?.isLive == true -> {
                    val localSha1 = currentLocal?.sha1.orEmpty().trim()
                    val remoteSha1 = currentRemote.sha1.trim()
                    if (localSha1.isEmpty() || remoteSha1.isEmpty() ||
                        !localSha1.equals(remoteSha1, ignoreCase = true)
                    ) {
                        throw IOException(
                            "Steam Cloud remote merge verification failed for $path; local and remote SHA-1 differ."
                        )
                    }
                }

                currentLocal != null -> throw IOException(
                    "Steam Cloud remote merge verification failed for $path; a deleted remote file remains local."
                )
            }
        }
        val localByPath = previousLocalByPath.toMutableMap().apply {
            changedPaths.forEach { path ->
                currentLocalByPath[path]?.let { put(path, it) } ?: remove(path)
            }
        }
        val remoteEntries = priorBaseline?.remoteEntries.orEmpty().associateBy { it.localRelativePath }
            .toMutableMap().apply {
                changedPaths.forEach { path ->
                    remoteByPath[path]?.let { put(path, it) } ?: remove(path)
                }
            }
        ensureNotCancelled(shouldContinue)
        SteamCloudBaselineStore.writeSnapshot(
            host,
            SteamCloudSyncBaseline(
                syncedAtMs = completedAtMs,
                localEntries = localByPath.values.sortedWith(localEntryComparator()),
                remoteEntries = remoteEntries.values.sortedWith(manifestEntryComparator()),
                steamId64 = snapshot.steamId64,
            )
        )
    }

    private fun restoreBaseline(host: Context, baseline: SteamCloudSyncBaseline?) {
        if (baseline == null) {
            SteamCloudBaselineStore.clear(host)
        } else {
            SteamCloudBaselineStore.writeSnapshot(host, baseline)
        }
    }

    private fun reconciliationFailure(
        recoveryRoot: File,
        original: Throwable,
        rollbackFailures: List<Throwable>,
    ): SteamCloudReconciliationException {
        val reported = if (rollbackFailures.isEmpty()) {
            original
        } else {
            IOException(
                "Steam Cloud rollback was incomplete: " +
                    rollbackFailures.joinToString("; ", transform = ::summarizeError),
                original,
            ).also { failure ->
                rollbackFailures.forEach(failure::addSuppressed)
            }
        }
        val preserveRecoveryData = runCatching { hasRecoveryData(recoveryRoot) }
            .getOrElse { inspectionError ->
                reported.addSuppressed(inspectionError)
                true
            }
        return SteamCloudReconciliationException(recoveryRoot, preserveRecoveryData, reported)
    }

    internal fun hasRecoveryData(root: File): Boolean {
        if (!root.exists()) {
            return false
        }
        if (root.isFile) {
            return true
        }
        val children = root.listFiles()
            ?: throw IOException("Failed to enumerate recovery directory: ${root.absolutePath}")
        return children.any { child -> hasRecoveryData(child) }
    }

    private fun localEntryComparator(): Comparator<SteamCloudLocalFileSnapshotEntry> =
        compareBy({ it.localRelativePath.lowercase(Locale.ROOT) }, { it.localRelativePath })

    private fun manifestEntryComparator(): Comparator<SteamCloudManifestEntry> =
        compareBy({ it.localRelativePath.lowercase(Locale.ROOT) }, { it.localRelativePath })

    internal fun applyRemoteOnlyChanges(
        stagingRoot: File,
        stsRoot: File,
        downloadedEntries: List<SteamCloudManifestEntry>,
        deletedEntries: List<SteamCloudRemoteOnlyChange>,
        rollbackRoot: File,
        shouldContinue: () -> Boolean = { true },
    ): SteamCloudApplyTransaction {
        if (!rollbackRoot.isDirectory && !rollbackRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud rollback directory: ${rollbackRoot.absolutePath}")
        }

        val appliedOperations = mutableListOf<AppliedRemoteMergeOperation>()
        val transaction = SteamCloudApplyTransaction {
            val failures = mutableListOf<Throwable>()
            appliedOperations.asReversed().forEach { operation ->
                runCatching {
                    rollbackRemoteMergeOperation(operation)
                }.onFailure(failures::add)
            }
            failures
        }
        try {
            downloadedEntries.forEach { entry ->
                ensureNotCancelled(shouldContinue)
                val target = File(stsRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val staged = File(stagingRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val backup = File(rollbackRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val hadOriginal = target.exists()
                val operation = AppliedRemoteMergeOperation.Downloaded(target, backup, hadOriginal)
                appliedOperations += operation
                if (hadOriginal) {
                    movePath(target, backup, shouldContinue) {
                        operation.backupReady = true
                    }
                }
                ensureNotCancelled(shouldContinue)
                movePath(staged, target, shouldContinue)
            }

            deletedEntries.forEach { change ->
                ensureNotCancelled(shouldContinue)
                val target = File(stsRoot, change.localRelativePath.replace('/', File.separatorChar))
                if (!target.exists()) {
                    return@forEach
                }
                val backup = File(rollbackRoot, change.localRelativePath.replace('/', File.separatorChar))
                val operation = AppliedRemoteMergeOperation.Deleted(target, backup)
                appliedOperations += operation
                movePath(target, backup, shouldContinue) {
                    operation.backupReady = true
                }
            }
        } catch (error: Throwable) {
            val rollbackFailures = transaction.rollback().failures
            if (rollbackFailures.isNotEmpty()) {
                throw reconciliationFailure(
                    recoveryRoot = rollbackRoot,
                    original = error,
                    rollbackFailures = rollbackFailures,
                )
            }
            throw error
        }
        return transaction
    }

    internal fun applyStaging(
        stagingRoot: File,
        stsRoot: File,
        replaceRoots: Set<SteamCloudRootKind>,
        rollbackRoot: File,
        preserveLocalRelativePaths: Set<String>,
        shouldContinue: () -> Boolean = { true },
    ): SteamCloudApplyTransaction {
        if (!rollbackRoot.isDirectory && !rollbackRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud rollback directory: ${rollbackRoot.absolutePath}")
        }

        val appliedOperations = mutableListOf<AppliedRootOperation>()
        val transaction = SteamCloudApplyTransaction {
            val failures = mutableListOf<Throwable>()
            appliedOperations.asReversed().forEach { operation ->
                runCatching {
                    rollbackRootOperation(operation)
                }.onFailure(failures::add)
            }
            failures
        }
        try {
            for (rootKind in replaceRoots) {
                ensureNotCancelled(shouldContinue)
                val liveRoot = File(stsRoot, rootKind.directoryName)
                val rollbackTarget = File(rollbackRoot, rootKind.directoryName)
                val operation = AppliedRootOperation(
                    liveRoot = liveRoot,
                    rollbackTarget = rollbackTarget,
                    hadOriginal = liveRoot.exists(),
                )
                appliedOperations += operation
                if (operation.hadOriginal) {
                    movePath(liveRoot, rollbackTarget, shouldContinue) {
                        operation.backupReady = true
                    }
                }

                val stagedRoot = File(stagingRoot, rootKind.directoryName)
                if (stagedRoot.exists()) {
                    ensureNotCancelled(shouldContinue)
                    movePath(stagedRoot, liveRoot, shouldContinue)
                }
                ensureNotCancelled(shouldContinue)
                restorePreservedLocalPaths(
                    rollbackRoot = rollbackTarget,
                    liveRoot = liveRoot,
                    relativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                        rootKind = rootKind,
                        configuredBlacklist = preserveLocalRelativePaths,
                    ),
                    shouldContinue = shouldContinue,
                )
            }
        } catch (error: Throwable) {
            val rollbackFailures = transaction.rollback().failures
            if (rollbackFailures.isNotEmpty()) {
                throw reconciliationFailure(
                    recoveryRoot = rollbackRoot,
                    original = error,
                    rollbackFailures = rollbackFailures,
                )
            }
            throw error
        }
        return transaction
    }

    private fun restorePreservedLocalPaths(
        rollbackRoot: File,
        liveRoot: File,
        relativeSuffixes: Set<String>,
        shouldContinue: () -> Boolean,
    ) {
        if (!rollbackRoot.exists() || relativeSuffixes.isEmpty()) {
            return
        }
        relativeSuffixes.forEach { relativeSuffix ->
            ensureNotCancelled(shouldContinue)
            val source = File(rollbackRoot, relativeSuffix.replace('/', File.separatorChar))
            if (!source.exists()) {
                return@forEach
            }
            val target = File(liveRoot, relativeSuffix.replace('/', File.separatorChar))
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to replace preserved local path: ${target.absolutePath}")
            }
            copyPath(source, target, shouldContinue)
        }
    }

    private fun movePath(
        source: File,
        target: File,
        shouldContinue: (() -> Boolean)? = null,
        onTargetReady: () -> Unit = {},
    ) {
        shouldContinue?.let(::ensureNotCancelled)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        shouldContinue?.let(::ensureNotCancelled)
        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Failed to replace existing path: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            onTargetReady()
            return
        }
        copyPath(source, target, shouldContinue)
        onTargetReady()
        shouldContinue?.let(::ensureNotCancelled)
        if (!source.deleteRecursively()) {
            throw IOException("Failed to delete source path after copy: ${source.absolutePath}")
        }
    }

    private fun copyPath(
        source: File,
        target: File,
        shouldContinue: (() -> Boolean)? = null,
    ) {
        shouldContinue?.let(::ensureNotCancelled)
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            val children = source.listFiles()
                ?: throw IOException("Failed to enumerate directory: ${source.absolutePath}")
            for (child in children) {
                copyPath(child, File(target, child.name), shouldContinue)
            }
            return
        }

        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    shouldContinue?.let(::ensureNotCancelled)
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (source.lastModified() > 0L) {
            target.setLastModified(source.lastModified())
        }
    }

    private fun rollbackRemoteMergeOperation(operation: AppliedRemoteMergeOperation) {
        when (operation) {
            is AppliedRemoteMergeOperation.Downloaded -> {
                if (!operation.hadOriginal) {
                    if (operation.target.exists() && !operation.target.deleteRecursively()) {
                        throw IOException("Failed to remove applied Steam Cloud path: ${operation.target.absolutePath}")
                    }
                    return
                }
                if (operation.backupReady) {
                    if (operation.target.exists() && !operation.target.deleteRecursively()) {
                        throw IOException("Failed to remove applied Steam Cloud path: ${operation.target.absolutePath}")
                    }
                    if (!operation.backup.exists()) {
                        throw IOException("Steam Cloud rollback backup is missing: ${operation.backup.absolutePath}")
                    }
                    movePath(operation.backup, operation.target)
                } else {
                    if (operation.backup.exists() && !operation.backup.deleteRecursively()) {
                        throw IOException("Failed to remove partial Steam Cloud rollback backup: ${operation.backup.absolutePath}")
                    }
                    if (!operation.target.exists()) {
                        throw IOException("Original Steam Cloud path and complete rollback backup are both missing: ${operation.target.absolutePath}")
                    }
                }
            }

            is AppliedRemoteMergeOperation.Deleted -> {
                if (operation.backupReady) {
                    if (!operation.backup.exists()) {
                        throw IOException("Steam Cloud rollback backup is missing: ${operation.backup.absolutePath}")
                    }
                    if (operation.target.exists() && !operation.target.deleteRecursively()) {
                        throw IOException("Failed to restore Steam Cloud path: ${operation.target.absolutePath}")
                    }
                    movePath(operation.backup, operation.target)
                } else {
                    if (operation.backup.exists() && !operation.backup.deleteRecursively()) {
                        throw IOException("Failed to remove partial Steam Cloud rollback backup: ${operation.backup.absolutePath}")
                    }
                    if (!operation.target.exists()) {
                        throw IOException("Original Steam Cloud path and complete rollback backup are both missing: ${operation.target.absolutePath}")
                    }
                }
            }
        }
    }

    private fun rollbackRootOperation(operation: AppliedRootOperation) {
        if (operation.hadOriginal) {
            if (!operation.backupReady) {
                if (operation.rollbackTarget.exists() && !operation.rollbackTarget.deleteRecursively()) {
                    throw IOException("Failed to remove partial Steam Cloud rollback root: ${operation.rollbackTarget.absolutePath}")
                }
                if (!operation.liveRoot.exists()) {
                    throw IOException("Original Steam Cloud root and complete rollback backup are both missing: ${operation.liveRoot.absolutePath}")
                }
                return
            }
            if (!operation.rollbackTarget.exists()) {
                throw IOException("Steam Cloud rollback root is missing: ${operation.rollbackTarget.absolutePath}")
            }
            if (operation.liveRoot.exists() && !operation.liveRoot.deleteRecursively()) {
                throw IOException("Failed to remove applied Steam Cloud root: ${operation.liveRoot.absolutePath}")
            }
            movePath(operation.rollbackTarget, operation.liveRoot)
            return
        }

        if (operation.liveRoot.exists() && !operation.liveRoot.deleteRecursively()) {
            throw IOException("Failed to remove newly created Steam Cloud root: ${operation.liveRoot.absolutePath}")
        }
    }

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
    }

    private fun isManifestReconnectRetryCandidate(
        error: Throwable,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
    ): Boolean {
        var sawManifestStage = diagnostics?.currentStage
            .orEmpty()
            .lowercase(Locale.US)
            .contains("getappfilechangelist")
        var sawReconnectFailure = diagnostics?.disconnectedDescription
            .orEmpty()
            .lowercase(Locale.US)
            .contains("unexpected")
        var current: Throwable? = error
        while (current != null) {
            val normalized = current.message.orEmpty().lowercase(Locale.US)
            if (normalized.contains("getappfilechangelist")) {
                sawManifestStage = true
            }
            if ((normalized.contains("steam disconnected") && normalized.contains("unexpected")) ||
                normalized.contains("client or session is no longer active")
            ) {
                sawReconnectFailure = true
            }
            current = current.cause
        }
        return sawManifestStage && sawReconnectFailure
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs)
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    private data class PullExecutionTelemetry(
        val connectMs: Long,
        val logOnMs: Long,
        val manifestMs: Long,
        val downloadMs: Long,
        val backupMs: Long,
        val applyMs: Long,
        val baselineMs: Long,
        val downloadConcurrency: Int,
        val downloadDetailsPath: String,
        val downloadResults: List<SteamCloudClient.DownloadResult>,
    ) {
        val totalRawBytes: Long
            get() = downloadResults.sumOf { it.rawBytes }

        val slowestDownloads: List<SteamCloudClient.DownloadResult>
            get() = downloadResults.sortedByDescending { it.totalMs }.take(8)
    }

    private fun downloadEntries(
        client: SteamCloudClient,
        appId: Int,
        entries: List<SteamCloudManifestEntry>,
        stagingRoot: File,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)?,
        shouldContinue: () -> Boolean,
    ): List<SteamCloudClient.DownloadResult> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val parallelism = minOf(PULL_DOWNLOAD_CONCURRENCY, entries.size)
        if (parallelism <= 1) {
            val results = ArrayList<SteamCloudClient.DownloadResult>(entries.size)
            entries.forEachIndexed { index, entry ->
                ensureNotCancelled(shouldContinue)
                val outputFile = File(stagingRoot, entry.localRelativePath)
                val downloadResult = client.downloadFile(
                    appId,
                    entry.remotePath,
                    outputFile,
                    entry.rawSize,
                    entry.sha1,
                )
                ensureNotCancelled(shouldContinue)
                results += downloadResult
                if (entry.timestamp > 0L) {
                    outputFile.setLastModified(entry.timestamp)
                }
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.DOWNLOADING,
                        completedFiles = index + 1,
                        totalFiles = entries.size,
                        currentPath = entry.localRelativePath,
                        progressPercent = 28 + (((index + 1) * 52) / entries.size),
                    )
                )
            }
            ensureNotCancelled(shouldContinue)
            return results
        }

        val executor = Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "steam-cloud-pull-download-${downloadThreadIds.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
        val completionService: CompletionService<IndexedDownloadResult> =
            java.util.concurrent.ExecutorCompletionService<IndexedDownloadResult>(executor)
        val futures = ArrayList<Future<IndexedDownloadResult>>(entries.size)
        val completedCount = AtomicInteger(0)

        try {
            entries.forEachIndexed { index, entry ->
                ensureNotCancelled(shouldContinue)
                futures += completionService.submit(Callable {
                    ensureNotCancelled(shouldContinue)
                    val outputFile = File(stagingRoot, entry.localRelativePath)
                    val downloadResult = client.downloadFile(
                        appId,
                        entry.remotePath,
                        outputFile,
                        entry.rawSize,
                        entry.sha1,
                    )
                    ensureNotCancelled(shouldContinue)
                    if (entry.timestamp > 0L) {
                        outputFile.setLastModified(entry.timestamp)
                    }
                    IndexedDownloadResult(index, downloadResult)
                })
            }

            val completedResults = ArrayList<IndexedDownloadResult>(entries.size)
            while (completedResults.size < entries.size) {
                ensureNotCancelled(shouldContinue)
                val future = completionService.poll(250L, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val result = future.get()
                    completedResults += result
                    val completed = completedCount.incrementAndGet()
                    reportProgress(
                        progressCallback,
                        SteamCloudSyncProgress(
                            direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                            phase = SteamCloudSyncPhase.DOWNLOADING,
                            completedFiles = completed,
                            totalFiles = entries.size,
                            currentPath = entries[result.index].localRelativePath,
                            progressPercent = 28 + ((completed * 52) / entries.size),
                        )
                    )
                } catch (error: ExecutionException) {
                    cancelOutstandingDownloads(futures)
                    throw unwrapExecutionCause(error)
                }
            }

            ensureNotCancelled(shouldContinue)
            completedResults.sortBy { it.index }
            return completedResults.map { it.downloadResult }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelOutstandingDownloads(futures)
            throw error
        } catch (error: Throwable) {
            cancelOutstandingDownloads(futures)
            throw error
        } finally {
            executor.shutdownNow()
            try {
                executor.awaitTermination(5L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun cancelOutstandingDownloads(futures: List<Future<IndexedDownloadResult>>) {
        futures.forEach { it.cancel(true) }
    }

    private fun unwrapExecutionCause(error: ExecutionException): Throwable {
        var current: Throwable = error
        while (current is ExecutionException && current.cause != null && current.cause !== current) {
            current = current.cause ?: break
        }
        return current
    }

    private fun reportProgress(
        progressCallback: ((SteamCloudSyncProgress) -> Unit)?,
        progress: SteamCloudSyncProgress,
    ) {
        progressCallback?.invoke(progress)
    }

    private fun ensureNotCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Steam Cloud sync cancelled by user.")
        }
    }

    private data class IndexedDownloadResult(
        val index: Int,
        val downloadResult: SteamCloudClient.DownloadResult,
    )

    private sealed interface AppliedRemoteMergeOperation {
        data class Downloaded(
            val target: File,
            val backup: File,
            val hadOriginal: Boolean,
        ) : AppliedRemoteMergeOperation {
            var backupReady: Boolean = false
        }

        data class Deleted(
            val target: File,
            val backup: File,
        ) : AppliedRemoteMergeOperation {
            var backupReady: Boolean = false
        }
    }

    private class AppliedRootOperation(
        val liveRoot: File,
        val rollbackTarget: File,
        val hadOriginal: Boolean,
    ) {
        var backupReady: Boolean = false
    }
}
