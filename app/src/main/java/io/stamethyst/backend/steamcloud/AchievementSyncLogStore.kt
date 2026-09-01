package io.stamethyst.backend.steamcloud

import android.content.Context
import android.util.Log
import io.stamethyst.backend.diag.RollingTextLogWriter
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Persistent, credential-free event log for the runtime-to-Steam achievement pipeline. */
internal object AchievementSyncLogStore {
    private const val TAG = "SteamAchievementSync"
    private const val MAX_BYTES_PER_FILE = 256L * 1024L
    private const val MAX_FILES = 3
    private const val MAX_ERROR_CAUSE_DEPTH = 8
    private const val MAX_ERROR_STACK_LINES = 12
    private val lock = Any()
    private var writer: RollingTextLogWriter? = null
    private var writerFile: File? = null

    fun append(context: Context, event: String, detail: String = "") {
        val line = buildString {
            append(timestamp())
            append(" event=").append(event)
            if (detail.isNotBlank()) append(" detail=").append(sanitize(detail))
        }
        Log.i(TAG, line)
        runCatching {
            synchronized(lock) {
                val file = RuntimePaths.achievementSyncLog(context.applicationContext)
                val activeWriter = if (writerFile == file) {
                    writer
                } else {
                    writer?.close()
                    RollingTextLogWriter(
                        baseFile = file,
                        maxBytesPerFile = MAX_BYTES_PER_FILE,
                        maxFiles = MAX_FILES,
                        appendExisting = true,
                    ).also {
                        writer = it
                        writerFile = file
                    }
                } ?: return
                activeWriter.appendLine(line)
                activeWriter.flush()
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to append achievement sync log", error)
        }
    }

    fun errorType(error: Throwable): String = error.javaClass.simpleName.ifBlank { "Throwable" }

    /** Includes the server response message and enough stack context to locate the failing stage. */
    fun errorDetails(error: Throwable): String = buildString {
        val root = unwrapAsyncThrowable(error)
        append("error_type=").append(root.javaClass.name)
        append(" error_message=").append(sanitizeValue(root.message))
        append(" cause_chain=").append(formatCauseChain(error))
        append(" stack=").append(formatStack(error))
    }

    private fun sanitize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .let(::redactSensitiveValues)
        .take(4000)

    private fun sanitizeValue(value: String?): String =
        value?.trim()?.takeUnless { it.isEmpty() }?.let(::sanitize).orEmpty().ifEmpty { "<none>" }

    private fun formatCauseChain(error: Throwable): String =
        generateSequence(unwrapAsyncThrowable(error)) { current ->
            current.cause?.takeUnless { it === current }
        }.take(MAX_ERROR_CAUSE_DEPTH).joinToString("<-") { current ->
            "${current.javaClass.name}:${sanitizeValue(current.message)}"
        }.ifEmpty { "<none>" }

    private fun formatStack(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_ERROR_STACK_LINES)
            .joinToString("|") { sanitize(it.trim()) }
            .ifEmpty { "<none>" }
    }

    private fun redactSensitiveValues(value: String): String = value.replace(
        Regex("(?i)(refresh[_-]?token|access[_-]?token|password|guard[_-]?data)(\\s*[:=]\\s*)[^ ,;|]+"),
        "$1$2<redacted>",
    )

    private fun unwrapAsyncThrowable(error: Throwable): Throwable {
        var current = error
        while (true) {
            val cause = when (current) {
                is ExecutionException -> current.cause
                is CompletionException -> current.cause
                else -> null
            }
            if (cause == null || cause === current) return current
            current = cause
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
