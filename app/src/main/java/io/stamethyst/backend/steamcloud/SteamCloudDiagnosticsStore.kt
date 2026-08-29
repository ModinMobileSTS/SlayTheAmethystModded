package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal object SteamCloudDiagnosticsStore {
    private const val SUMMARY_FILE_NAME = "last-operation-summary.txt"
    private const val FAILURE_HISTORY_DIR_NAME = "failures"
    private const val LOGIN_HISTORY_DIR_NAME = "login-history"
    private const val FAILURE_HISTORY_LIMIT = 10
    private const val LOGIN_HISTORY_LIMIT = 5

    @JvmStatic
    fun summaryFile(context: Context): File = File(SteamCloudManifestStore.outputDir(context), SUMMARY_FILE_NAME)

    fun failureHistoryDir(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), FAILURE_HISTORY_DIR_NAME)

    fun loginHistoryDir(context: Context): File =
        File(SteamCloudManifestStore.outputDir(context), LOGIN_HISTORY_DIR_NAME)

    @Throws(IOException::class)
    fun writeSummary(
        context: Context,
        operation: String,
        outcome: String,
        accountName: String,
        startedAtMs: Long,
        completedAtMs: Long,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
        failureSummary: String? = null,
        error: Throwable? = null,
        extraLines: List<String> = emptyList(),
    ) {
        SteamCloudOperationMutex.runExclusive(context) {
            writeSummaryExclusive(
                context = context,
                operation = operation,
                outcome = outcome,
                accountName = accountName,
                startedAtMs = startedAtMs,
                completedAtMs = completedAtMs,
                diagnostics = diagnostics,
                failureSummary = failureSummary,
                error = error,
                extraLines = extraLines,
            )
        }
    }

    private fun writeSummaryExclusive(
        context: Context,
        operation: String,
        outcome: String,
        accountName: String,
        startedAtMs: Long,
        completedAtMs: Long,
        diagnostics: SteamCloudClient.DiagnosticsSnapshot?,
        failureSummary: String?,
        error: Throwable?,
        extraLines: List<String>,
    ) {
        val file = summaryFile(context)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud diagnostics directory: ${parent.absolutePath}")
        }

        val lines = buildList {
            add("Steam Cloud diagnostics summary")
            add("")
            add("Outcome: ${outcome.trim().ifBlank { "UNKNOWN" }}")
            add("Operation: ${operation.trim().ifBlank { "<unknown>" }}")
            add("Account: ${accountName.trim().ifBlank { "<unknown>" }}")
            add("App ID: $STEAM_CLOUD_APP_ID")
            add("Started At: ${formatTimestamp(startedAtMs)}")
            add("Completed At: ${formatTimestamp(completedAtMs)}")
            add("Duration Ms: ${maxOf(0L, completedAtMs - startedAtMs)}")
            add(
                "Failure Summary: ${
                    failureSummary?.trim()?.takeIf { it.isNotEmpty() } ?: describeFailure(error)
                }"
            )
            add(
                "Protocol Types: ${
                    diagnostics?.protocolTypesDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<unknown>"
                }"
            )
            add(
                "Watt Acceleration: ${
                    diagnostics?.wattAccelerationDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<unknown>"
                }"
            )
            add(
                "Current Stage: ${
                    diagnostics?.currentStage?.trim()?.takeIf { it.isNotEmpty() } ?: "<unknown>"
                }"
            )
            error?.let { failure ->
                add("Error Type: ${unwrapAsyncThrowable(failure).javaClass.name}")
                add("Error Cause Chain: ${formatExceptionCauseChain(failure)}")
            }
            add(
                "Connected Callback: ${
                    if (diagnostics?.connectedCallbackReceived == true) "received" else "not received"
                }"
            )
            add(
                "Logon Result: ${
                    diagnostics?.loggedOnResultDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not received>"
                }"
            )
            add(
                "Credentials Auth SteamID64: ${
                    diagnostics?.credentialsAuthSteamId64?.trim()?.takeIf { it.isNotEmpty() } ?: "<not resolved>"
                }"
            )
            add(
                "LoggedOn Callback SteamID64: ${
                    diagnostics?.loggedOnCallbackSteamId64?.trim()?.takeIf { it.isNotEmpty() } ?: "<not resolved>"
                }"
            )
            add(
                "SteamClient SteamID64: ${
                    diagnostics?.steamClientSteamId64?.trim()?.takeIf { it.isNotEmpty() } ?: "<not resolved>"
                }"
            )
            add(
                "Disconnected Callback: ${
                    diagnostics?.disconnectedDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not observed>"
                }"
            )
            add(
                "Resolved CM Endpoint: ${
                    diagnostics?.resolvedServerDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not resolved>"
                }"
            )
            add(
                "Proxy/Accelerator Detected: ${
                    if (SteamCloudNetworkEnvironment.isProxyOrAcceleratorActive(context)) "yes" else "no"
                }"
            )
            add(
                "CM Candidate Source: ${
                    diagnostics?.candidateSourceDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not selected>"
                }"
            )
            add("CM Server Selection Ms: ${formatOptionalDurationMs(diagnostics?.cmServerSelectionMs)}")
            add("CM Connect Wait Ms: ${formatOptionalDurationMs(diagnostics?.cmConnectWaitMs)}")
            add(
                "Allowed Challenges: ${
                    diagnostics?.allowedChallengesDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not evaluated>"
                }"
            )
            add(
                "Last Auth Prompt: ${
                    diagnostics?.lastAuthPromptDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not requested>"
                }"
            )
            add("Guard Data Provided: ${if (diagnostics?.guardDataConfigured == true) "yes" else "no"}")
            add("Guard Data Updated: ${if (diagnostics?.guardDataUpdated == true) "yes" else "no"}")
            add(
                "JavaSteam Last Log: ${
                    diagnostics?.javaSteamLastLogDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not captured>"
                }"
            )
            add(
                "JavaSteam Last Error: ${
                    diagnostics?.javaSteamLastErrorDescription?.trim()?.takeIf { it.isNotEmpty() } ?: "<not captured>"
                }"
            )
            add("Summary: ${file.absolutePath}")

            if (extraLines.isNotEmpty()) {
                add("")
                add("Details:")
                extraLines.forEach { line ->
                    add("  - ${line.trim().ifBlank { "<blank>" }}")
                }
            }

            error?.let { failure ->
                add("")
                appendExceptionChain(this, failure)
                add("")
                appendExceptionStack(this, failure)
            }

            diagnostics?.let { snapshot ->
                if (snapshot.diagnosticEventLines.isNotEmpty()) {
                    add("")
                    add("Diagnostic Event Timeline:")
                    snapshot.diagnosticEventLines.forEach { line ->
                        add("  - $line")
                    }
                }
                if (snapshot.javaSteamLogTailLines.isNotEmpty()) {
                    add("")
                    add("JavaSteam Log Tail:")
                    snapshot.javaSteamLogTailLines.forEach { line ->
                        add("  - $line")
                    }
                }
                if (snapshot.javaSteamErrorStackLines.isNotEmpty()) {
                    add("")
                    add("JavaSteam Error Stack:")
                    snapshot.javaSteamErrorStackLines.forEach { line ->
                        add("  $line")
                    }
                }
            }
        }
        val text = lines.joinToString("\n") + "\n"
        SteamCloudAtomicFileStore.writeText(file, text, Charsets.UTF_8)
        if (outcome.equals("FAILED", ignoreCase = true)) {
            runCatching { writeFailureHistory(context, operation, startedAtMs, completedAtMs, text) }
        }
        if (operation == "credentials_login" && outcome.equals("FAILED", ignoreCase = true)) {
            runCatching { writeLoginHistory(context, startedAtMs, completedAtMs, outcome, text) }
        }
    }

    fun clear(context: Context) {
        SteamCloudOperationMutex.runExclusive(context) {
            summaryFile(context).delete()
        }
    }

    @Throws(IOException::class)
    private fun writeFailureHistory(
        context: Context,
        operation: String,
        startedAtMs: Long,
        completedAtMs: Long,
        text: String,
    ) {
        val dir = failureHistoryDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud failure history directory: ${dir.absolutePath}")
        }
        val safeOperation = operation.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .ifBlank { "unknown" }
        val fileName = "failure-$safeOperation-${formatFileTimestamp(startedAtMs)}-${formatFileTimestamp(completedAtMs)}.txt"
        File(dir, fileName).writeText(text, Charsets.UTF_8)
        pruneFailureHistory(dir)
    }

    @Throws(IOException::class)
    private fun writeLoginHistory(
        context: Context,
        startedAtMs: Long,
        completedAtMs: Long,
        outcome: String,
        text: String,
    ) {
        val dir = loginHistoryDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Failed to create Steam Cloud login history directory: ${dir.absolutePath}")
        }
        val normalizedOutcome = outcome.trim().lowercase(Locale.US).ifBlank { "unknown" }
        val baseFileName = "login-$normalizedOutcome-${formatFileTimestamp(startedAtMs)}-${formatFileTimestamp(completedAtMs)}.txt"
        allocateLoginHistoryFile(dir, baseFileName).writeText(text, Charsets.UTF_8)
        pruneLoginHistory(dir)
    }

    private fun allocateLoginHistoryFile(dir: File, baseFileName: String): File {
        val plain = File(dir, baseFileName)
        if (plain.createNewFile()) {
            return plain
        }
        val stem = baseFileName.removeSuffix(".txt")
        for (sequence in 1..99) {
            val candidate = File(dir, "$stem-$sequence.txt")
            if (candidate.createNewFile()) {
                return candidate
            }
        }
        throw IOException("Failed to allocate Steam login history slot in ${dir.absolutePath}")
    }

    private fun pruneFailureHistory(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("failure-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(FAILURE_HISTORY_LIMIT).forEach { it.delete() }
    }

    private fun pruneLoginHistory(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("login-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(LOGIN_HISTORY_LIMIT).forEach { it.delete() }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    private fun formatFileTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(timestampMs))
    }

    private fun formatOptionalDurationMs(value: Long?): String {
        val duration = value ?: return "<not recorded>"
        return if (duration >= 0L) duration.toString() else "<not reached>"
    }

    private fun appendExceptionChain(lines: MutableList<String>, error: Throwable) {
        lines += "Exception Chain:"
        var current: Throwable? = unwrapAsyncThrowable(error)
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message?.trim().takeUnless { it.isNullOrEmpty() } ?: "<no message>"
            lines += "  - ${current.javaClass.name}: $message"
            val next = current.cause
            if (next == null || next === current) {
                break
            }
            current = next
            depth++
        }
    }

    private fun appendExceptionStack(lines: MutableList<String>, error: Throwable) {
        lines += "Full Exception Stack:"
        stackTraceText(error).lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line -> lines += "  $line" }
    }

    private fun describeFailure(error: Throwable?): String {
        if (error == null) {
            return "<none>"
        }
        val root = generateSequence(unwrapAsyncThrowable(error)) { current ->
            current.cause?.takeUnless { it === current }
        }.firstOrNull { current ->
            !current.message.isNullOrBlank()
        } ?: unwrapAsyncThrowable(error)
        val message = root.message?.trim().takeUnless { it.isNullOrEmpty() }
        return if (message != null) {
            "${root.javaClass.simpleName}: $message"
        } else {
            root.javaClass.name
        }
    }

    private fun formatExceptionCauseChain(error: Throwable): String {
        return generateSequence(unwrapAsyncThrowable(error)) { current ->
            current.cause?.takeUnless { it === current }
        }.take(8).joinToString(" <- ") { current ->
            val message = current.message?.trim().takeUnless { it.isNullOrEmpty() }
            if (message == null) {
                current.javaClass.name
            } else {
                "${current.javaClass.name}: $message"
            }
        }.ifBlank { "<none>" }
    }

    private fun stackTraceText(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun unwrapAsyncThrowable(error: Throwable): Throwable {
        var current = error
        while (true) {
            val cause = when (current) {
                is ExecutionException -> current.cause
                is CompletionException -> current.cause
                else -> null
            }
            if (cause == null || cause === current) {
                return current
            }
            current = cause
        }
    }
}
