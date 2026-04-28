package io.stamethyst.backend.steamcloud

import android.app.Activity
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.settings.SettingsSaveBackupService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object SteamCloudPullCoordinator {
    private const val PULL_DOWNLOAD_CONCURRENCY = 4
    private val downloadThreadIds = AtomicInteger(1)

    data class MergeRemoteChangesResult(
        val downloadedFileCount: Int,
        val deletedLocalFileCount: Int,
        val completedAtMs: Long,
    )

    @Throws(Exception::class)
    fun refreshManifest(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
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
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                val rawEntries = client.listFiles(STEAM_CLOUD_APP_ID)
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = rawEntries,
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
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "refresh_manifest",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
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
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        outputRoot: File,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): SteamCloudManifestSnapshot {
        val startedAtMs = System.currentTimeMillis()
        if (!outputRoot.isDirectory && !outputRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud backup staging directory: ${outputRoot.absolutePath}")
        }

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
                client.start()
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                        progressPercent = 20,
                    )
                )
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                )
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
                downloadResults += downloadEntries(
                    client = client,
                    appId = STEAM_CLOUD_APP_ID,
                    entries = SteamCloudPullPlanner.buildPlan(snapshot).entries,
                    stagingRoot = outputRoot,
                    progressCallback = progressCallback,
                )
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
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "backup_remote",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
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
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): SteamCloudPullResult {
        val startedAtMs = System.currentTimeMillis()
        val outputDir = SteamCloudManifestStore.outputDir(host)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${outputDir.absolutePath}")
        }

        val stagingRoot = File(outputDir, "pull-staging-${System.currentTimeMillis()}-${System.nanoTime()}")
        val rollbackRoot = File(outputDir, "pull-rollback-${System.currentTimeMillis()}-${System.nanoTime()}")
        var snapshot: SteamCloudManifestSnapshot? = null
        val downloadResults = mutableListOf<SteamCloudClient.DownloadResult>()
        val client = SteamCloudClient(host)

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
                val connectStartedAtNs = System.nanoTime()
                client.start()
                val connectMs = elapsedMs(connectStartedAtNs)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                val logOnStartedAtNs = System.nanoTime()
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                val logOnMs = elapsedMs(logOnStartedAtNs)
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                        progressPercent = 20,
                    )
                )
                val manifestStartedAtNs = System.nanoTime()
                snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                )
                val manifestMs = elapsedMs(manifestStartedAtNs)
                SteamCloudManifestStore.writeSnapshot(host, requireNotNull(snapshot))
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
                downloadResults += downloadEntries(
                    client = client,
                    appId = STEAM_CLOUD_APP_ID,
                    entries = plan.entries,
                    stagingRoot = stagingRoot,
                    progressCallback = progressCallback,
                )
                val downloadMs = elapsedMs(downloadStartedAtNs)
                val downloadDetailsPath = writePullDownloadDetails(host, downloadResults)
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
                val backupLabel = SettingsSaveBackupService.backupExistingSavesToDownloads(
                    host,
                    RuntimePaths.stsRoot(host)
                )
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
                applyStaging(
                    stagingRoot = stagingRoot,
                    stsRoot = RuntimePaths.stsRoot(host),
                    replaceRoots = pullPlan.replaceRoots,
                    rollbackRoot = rollbackRoot,
                    preserveLocalRelativePaths = syncBlacklist,
                )
                val applyMs = elapsedMs(applyStartedAtNs)

                val result = SteamCloudPullResult(
                    appliedFileCount = pullPlan.entries.size,
                    backupLabel = backupLabel,
                    completedAtMs = System.currentTimeMillis(),
                    summaryPath = SteamCloudManifestStore.pullSummaryFile(host).absolutePath,
                    warnings = filteredSnapshot.warnings,
                )
                val baselineStartedAtNs = System.nanoTime()
                SteamCloudBaselineStore.writeSnapshot(
                    host,
                    SteamCloudSyncBaseline(
                        syncedAtMs = result.completedAtMs,
                        localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                            entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                            configuredBlacklist = syncBlacklist,
                        ),
                        remoteEntries = filteredSnapshot.entries,
                    )
                )
                val baselineMs = elapsedMs(baselineStartedAtNs)
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
                writePullSummary(host, requireNotNull(snapshot), result, telemetry)
                SteamCloudAuthStore.recordPullSuccess(host, result.completedAtMs)
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
                return result
            }
        } catch (error: Throwable) {
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
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
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = summarizeError(error),
                    error = error,
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
                    },
                )
            }
            throw error
        } finally {
            stagingRoot.deleteRecursively()
            rollbackRoot.deleteRecursively()
        }
    }

    @Throws(Exception::class)
    fun mergeRemoteOnlyChanges(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): MergeRemoteChangesResult {
        require(plan.conflicts.isEmpty()) {
            "Steam Cloud remote merge was requested with unresolved conflicts."
        }
        require(plan.remoteOnlyChanges.isNotEmpty()) {
            "Steam Cloud remote merge was requested with no remote-only changes."
        }

        val startedAtMs = System.currentTimeMillis()
        val outputDir = SteamCloudManifestStore.outputDir(host)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${outputDir.absolutePath}")
        }

        val stagingRoot = File(outputDir, "merge-pull-staging-${System.currentTimeMillis()}-${System.nanoTime()}")
        val rollbackRoot = File(outputDir, "merge-pull-rollback-${System.currentTimeMillis()}-${System.nanoTime()}")
        val downloads = plan.remoteOnlyChanges.mapNotNull { it.currentRemote }
            .sortedWith(compareBy<SteamCloudManifestEntry>({ it.localRelativePath.lowercase() }, { it.localRelativePath }))
        val deletions = plan.remoteOnlyChanges.filter { it.kind == SteamCloudRemoteOnlyChangeKind.REMOTE_FILE_DELETED }
            .sortedWith(compareBy<SteamCloudRemoteOnlyChange>({ it.localRelativePath.lowercase() }, { it.localRelativePath }))
        val downloadResults = mutableListOf<SteamCloudClient.DownloadResult>()
        val client = SteamCloudClient(host)

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
                client.start()
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PULL_CLOUD_TO_LOCAL,
                        phase = SteamCloudSyncPhase.LOGGING_ON,
                        progressPercent = 12,
                    )
                )
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
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
                    )
                }
            }

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
            applyRemoteOnlyChanges(
                stagingRoot = stagingRoot,
                stsRoot = RuntimePaths.stsRoot(host),
                downloadedEntries = downloads,
                deletedEntries = deletions,
                rollbackRoot = rollbackRoot,
            )

            val snapshot = SteamCloudManifestStore.readSnapshot(host)
                ?: throw IOException("Steam Cloud manifest is missing after upload-plan refresh.")
            val completedAtMs = System.currentTimeMillis()
            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = completedAtMs,
                    localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                        entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                        configuredBlacklist = syncBlacklist,
                    ),
                    remoteEntries = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = snapshot,
                        configuredBlacklist = syncBlacklist,
                    ).entries,
                )
            )
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
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "merge_pull",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = listOf(
                        "Remote-only changes: ${plan.remoteOnlyChanges.size}",
                        "Downloads planned: ${downloads.size}",
                        "Deletes planned: ${deletions.size}",
                    ),
                )
            }
            throw error
        } finally {
            stagingRoot.deleteRecursively()
            rollbackRoot.deleteRecursively()
        }
    }

    private fun writePullSummary(
        host: Activity,
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
        host: Activity,
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

    private fun applyRemoteOnlyChanges(
        stagingRoot: File,
        stsRoot: File,
        downloadedEntries: List<SteamCloudManifestEntry>,
        deletedEntries: List<SteamCloudRemoteOnlyChange>,
        rollbackRoot: File,
    ) {
        if (!rollbackRoot.isDirectory && !rollbackRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud rollback directory: ${rollbackRoot.absolutePath}")
        }

        val appliedOperations = mutableListOf<AppliedRemoteMergeOperation>()
        try {
            downloadedEntries.forEach { entry ->
                val target = File(stsRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val staged = File(stagingRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val backup = File(rollbackRoot, entry.localRelativePath.replace('/', File.separatorChar))
                val hadOriginal = target.exists()
                if (hadOriginal) {
                    movePath(target, backup)
                }
                movePath(staged, target)
                appliedOperations += AppliedRemoteMergeOperation.Downloaded(target, backup, hadOriginal)
            }

            deletedEntries.forEach { change ->
                val target = File(stsRoot, change.localRelativePath.replace('/', File.separatorChar))
                if (!target.exists()) {
                    return@forEach
                }
                val backup = File(rollbackRoot, change.localRelativePath.replace('/', File.separatorChar))
                movePath(target, backup)
                appliedOperations += AppliedRemoteMergeOperation.Deleted(target, backup)
            }
        } catch (error: Throwable) {
            appliedOperations.asReversed().forEach { operation ->
                when (operation) {
                    is AppliedRemoteMergeOperation.Downloaded -> {
                        if (operation.target.exists()) {
                            operation.target.deleteRecursively()
                        }
                        if (operation.hadOriginal && operation.backup.exists()) {
                            movePath(operation.backup, operation.target)
                        }
                    }

                    is AppliedRemoteMergeOperation.Deleted -> {
                        if (operation.backup.exists()) {
                            movePath(operation.backup, operation.target)
                        }
                    }
                }
            }
            throw error
        }
    }

    private fun applyStaging(
        stagingRoot: File,
        stsRoot: File,
        replaceRoots: Set<SteamCloudRootKind>,
        rollbackRoot: File,
        preserveLocalRelativePaths: Set<String>,
    ) {
        if (!rollbackRoot.isDirectory && !rollbackRoot.mkdirs()) {
            throw IOException("Failed to create Steam Cloud rollback directory: ${rollbackRoot.absolutePath}")
        }

        val movedBackups = mutableListOf<Pair<File, File>>()
        try {
            for (rootKind in replaceRoots) {
                val liveRoot = File(stsRoot, rootKind.directoryName)
                val rollbackTarget = File(rollbackRoot, rootKind.directoryName)
                if (liveRoot.exists()) {
                    movePath(liveRoot, rollbackTarget)
                    movedBackups += liveRoot to rollbackTarget
                }

                val stagedRoot = File(stagingRoot, rootKind.directoryName)
                if (stagedRoot.exists()) {
                    movePath(stagedRoot, liveRoot)
                }
                restorePreservedLocalPaths(
                    rollbackRoot = rollbackTarget,
                    liveRoot = liveRoot,
                    relativeSuffixes = SteamCloudSyncBlacklist.relativeSuffixesForRoot(
                        rootKind = rootKind,
                        configuredBlacklist = preserveLocalRelativePaths,
                    ),
                )
            }
        } catch (error: Throwable) {
            for ((liveRoot, rollbackTarget) in movedBackups.asReversed()) {
                if (liveRoot.exists()) {
                    liveRoot.deleteRecursively()
                }
                if (rollbackTarget.exists()) {
                    movePath(rollbackTarget, liveRoot)
                }
            }
            throw error
        }
    }

    private fun restorePreservedLocalPaths(
        rollbackRoot: File,
        liveRoot: File,
        relativeSuffixes: Set<String>,
    ) {
        if (!rollbackRoot.exists() || relativeSuffixes.isEmpty()) {
            return
        }
        relativeSuffixes.forEach { relativeSuffix ->
            val source = File(rollbackRoot, relativeSuffix.replace('/', File.separatorChar))
            if (!source.exists()) {
                return@forEach
            }
            val target = File(liveRoot, relativeSuffix.replace('/', File.separatorChar))
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Failed to replace preserved local path: ${target.absolutePath}")
            }
            copyPath(source, target)
        }
    }

    private fun movePath(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Failed to replace existing path: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }
        copyPath(source, target)
        if (!source.deleteRecursively()) {
            throw IOException("Failed to delete source path after copy: ${source.absolutePath}")
        }
    }

    private fun copyPath(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.absolutePath}")
            }
            val children = source.listFiles() ?: return
            for (child in children) {
                copyPath(child, File(target, child.name))
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

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
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
    ): List<SteamCloudClient.DownloadResult> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val parallelism = minOf(PULL_DOWNLOAD_CONCURRENCY, entries.size)
        if (parallelism <= 1) {
            val results = ArrayList<SteamCloudClient.DownloadResult>(entries.size)
            entries.forEachIndexed { index, entry ->
                val outputFile = File(stagingRoot, entry.localRelativePath)
                val downloadResult = client.downloadFile(
                    appId,
                    entry.remotePath,
                    outputFile,
                    entry.rawSize,
                    entry.sha1,
                )
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
                futures += completionService.submit(Callable {
                    val outputFile = File(stagingRoot, entry.localRelativePath)
                    val downloadResult = client.downloadFile(
                        appId,
                        entry.remotePath,
                        outputFile,
                        entry.rawSize,
                        entry.sha1,
                    )
                    if (entry.timestamp > 0L) {
                        outputFile.setLastModified(entry.timestamp)
                    }
                    IndexedDownloadResult(index, downloadResult)
                })
            }

            val completedResults = ArrayList<IndexedDownloadResult>(entries.size)
            repeat(entries.size) {
                val future = completionService.take()
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

    private data class IndexedDownloadResult(
        val index: Int,
        val downloadResult: SteamCloudClient.DownloadResult,
    )

    private sealed interface AppliedRemoteMergeOperation {
        data class Downloaded(
            val target: File,
            val backup: File,
            val hadOriginal: Boolean,
        ) : AppliedRemoteMergeOperation

        data class Deleted(
            val target: File,
            val backup: File,
        ) : AppliedRemoteMergeOperation
    }
}
