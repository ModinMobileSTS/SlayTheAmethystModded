package io.stamethyst.backend.steamcloud

import android.app.Activity
import `in`.dragonbra.javasteam.enums.EResult
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SteamCloudPushCoordinator {
    @Throws(Exception::class)
    fun buildUploadPlan(
        host: Activity,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
    ): SteamCloudUploadPlan {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        try {
            client.use {
                client.beginOperationDiagnostics(
                    "plan_upload",
                    authMaterial.accountName,
                    authMaterial.guardData.isNotBlank(),
                )
                client.start()
                client.logOnWithRefreshToken(authMaterial.accountName, authMaterial.refreshToken)
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                )
                SteamCloudManifestStore.writeSnapshot(host, snapshot)
                SteamCloudAuthStore.recordManifestSuccess(host, snapshot.fetchedAtMs)

                val baseline = SteamCloudBaselineStore.readSnapshot(host)
                val plan = SteamCloudDiffPlanner.buildUploadPlan(
                    plannedAtMs = System.currentTimeMillis(),
                    currentLocalEntries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                    currentRemoteSnapshot = snapshot,
                    baseline = baseline,
                )
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "SUCCESS",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = client.snapshotDiagnostics(),
                    extraLines = listOf(
                        "Manifest files: ${snapshot.fileCount}",
                        "Upload candidates: ${plan.uploadCandidates.size}",
                        "Conflicts: ${plan.conflicts.size}",
                        "Remote-only changes: ${plan.remoteOnlyChanges.size}",
                        "Baseline configured: ${plan.baselineConfigured}",
                    ) + plan.warnings.map { "Warning: $it" },
                )
                return plan
            }
        } catch (error: Throwable) {
            SteamCloudAuthStore.recordFailure(host, summarizeError(error))
            runCatching {
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
                    extraLines = listOf(
                        "Existing guard data provided: ${if (authMaterial.guardData.isBlank()) "no" else "yes"}",
                    ),
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
                val uploadedFile = client.uploadFile(
                    STEAM_CLOUD_APP_ID,
                    candidate.remotePath,
                    sourceFile,
                    requireNotNull(uploadBatch).batchId,
                )
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
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
                    remoteEntries = refreshedSnapshot.entries,
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
                    diagnostics = client.snapshotDiagnostics(),
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
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
    ): SteamCloudPushResult {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null

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

            val localEntries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host))
            val mirrorPlan = SteamCloudMirrorPlanner.buildLocalMirrorPlan(
                currentLocalEntries = localEntries,
                currentRemoteSnapshot = currentRemoteSnapshot,
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = mirrorPlan.uploadCandidates.size,
                    progressPercent = 28,
                )
            )

            if (mirrorPlan.uploadCandidates.isNotEmpty() || mirrorPlan.deleteRemotePaths.isNotEmpty()) {
                uploadBatch = client.beginUploadBatch(
                    STEAM_CLOUD_APP_ID,
                    mirrorPlan.uploadCandidates.map { it.remotePath },
                    mirrorPlan.deleteRemotePaths,
                )
            }

            var uploadedBytes = 0L
            val totalUploads = mirrorPlan.uploadCandidates.size
            mirrorPlan.uploadCandidates.forEachIndexed { index, candidate ->
                val sourceFile = File(
                    RuntimePaths.stsRoot(host),
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                val uploadedFile = client.uploadFile(
                    STEAM_CLOUD_APP_ID,
                    candidate.remotePath,
                    sourceFile,
                    requireNotNull(uploadBatch).batchId,
                )
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
                uploadedFileCount = mirrorPlan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                deletedRemoteFileCount = mirrorPlan.deleteRemotePaths.size,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = currentRemoteSnapshot.warnings + refreshedSnapshot.warnings,
            )
            SteamCloudBaselineStore.writeSnapshot(
                host,
                SteamCloudSyncBaseline(
                    syncedAtMs = result.completedAtMs,
                    localEntries = localEntries,
                    remoteEntries = refreshedSnapshot.entries,
                )
            )
            writeMirrorPushSummary(
                host = host,
                plan = mirrorPlan,
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
                    diagnostics = client.snapshotDiagnostics(),
                    failureSummary = summarizeError(error),
                    error = error,
                    extraLines = buildList {
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

    private fun summarizeError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isNotEmpty()) {
            message
        } else {
            error.javaClass.simpleName
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }
}
