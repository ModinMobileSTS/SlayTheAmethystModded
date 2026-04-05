package io.stamethyst.backend.diag

import android.content.Context
import android.net.Uri
import android.os.Build
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.crash.ProcessExitInfoCapture
import io.stamethyst.backend.crash.SignalCrashDumpReader
import io.stamethyst.backend.launch.JvmLogRotationManager
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
    private const val MAX_HISTOGRAMS_IN_ARCHIVE = 6
    private const val MAX_HISTOGRAM_SUMMARY_CLASSES = 12

    fun buildJvmLogExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-jvm-logs-export-${formatter.format(Date())}.zip"
    }

    fun buildCrashExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-crash-report-${formatter.format(Date())}.zip"
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
    fun exportJvmLogBundle(context: Context, destination: Uri): Int {
        context.contentResolver.openOutputStream(destination).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            return writeDiagnosticsBundle(context, output, null)
        }
    }

    @Throws(IOException::class)
    private fun writeDiagnosticsBundle(
        context: Context,
        output: OutputStream,
        crashContext: CrashArchiveContext?
    ): Int {
        val stsRoot = RuntimePaths.stsRoot(context)
        var exportedCount = 0
        ZipOutputStream(output).use { zipOutput ->
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/device_info.txt",
                buildJvmLogDeviceInfo(context)
            )
            val latestCrashSummary = LatestLogCrashDetector.detect(context)
            val lastNonBlankLogLine = LatestLogCrashDetector.readLastNonBlankLine(context)
            val exitSummary = ProcessExitInfoCapture.peekLatestInterestingProcessExitInfo(context)
            val processExitTrace = ProcessExitInfoCapture.readLatestInterestingProcessExitTrace(context)
            val processExitTraceSummary = ProcessExitInfoCapture.summarizeProcessExitTrace(processExitTrace)
            val signalDumpSummary = SignalCrashDumpReader.readSummary(context)
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/latest_log_summary.txt",
                DiagnosticsSummaryFormatter.buildLatestLogSummary(
                    latestCrash = latestCrashSummary,
                    lastNonBlankLine = lastNonBlankLogLine
                )
            )
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/process_exit_info.txt",
                DiagnosticsSummaryFormatter.buildProcessExitInfoSummary(
                    exitSummary = exitSummary,
                    signalDumpSummary = signalDumpSummary,
                    processExitTraceSummary = processExitTraceSummary
                )
            )
            processExitTrace?.let { traceText ->
                writeTextEntry(
                    zipOutput,
                    "sts/jvm_logs/process_exit_trace.txt",
                    traceText
                )
            }
            writeTextEntry(
                zipOutput,
                "sts/jvm_logs/launcher_settings.txt",
                LauncherSettingsDiagnosticsFormatter.buildFromContext(context)
            )

            val writtenJvmEntries = LinkedHashSet<String>()
            JvmLogRotationManager.listLogFiles(context).forEach { logFile ->
                val entryName = "sts/jvm_logs/${logFile.name}"
                if (writtenJvmEntries.add(entryName) && logFile.isFile) {
                    writeFileToZip(zipOutput, logFile, entryName)
                    exportedCount++
                }
            }

            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.bootBridgeEventsLog(context),
                "sts/jvm_logs/${RuntimePaths.bootBridgeEventsLog(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmGcLog(context),
                "sts/jvm_logs/${RuntimePaths.jvmGcLog(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmHeapSnapshot(context),
                "sts/jvm_logs/${RuntimePaths.jvmHeapSnapshot(context).name}"
            )
            exportedCount += writeOptionalFile(
                zipOutput,
                RuntimePaths.jvmSignalDump(context),
                "sts/jvm_logs/${RuntimePaths.jvmSignalDump(context).name}"
            )
            RuntimePaths.listLogcatCaptureFiles(context).forEach { logcatFile ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    logcatFile,
                    "sts/logcat/${logcatFile.name}"
                )
            }
            RuntimePaths.listLauncherLogcatCaptureFiles(context).forEach { logcatFile ->
                exportedCount += writeOptionalFile(
                    zipOutput,
                    logcatFile,
                    "sts/logcat/${logcatFile.name}"
                )
            }

            val histogramFiles = collectHistogramFiles(context)
            writeTextEntry(
                zipOutput,
                "sts/jvm_histograms/summary.txt",
                buildHistogramSummary(histogramFiles)
            )
            histogramFiles.forEach { histogramFile ->
                writeFileToZip(
                    zipOutput,
                    histogramFile,
                    "sts/jvm_histograms/${histogramFile.name}"
                )
                exportedCount++
            }

            if (crashContext != null) {
                writeTextEntry(
                    zipOutput,
                    "sts/crash/summary.txt",
                    buildCrashSummary(context, crashContext)
                )
            }

            if (exportedCount <= 0) {
                val message = buildString {
                    append("No diagnostic logs found.\n")
                    append("Expected files under:\n")
                    append("- ").append(stsRoot.absolutePath).append('\n')
                }
                writeTextEntry(zipOutput, "sts/README.txt", message)
            }
        }
        return exportedCount
    }

    private fun collectHistogramFiles(context: Context): List<File> {
        return RuntimePaths.jvmHistogramsDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
            ?.sortedByDescending { it.name }
            ?.take(MAX_HISTOGRAMS_IN_ARCHIVE)
            ?.toList()
            .orEmpty()
    }

    private fun buildHistogramSummary(histogramFiles: List<File>): String {
        if (histogramFiles.isEmpty()) {
            return "No JVM histogram dumps captured yet.\n"
        }

        val latest = histogramFiles.first()
        val lines = try {
            latest.readLines(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return buildString {
                append("Histogram files captured: ").append(histogramFiles.size).append('\n')
                append("Latest file: ").append(latest.name).append('\n')
                append("Failed to read latest histogram file.\n")
            }
        }

        val header = LinkedHashMap<String, String>()
        val topClasses = ArrayList<String>(MAX_HISTOGRAM_SUMMARY_CLASSES)
        var inBody = false
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (!inBody) {
                if (line.isBlank()) {
                    inBody = true
                    continue
                }
                val separatorIndex = line.indexOf('=')
                if (separatorIndex > 0 && separatorIndex < line.length - 1) {
                    val key = line.substring(0, separatorIndex).trim()
                    val value = line.substring(separatorIndex + 1).trim()
                    if (key.isNotEmpty()) {
                        header[key] = value
                    }
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("num") || trimmed.startsWith("-")) {
                continue
            }
            topClasses.add(trimmed)
            if (topClasses.size >= MAX_HISTOGRAM_SUMMARY_CLASSES) {
                break
            }
        }

        return buildString {
            append("Histogram files captured: ").append(histogramFiles.size).append('\n')
            append("Latest file: ").append(latest.name).append('\n')
            header.forEach { (key, value) ->
                append(key).append('=').append(value).append('\n')
            }
            append('\n')
            append("Top classes from latest dump:\n")
            if (topClasses.isEmpty()) {
                append("(no class rows parsed)\n")
            } else {
                topClasses.forEach { row -> append(row).append('\n') }
            }
        }
    }

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
        append("device.abis=").append(Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" }).append('\n')
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
