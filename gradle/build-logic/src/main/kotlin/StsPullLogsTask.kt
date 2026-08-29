import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Gradle task that exports a full diagnostics archive by triggering the in-app
 * [DiagnosticsArchiveBuilder] chain via [DiagnosticsProcessService], then pulling
 * the resulting zip over adb.
 *
 * This replaces the old hand-rolled adb-file-enumeration approach.  The service
 * handles every category (JVM logs, steam cloud, easytier, workshop, window
 * diagnostics, logcat, crash reports, …) through the same code path that
 * Settings → Share Logs uses.
 *
 * Protocol:
 *   1. Clear previous staging artifacts inside the app's files dir.
 *   2. Broadcast ACTION_ADB_STAGE_JVM_LOG to start the service.
 *   3. Poll the sentinel file (ready / error) up to [POLL_TIMEOUT_SECONDS] s.
 *   4. Pull export.zip and rename it to the standard timestamped name.
 */
abstract class StsPullLogsTask : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val adbPath: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    @get:Optional
    abstract val deviceSerial: Property<String>

    @get:Input
    @get:Optional
    abstract val logsDir: Property<String>

    @TaskAction
    fun pullLogs() {
        val packageName = applicationId.get()
        val outputDir = logsDir.orNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { project.file(it) }
            ?: project.layout.buildDirectory.dir("sts-logs").get().asFile
        outputDir.mkdirs()

        val stagingDir = "files/$STAGING_DIR"

        logger.lifecycle("Clearing previous staging artifacts…")
        clearStagingArtifacts(packageName, stagingDir)

        logger.lifecycle("Triggering in-app diagnostics export via DiagnosticsProcessService…")
        triggerAdbStage(packageName)

        logger.lifecycle("Waiting for export (up to ${POLL_TIMEOUT_SECONDS}s)…")
        val outcome = pollForSentinel(packageName, stagingDir)

        when (outcome) {
            is Outcome.Error -> {
                logger.error("DiagnosticsProcessService reported an error:\n${outcome.message}")
                throw RuntimeException("stsPullLogs: in-app export failed – see error above")
            }
            is Outcome.Ready -> {
                logger.lifecycle("Export complete (${outcome.entryCount} entries). Pulling zip…")
                val archiveFile = File(outputDir, buildExportFileName())
                pullFile(packageName, stagingDir, STAGING_ZIP, archiveFile)
                logger.lifecycle("SlayTheAmethyst diagnostics archive: ${archiveFile.absolutePath}")
            }
            Outcome.Timeout -> {
                throw RuntimeException(
                    "stsPullLogs: timed out after ${POLL_TIMEOUT_SECONDS}s waiting for export sentinel"
                )
            }
        }
    }

    // ── staging helpers ───────────────────────────────────────────────────────

    private fun clearStagingArtifacts(pkg: String, stagingDir: String) {
        for (name in listOf(STAGING_ZIP, SENTINEL_READY, SENTINEL_ERROR)) {
            // clear both possible locations
            val sdcard = "/sdcard/Android/data/$pkg/$stagingDir/$name"
            adb("shell", "rm", "-f", sdcard)
            runAs(pkg, "rm -f ${quote("$stagingDir/$name")}")
        }
    }

    private fun triggerAdbStage(pkg: String) {
        // Trigger via exported BroadcastReceiver; the service itself is not exported.
        // "am broadcast" works from the adb shell without run-as.
        adb("shell", "am", "broadcast",
            "-a", ACTION_ADB_STAGE,
            "-n", "$pkg/.backend.diag.AdbDiagnosticsReceiver")
    }

    private sealed class Outcome {
        data class Ready(val entryCount: Int) : Outcome()
        data class Error(val message: String) : Outcome()
        object Timeout : Outcome()
    }

    private fun pollForSentinel(pkg: String, stagingDir: String): Outcome {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            val ready = readFile(pkg, stagingDir, SENTINEL_READY)
            if (ready != null) {
                return Outcome.Ready(ready.trim().toIntOrNull() ?: 0)
            }
            val error = readFile(pkg, stagingDir, SENTINEL_ERROR)
            if (error != null) {
                return Outcome.Error(error)
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return Outcome.Timeout
    }

    /** Try sdcard path first (no run-as needed), fall back to run-as. */
    private fun readFile(pkg: String, dir: String, name: String): String? {
        val sdcard = "/sdcard/Android/data/$pkg/$dir/$name"
        val sdcardResult = adb("shell", "sh", "-c", "cat ${quote(sdcard)} 2>/dev/null")
        if (sdcardResult.isNotEmpty()) return sdcardResult
        return readAs(pkg, "$dir/$name")
    }

    private fun pullFile(pkg: String, dir: String, name: String, localFile: File) {
        // Try sdcard path first (adb pull, no run-as required)
        val sdcard = "/sdcard/Android/data/$pkg/$dir/$name"
        if (devicePathExists(sdcard)) {
            adb("pull", sdcard, localFile.absolutePath)
            return
        }
        // Fall back to run-as cat (debuggable builds with internal storage)
        val bytes = runAsBinary(pkg, "cat ${quote("$dir/$name")}")
        if (bytes.isNotEmpty()) {
            localFile.writeBytes(bytes)
            return
        }
        throw RuntimeException("Cannot pull $dir/$name: neither sdcard nor run-as path accessible")
    }

    private fun resolveFullPath(pkg: String, relative: String): String? {
        // Try external first (no run-as required)
        val external = "/sdcard/Android/data/$pkg/$relative"
        if (devicePathExists(external)) return external
        // Internal via run-as
        return "files/${relative.removePrefix("files/")}"
    }

    // ── adb primitives ────────────────────────────────────────────────────────

    private fun adb(vararg args: String): String {
        val out = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(buildAdbArgs(*args))
            isIgnoreExitValue = true
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun runAs(pkg: String, cmd: String): String {
        return adb("shell", "run-as", pkg, "sh", "-c", cmd)
    }

    private fun runAsBinary(pkg: String, cmd: String): ByteArray {
        val out = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(buildAdbArgs("exec-out", "run-as", pkg, "sh", "-c", cmd))
            isIgnoreExitValue = true
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
        }
        return out.toByteArray()
    }

    private fun readAs(pkg: String, path: String): String? {
        val result = runAs(pkg, "cat ${quote(path)} 2>/dev/null")
        return result.takeIf { it.isNotEmpty() }
    }

    private fun devicePathExists(path: String): Boolean {
        val out = adb("shell", "sh", "-c", "ls ${quote(path)} >/dev/null 2>&1 && echo ok")
        return out.trim() == "ok"
    }

    private fun buildAdbArgs(vararg args: String): List<String> = buildList {
        add(adbPath.get())
        val serial = deviceSerial.orNull.orEmpty()
        if (serial.isNotEmpty()) { add("-s"); add(serial) }
        addAll(args.toList())
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun buildExportFileName() =
        "sts-jvm-logs-export-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    companion object {
        private const val ACTION_ADB_STAGE = "io.stamethyst.action.ADB_STAGE_JVM_LOG"
        private const val STAGING_DIR      = "sts-logs-staging"
        private const val STAGING_ZIP      = "export.zip"
        private const val SENTINEL_READY   = "ready"
        private const val SENTINEL_ERROR   = "error"
        private const val POLL_TIMEOUT_SECONDS = 60
        private const val POLL_INTERVAL_MS     = 1_500L
    }
}
