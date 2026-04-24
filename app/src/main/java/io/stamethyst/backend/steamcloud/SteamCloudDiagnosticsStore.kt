package io.stamethyst.backend.steamcloud

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal object SteamCloudDiagnosticsStore {
    private const val SUMMARY_FILE_NAME = "last-operation-summary.txt"

    @JvmStatic
    fun summaryFile(context: Context): File = File(SteamCloudManifestStore.outputDir(context), SUMMARY_FILE_NAME)

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
            }

            diagnostics?.let { snapshot ->
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
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    fun clear(context: Context) {
        summaryFile(context).delete()
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
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
