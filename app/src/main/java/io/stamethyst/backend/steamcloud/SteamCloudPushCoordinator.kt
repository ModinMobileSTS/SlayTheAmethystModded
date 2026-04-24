package io.stamethyst.backend.steamcloud

import android.app.Activity
import `in`.dragonbra.javasteam.enums.EResult
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object SteamCloudPushCoordinator {
    private const val FAILURE_PATH_SAMPLE_LIMIT = 12

    private data class PlanUploadTelemetry(
        var clientInitMs: Long? = null,
        var connectMs: Long? = null,
        var logOnMs: Long? = null,
        var manifestRpcMs: Long? = null,
        var manifestMapMs: Long? = null,
        var manifestWriteMs: Long? = null,
        var baselineReadMs: Long? = null,
        var localSnapshotMs: Long? = null,
        var diffPlanMs: Long? = null,
        var totalMeasuredMs: Long? = null,
        var remoteEntryCount: Int? = null,
        var localEntryCount: Int? = null,
    )

    private data class PreparedMirrorPlan(
        val uploadCandidates: List<SteamCloudUploadCandidate>,
        val deleteRemotePaths: List<String>,
        val warnings: List<String>,
        val removedDeleteOverlaps: List<String>,
    )

    @Throws(Exception::class)
    fun buildUploadPlan(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
    ): SteamCloudUploadPlan {
        val startedAtMs = System.currentTimeMillis()
        val totalStartedAtNs = System.nanoTime()
        val clientInitStartedAtNs = System.nanoTime()
        val client = SteamCloudClient(host)
        val telemetry = PlanUploadTelemetry(
            clientInitMs = elapsedMs(clientInitStartedAtNs),
        )
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "plan_upload",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                val connectStartedAtNs = System.nanoTime()
                client.start()
                telemetry.connectMs = elapsedMs(connectStartedAtNs)
                val logOnStartedAtNs = System.nanoTime()
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                telemetry.logOnMs = elapsedMs(logOnStartedAtNs)

                val manifestRpcStartedAtNs = System.nanoTime()
                val remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID)
                telemetry.manifestRpcMs = elapsedMs(manifestRpcStartedAtNs)
                telemetry.remoteEntryCount = remoteEntries.size

                val manifestMapStartedAtNs = System.nanoTime()
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = remoteEntries,
                )
                telemetry.manifestMapMs = elapsedMs(manifestMapStartedAtNs)

                val manifestWriteStartedAtNs = System.nanoTime()
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)
                telemetry.manifestWriteMs = elapsedMs(manifestWriteStartedAtNs)

                val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
                val filteredSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                    snapshot = snapshot,
                    configuredBlacklist = syncBlacklist,
                )

                val baselineReadStartedAtNs = System.nanoTime()
                val baseline = SteamCloudSyncBlacklist.filterBaseline(
                    baseline = SteamCloudBaselineStore.readSnapshot(host),
                    configuredBlacklist = syncBlacklist,
                )
                telemetry.baselineReadMs = elapsedMs(baselineReadStartedAtNs)

                val localSnapshotStartedAtNs = System.nanoTime()
                val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                    entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                    configuredBlacklist = syncBlacklist,
                )
                telemetry.localSnapshotMs = elapsedMs(localSnapshotStartedAtNs)
                telemetry.localEntryCount = localEntries.size

                val diffPlanStartedAtNs = System.nanoTime()
                val plan = SteamCloudDiffPlanner.buildUploadPlan(
                    plannedAtMs = System.currentTimeMillis(),
                    currentLocalEntries = localEntries,
                    currentRemoteSnapshot = filteredSnapshot,
                    baseline = baseline,
                )
                telemetry.diffPlanMs = elapsedMs(diffPlanStartedAtNs)
                telemetry.totalMeasuredMs = elapsedMs(totalStartedAtNs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = buildList {
                        addAll(planUploadTimingLines(telemetry))
                        add("Manifest files: ${snapshot.fileCount}")
                        add("Upload candidates: ${plan.uploadCandidates.size}")
                        add("Conflicts: ${plan.conflicts.size}")
                        add("Remote-only changes: ${plan.remoteOnlyChanges.size}")
                        add("Baseline configured: ${plan.baselineConfigured}")
                    } + plan.warnings.map { "Warning: $it" },
                )
                return plan
            }
        } catch (error: Throwable) {
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                telemetry.totalMeasuredMs = elapsedMs(totalStartedAtNs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        addAll(planUploadTimingLines(telemetry))
                        add("Existing guard data provided: ${if (authMaterial.guardData.isBlank()) "no" else "yes"}")
                    },
                )
            }
            throw error
        }
    }

    @Throws(Exception::class)
    fun pushLocalChanges(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): SteamCloudPushResult {
        require(plan.conflicts.isEmpty()) {
            "Steam Cloud push was requested with unresolved conflicts."
        }
        require(plan.uploadCandidates.isNotEmpty()) {
            "Steam Cloud push was requested with no upload candidates."
        }

        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null

        try {
            client.beginOperationDiagnostics(
                "manual_push",
                authMaterial.accountName,
                authMaterial.guardData.isNotBlank(),
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CONNECTING,
                    progressPercent = 5,
                )
            )
            client.start()
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.LOGGING_ON,
                    progressPercent = 12,
                )
            )
            client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = plan.uploadCandidates.size,
                    progressPercent = 20,
                )
            )
            uploadBatch = client.beginUploadBatch(
                STEAM_CLOUD_APP_ID,
                plan.uploadCandidates.map { it.remotePath },
            )

            var uploadedBytes = 0L
            plan.uploadCandidates.forEachIndexed { index, candidate ->
                val sourceFile = File(
                    RuntimePaths.stsRoot(host),
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                val uploadedFile = try {
                    client.uploadFile(
                        STEAM_CLOUD_APP_ID,
                        candidate.remotePath,
                        sourceFile,
                        requireNotNull(uploadBatch).batchId,
                    )
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}): ${summarizeError(error)}",
                        error,
                    )
                }
                uploadedBytes += uploadedFile.fileSize
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.UPLOADING,
                        completedFiles = index + 1,
                        totalFiles = plan.uploadCandidates.size,
                        currentPath = candidate.localRelativePath,
                        progressPercent = 30 + (((index + 1) * 55) / plan.uploadCandidates.size),
                    )
                )
            }
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = plan.uploadCandidates.size,
                    totalFiles = plan.uploadCandidates.size,
                    progressPercent = 92,
                )
            )
            client.completeUploadBatch(
                STEAM_CLOUD_APP_ID,
                requireNotNull(uploadBatch).batchId,
                EResult.OK,
            )

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, refreshedSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, refreshedSnapshot.fetchedAtMs)

            val result = SteamCloudPushResult(
                uploadedFileCount = plan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = plan.warnings + refreshedSnapshot.warnings,
            )
            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                        entries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                        configuredBlacklist = syncBlacklist,
                    ),
                    remoteEntries = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = refreshedSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ).entries,
                )
            )
            writePushSummary(
                host = host,
                plan = plan,
                snapshot = refreshedSnapshot,
                result = result,
            )
            SteamCloudAuthStore.recordPushSuccess(host, result.completedAtMs)
            SteamCloudDiagnosticsStore.writeSummary(
                context = host,
                operation = "manual_push",
                outcome = "SUCCESS",
                accountName = authMaterial.accountName,
                startedAtMs = startedAtMs,
                completedAtMs = result.completedAtMs,
                diagnostics = client.snapshotDiagnostics(),
                extraLines = listOf(
                    "Uploaded files: ${result.uploadedFileCount}",
                    "Uploaded bytes: ${result.uploadedBytes}",
                    "Upload summary: ${result.summaryPath}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "manual_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        add("Upload candidates before failure: ${plan.uploadCandidates.size}")
                        add("Conflicts before failure: ${plan.conflicts.size}")
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                        plan.warnings.forEach { warning -> add("Warning: $warning") }
                    },
                )
            }
            throw error
        } finally {
            client.close()
        }
    }

    @Throws(Exception::class)
    fun overwriteRemoteWithLocal(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): SteamCloudPushResult {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null
        var preparedPlan: PreparedMirrorPlan? = null

        try {
            client.beginOperationDiagnostics(
                "force_push",
                authMaterial.accountName,
                authMaterial.guardData.isNotBlank(),
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CONNECTING,
                    progressPercent = 5,
                )
            )
            client.start()
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.LOGGING_ON,
                    progressPercent = 12,
                )
            )
            client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                    progressPercent = 20,
                )
            )

            val currentRemoteSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, currentRemoteSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, currentRemoteSnapshot.fetchedAtMs)

            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                entries = SteamCloudLocalSnapshotCollector.collect(sourceRoot),
                configuredBlacklist = syncBlacklist,
            )
            preparedPlan = prepareMirrorPlan(
                SteamCloudMirrorPlanner.buildLocalMirrorPlan(
                    currentLocalEntries = localEntries,
                    currentRemoteSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = currentRemoteSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ),
                    baseline = SteamCloudSyncBlacklist.filterBaseline(
                        baseline = SteamCloudBaselineStore.readSnapshot(host),
                        configuredBlacklist = syncBlacklist,
                    ),
                )
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = preparedPlan.uploadCandidates.size,
                    progressPercent = 28,
                )
            )

            if (preparedPlan.uploadCandidates.isNotEmpty() || preparedPlan.deleteRemotePaths.isNotEmpty()) {
                uploadBatch = client.beginUploadBatch(
                    STEAM_CLOUD_APP_ID,
                    preparedPlan.uploadCandidates.map { it.remotePath },
                    preparedPlan.deleteRemotePaths,
                )
            }

            var uploadedBytes = 0L
            val totalUploads = preparedPlan.uploadCandidates.size
            preparedPlan.uploadCandidates.forEachIndexed { index, candidate ->
                val sourceFile = File(
                    sourceRoot,
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                val uploadedFile = try {
                    client.uploadFile(
                        STEAM_CLOUD_APP_ID,
                        candidate.remotePath,
                        sourceFile,
                        requireNotNull(uploadBatch).batchId,
                    )
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}): ${summarizeError(error)}",
                        error,
                    )
                }
                uploadedBytes += uploadedFile.fileSize
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.UPLOADING,
                        completedFiles = index + 1,
                        totalFiles = totalUploads,
                        currentPath = candidate.localRelativePath,
                        progressPercent = if (totalUploads <= 0) {
                            85
                        } else {
                            30 + (((index + 1) * 55) / totalUploads)
                        },
                    )
                )
            }

            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = totalUploads,
                    totalFiles = totalUploads,
                    progressPercent = 92,
                )
            )
            uploadBatch?.let { batch ->
                client.completeUploadBatch(
                    STEAM_CLOUD_APP_ID,
                    batch.batchId,
                    EResult.OK,
                )
            }

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
            )
            SteamCloudManifestStore.writeSnapshot(host, refreshedSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, refreshedSnapshot.fetchedAtMs)

            val result = SteamCloudPushResult(
                uploadedFileCount = preparedPlan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                deletedRemoteFileCount = preparedPlan.deleteRemotePaths.size,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = currentRemoteSnapshot.warnings + preparedPlan.warnings + refreshedSnapshot.warnings,
            )
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = localEntries,
                    remoteEntries = SteamCloudSyncBlacklist.filterManifestSnapshot(
                        snapshot = refreshedSnapshot,
                        configuredBlacklist = syncBlacklist,
                    ).entries,
                )
            )
            writeMirrorPushSummary(
                host = host,
                plan = SteamCloudMirrorPlan(
                    uploadCandidates = preparedPlan.uploadCandidates,
                    deleteRemotePaths = preparedPlan.deleteRemotePaths,
                ),
                snapshot = refreshedSnapshot,
                result = result,
            )
            SteamCloudAuthStore.recordPushSuccess(host, result.completedAtMs)
            SteamCloudDiagnosticsStore.writeSummary(
                context = host,
                operation = "force_push",
                outcome = "SUCCESS",
                accountName = authMaterial.accountName,
                startedAtMs = startedAtMs,
                completedAtMs = result.completedAtMs,
                diagnostics = client.snapshotDiagnostics(),
                extraLines = listOf(
                    "Uploaded files: ${result.uploadedFileCount}",
                    "Uploaded bytes: ${result.uploadedBytes}",
                    "Deleted remote files: ${result.deletedRemoteFileCount}",
                    "Upload summary: ${result.summaryPath}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "force_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
                        addAll(describePreparedMirrorPlan(preparedPlan))
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                    },
                )
            }
            throw error
        } finally {
            client.close()
        }
    }

    private fun writePushSummary(
        host: Activity,
        plan: SteamCloudUploadPlan,
        snapshot: SteamCloudManifestSnapshot,
        result: SteamCloudPushResult,
    ) {
        val summaryFile = SteamCloudManifestStore.pushSummaryFile(host)
        val parent = summaryFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud push summary")
            add("")
            add("Completed At: ${formatTimestamp(result.completedAtMs)}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Uploaded Files: ${result.uploadedFileCount}")
            add("Uploaded Bytes: ${result.uploadedBytes}")
            if (result.deletedRemoteFileCount > 0) {
                add("Deleted Remote Files: ${result.deletedRemoteFileCount}")
            }
            add("Remote Files After Push: ${snapshot.fileCount}")
            add("Manifest: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
            add("Baseline: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}")
            if (plan.remoteOnlyChanges.isNotEmpty()) {
                add("Remote-only Changes Left Unmodified: ${plan.remoteOnlyChanges.size}")
            }
            if (result.warnings.isNotEmpty()) {
                add("")
                add("Warnings:")
                result.warnings.distinct().forEach { add(" - $it") }
            }
        }
        summaryFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun writeMirrorPushSummary(
        host: Activity,
        plan: SteamCloudMirrorPlan,
        snapshot: SteamCloudManifestSnapshot,
        result: SteamCloudPushResult,
    ) {
        val summaryFile = SteamCloudManifestStore.pushSummaryFile(host)
        val parent = summaryFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud summary directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud push summary")
            add("")
            add("Completed At: ${formatTimestamp(result.completedAtMs)}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Uploaded Files: ${result.uploadedFileCount}")
            add("Uploaded Bytes: ${result.uploadedBytes}")
            add("Deleted Remote Files: ${result.deletedRemoteFileCount}")
            add("Remote Files After Push: ${snapshot.fileCount}")
            add("Manifest: ${SteamCloudManifestStore.manifestFile(host).absolutePath}")
            add("Baseline: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}")
            if (plan.deleteRemotePaths.isNotEmpty()) {
                add("Deleted Remote Paths:")
                plan.deleteRemotePaths.forEach { add(" - $it") }
            }
            if (result.warnings.isNotEmpty()) {
                add("")
                add("Warnings:")
                result.warnings.distinct().forEach { add(" - $it") }
            }
        }
        summaryFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun reportProgress(
        progressCallback: ((SteamCloudSyncProgress) -> Unit)?,
        progress: SteamCloudSyncProgress,
    ) {
        progressCallback?.invoke(progress)
    }

    private fun planUploadTimingLines(telemetry: PlanUploadTelemetry): List<String> = listOf(
        "Plan total measured ms: ${formatTimingMs(telemetry.totalMeasuredMs)}",
        "Client init ms: ${formatTimingMs(telemetry.clientInitMs)}",
        "Connect ms: ${formatTimingMs(telemetry.connectMs)}",
        "Logon ms: ${formatTimingMs(telemetry.logOnMs)}",
        "Manifest RPC ms: ${formatTimingMs(telemetry.manifestRpcMs)}",
        "Manifest map ms: ${formatTimingMs(telemetry.manifestMapMs)}",
        "Manifest write ms: ${formatTimingMs(telemetry.manifestWriteMs)}",
        "Baseline read ms: ${formatTimingMs(telemetry.baselineReadMs)}",
        "Local snapshot ms: ${formatTimingMs(telemetry.localSnapshotMs)}",
        "Diff plan ms: ${formatTimingMs(telemetry.diffPlanMs)}",
        "Remote entries: ${formatTimingCount(telemetry.remoteEntryCount)}",
        "Local entries: ${formatTimingCount(telemetry.localEntryCount)}",
    )

    private fun formatTimingMs(value: Long?): String = value?.toString() ?: "<not reached>"

    private fun formatTimingCount(value: Int?): String = value?.toString() ?: "<not reached>"

    private fun elapsedMs(startedAtNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs).coerceAtLeast(0L)

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
    }

    private fun prepareMirrorPlan(plan: SteamCloudMirrorPlan): PreparedMirrorPlan {
        val duplicateUploads = plan.uploadCandidates
            .groupBy { normalizeRemotePathKey(it.remotePath) }
            .values
            .filter { it.size > 1 }
        require(duplicateUploads.isEmpty()) {
            val sample = duplicateUploads
                .map { group -> group.first().remotePath }
                .sortedWith(compareBy<String>({ it.lowercase(Locale.ROOT) }, { it }))
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .joinToString(", ")
            "Steam Cloud local override planned duplicate upload paths: $sample"
        }

        val uploadCandidates = plan.uploadCandidates
            .distinctBy { normalizeRemotePathKey(it.remotePath) }
            .sortedWith(
                compareBy<SteamCloudUploadCandidate>({ normalizeRemotePathKey(it.remotePath) }, { it.remotePath })
            )
        val uploadKeys = uploadCandidates.mapTo(linkedSetOf()) { normalizeRemotePathKey(it.remotePath) }
        val removedDeleteOverlaps = plan.deleteRemotePaths
            .filter { normalizeRemotePathKey(it) in uploadKeys }
            .distinctBy(::normalizeRemotePathKey)
            .sortedWith(compareBy<String>({ normalizeRemotePathKey(it) }, { it }))
        val deleteRemotePaths = plan.deleteRemotePaths
            .distinctBy(::normalizeRemotePathKey)
            .filterNot { normalizeRemotePathKey(it) in uploadKeys }
            .sortedWith(compareBy<String>({ normalizeRemotePathKey(it) }, { it }))

        val warnings = buildList {
            if (removedDeleteOverlaps.isNotEmpty()) {
                add(
                    "Removed ${removedDeleteOverlaps.size} overlapping Steam Cloud delete request(s) because those paths are also being uploaded."
                )
            }
        }

        return PreparedMirrorPlan(
            uploadCandidates = uploadCandidates,
            deleteRemotePaths = deleteRemotePaths,
            warnings = warnings,
            removedDeleteOverlaps = removedDeleteOverlaps,
        )
    }

    private fun describePreparedMirrorPlan(plan: PreparedMirrorPlan?): List<String> {
        if (plan == null) {
            return emptyList()
        }
        return buildList {
            add("Mirror upload candidates before failure: ${plan.uploadCandidates.size}")
            add("Mirror delete candidates before failure: ${plan.deleteRemotePaths.size}")
            if (plan.removedDeleteOverlaps.isNotEmpty()) {
                add("Removed overlapping delete paths: ${plan.removedDeleteOverlaps.size}")
                plan.removedDeleteOverlaps
                    .take(FAILURE_PATH_SAMPLE_LIMIT)
                    .forEach { remotePath -> add("Overlap path: $remotePath") }
            }
            plan.uploadCandidates
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .forEach { candidate ->
                    add(
                        "Upload path: ${candidate.remotePath} | localSha1=${candidate.sha1.ifBlank { "<none>" }} | size=${candidate.fileSize}"
                    )
                }
            plan.deleteRemotePaths
                .take(FAILURE_PATH_SAMPLE_LIMIT)
                .forEach { remotePath -> add("Delete path: $remotePath") }
            plan.warnings.forEach { warning -> add("Warning: $warning") }
        }
    }

    private fun normalizeRemotePathKey(remotePath: String): String =
        remotePath.trim().replace('\\', '/').lowercase(Locale.ROOT)

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }
}
