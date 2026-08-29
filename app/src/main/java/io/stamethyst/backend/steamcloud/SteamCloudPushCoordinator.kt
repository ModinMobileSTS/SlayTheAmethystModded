package io.stamethyst.backend.steamcloud

import android.content.Context
import `in`.dragonbra.javasteam.enums.EResult
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class SteamCloudStalePlanException(message: String) : IOException(message)

internal class SteamCloudPushReconciliationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal object SteamCloudPushCoordinator {
    private const val FAILURE_PATH_SAMPLE_LIMIT = 12
    private const val BACKGROUND_UPLOAD_SNAPSHOT_DIRECTORY_NAME = "steam-cloud-background-uploads"
    private const val BACKGROUND_UPLOAD_SNAPSHOT_PREFIX = "upload-"
    // After a CompleteAppUploadBatch failure the coordinator polls the manifest to verify that
    // the batch changes are already visible server-side before deciding to surface the error.
    // Steam's eventual-consistency window can be several seconds, so use more attempts and
    // longer delays than the original 3 × 1 s that was too aggressive.
    private const val UPLOAD_BATCH_RECONCILIATION_MAX_ATTEMPTS = 6
    private val UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES = longArrayOf(
        2_000L, 5_000L, 10_000L, 15_000L, 20_000L,
    )

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

    /**
     * Immutable local input for a background upload.  The snapshot is created while the live-save
     * lease is held, then uploaded after the game can safely take that lease for its own writes.
     */
    internal data class BackgroundUploadSnapshot(
        val root: File,
        val localEntries: List<SteamCloudLocalFileSnapshotEntry>,
        val containsAllManagedRoots: Boolean,
    ) {
        fun delete() {
            if (root.exists() && !root.deleteRecursively()) {
                throw IOException("Failed to remove Steam Cloud background upload snapshot: ${root.absolutePath}")
            }
        }
    }

    internal fun isBackgroundUploadEligible(plan: SteamCloudUploadPlan): Boolean =
        plan.conflicts.isEmpty() &&
            plan.uploadCandidates.isNotEmpty() &&
            plan.remoteOnlyChanges.isEmpty() &&
            plan.remoteDeleteCandidates.isEmpty()

    internal fun isBackgroundCheckSnapshotEligible(plan: SteamCloudUploadPlan): Boolean =
        plan.conflicts.isEmpty() &&
            plan.remoteOnlyChanges.isEmpty() &&
            (plan.uploadCandidates.isNotEmpty() || plan.remoteDeleteCandidates.isNotEmpty())

    /**
     * Copies only the already-planned upload inputs while the game is excluded from live saves.
     * The caller can upload the returned root after this method returns without touching live data.
     */
    @Throws(IOException::class)
    fun prepareBackgroundUploadSnapshot(
        host: Context,
        plan: SteamCloudUploadPlan,
        shouldContinue: () -> Boolean = { true },
    ): BackgroundUploadSnapshot {
        require(isBackgroundUploadEligible(plan)) {
            "Steam Cloud background upload requires a conflict-free upload-only plan."
        }
        val snapshotParent = backgroundSnapshotParent(host)
        if (!snapshotParent.isDirectory && !snapshotParent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud background upload directory: ${snapshotParent.absolutePath}")
        }
        var snapshotRoot: File? = null
        var completed = false
        try {
            val snapshot = SteamCloudOperationMutex.runExclusive(host) {
                SteamCloudLiveSaveLease.runMutation(host) {
                    ensureNotCancelled(shouldContinue)
                    clearStaleBackgroundUploadSnapshots(snapshotParent)
                    snapshotRoot = File(
                        snapshotParent,
                        "$BACKGROUND_UPLOAD_SNAPSHOT_PREFIX${System.currentTimeMillis()}-${System.nanoTime()}",
                    )
                    val liveEntries = SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host))
                    val liveByPath = indexLocalEntries(liveEntries)
                    val targetRoot = requireNotNull(snapshotRoot)
                    if (!targetRoot.mkdirs()) {
                        throw IOException(
                            "Failed to create Steam Cloud background upload snapshot: " +
                                targetRoot.absolutePath,
                        )
                    }
                    plan.uploadCandidates.forEach { candidate ->
                        ensureNotCancelled(shouldContinue)
                        val current = liveByPath[candidate.localRelativePath]
                            ?: throw SteamCloudStalePlanException(
                                "Steam Cloud background upload source is missing " +
                                    "${candidate.localRelativePath}."
                            )
                        if (!localEntryMatchesUploadCandidate(current, candidate)) {
                            throw SteamCloudStalePlanException(
                                "Steam Cloud background upload source changed " +
                                    "${candidate.localRelativePath}."
                            )
                        }
                        val source = File(
                            RuntimePaths.stsRoot(host),
                            candidate.localRelativePath.replace('/', File.separatorChar),
                        )
                        if (!source.isFile) {
                            throw SteamCloudStalePlanException(
                                "Steam Cloud background upload source is not a file " +
                                    "${candidate.localRelativePath}."
                            )
                        }
                        SteamCloudStagedPathStore.copyPath(
                            source,
                            File(targetRoot, candidate.localRelativePath.replace('/', File.separatorChar)),
                        )
                    }
                    ensureNotCancelled(shouldContinue)
                    val snapshotEntries = SteamCloudLocalSnapshotCollector.collect(targetRoot)
                    val snapshotByPath = indexLocalEntries(snapshotEntries)
                    if (snapshotByPath.size != plan.uploadCandidates.size) {
                        throw IOException("Steam Cloud background upload snapshot contains unexpected files.")
                    }
                    plan.uploadCandidates.forEach { candidate ->
                        val staged = snapshotByPath[candidate.localRelativePath]
                            ?: throw IOException(
                                "Steam Cloud background upload snapshot is missing " +
                                    "${candidate.localRelativePath}.",
                            )
                        if (!snapshotEntryMatchesUploadCandidate(staged, candidate)) {
                            throw IOException(
                                "Steam Cloud background upload snapshot does not match " +
                                    "${candidate.localRelativePath}.",
                            )
                        }
                    }
                    BackgroundUploadSnapshot(
                        root = targetRoot,
                        localEntries = plan.uploadCandidates.map { candidate ->
                            SteamCloudLocalFileSnapshotEntry(
                                localRelativePath = candidate.localRelativePath,
                                rootKind = candidate.rootKind,
                                fileSize = candidate.fileSize,
                                lastModifiedMs = candidate.lastModifiedMs,
                                sha256 = candidate.sha256,
                                sha1 = candidate.sha1,
                            )
                        },
                        containsAllManagedRoots = false,
                    )
                }
            }
            completed = true
            return snapshot
        } finally {
            snapshotRoot?.takeIf { !completed && it.exists() }?.deleteRecursively()
        }
    }

    /**
     * Freezes the complete managed save set before a background cloud check starts.  The later
     * diff and upload must use this snapshot once the game is allowed to take the live-save lease.
     */
    @Throws(IOException::class)
    fun prepareBackgroundCheckSnapshot(
        host: Context,
        shouldContinue: () -> Boolean = { true },
    ): BackgroundUploadSnapshot {
        val snapshotParent = backgroundSnapshotParent(host)
        if (!snapshotParent.isDirectory && !snapshotParent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud background upload directory: ${snapshotParent.absolutePath}")
        }
        var snapshotRoot: File? = null
        var completed = false
        try {
            val snapshot = SteamCloudOperationMutex.runExclusive(host) {
                SteamCloudLiveSaveLease.runMutation(host) {
                    ensureNotCancelled(shouldContinue)
                    clearStaleBackgroundUploadSnapshots(snapshotParent)
                    snapshotRoot = File(
                        snapshotParent,
                        "$BACKGROUND_UPLOAD_SNAPSHOT_PREFIX${System.currentTimeMillis()}-${System.nanoTime()}",
                    )
                    val targetRoot = requireNotNull(snapshotRoot)
                    if (!targetRoot.mkdirs()) {
                        throw IOException(
                            "Failed to create Steam Cloud background upload snapshot: ${targetRoot.absolutePath}"
                        )
                    }
                    SteamCloudRootKind.entries.forEach { rootKind ->
                        ensureNotCancelled(shouldContinue)
                        val source = File(RuntimePaths.stsRoot(host), rootKind.directoryName)
                        if (source.exists()) {
                            SteamCloudStagedPathStore.copyPath(
                                source,
                                File(targetRoot, rootKind.directoryName),
                            )
                        }
                    }
                    ensureNotCancelled(shouldContinue)
                    BackgroundUploadSnapshot(
                        root = targetRoot,
                        localEntries = SteamCloudLocalSnapshotCollector.collect(targetRoot),
                        containsAllManagedRoots = true,
                    )
                }
            }
            completed = true
            return snapshot
        } finally {
            snapshotRoot?.takeIf { !completed && it.exists() }?.deleteRecursively()
        }
    }

    private fun backgroundSnapshotParent(context: Context): File {
        val appContext = context.applicationContext ?: context
        val noBackupDirectory = runCatching { appContext.noBackupFilesDir }.getOrNull()
            ?: File(appContext.filesDir, "no_backup")
        return File(noBackupDirectory, BACKGROUND_UPLOAD_SNAPSHOT_DIRECTORY_NAME)
    }

    private fun clearStaleBackgroundUploadSnapshots(snapshotParent: File) {
        val children = snapshotParent.listFiles()
            ?: throw IOException(
                "Failed to enumerate Steam Cloud background upload directory: ${snapshotParent.absolutePath}"
            )
        children
            .filter { it.name.startsWith(BACKGROUND_UPLOAD_SNAPSHOT_PREFIX) }
            .forEach { snapshot ->
                if (!snapshot.deleteRecursively()) {
                    throw IOException(
                        "Failed to remove stale Steam Cloud background upload snapshot: ${snapshot.absolutePath}"
                    )
                }
            }
    }

    @Throws(Exception::class)
    fun buildUploadPlan(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
        sourceEntries: List<SteamCloudLocalFileSnapshotEntry>? = null,
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
                client.logOnWithRefreshToken(
                    authMaterial.accountName,
                    authMaterial.refreshToken,
                    authMaterial.steamId64,
                )
                telemetry.logOnMs = elapsedMs(logOnStartedAtNs)

                val manifestRpcStartedAtNs = System.nanoTime()
                val remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID)
                telemetry.manifestRpcMs = elapsedMs(manifestRpcStartedAtNs)
                telemetry.remoteEntryCount = remoteEntries.size

                val manifestMapStartedAtNs = System.nanoTime()
                val snapshot = SteamCloudPathMapper.buildManifestSnapshot(
                    fetchedAtMs = System.currentTimeMillis(),
                    remoteEntries = remoteEntries,
                    steamId64 = authMaterial.steamId64,
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
                    baseline = SteamCloudBaselineStore.readSnapshot(host, authMaterial.steamId64),
                    configuredBlacklist = syncBlacklist,
                )
                telemetry.baselineReadMs = elapsedMs(baselineReadStartedAtNs)

                val localSnapshotStartedAtNs = System.nanoTime()
                val localEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                    entries = sourceEntries ?: SteamCloudLocalSnapshotCollector.collect(RuntimePaths.stsRoot(host)),
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
                val baselineRefreshed = plan.isAlreadySynced() && shouldContinue()
                if (baselineRefreshed) {
                    SteamCloudBaselineStore.writeSnapshot(
                        host,
                        SteamCloudSyncBaseline(
                            syncedAtMs = System.currentTimeMillis(),
                            localEntries = localEntries,
                            remoteEntries = filteredSnapshot.entriesForPlanning,
                            steamId64 = authMaterial.steamId64,
                        )
                    )
                }
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
                        add("Remote delete candidates: ${plan.remoteDeleteCandidates.size}")
                        add("Conflicts: ${plan.conflicts.size}")
                        add("Remote-only changes: ${plan.remoteOnlyChanges.size}")
                        add("Baseline configured: ${plan.baselineConfigured}")
                        add("Baseline refreshed: ${if (baselineRefreshed) "yes" else "no"}")
                    } + plan.warnings.map { "Warning: $it" },
                )
                return plan
            }
        } catch (error: Throwable) {
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry && shouldContinue() && isReconnectRetryCandidate(error, failureDiagnostics)) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                return buildUploadPlan(
                    host = host,
                    authMaterial = authMaterial,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                    sourceEntries = sourceEntries,
                )
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(error), authMaterial)
            runCatching {
                telemetry.totalMeasuredMs = elapsedMs(totalStartedAtNs)
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "plan_upload",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
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
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        plan: SteamCloudUploadPlan,
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        sourceEntries: List<SteamCloudLocalFileSnapshotEntry>? = null,
        allowSnapshotDeletes: Boolean = false,
    ): SteamCloudPushResult {
        require(plan.conflicts.isEmpty()) {
            "Steam Cloud push was requested with unresolved conflicts."
        }
        require(plan.uploadCandidates.isNotEmpty() || plan.remoteDeleteCandidates.isNotEmpty()) {
            "Steam Cloud push was requested with no upload or delete candidates."
        }
        require(sourceEntries == null || plan.remoteDeleteCandidates.isEmpty() || allowSnapshotDeletes) {
            "Steam Cloud partial upload snapshots cannot delete remote files."
        }
        // A supplied source snapshot has already been frozen under the live-save lease. Never
        // re-read the game root for it, or a running game could change the uploaded version.
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null
        var remoteCommitMayHaveCompleted = false
        var liveSaveLease: SteamCloudLiveSaveLease.Lease? = null
        var priorBaseline: SteamCloudSyncBaseline? = null
        var uploadedBytes = 0L
        var uploadedFileCount = 0
        val totalOperations = plan.syncOperationCount()

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
            client.logOnWithRefreshToken(
                authMaterial.accountName,
                authMaterial.refreshToken,
                authMaterial.steamId64,
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.REFRESHING_MANIFEST,
                    progressPercent = 16,
                )
            )

            val syncBlacklist = LauncherConfig.readSteamCloudSyncBlacklistPaths(host)
            val currentRemoteSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                steamId64 = authMaterial.steamId64,
            )
            val currentFilteredRemoteSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                snapshot = currentRemoteSnapshot,
                configuredBlacklist = syncBlacklist,
            )
            val currentLocalEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                entries = sourceEntries ?: SteamCloudLocalSnapshotCollector.collect(sourceRoot),
                configuredBlacklist = syncBlacklist,
            )
            val preUploadLocalEntries = currentLocalEntries
            priorBaseline = SteamCloudSyncBlacklist.filterBaseline(
                baseline = SteamCloudBaselineStore.readSnapshot(host, authMaterial.steamId64),
                configuredBlacklist = syncBlacklist,
            )
            validateUploadPlanAgainstCurrentSnapshot(
                plan = plan,
                currentRemoteSnapshot = currentFilteredRemoteSnapshot,
                currentLocalEntries = currentLocalEntries,
            )
            ensureNotCancelled(shouldContinue)
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.PREPARING_UPLOAD,
                    completedFiles = 0,
                    totalFiles = totalOperations,
                    progressPercent = 20,
                )
            )
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.CREATING_UPLOAD_BATCH,
                    completedFiles = 0,
                    totalFiles = totalOperations,
                    progressPercent = 24,
                )
            )
            uploadBatch = client.beginUploadBatch(
                STEAM_CLOUD_APP_ID,
                plan.uploadCandidates.map { it.remotePath },
                plan.remoteDeleteCandidates.map { it.remotePath },
            )
            ensureNotCancelled(shouldContinue)

            val totalUploads = plan.uploadCandidates.size
            plan.uploadCandidates.forEachIndexed { index, candidate ->
                ensureNotCancelled(shouldContinue)
                val sourceFile = File(
                    sourceRoot,
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.REQUESTING_UPLOAD_SLOT,
                        completedFiles = index + 1,
                        totalFiles = totalOperations,
                        currentPath = candidate.localRelativePath,
                        progressPercent = 28 + ((index * 55) / totalUploads),
                    )
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
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}, batchId=${requireNotNull(uploadBatch).batchId}): ${summarizeErrorWithCauses(error)}",
                        error,
                    )
                }
                validateUploadedSnapshot(candidate, uploadedFile)
                ensureNotCancelled(shouldContinue)
                uploadedBytes += uploadedFile.fileSize
                uploadedFileCount = index + 1
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.UPLOADING,
                        completedFiles = index + 1,
                        totalFiles = totalOperations,
                        currentPath = candidate.localRelativePath,
                        progressPercent = 30 + (((index + 1) * 55) / totalUploads),
                    )
                )
            }
            reportProgress(
                progressCallback,
                SteamCloudSyncProgress(
                    direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                    phase = SteamCloudSyncPhase.FINALIZING,
                    completedFiles = totalOperations,
                    totalFiles = totalOperations,
                    progressPercent = 92,
                )
            )
            ensureNotCancelled(shouldContinue)
            if (plan.remoteDeleteCandidates.isNotEmpty()) {
                if (!allowSnapshotDeletes) {
                    liveSaveLease = SteamCloudLiveSaveLease.acquireForMutation(host)
                    plan.remoteDeleteCandidates.forEach { candidate ->
                        val localFile = File(
                            RuntimePaths.stsRoot(host),
                            candidate.localRelativePath.replace('/', File.separatorChar),
                        )
                        if (localFile.exists()) {
                            throw SteamCloudStalePlanException(
                                "Steam Cloud delete source was recreated before commit: " +
                                    candidate.localRelativePath
                            )
                        }
                    }
                }
                ensureNotCancelled(shouldContinue)
            }
            plan.remoteDeleteCandidates.forEach { candidate ->
                ensureNotCancelled(shouldContinue)
                client.deleteFile(
                    STEAM_CLOUD_APP_ID,
                    candidate.remotePath,
                    requireNotNull(uploadBatch).batchId,
                )
            }
            ensureNotCancelled(shouldContinue)
            remoteCommitMayHaveCompleted = true
            val completionRecoveredFromManifest = completeUploadBatchOrReconcile(
                client = client,
                batch = requireNotNull(uploadBatch),
                uploadCandidates = plan.uploadCandidates,
                deleteRemotePaths = plan.remoteDeleteCandidates.map { it.remotePath },
                shouldContinue = shouldContinue,
            )
            uploadBatch = null

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                steamId64 = authMaterial.steamId64,
            )
            SteamCloudManifestStore.writeSnapshot(host, refreshedSnapshot)
            SteamCloudAuthStore.recordManifestSuccess(host, refreshedSnapshot.fetchedAtMs)

            val result = SteamCloudPushResult(
                uploadedFileCount = plan.uploadCandidates.size,
                uploadedBytes = uploadedBytes,
                deletedRemoteFileCount = plan.remoteDeleteCandidates.size,
                completedAtMs = System.currentTimeMillis(),
                summaryPath = SteamCloudManifestStore.pushSummaryFile(host).absolutePath,
                warnings = plan.warnings + refreshedSnapshot.warnings,
            )
            val refreshedFilteredSnapshot = SteamCloudSyncBlacklist.filterManifestSnapshot(
                snapshot = refreshedSnapshot,
                configuredBlacklist = syncBlacklist,
            )
            val reconciledLocalEntries = SteamCloudSyncBlacklist.filterLocalEntries(
                entries = sourceEntries ?: SteamCloudLocalSnapshotCollector.collect(sourceRoot),
                configuredBlacklist = syncBlacklist,
            )
            val reconciledBaseline = buildReconciledBaseline(
                syncedAtMs = result.completedAtMs,
                priorBaseline = priorBaseline,
                preUploadLocalEntries = preUploadLocalEntries,
                preUploadRemoteSnapshot = currentFilteredRemoteSnapshot,
                currentLocalEntries = reconciledLocalEntries,
                currentRemoteSnapshot = refreshedFilteredSnapshot,
                uploadCandidates = plan.uploadCandidates,
                deleteCandidates = plan.remoteDeleteCandidates,
            )
            SteamCloudBaselineStore.writeSnapshot(
                host,
                reconciledBaseline,
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
                    "Deleted remote files: ${result.deletedRemoteFileCount}",
                    "Upload summary: ${result.summaryPath}",
                    "Manifest path: ${SteamCloudManifestStore.manifestFile(host).absolutePath}",
                    "Baseline path: ${SteamCloudBaselineStore.baselineFile(host).absolutePath}",
                    "Baseline source: ${if (sourceEntries == null) "live saves" else "background snapshot"}",
                    "Upload batch completion recovered from manifest: ${if (completionRecoveredFromManifest) "yes" else "no"}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            var uploadBatchCompletionError: Throwable? = null
            if (!remoteCommitMayHaveCompleted) uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }.onFailure { completionError ->
                    uploadBatchCompletionError = completionError
                }
            }
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                !remoteCommitMayHaveCompleted &&
                uploadedFileCount == 0 &&
                shouldContinue() &&
                isReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                client.close()
                return pushLocalChanges(
                    host = host,
                    authMaterial = authMaterial,
                    plan = plan,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                    sourceRoot = sourceRoot,
                    sourceEntries = sourceEntries,
                    allowSnapshotDeletes = allowSnapshotDeletes,
                )
            }
            val surfacedError = if (remoteCommitMayHaveCompleted) {
                asReconciliationFailure(error)
            } else {
                error
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(surfacedError), authMaterial)
            try {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "manual_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(surfacedError),
                    error = surfacedError,
                    extraLines = buildList {
                        add("Upload candidates before failure: ${plan.uploadCandidates.size}")
                        add("Remote delete candidates before failure: ${plan.remoteDeleteCandidates.size}")
                        add("Conflicts before failure: ${plan.conflicts.size}")
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                        uploadBatchCompletionError?.let { completionError ->
                            add("Upload batch failure completion failed: ${summarizeErrorWithCauses(completionError)}")
                        }
                        plan.warnings.forEach { warning -> add("Warning: $warning") }
                    },
                )
            } catch (diagnosticsError: Throwable) {
                surfacedError.addSuppressed(diagnosticsError)
            }
            throw surfacedError
        } finally {
            liveSaveLease?.close()
            client.close()
        }
    }

    @Throws(Exception::class)
    fun overwriteRemoteWithLocal(
        host: Context,
        authMaterial: SteamCloudAuthStore.SavedAuthMaterial,
        sourceRoot: File = RuntimePaths.stsRoot(host),
        progressCallback: ((SteamCloudSyncProgress) -> Unit)? = null,
        shouldContinue: () -> Boolean = { true },
        allowReconnectRetry: Boolean = true,
    ): SteamCloudPushResult {
        val startedAtMs = System.currentTimeMillis()
        val client = SteamCloudClient(host)
        var uploadBatch: SteamCloudClient.UploadBatch? = null
        var preparedPlan: PreparedMirrorPlan? = null
        var remoteCommitMayHaveCompleted = false
        var liveDeleteLease: SteamCloudLiveSaveLease.Lease? = null
        var uploadedBytes = 0L
        var uploadedFileCount = 0
        val sourceIsLiveRoot = sourceRoot.canonicalFile == RuntimePaths.stsRoot(host).canonicalFile

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
            client.logOnWithRefreshToken(
                authMaterial.accountName,
                authMaterial.refreshToken,
                authMaterial.steamId64,
            )
            ensureNotCancelled(shouldContinue)
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
                steamId64 = authMaterial.steamId64,
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
                        baseline = SteamCloudBaselineStore.readSnapshot(host, authMaterial.steamId64),
                        configuredBlacklist = syncBlacklist,
                    ),
                )
            )
            ensureNotCancelled(shouldContinue)
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
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.CREATING_UPLOAD_BATCH,
                        completedFiles = 0,
                        totalFiles = preparedPlan.uploadCandidates.size,
                        progressPercent = 29,
                    )
                )
                uploadBatch = client.beginUploadBatch(
                    STEAM_CLOUD_APP_ID,
                    preparedPlan.uploadCandidates.map { it.remotePath },
                    preparedPlan.deleteRemotePaths,
                )
                ensureNotCancelled(shouldContinue)
            }

            val totalUploads = preparedPlan.uploadCandidates.size
            preparedPlan.uploadCandidates.forEachIndexed { index, candidate ->
                ensureNotCancelled(shouldContinue)
                val sourceFile = File(
                    sourceRoot,
                    candidate.localRelativePath.replace('/', File.separatorChar)
                )
                reportProgress(
                    progressCallback,
                    SteamCloudSyncProgress(
                        direction = SteamCloudSyncDirection.PUSH_LOCAL_TO_CLOUD,
                        phase = SteamCloudSyncPhase.REQUESTING_UPLOAD_SLOT,
                        completedFiles = index + 1,
                        totalFiles = totalUploads,
                        currentPath = candidate.localRelativePath,
                        progressPercent = if (totalUploads <= 0) {
                            85
                        } else {
                            30 + ((index * 55) / totalUploads)
                        },
                    )
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
                        "Steam Cloud upload failed for ${candidate.remotePath} (${candidate.localRelativePath}, localSha1=${candidate.sha1.ifBlank { "<none>" }}, size=${candidate.fileSize}, batchId=${requireNotNull(uploadBatch).batchId}): ${summarizeErrorWithCauses(error)}",
                        error,
                    )
                }
                validateUploadedSnapshot(candidate, uploadedFile)
                ensureNotCancelled(shouldContinue)
                uploadedBytes += uploadedFile.fileSize
                uploadedFileCount = index + 1
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
            ensureNotCancelled(shouldContinue)
            var completionRecoveredFromManifest = false
            if (!remoteCommitMayHaveCompleted) uploadBatch?.let { batch ->
                if (sourceIsLiveRoot && preparedPlan.deleteRemotePaths.isNotEmpty()) {
                    liveDeleteLease = SteamCloudLiveSaveLease.acquireForMutation(host)
                    verifyMirrorDeleteSourcesRemainAbsent(sourceRoot, preparedPlan.deleteRemotePaths)
                    ensureNotCancelled(shouldContinue)
                }
                try {
                    preparedPlan.deleteRemotePaths.forEach { remotePath ->
                        ensureNotCancelled(shouldContinue)
                        client.deleteFile(STEAM_CLOUD_APP_ID, remotePath, batch.batchId)
                    }
                    ensureNotCancelled(shouldContinue)
                    remoteCommitMayHaveCompleted = true
                    completionRecoveredFromManifest = completeUploadBatchOrReconcile(
                        client = client,
                        batch = batch,
                        uploadCandidates = preparedPlan.uploadCandidates,
                        deleteRemotePaths = preparedPlan.deleteRemotePaths,
                        shouldContinue = shouldContinue,
                    )
                    uploadBatch = null
                } finally {
                    liveDeleteLease?.close()
                    liveDeleteLease = null
                }
            }

            val refreshedSnapshot = SteamCloudPathMapper.buildManifestSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                remoteEntries = client.listFiles(STEAM_CLOUD_APP_ID),
                steamId64 = authMaterial.steamId64,
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
                    ).entriesForPlanning,
                    steamId64 = authMaterial.steamId64,
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
                    "Upload batch completion recovered from manifest: ${if (completionRecoveredFromManifest) "yes" else "no"}",
                ) + result.warnings.distinct().map { "Warning: $it" },
            )
            return result
        } catch (error: Throwable) {
            var uploadBatchCompletionError: Throwable? = null
            if (!remoteCommitMayHaveCompleted) uploadBatch?.let { batch ->
                runCatching {
                    client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.Fail)
                }.onFailure { completionError ->
                    uploadBatchCompletionError = completionError
                }
            }
            val failureDiagnostics = client.snapshotDiagnostics()
            if (allowReconnectRetry &&
                !remoteCommitMayHaveCompleted &&
                uploadedFileCount == 0 &&
                shouldContinue() &&
                isReconnectRetryCandidate(error, failureDiagnostics)
            ) {
                SteamCloudNetworkEnvironment.clearNetworkCache(host)
                client.close()
                return overwriteRemoteWithLocal(
                    host = host,
                    authMaterial = authMaterial,
                    sourceRoot = sourceRoot,
                    progressCallback = progressCallback,
                    shouldContinue = shouldContinue,
                    allowReconnectRetry = false,
                )
            }
            val surfacedError = if (remoteCommitMayHaveCompleted) {
                asReconciliationFailure(error)
            } else {
                error
            }
            SteamCloudAuthStore.recordFailure(host, summarizeError(surfacedError), authMaterial)
            runCatching {
                SteamCloudDiagnosticsStore.writeSummary(
                    context = host,
                    operation = "force_push",
                    outcome = "FAILED",
                    accountName = authMaterial.accountName,
                    startedAtMs = startedAtMs,
                    completedAtMs = System.currentTimeMillis(),
                    diagnostics = failureDiagnostics,
                    failureSummary = summarizeError(surfacedError),
                    error = surfacedError,
                    extraLines = buildList {
                        addAll(describePreparedMirrorPlan(preparedPlan))
                        uploadBatch?.let { batch ->
                            add("Upload batch id: ${batch.batchId}")
                        }
                        uploadBatchCompletionError?.let { completionError ->
                            add("Upload batch failure completion failed: ${summarizeErrorWithCauses(completionError)}")
                        }
                    },
                )
            }
            throw surfacedError
        } finally {
            liveDeleteLease?.close()
            client.close()
        }
    }

    private fun writePushSummary(
        host: Context,
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
            if (plan.remoteDeleteCandidates.isNotEmpty()) {
                add("Deleted Remote Paths:")
                plan.remoteDeleteCandidates.forEach { candidate -> add(" - ${candidate.remotePath}") }
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
        host: Context,
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

    private fun completeUploadBatchOrReconcile(
        client: SteamCloudClient,
        batch: SteamCloudClient.UploadBatch,
        uploadCandidates: List<SteamCloudUploadCandidate>,
        deleteRemotePaths: List<String>,
        shouldContinue: () -> Boolean,
    ): Boolean {
        try {
            client.completeUploadBatch(STEAM_CLOUD_APP_ID, batch.batchId, EResult.OK)
            return false
        } catch (completionError: Throwable) {
            // completeUploadBatch already retried internally on EResult.Fail and other transient
            // codes.  If it still failed, check whether the changes landed anyway (the CM can
            // commit the batch server-side before returning a successful response, so the error
            // may be a false-negative in the protocol layer).
            for (attempt in 1..UPLOAD_BATCH_RECONCILIATION_MAX_ATTEMPTS) {
                ensureNotCancelled(shouldContinue)
                if (attempt > 1) {
                    val delayMs = UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES[
                        minOf(attempt - 2, UPLOAD_BATCH_RECONCILIATION_DELAY_MS_VALUES.size - 1)
                    ]
                    Thread.sleep(delayMs)
                }
                val remoteEntries = try {
                    client.listFiles(STEAM_CLOUD_APP_ID)
                } catch (manifestError: Throwable) {
                    completionError.addSuppressed(manifestError)
                    continue
                }
                if (uploadBatchChangesAreVisible(remoteEntries, uploadCandidates, deleteRemotePaths)) {
                    return true
                }
            }
            throw completionError
        }
    }

    internal fun uploadBatchChangesAreVisible(
        remoteEntries: List<SteamCloudClient.RemoteFileRecord>,
        uploadCandidates: List<SteamCloudUploadCandidate>,
        deleteRemotePaths: List<String>,
    ): Boolean {
        val entriesByPath = remoteEntries.associateBy { normalizeRemotePathKey(it.remotePath) }
        val uploadsVisible = uploadCandidates.all { candidate ->
            val remote = entriesByPath[normalizeRemotePathKey(candidate.remotePath)] ?: return@all false
            if (classifySteamCloudPersistState(remote.persistState) != SteamCloudPersistStateKind.LIVE) {
                return@all false
            }
            // Size must always match.
            if (remote.rawFileSize != candidate.fileSize) return@all false
            // A size-only match cannot prove that a committed batch contains the candidate.
            // Reconciliation must fail closed when either side lacks the SHA-1.
            val candidateSha1 = candidate.sha1.trim()
            val remoteSha1 = remote.sha1.trim()
            candidateSha1.isNotBlank() &&
                remoteSha1.isNotBlank() &&
                candidateSha1.equals(remoteSha1, ignoreCase = true)
        }
        val deletesVisible = deleteRemotePaths.all { remotePath ->
            val remote = entriesByPath[normalizeRemotePathKey(remotePath)]
            remote == null ||
                classifySteamCloudPersistState(remote.persistState) == SteamCloudPersistStateKind.TOMBSTONE
        }
        return uploadsVisible && deletesVisible
    }

    private fun verifyMirrorDeleteSourcesRemainAbsent(
        sourceRoot: File,
        deleteRemotePaths: List<String>,
    ) {
        deleteRemotePaths.forEach { remotePath ->
            val mappedPath = SteamCloudPathMapper.mapRemotePath(remotePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud delete path is no longer safe to map: $remotePath"
                )
            val source = File(
                sourceRoot,
                mappedPath.localRelativePath.replace('/', File.separatorChar),
            )
            if (source.exists()) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud delete source was recreated before commit: " +
                        mappedPath.localRelativePath
                )
            }
        }
    }

    /**
     * Validates a plan after the client has logged on and before an upload batch is created.
     *
     * A non-zero plan timestamp is tied to the manifest snapshot persisted while the plan was
     * built.  The newly fetched snapshot is compared with that snapshot by content identity,
     * rather than by its local fetch time, because every fresh fetch necessarily has a new local
     * timestamp.  Timestamp-zero plans remain usable for callers that construct plans manually,
     * but still receive all structural, local-file, and current-remote checks that are possible.
     */
    internal fun validateUploadPlanAgainstCurrentSnapshot(
        plan: SteamCloudUploadPlan,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        plannedRemoteSnapshot: SteamCloudManifestSnapshot? = null,
    ) {
        if (plan.remoteManifestFetchedAtMs < 0L) {
            throw SteamCloudStalePlanException(
                "Steam Cloud push plan is invalid: remote manifest timestamp is negative."
            )
        }
        if (plan.remoteManifestFetchedAtMs != 0L) {
            val plannedIdentity = plan.plannedRemoteManifestIdentity.trim()
            if (plannedIdentity.isBlank()) {
                val explicitSnapshot = plannedRemoteSnapshot
                    ?: throw SteamCloudStalePlanException(
                        "Steam Cloud push plan is stale: its remote manifest identity is unavailable."
                    )
                if (explicitSnapshot.fetchedAtMs != plan.remoteManifestFetchedAtMs) {
                    throw SteamCloudStalePlanException(
                        "Steam Cloud push plan is stale: planned remote manifest timestamp " +
                            "${plan.remoteManifestFetchedAtMs} is not the supplied snapshot timestamp " +
                            "${explicitSnapshot.fetchedAtMs}."
                    )
                }
                validateManifestSnapshotShape(explicitSnapshot, "planned")
            }
            validateManifestSnapshotShape(currentRemoteSnapshot, "current")
            val expectedIdentity = plannedIdentity.ifBlank {
                SteamCloudManifestIdentity.compute(requireNotNull(plannedRemoteSnapshot))
            }
            if (!expectedIdentity.equals(
                    SteamCloudManifestIdentity.compute(currentRemoteSnapshot),
                    ignoreCase = true,
                )) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: the remote manifest changed after the plan was built."
                )
            }
        }

        validateManifestSnapshotShape(currentRemoteSnapshot, "current")
        val hasDigestBackedManifestIdentity = plan.remoteManifestFetchedAtMs != 0L &&
            plan.plannedRemoteManifestIdentity.isNotBlank()
        val localByPath = indexLocalEntries(currentLocalEntries)
        val remoteByPath = indexManifestEntries(currentRemoteSnapshot.entries)
        val currentRemoteByPlanningPath = indexManifestEntries(currentRemoteSnapshot.entriesForPlanning)
        val plannedRemoteByPath = plannedRemoteSnapshot
            ?.entriesForPlanning
            ?.let(::indexManifestEntries)
            .orEmpty()
        val uploadPaths = mutableSetOf<String>()

        plan.uploadCandidates.forEach { candidate ->
            val mappedLocalPath = SteamCloudPathMapper.mapLocalRelativePath(candidate.localRelativePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload local path is not a supported safe path " +
                        "(${candidate.localRelativePath})."
                )
            if (mappedLocalPath.rootKind != candidate.rootKind ||
                mappedLocalPath.localRelativePath != candidate.localRelativePath
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload local path identity changed for " +
                        "${candidate.localRelativePath}."
                )
            }
            val mappedRemotePath = SteamCloudPathMapper.mapRemotePath(candidate.remotePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload remote path is not a supported safe path " +
                        "(${candidate.remotePath})."
                )
            if (mappedRemotePath.rootKind != candidate.rootKind ||
                mappedRemotePath.localRelativePath != candidate.localRelativePath ||
                normalizeRemotePathKey(candidate.remotePath) !=
                    normalizeRemotePathKey(requireNotNull(SteamCloudPathMapper.buildRemotePath(candidate.localRelativePath)))
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload path mapping changed for " +
                        "${candidate.localRelativePath}."
                )
            }
            if (!uploadPaths.add(normalizeRemotePathKey(candidate.remotePath))) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: duplicate upload target ${candidate.remotePath}."
                )
            }

            val currentLocal = localByPath[candidate.localRelativePath]
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload source is missing " +
                        "${candidate.localRelativePath}."
                )
            if (!localEntryMatchesUploadCandidate(currentLocal, candidate)) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload source changed " +
                        "${candidate.localRelativePath}."
                )
            }

            val plannedRemote = plannedRemoteByPath[candidate.localRelativePath]
            val currentRemote = remoteByPath[candidate.localRelativePath]
            val currentRemoteForPlanning = currentRemoteByPlanningPath[candidate.localRelativePath]
            when (candidate.kind) {
                SteamCloudUploadCandidateKind.NEW_FILE -> {
                    val plannedTombstone = plannedRemote?.takeIf { it.isTombstone }
                    val currentTombstone = currentRemote?.takeIf { it.isTombstone }
                    if (currentRemoteForPlanning?.isLive == true ||
                        (plannedTombstone != null &&
                            (currentTombstone == null ||
                                !manifestEntryHasSameIdentity(plannedTombstone, currentTombstone))) ||
                        (!hasDigestBackedManifestIdentity &&
                            plan.remoteManifestFetchedAtMs != 0L &&
                            plannedRemote != null &&
                            plannedTombstone == null)
                    ) {
                        throw SteamCloudStalePlanException(
                            "Steam Cloud push plan is stale: new upload target already exists " +
                                "${candidate.remotePath}."
                        )
                    }
                }

                SteamCloudUploadCandidateKind.MODIFIED_FILE -> {
                    if (currentRemote == null || !currentRemote.isLive ||
                        (!hasDigestBackedManifestIdentity &&
                            plan.remoteManifestFetchedAtMs != 0L &&
                            (plannedRemote == null || !manifestEntryHasSameIdentity(plannedRemote, currentRemote)))
                    ) {
                        throw SteamCloudStalePlanException(
                            "Steam Cloud push plan is stale: remote upload target changed " +
                                "${candidate.remotePath}."
                        )
                    }
                }
            }
        }

        val deletePaths = mutableSetOf<String>()
        plan.remoteDeleteCandidates.forEach { candidate ->
            if (candidate.rootKind != SteamCloudRootKind.SAVES) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete target is not a save " +
                        "(${candidate.remotePath})."
                )
            }
            val mappedLocalPath = SteamCloudPathMapper.mapLocalRelativePath(candidate.localRelativePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete local path is not a supported safe path " +
                        "(${candidate.localRelativePath})."
                )
            val mappedRemotePath = SteamCloudPathMapper.mapRemotePath(candidate.remotePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete remote path is not a supported safe path " +
                        "(${candidate.remotePath})."
                )
            if (mappedLocalPath.rootKind != candidate.rootKind ||
                mappedLocalPath.localRelativePath != candidate.localRelativePath ||
                mappedRemotePath.rootKind != candidate.rootKind ||
                mappedRemotePath.localRelativePath != candidate.localRelativePath ||
                normalizeRemotePathKey(candidate.remotePath) !=
                    normalizeRemotePathKey(requireNotNull(SteamCloudPathMapper.buildRemotePath(candidate.localRelativePath)))
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete path identity changed for " +
                        "${candidate.localRelativePath}."
                )
            }
            if (!deletePaths.add(normalizeRemotePathKey(candidate.remotePath))) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: duplicate delete target ${candidate.remotePath}."
                )
            }
            if (normalizeRemotePathKey(candidate.remotePath) in uploadPaths) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: upload and delete overlap at ${candidate.remotePath}."
                )
            }
            if (candidate.localRelativePath in localByPath) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete source was recreated " +
                        "${candidate.localRelativePath}."
                )
            }

            val plannedRemote = plannedRemoteByPath[candidate.localRelativePath]
                ?: if (!hasDigestBackedManifestIdentity && plan.remoteManifestFetchedAtMs != 0L) {
                    throw SteamCloudStalePlanException(
                        "Steam Cloud push plan is stale: delete target was not present in the planned manifest " +
                            "${candidate.remotePath}."
                    )
                } else {
                    null
                }
            val currentRemote = remoteByPath[candidate.localRelativePath]
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete target is no longer present " +
                        "${candidate.remotePath}."
                )
            if (normalizeRemotePathKey(currentRemote.remotePath) != normalizeRemotePathKey(candidate.remotePath) ||
                (plannedRemote != null && !manifestEntryHasSameIdentity(plannedRemote, currentRemote))
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud push plan is stale: delete target identity changed " +
                        "${candidate.remotePath}."
                )
            }
        }
    }

    /**
     * Builds the next baseline without treating a post-upload local snapshot as proof that all
     * paths are synced.  Uploaded paths are admitted only after a fresh local match; untouched
     * paths retain the previous baseline, or use the pre-upload matching pair when no baseline
     * exists yet.  A failure happens before the caller writes the returned baseline, so the old
     * file remains intact after a remote commit.
     */
    internal fun buildReconciledBaseline(
        syncedAtMs: Long,
        priorBaseline: SteamCloudSyncBaseline?,
        preUploadLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        preUploadRemoteSnapshot: SteamCloudManifestSnapshot,
        currentLocalEntries: List<SteamCloudLocalFileSnapshotEntry>,
        currentRemoteSnapshot: SteamCloudManifestSnapshot,
        uploadCandidates: List<SteamCloudUploadCandidate>,
        deleteCandidates: List<SteamCloudRemoteDeleteCandidate>,
    ): SteamCloudSyncBaseline {
        validateManifestSnapshotShape(preUploadRemoteSnapshot, "pre-upload")
        validateManifestSnapshotShape(currentRemoteSnapshot, "post-upload")
        val currentLocalByPath = indexLocalEntries(currentLocalEntries)
        val preUploadLocalByPath = indexLocalEntries(preUploadLocalEntries)
        val currentRemoteByPath = indexManifestEntries(currentRemoteSnapshot.entriesForPlanning)
        val preUploadRemoteByPath = indexManifestEntries(preUploadRemoteSnapshot.entriesForPlanning)
        val localBaselineByPath = priorBaseline?.localEntries
            ?.associateBy { it.localRelativePath }
            ?.toMutableMap()
            ?: linkedMapOf()
        val remoteBaselineByPath = priorBaseline?.remoteEntries
            ?.associateBy { it.localRelativePath }
            ?.toMutableMap()
            ?: linkedMapOf()
        val uploadPaths = uploadCandidates.mapTo(mutableSetOf()) { it.localRelativePath }
        val deletePaths = deleteCandidates.mapTo(mutableSetOf()) { it.localRelativePath }

        uploadCandidates.forEach { candidate ->
            val currentLocal = currentLocalByPath[candidate.localRelativePath]
                ?: throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: uploaded file disappeared " +
                        "${candidate.localRelativePath}; the previous baseline was retained."
                )
            if (!localEntryMatchesUploadCandidate(currentLocal, candidate)) {
                throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: uploaded file changed locally " +
                        "${candidate.localRelativePath}; the previous baseline was retained."
                )
            }
            val currentRemote = currentRemoteByPath[candidate.localRelativePath]
                ?: throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: uploaded remote file is missing " +
                        "${candidate.remotePath}; the previous baseline was retained."
                )
            if (!remoteEntryMatchesUploadCandidate(currentRemote, candidate)) {
                throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: uploaded remote file does not " +
                        "match ${candidate.remotePath}; the previous baseline was retained."
                )
            }
            localBaselineByPath[candidate.localRelativePath] = currentLocal
            remoteBaselineByPath[candidate.localRelativePath] = currentRemote
        }

        deleteCandidates.forEach { candidate ->
            if (candidate.localRelativePath in currentLocalByPath) {
                throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: deleted file was recreated locally " +
                        "${candidate.localRelativePath}; the previous baseline was retained."
                )
            }
            val currentRemote = currentRemoteByPath[candidate.localRelativePath]
            if (currentRemote?.isLive == true) {
                throw SteamCloudPushReconciliationException(
                    "Steam Cloud reconciliation failed after remote commit: delete target remains remote " +
                        "${candidate.remotePath}; the previous baseline was retained."
                )
            }
            localBaselineByPath.remove(candidate.localRelativePath)
            if (currentRemote?.isTombstone == true) {
                remoteBaselineByPath[candidate.localRelativePath] = currentRemote
            } else {
                remoteBaselineByPath.remove(candidate.localRelativePath)
            }
        }

        if (priorBaseline == null) {
            preUploadLocalByPath.forEach { (localPath, localEntry) ->
                if (localPath !in uploadPaths && localPath !in deletePaths) {
                    val preUploadRemote = preUploadRemoteByPath[localPath]
                    val currentLocal = currentLocalByPath[localPath]
                    val currentRemote = currentRemoteByPath[localPath]
                    if (preUploadRemote != null &&
                        currentLocal != null &&
                        currentRemote != null &&
                        localEntriesHaveSameIdentity(localEntry, currentLocal) &&
                        manifestEntryHasSameIdentity(preUploadRemote, currentRemote) &&
                        localEntryMatchesRemoteForBaseline(currentLocal, currentRemote)
                    ) {
                        localBaselineByPath[localPath] = currentLocal
                        remoteBaselineByPath[localPath] = currentRemote
                    }
                }
            }
        }

        return SteamCloudSyncBaseline(
            syncedAtMs = syncedAtMs,
            localEntries = localBaselineByPath.values.sortedWith(localEntryComparator()),
            remoteEntries = remoteBaselineByPath.values.sortedWith(remoteEntryComparator()),
            steamId64 = currentRemoteSnapshot.steamId64,
        )
    }

    private fun validateManifestSnapshotShape(
        snapshot: SteamCloudManifestSnapshot,
        label: String,
    ) {
        if (snapshot.fileCount != snapshot.entries.size ||
            snapshot.preferencesCount != snapshot.entries.count { it.rootKind == SteamCloudRootKind.PREFERENCES } ||
            snapshot.savesCount != snapshot.entries.count { it.rootKind == SteamCloudRootKind.SAVES }
        ) {
            throw SteamCloudStalePlanException(
                "Steam Cloud $label manifest snapshot has inconsistent file counts."
            )
        }

        val remotePaths = mutableSetOf<String>()
        val localPaths = mutableSetOf<String>()
        snapshot.entriesForPlanning.forEach { entry ->
            val mappedPath = SteamCloudPathMapper.mapRemotePath(entry.remotePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud $label manifest contains an unsupported remote path " +
                        "${entry.remotePath}."
                )
            if (mappedPath.rootKind != entry.rootKind ||
                mappedPath.localRelativePath != entry.localRelativePath
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud $label manifest path identity is inconsistent for ${entry.remotePath}."
                )
            }
            if (!remotePaths.add(normalizeRemotePathKey(entry.remotePath)) ||
                !localPaths.add(entry.localRelativePath)
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud $label manifest contains duplicate path identity for ${entry.remotePath}."
                )
            }
        }
    }

    private fun indexLocalEntries(
        entries: List<SteamCloudLocalFileSnapshotEntry>,
    ): Map<String, SteamCloudLocalFileSnapshotEntry> {
        val indexed = linkedMapOf<String, SteamCloudLocalFileSnapshotEntry>()
        entries.forEach { entry ->
            val mappedPath = SteamCloudPathMapper.mapLocalRelativePath(entry.localRelativePath)
                ?: throw SteamCloudStalePlanException(
                    "Steam Cloud local snapshot contains an unsupported path ${entry.localRelativePath}."
                )
            if (mappedPath.rootKind != entry.rootKind ||
                mappedPath.localRelativePath != entry.localRelativePath
            ) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud local snapshot path identity is inconsistent for ${entry.localRelativePath}."
                )
            }
            if (indexed.put(entry.localRelativePath, entry) != null) {
                throw SteamCloudStalePlanException(
                    "Steam Cloud local snapshot contains duplicate path identity ${entry.localRelativePath}."
                )
            }
        }
        return indexed
    }

    private fun indexManifestEntries(
        entries: List<SteamCloudManifestEntry>,
    ): Map<String, SteamCloudManifestEntry> = entries.associateBy { it.localRelativePath }

    private fun manifestEntriesHaveSameIdentity(
        left: List<SteamCloudManifestEntry>,
        right: List<SteamCloudManifestEntry>,
    ): Boolean {
        val leftByPath = indexManifestEntries(left)
        val rightByPath = indexManifestEntries(right)
        return leftByPath.keys == rightByPath.keys &&
            leftByPath.all { (localPath, leftEntry) ->
                rightByPath[localPath]?.let { rightEntry ->
                    manifestEntryHasSameIdentity(leftEntry, rightEntry)
                } == true
            }
    }

    private fun manifestEntryHasSameIdentity(
        left: SteamCloudManifestEntry,
        right: SteamCloudManifestEntry,
    ): Boolean {
        if (normalizeRemotePathKey(left.remotePath) != normalizeRemotePathKey(right.remotePath) ||
            left.localRelativePath != right.localRelativePath ||
            left.rootKind != right.rootKind ||
            left.rawSize != right.rawSize ||
            left.timestamp != right.timestamp ||
            !left.machineName.trim().equals(right.machineName.trim(), ignoreCase = true) ||
            !steamCloudPersistStatesMatch(left.persistState, right.persistState)
        ) {
            return false
        }
        val leftSha1 = left.sha1.trim()
        val rightSha1 = right.sha1.trim()
        return when {
            leftSha1.isBlank() && rightSha1.isBlank() -> true
            leftSha1.isBlank() || rightSha1.isBlank() -> false
            else -> leftSha1.equals(rightSha1, ignoreCase = true)
        }
    }

    private fun localEntriesHaveSameIdentity(
        left: SteamCloudLocalFileSnapshotEntry,
        right: SteamCloudLocalFileSnapshotEntry,
    ): Boolean = left.localRelativePath == right.localRelativePath &&
        left.rootKind == right.rootKind &&
        left.fileSize == right.fileSize &&
        left.lastModifiedMs == right.lastModifiedMs &&
        left.sha256.equals(right.sha256, ignoreCase = true) &&
        hashesMatchWhenKnown(left.sha1, right.sha1)

    private fun localEntryMatchesUploadCandidate(
        current: SteamCloudLocalFileSnapshotEntry,
        candidate: SteamCloudUploadCandidate,
    ): Boolean {
        if (current.localRelativePath != candidate.localRelativePath ||
            current.rootKind != candidate.rootKind ||
            current.fileSize != candidate.fileSize ||
            current.lastModifiedMs != candidate.lastModifiedMs
        ) {
            return false
        }
        val candidateSha256 = candidate.sha256.trim()
        if (candidateSha256.isNotBlank() &&
            !candidateSha256.equals(current.sha256.trim(), ignoreCase = true)
        ) {
            return false
        }
        val candidateSha1 = candidate.sha1.trim()
        if (candidateSha1.isNotBlank() &&
            !candidateSha1.equals(current.sha1.trim(), ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun snapshotEntryMatchesUploadCandidate(
        current: SteamCloudLocalFileSnapshotEntry,
        candidate: SteamCloudUploadCandidate,
    ): Boolean {
        if (current.localRelativePath != candidate.localRelativePath ||
            current.rootKind != candidate.rootKind ||
            current.fileSize != candidate.fileSize
        ) {
            return false
        }
        return current.sha256.trim().equals(candidate.sha256.trim(), ignoreCase = true) &&
            current.sha1.trim().isNotBlank() &&
            candidate.sha1.trim().isNotBlank() &&
            current.sha1.trim().equals(candidate.sha1.trim(), ignoreCase = true)
    }

    private fun validateUploadedSnapshot(
        candidate: SteamCloudUploadCandidate,
        uploadedFile: SteamCloudClient.UploadedFile,
    ) {
        val plannedSha1 = candidate.sha1.trim()
        val uploadedSha1 = uploadedFile.sha1Hex.trim()
        if (uploadedFile.fileSize != candidate.fileSize ||
            plannedSha1.isBlank() ||
            uploadedSha1.isBlank() ||
            !plannedSha1.equals(uploadedSha1, ignoreCase = true)
        ) {
            throw SteamCloudStalePlanException(
                "Steam Cloud upload source changed after planning: ${candidate.localRelativePath} " +
                    "(plannedSize=${candidate.fileSize}, uploadedSize=${uploadedFile.fileSize}, " +
                    "plannedSha1=${plannedSha1.ifBlank { "<missing>" }}, " +
                    "uploadedSha1=${uploadedSha1.ifBlank { "<missing>" }})."
            )
        }
    }

    private fun remoteEntryMatchesUploadCandidate(
        current: SteamCloudManifestEntry,
        candidate: SteamCloudUploadCandidate,
    ): Boolean {
        if (normalizeRemotePathKey(current.remotePath) != normalizeRemotePathKey(candidate.remotePath) ||
            current.localRelativePath != candidate.localRelativePath ||
            current.rootKind != candidate.rootKind ||
            current.rawSize != candidate.fileSize
        ) {
            return false
        }
        val candidateSha1 = candidate.sha1.trim()
        val currentSha1 = current.sha1.trim()
        return candidateSha1.isBlank() ||
            (currentSha1.isNotBlank() && candidateSha1.equals(currentSha1, ignoreCase = true))
    }

    private fun localEntryMatchesRemoteForBaseline(
        local: SteamCloudLocalFileSnapshotEntry,
        remote: SteamCloudManifestEntry,
    ): Boolean {
        if (local.localRelativePath != remote.localRelativePath ||
            local.rootKind != remote.rootKind ||
            local.fileSize != remote.rawSize
        ) {
            return false
        }
        val localSha1 = local.sha1.trim()
        val remoteSha1 = remote.sha1.trim()
        return localSha1.isNotBlank() &&
            remoteSha1.isNotBlank() &&
            localSha1.equals(remoteSha1, ignoreCase = true)
    }

    private fun hashesMatchWhenKnown(left: String, right: String): Boolean {
        val leftHash = left.trim()
        val rightHash = right.trim()
        return leftHash.isBlank() ||
            rightHash.isBlank() ||
            leftHash.equals(rightHash, ignoreCase = true)
    }

    private fun localEntryComparator(): Comparator<SteamCloudLocalFileSnapshotEntry> =
        compareBy({ it.localRelativePath.lowercase(Locale.ROOT) }, { it.localRelativePath })

    private fun remoteEntryComparator(): Comparator<SteamCloudManifestEntry> =
        compareBy({ it.localRelativePath.lowercase(Locale.ROOT) }, { it.localRelativePath })

    private fun asReconciliationFailure(error: Throwable): SteamCloudPushReconciliationException {
        if (error is SteamCloudPushReconciliationException) {
            return error
        }
        return SteamCloudPushReconciliationException(
            "Steam Cloud reconciliation failed after remote commit: ${summarizeError(error)}; " +
                "the persisted manifest or baseline may require verification before retrying.",
            error,
        )
    }

    private fun ensureNotCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) {
            throw CancellationException("Steam Cloud sync cancelled by user.")
        }
    }

    private fun SteamCloudUploadPlan.isAlreadySynced(): Boolean =
        conflicts.isEmpty() &&
            uploadCandidates.isEmpty() &&
            remoteDeleteCandidates.isEmpty() &&
            remoteOnlyChanges.isEmpty()

    private fun SteamCloudUploadPlan.syncOperationCount(): Int =
        uploadCandidates.size + remoteDeleteCandidates.size

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

    private fun summarizeErrorWithCauses(error: Throwable): String {
        return generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .take(8)
            .joinToString(" <- ") { current ->
                val message = current.message?.trim().orEmpty()
                if (message.isNotEmpty()) {
                    "${current.javaClass.simpleName}: $message"
                } else {
                    current.javaClass.simpleName
                }
            }
    }

    private fun isReconnectRetryCandidate(
        error: Throwable,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
    ): Boolean {
        var sawReconnectableStage = diagnostics?.currentStage
            .orEmpty()
            .lowercase(Locale.US)
            .let { stage ->
                stage.contains("beginappuploadbatch") ||
                    stage.contains("beginhttpupload") ||
                    stage.contains("commithttpupload") ||
                    stage.contains("completeappuploadbatch") ||
                    stage.contains("getappfilechangelist")
            }
        var sawReconnectFailure = diagnostics?.disconnectedDescription
            .orEmpty()
            .lowercase(Locale.US)
            .let { description ->
                description.contains("unexpected") || description.contains("transport abort")
            }
        diagnostics?.diagnosticEventLines.orEmpty().forEach { eventLine ->
            val normalized = eventLine.lowercase(Locale.US)
            sawReconnectableStage = sawReconnectableStage ||
                normalized.contains("begin_app_upload_batch") ||
                normalized.contains("begin_http_upload") ||
                normalized.contains("http_upload") ||
                normalized.contains("upload_file failed") ||
                normalized.contains("getappfilechangelist")
            sawReconnectFailure = sawReconnectFailure ||
                normalized.contains("disconnected_callback reason=unexpected") ||
                normalized.contains("transport_abort_log") ||
                normalized.contains("software caused connection abort") ||
                normalized.contains("connection reset") ||
                normalized.contains("broken pipe")
        }
        var current: Throwable? = error
        while (current != null) {
            val normalized = current.message.orEmpty().lowercase(Locale.US)
            sawReconnectableStage = sawReconnectableStage ||
                normalized.contains("beginappuploadbatch") ||
                normalized.contains("beginhttpupload") ||
                normalized.contains("commithttpupload") ||
                normalized.contains("completeappuploadbatch") ||
                normalized.contains("getappfilechangelist")
            if ((normalized.contains("steam disconnected") && normalized.contains("unexpected")) ||
                normalized.contains("client or session is no longer active") ||
                normalized.contains("software caused connection abort") ||
                normalized.contains("connection reset") ||
                normalized.contains("broken pipe") ||
                current is SocketException
            ) {
                sawReconnectFailure = true
            }
            current = current.cause
        }
        return sawReconnectableStage && sawReconnectFailure
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
