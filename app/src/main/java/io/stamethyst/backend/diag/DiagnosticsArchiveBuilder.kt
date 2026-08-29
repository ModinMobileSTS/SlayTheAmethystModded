package io.stamethyst.backend.diag

import android.content.Context
import android.net.Uri
import android.os.Build
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.easytier.EasyTierConfigRepository
import io.stamethyst.backend.easytier.EasyTierDiagnosticsStore
import io.stamethyst.backend.easytier.EasyTierStateStore
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.crash.SignalCrashDumpReader
import io.stamethyst.backend.launch.JvmLogRotationManager
import io.stamethyst.backend.steamcloud.SteamCloudDiagnosticsStore
import io.stamethyst.backend.steamcloud.SteamCloudManifestStore
import io.stamethyst.backend.steamcloud.SteamGamePresenceDiagnosticsStore
import io.stamethyst.backend.workshop.WorkshopAutoImportPatchLogStore
import io.stamethyst.backend.workshop.WorkshopBrowseFailureLogStore
import io.stamethyst.backend.workshop.WorkshopDownloadLogService
import io.stamethyst.backend.workshop.WorkshopDownloadTaskRecord
import io.stamethyst.backend.workshop.WorkshopDownloadTaskStore
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class CrashArchiveContext(
    val code: Int,
    val isSignal: Boolean,
    val detail: String?
)

internal data class DiagnosticsArchiveResult(
    val archiveFile: File,
    val entryCount: Int
)

internal object DiagnosticsArchiveBuilder {
    private const val SHARE_DIR_NAME = "share"
    private const val MAX_WORKSHOP_DOWNLOAD_TASKS_IN_ARCHIVE = 10
    private const val MAX_JVM_HISTOGRAMS_IN_PERFORMANCE_ARCHIVE = 10

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    fun buildCrashExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-crash-report-${formatter.format(Date())}.zip"
    }

    fun buildPerformanceExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-performance-logs-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    fun createJvmLogShareArchive(context: Context): DiagnosticsArchiveResult {
        val archiveFile = allocateShareArchiveFile(context, buildJvmLogExportFileName())
        val entryCount = FileOutputStream(archiveFile, false).use { output ->
            writeDiagnosticsBundle(context, output, null)
        }
        return DiagnosticsArchiveResult(archiveFile, entryCount)
    }

    @Throws(IOException::class)
    fun createCrashShareArchive(
        context: Context,
        crashContext: CrashArchiveContext
    ): DiagnosticsArchiveResult {
        val archiveFile = allocateShareArchiveFile(context, buildCrashExportFileName())
        val entryCount = FileOutputStream(archiveFile, false).use { output ->
            writeDiagnosticsBundle(context, output, crashContext)
        }
        return DiagnosticsArchiveResult(archiveFile, entryCount)
    }

    @Throws(IOException::class)
    fun createPerformanceShareArchive(context: Context): DiagnosticsArchiveResult {
        val archiveFile = allocateShareArchiveFile(context, buildPerformanceExportFileName())
        val entryCount = FileOutputStream(archiveFile, false).use { output ->
            writePerformanceDiagnosticsBundle(context, output)
        }
        return DiagnosticsArchiveResult(archiveFile, entryCount)
    }

    /**
     * Called by [io.stamethyst.backend.diag.DiagnosticsProcessService.runAdbStage] via the
     * adb staging path. Uses the same bundle as Share Logs / exportJvmLogBundle.
     */
    @Throws(IOException::class)
    fun writeDiagnosticsBundlePublic(context: Context, output: OutputStream): Int =
        writeDiagnosticsBundle(context, output, null)

    @Throws(IOException::class)
    fun exportJvmLogBundle(context: Context, destination: Uri): Int {
        context.contentResolver.openOutputStream(destination).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            return writeDiagnosticsBundle(context, output, null)
        }
    }

    @Throws(IOException::class)
    fun exportPerformanceDiagnosticsBundle(context: Context, destination: Uri): Int {
        context.contentResolver.openOutputStream(destination).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            return writePerformanceDiagnosticsBundle(context, output)
        }
    }

    @Throws(IOException::class)
    internal fun writePerformanceDiagnosticsBundle(context: Context, output: OutputStream): Int {
        var exportedCount = 0
        ZipOutputStream(output).use { zipOutput ->
            exportedCount += writeTextEntryAndCount(
                zipOutput,
                "sts/performance/readme.txt",
                "Performance diagnostics captured on-device. Missing files were not generated in the latest session.\n"
            )
            exportedCount += writeTextEntryAndCount(
                zipOutput,
                "sts/performance/device_info.txt",
                buildJvmLogDeviceInfo(context)
            )
            exportedCount += writeTextEntryAndCount(
                zipOutput,
                "sts/performance/launcher_settings.txt",
                runCatching {
                    LauncherSettingsDiagnosticsFormatter.buildFromContext(context)
                }.getOrElse { error ->
                    "launcher_settings_unavailable=${error.javaClass.simpleName}: ${error.message.orEmpty()}\n"
                }
            )
            val files = listOf(
                RuntimePaths.frameProbeIncidents(context),
                RuntimePaths.frameProbePreviousIncidents(context),
                RuntimePaths.latestLog(context),
                RuntimePaths.jvmGcLog(context),
                RuntimePaths.jvmHeapSnapshot(context),
                RuntimePaths.launcherPerfSnapshot(context),
            RuntimePaths.performanceLaunchAuditLog(context),
            RuntimePaths.arthasBridgeLog(context),
            )
            files.forEach { file ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    file,
                    "sts/performance/${file.name}"
                )
            }
            RuntimePaths.listMemoryDiagnosticsFiles(context).forEach { file ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    file,
                    "sts/performance/memory_diagnostics/${file.name}"
                )
            }
            exportedCount += writeOptionalDirectoryFiles(
                zipOutput,
                RuntimePaths.jvmHistogramsDir(context),
                "sts/performance/jvm_histograms",
                limit = MAX_JVM_HISTOGRAMS_IN_PERFORMANCE_ARCHIVE,
                predicate = { it.name.endsWith(".txt", ignoreCase = true) }
            )
            exportedCount += writeOptionalDirectoryFiles(
                zipOutput,
                RuntimePaths.offlineArthasOutputDir(context),
                "sts/performance/arthas",
                predicate = { file ->
                    file.name.endsWith(".txt", ignoreCase = true) ||
                        file.name.endsWith(".log", ignoreCase = true)
                }
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeDiagnosticsBundle(
        context: Context,
        output: OutputStream,
        crashContext: CrashArchiveContext?
    ): Int {
        var exportedCount = 0
        ZipOutputStream(output).use { zipOutput ->
            writeTextEntry(
                zipOutput,
                "sts/readme.txt",
                buildArchiveReadme()
            )
            writeTextEntry(
                zipOutput,
                "sts/info/device_info.txt",
                buildJvmLogDeviceInfo(context)
            )
            val latestCrashSummary = LatestLogCrashDetector.detect(context)
            val lastNonBlankLogLine = LatestLogCrashDetector.readLastNonBlankLine(context)
            val processExitTrace = ProcessExitInfoCapture.readLatestInterestingProcessExitTrace(context)
            writeTextEntry(
                zipOutput,
                "sts/logs/latest_log_summary.txt",
                DiagnosticsSummaryFormatter.buildLatestLogSummary(
                    latestCrash = latestCrashSummary,
                    lastNonBlankLine = lastNonBlankLogLine
                )
            )
            processExitTrace?.let { traceText ->
                writeTextEntry(
                    zipOutput,
                    "sts/logs/process_exit_trace.txt",
                    traceText
                )
            }
            writeTextEntry(
                zipOutput,
                "sts/info/launcher_settings.txt",
                LauncherSettingsDiagnosticsFormatter.buildFromContext(context)
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                SteamCloudDiagnosticsStore.summaryFile(context),
                "sts/steam_cloud/phase1/${SteamCloudDiagnosticsStore.summaryFile(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                SteamGamePresenceDiagnosticsStore.summaryFile(context),
                "sts/steam-game-presence/${SteamGamePresenceDiagnosticsStore.summaryFile(context).name}"
            )
            SteamGamePresenceDiagnosticsStore.listEventLogFiles(context).forEach { file ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    file,
                    "sts/steam-game-presence/${file.name}"
                )
            }
            exportedCount += writeOptionalFile(
                zipOutput,
                SteamCloudManifestStore.manifestFile(context),
                "sts/steam_cloud/phase1/${SteamCloudManifestStore.manifestFile(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                SteamCloudManifestStore.pullSummaryFile(context),
                "sts/steam_cloud/phase1/${SteamCloudManifestStore.pullSummaryFile(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                File(SteamCloudManifestStore.outputDir(context), "last-websocket-cm-endpoint.txt"),
                "sts/steam_cloud/phase1/last-websocket-cm-endpoint.txt"
            )
            exportedCount += writeOptionalDirectoryFiles(
                zipOutput,
                SteamCloudDiagnosticsStore.failureHistoryDir(context),
                "sts/steam_cloud/phase1/failures"
            )
            exportedCount += writeOptionalDirectoryFiles(
                zipOutput,
                SteamCloudDiagnosticsStore.loginHistoryDir(context),
                "sts/steam_cloud/phase1/login-history"
            )
            exportedCount += writeOptionalDirectoryFiles(
                zipOutput,
                SteamCloudDiagnosticsStore.loginHistoryDir(context),
                "sts/steam_login",
                limit = 5
            )
            val writtenJvmEntries = LinkedHashSet<String>()
            JvmLogRotationManager.listLogFiles(context).forEach { logFile ->
                val entryName = "sts/logs/${logFile.name}"
                if (writtenJvmEntries.add(entryName) && logFile.isFile) {
                    writeFileToZip(zipOutput, logFile, entryName)
                    exportedCount++
                }
            }

            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.bootBridgeEventsLog(context),
                "sts/logs/${RuntimePaths.bootBridgeEventsLog(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmGcLog(context),
                "sts/logs/${RuntimePaths.jvmGcLog(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmHeapSnapshot(context),
                "sts/logs/${RuntimePaths.jvmHeapSnapshot(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmSignalDump(context),
                "sts/logs/${RuntimePaths.jvmSignalDump(context).name}"
            )
            RuntimePaths.listMemoryDiagnosticsFiles(context).forEach { memoryLogFile ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    memoryLogFile,
                    "sts/memory_diagnostics/${memoryLogFile.name}"
                )
            }
            exportedCount += writeAchievementSyncLogsForArchive(zipOutput, context)
            exportedCount += writeWindowDiagnosticsForArchive(zipOutput, context)
            RuntimePaths.listLogcatCaptureFiles(context)
                .groupBy { if (it.name.contains("system")) "system" else "app" }
                .values
                .flatMap { it.take(5) }
                .forEach { logcatFile ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    logcatFile,
                    "sts/logcat/${if (logcatFile.name.contains("system")) "system" else "app"}/${logcatFile.name}"
                )
            }
            RuntimePaths.listLauncherLogcatCaptureFiles(context)
                .groupBy { if (it.name.contains("system")) "system" else "app" }
                .values
                .flatMap { it.take(5) }
                .forEach { logcatFile ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    logcatFile,
                    "sts/logcat/${if (logcatFile.name.contains("system")) "system" else "app"}/${logcatFile.name}"
                )
            }
            exportedCount += writeLauncherCrashReportsForArchive(zipOutput, context)

            exportedCount += writeWorkshopDownloadDiagnostics(zipOutput, context)
            exportedCount += writeWorkshopBrowseFailureLogsForArchive(zipOutput, context)
            exportedCount += writeWorkshopAutoImportPatchLogsForArchive(zipOutput, context)
            exportedCount += writeEasyTierDiagnosticsForArchive(zipOutput, context)

            if (crashContext != null) {
                writeTextEntry(
                    zipOutput,
                    "sts/crash/summary.txt",
                    buildCrashSummary(context, crashContext)
                )
            }

        }
        return exportedCount
    }

    private fun buildArchiveReadme(): String = """
        Slay the Amethyst 诊断日志包

        目录说明：
        - easytier/：EasyTier 配置、当前状态，以及最近 5 条断开/重连/失败记录（含 :easytier 进程退出原因）。
        - feedback/：反馈提交所需的 issue 内容、请求信息和日志摘要；该目录保持反馈包原结构。
        - info/：设备信息和启动器设置。
        - logs/：JVM 日志及启动桥接、GC、堆快照、信号转储等启动器日志，JVM 日志最多保留 5 槽位。
        - achievement_sync/：成就请求解析、游戏内弹窗、Steam 查询、上传及失败事件，最多保留 3 槽位，不包含 Steam 凭据。
        - memory_diagnostics/：内存压力和内存诊断日志，最多保留 5 槽位。
        - window/：游戏窗口、viewport、Surface、尺寸同步和触控坐标映射诊断日志，最多保留 3 槽位。
        - logcat/app/：应用进程 logcat，最多 5 槽位。
        - logcat/system/：系统进程 logcat，最多 5 槽位。
        - launcher_crash_reports/：启动器崩溃报告，最多 5 槽位。
        - steam_login/：Steam credentials 登录失败记录，最多 5 槽位。
        - steam_cloud/：Steam Cloud 操作、失败历史和协议诊断信息。
        - steam-game-presence/：Steam 在线状态上报的最后摘要和连续事件日志，最多保留 3 槽位。
        - workshop/market_failed/：Workshop 市场查询失败日志，最多 5 槽位。
        - workshop/download_tasks/：最近 10 条 Workshop 下载任务日志。
        - workshop/auto_import_patch_logs/：自动导入补丁日志，最多 10 槽位。
        - crash/：本次崩溃归档的摘要（仅崩溃报告包包含）。

        已从导出包移除：jvm_histograms/、performance_launch_audit.log、process_exit_info.txt。
        某些目录或文件在没有对应事件时不会生成。
    """.trimIndent() + "\n"

    private fun buildCrashSummary(
        context: Context,
        crashContext: CrashArchiveContext
    ): String {
        val exitSummary = ProcessExitInfoCapture.peekLatestInterestingProcessExitInfo(context)
        val processExitTraceSummary = ProcessExitInfoCapture.summarizeProcessExitTrace(
            ProcessExitInfoCapture.readLatestInterestingProcessExitTrace(context)
        )
        val signalDumpSummary = SignalCrashDumpReader.readSummary(context)
        return buildString {
            append("crash.code=").append(crashContext.code).append('\n')
            append("crash.isSignal=").append(crashContext.isSignal).append('\n')
            append("crash.detail=")
            append(crashContext.detail?.trim().takeUnless { it.isNullOrEmpty() } ?: "none")
            append('\n').append('\n')
            append(
                DiagnosticsSummaryFormatter.buildProcessExitInfoSummary(
                    exitSummary = exitSummary,
                    signalDumpSummary = signalDumpSummary,
                    processExitTraceSummary = processExitTraceSummary
                )
            )
        }
    }

    @Throws(IOException::class)
    private fun writeWorkshopDownloadDiagnostics(
        zipOutput: ZipOutputStream,
        context: Context
    ): Int {
        val tasks = try {
            WorkshopDownloadTaskStore(context).list()
        } catch (error: Throwable) {
            writeTextEntry(
                zipOutput,
                "sts/workshop/download_tasks/read_error.txt",
                buildString {
                    append("Failed to read workshop download task logs.\n")
                    append(error.javaClass.name)
                    error.message?.trim()?.takeIf { it.isNotEmpty() }?.let { message ->
                        append(": ").append(message)
                    }
                    append('\n')
                }
            )
            return 1
        }
        if (tasks.isEmpty()) {
            return 0
        }

        val sortedTasks = tasks.sortedByDescending { it.updatedAtMillis }
            .take(MAX_WORKSHOP_DOWNLOAD_TASKS_IN_ARCHIVE)
        writeTextEntry(
            zipOutput,
            "sts/workshop/download_tasks/index.txt",
            buildWorkshopDownloadTaskIndex(sortedTasks)
        )

        var exportedCount = 0
        sortedTasks.forEach { task ->
            val archiveFileName = WorkshopDownloadLogService.fileName(task)
            writeTextEntry(
                zipOutput,
                "sts/workshop/download_tasks/$archiveFileName",
                WorkshopDownloadLogService.buildLogText(task)
            )
            exportedCount++

            exportedCount += writeOptionalFile(
                zipOutput,
                rawWorkshopDownloadLogFile(context, task),
                "sts/workshop/raw_download_logs/${rawWorkshopDownloadLogArchiveName(task)}"
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeWorkshopBrowseFailureLogsForArchive(
        zipOutput: ZipOutputStream,
        context: Context
    ): Int {
        val logFiles = WorkshopBrowseFailureLogStore.listLogFiles(context)
        if (logFiles.isEmpty()) {
            return 0
        }
        writeTextEntry(
            zipOutput,
            "sts/workshop/market_failed/index.txt",
            buildWorkshopBrowseFailureLogIndex(logFiles)
        )
        var exportedCount = 0
        logFiles.forEach { logFile ->
            exportedCount += writeOptionalFile(
                zipOutput,
                logFile,
                "sts/workshop/market_failed/${logFile.name}"
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeWorkshopAutoImportPatchLogsForArchive(
        zipOutput: ZipOutputStream,
        context: Context
    ): Int {
        val logFiles = WorkshopAutoImportPatchLogStore.listLogFiles(context)
        if (logFiles.isEmpty()) {
            return 0
        }
        writeTextEntry(
            zipOutput,
            "sts/workshop/auto_import_patch_logs/index.txt",
            buildWorkshopAutoImportPatchLogIndex(logFiles)
        )
        var exportedCount = 0
        logFiles.forEach { logFile ->
            exportedCount += writeOptionalFile(
                zipOutput,
                logFile,
                "sts/workshop/auto_import_patch_logs/${logFile.name}"
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeLauncherCrashReportsForArchive(
        zipOutput: ZipOutputStream,
        context: Context
    ): Int {
        val reportFiles = RuntimePaths.listLauncherCrashReportFiles(context)
        if (reportFiles.isEmpty()) {
            return 0
        }
        writeTextEntry(
            zipOutput,
            "sts/launcher_crash_reports/index.txt",
            buildLauncherCrashReportIndex(context, reportFiles)
        )
        var exportedCount = 0
        reportFiles.forEach { reportFile ->
            exportedCount += writeOptionalFile(
                zipOutput,
                reportFile,
                "sts/launcher_crash_reports/${reportFile.name}"
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeEasyTierDiagnosticsForArchive(
        zipOutput: ZipOutputStream,
        context: Context
    ): Int {
        val config = EasyTierConfigRepository.current()
        val exportedConfigEntry = writeTextEntryAndCount(
            zipOutput,
            "sts/easytier/config_snapshot.txt",
            buildEasyTierConfigSnapshot(context, config)
        )
        val stateCount = writeOptionalFile(
            zipOutput,
            EasyTierStateStore.stateFile(context),
            "sts/easytier/${EasyTierStateStore.stateFile(context).name}"
        )
        val summaryCount = writeOptionalFile(
            zipOutput,
            EasyTierDiagnosticsStore.summaryFile(context),
            "sts/easytier/${EasyTierDiagnosticsStore.summaryFile(context).name}"
        )
        val historyCount = writeOptionalDirectoryFiles(
            zipOutput,
            EasyTierDiagnosticsStore.eventHistoryDir(context),
            "sts/easytier",
            limit = 5,
            // Must match every archived event, not just "-failed-". A mid-game drop lands on
            // DISCONNECTED/RECONNECTING, so a "-failed-" filter would write those records to disk
            // and then silently omit them from the bundle the diagnosis is actually run against.
            predicate = { EasyTierDiagnosticsStore.isEventHistoryFile(it.name) }
        )
        return exportedConfigEntry + stateCount + summaryCount + historyCount
    }

    private fun buildLauncherCrashReportIndex(context: Context, reportFiles: List<File>): String = buildString {
        append("Launcher crash reports\n")
        append("Directory: ").append(RuntimePaths.launcherCrashReportsDir(context).absolutePath).append('\n')
        append("Report count: ").append(reportFiles.size).append('\n')
        append('\n')
        reportFiles.forEach { reportFile ->
            append("- ").append(reportFile.name).append('\n')
            append("  Size: ").append(reportFile.length()).append(" bytes\n")
            append("  Modified At Ms: ").append(reportFile.lastModified()).append('\n')
            append("  Log Entry: sts/launcher_crash_reports/")
                .append(reportFile.name)
                .append('\n')
            append('\n')
        }
    }

    private fun buildWorkshopAutoImportPatchLogIndex(logFiles: List<File>): String = buildString {
        append("Workshop auto import patch logs\n")
        append("Log slots: ").append(WorkshopAutoImportPatchLogStore.MAX_LOG_SLOTS).append('\n')
        append("Log count: ").append(logFiles.size).append('\n')
        append('\n')
        logFiles.forEach { logFile ->
            append("- ").append(logFile.name).append('\n')
            append("  Size: ").append(logFile.length()).append(" bytes\n")
            append("  Modified At Ms: ").append(logFile.lastModified()).append('\n')
            append("  Log Entry: sts/workshop/auto_import_patch_logs/")
                .append(logFile.name)
                .append('\n')
            append('\n')
        }
    }

    private fun buildWorkshopBrowseFailureLogIndex(logFiles: List<File>): String = buildString {
        append("Workshop browse failure logs\n")
        append("Log slots: ").append(WorkshopBrowseFailureLogStore.MAX_LOG_SLOTS).append('\n')
        append("Log count: ").append(logFiles.size).append('\n')
        append('\n')
        logFiles.forEach { logFile ->
            append("- ").append(logFile.name).append('\n')
            append("  Size: ").append(logFile.length()).append(" bytes\n")
            append("  Modified At Ms: ").append(logFile.lastModified()).append('\n')
            append("  Log Entry: sts/workshop/market_failed/")
                .append(logFile.name)
                .append('\n')
            append('\n')
        }
    }

    private fun buildWorkshopDownloadTaskIndex(tasks: List<WorkshopDownloadTaskRecord>): String = buildString {
        append("Workshop download task logs\n")
        append("Task count: ").append(tasks.size).append('\n')
        append('\n')
        tasks.forEach { task ->
            append("- ").append(task.publishedFileId).append('\n')
            append("  Title: ").append(task.title.ifBlank { "<empty>" }).append('\n')
            append("  Status: ").append(task.status.name).append('\n')
            append("  Message: ").append(task.message.ifBlank { "<empty>" }).append('\n')
            append("  Updated At Ms: ").append(task.updatedAtMillis).append('\n')
            append("  Log Entry: sts/workshop/download_tasks/")
                .append(WorkshopDownloadLogService.fileName(task))
                .append('\n')
            append('\n')
        }
    }

    private fun buildEasyTierConfigSnapshot(
        context: Context,
        config: io.stamethyst.backend.easytier.EasyTierResolvedConfig
    ): String =
        buildString {
            append("EasyTier config snapshot\n")
            append("enabled=").append(config.enabled).append('\n')
            append("canConnect=").append(config.canConnect).append('\n')
            append("defaultMode=").append(config.defaultMode.cloudControlValue).append('\n')
            append("allowSharedCommunityNetwork=").append(config.allowSharedCommunityNetwork).append('\n')
            append("roomApiBaseUrl=").append(config.roomApiBaseUrl.ifBlank { "<empty>" }).append('\n')
            append("webConsoleApiBaseUrl=").append(config.webConsoleApiBaseUrl.ifBlank { "<empty>" }).append('\n')
            append("configServerUrl=").append(config.configServerUrl.ifBlank { "<empty>" }).append('\n')
            append("entryNodeUrl=").append(config.entryNodeUrl.ifBlank { "<empty>" }).append('\n')
            append("connectTimeoutSeconds=").append(config.connectTimeoutSeconds).append('\n')
            append("statusPollIntervalSeconds=").append(config.statusPollIntervalSeconds).append('\n')
            append("stateFile=").append(EasyTierStateStore.stateFile(context).absolutePath).append('\n')
            append("summaryFile=").append(EasyTierDiagnosticsStore.summaryFile(context).absolutePath).append('\n')
            append("eventHistoryDir=").append(EasyTierDiagnosticsStore.eventHistoryDir(context).absolutePath).append('\n')
        }

    private fun rawWorkshopDownloadLogFile(context: Context, task: WorkshopDownloadTaskRecord): File {
        return File(
            context.filesDir,
            "workshop/${task.details.summary.appId}/${task.publishedFileId}/download.log"
        )
    }

    @Throws(IOException::class)
    internal fun writeWindowDiagnosticsForArchive(zipOutput: ZipOutputStream, context: Context): Int {
        var exportedCount = 0
        RuntimePaths.listWindowDiagnosticsFiles(context).forEach { windowLogFile ->
            exportedCount += writeOptionalFile(
                zipOutput,
                windowLogFile,
                "sts/window/${windowLogFile.name}"
            )
        }
        return exportedCount
    }

    @Throws(IOException::class)
    internal fun writeAchievementSyncLogsForArchive(zipOutput: ZipOutputStream, context: Context): Int {
        var exportedCount = 0
        RuntimePaths.listAchievementSyncLogFiles(context).forEach { achievementLogFile ->
            exportedCount += writeOptionalFile(
                zipOutput,
                achievementLogFile,
                "sts/achievement_sync/${achievementLogFile.name}"
            )
        }
        return exportedCount
    }

    private fun rawWorkshopDownloadLogArchiveName(task: WorkshopDownloadTaskRecord): String {
        return "workshop-download-${task.publishedFileId}-raw-download.log"
    }

    @Throws(IOException::class)
    private fun allocateShareArchiveFile(context: Context, fileName: String): File {
        val shareDir = File(context.cacheDir, SHARE_DIR_NAME)
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw IOException("Failed to create share directory: ${shareDir.absolutePath}")
        }
        val archiveFile = File(shareDir, fileName)
        val parent = archiveFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create share directory: ${parent.absolutePath}")
        }
        if (archiveFile.exists() && !archiveFile.delete()) {
            throw IOException("Failed to replace existing archive: ${archiveFile.absolutePath}")
        }
        return archiveFile
    }

    @Throws(IOException::class)
    private fun writeOptionalFile(
        zipOutput: ZipOutputStream,
        file: File,
        entryName: String
    ): Int {
        if (!file.isFile || file.length() <= 0L) {
            return 0
        }
        writeFileToZip(zipOutput, file, entryName)
        return 1
    }

    @Throws(IOException::class)
    private fun writeOptionalDirectoryFiles(
        zipOutput: ZipOutputStream,
        dir: File,
        entryDirName: String,
        limit: Int? = null,
        predicate: (File) -> Boolean = { true }
    ): Int {
        val files = dir.listFiles { file -> file.isFile && file.length() > 0L && predicate(file) }
            ?.sortedByDescending { it.lastModified() }
            ?.let { list -> limit?.let(list::take) ?: list }
            ?: return 0
        var count = 0
        files.forEach { file ->
            writeFileToZip(zipOutput, file, "$entryDirName/${file.name}")
            count++
        }
        return count
    }

    private fun buildJvmLogDeviceInfo(context: Context): String = buildString {
        val launcherVersion = resolveLauncherVersion(context)
        append("launcher.package=").append(context.packageName).append('\n')
        append("launcher.versionName=").append(launcherVersion.first).append('\n')
        append("launcher.versionCode=").append(launcherVersion.second).append('\n')
        append("device.manufacturer=").append(normalizeInfoValue(Build.MANUFACTURER)).append('\n')
        append("device.brand=").append(normalizeInfoValue(Build.BRAND)).append('\n')
        append("device.model=").append(normalizeInfoValue(Build.MODEL)).append('\n')
        append("device.device=").append(normalizeInfoValue(Build.DEVICE)).append('\n')
        append("device.product=").append(normalizeInfoValue(Build.PRODUCT)).append('\n')
        append("device.hardware=").append(normalizeInfoValue(Build.HARDWARE)).append('\n')
        append("android.release=").append(normalizeInfoValue(Build.VERSION.RELEASE)).append('\n')
        append("android.sdkInt=").append(Build.VERSION.SDK_INT).append('\n')
        append("android.securityPatch=").append(normalizeInfoValue(Build.VERSION.SECURITY_PATCH)).append('\n')
        append("device.abis=")
            .append((Build.SUPPORTED_ABIS ?: emptyArray()).joinToString(", ").ifBlank { "unknown" })
            .append('\n')
        append("device.fingerprint=").append(normalizeInfoValue(Build.FINGERPRINT)).append('\n')
    }

    private fun normalizeInfoValue(value: String?): String {
        return value?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    }

    @Suppress("DEPRECATION")
    private fun resolveLauncherVersion(context: Context): Pair<String, String> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionName = normalizeInfoValue(packageInfo.versionName)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                packageInfo.versionCode.toString()
            }
            versionName to versionCode
        } catch (_: Throwable) {
            "unknown" to "unknown"
        }
    }

    @Throws(IOException::class)
    private fun writeTextEntry(zipOutput: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zipOutput.putNextEntry(entry)
        zipOutput.write(content.toByteArray(StandardCharsets.UTF_8))
        zipOutput.closeEntry()
    }

    private fun writeTextEntryAndCount(
        zipOutput: ZipOutputStream,
        entryName: String,
        content: String
    ): Int {
        writeTextEntry(zipOutput, entryName, content)
        return 1
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, sourceFile: File, entryName: String) {
        val entry = ZipEntry(entryName)
        if (sourceFile.lastModified() > 0) {
            entry.time = sourceFile.lastModified()
        }
        zipOutput.putNextEntry(entry)
        FileInputStream(sourceFile).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeEntry()
    }
}
