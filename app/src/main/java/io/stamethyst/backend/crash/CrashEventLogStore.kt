package io.stamethyst.backend.crash

import android.content.Context
import android.util.Log
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashEventLogStore {
    private const val TAG = "CrashEventLogStore"

    @JvmStatic
    fun clear(context: Context) {
        try {
            val file = RuntimePaths.lastCrashReport(context)
            if (file.exists() && !file.delete() && file.exists()) {
                Log.w(TAG, "Failed to delete stale crash report: ${file.absolutePath}")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to clear crash report", error)
        }
    }

    @JvmStatic
    fun recordLaunchResult(
        context: Context,
        stage: String,
        code: Int,
        isSignal: Boolean,
        detail: String?
    ) {
        val out = StringBuilder(256)
        out.append("time=").append(nowString()).append('\n')
        out.append("stage=").append(stage).append('\n')
        out.append("type=launch_result").append('\n')
        out.append("code=").append(code).append('\n')
        out.append("isSignal=").append(isSignal).append('\n')
        out.append("detail=").append(detail?.trim() ?: "").append('\n')
        out.append('\n')
        appendRaw(context, out.toString())
    }

    @JvmStatic
    fun recordThrowable(context: Context, stage: String, error: Throwable) {
        val out = StringBuilder(1024)
        out.append("time=").append(nowString()).append('\n')
        out.append("stage=").append(stage).append('\n')
        out.append("type=throwable").append('\n')
        out.append("class=").append(error.javaClass.name).append('\n')
        out.append("message=").append(error.message.toString()).append('\n')
        out.append("stacktrace:\n")
        val stackBuffer = StringWriter(2048)
        val writer = PrintWriter(stackBuffer)
        error.printStackTrace(writer)
        writer.flush()
        out.append(stackBuffer)
        out.append('\n')
        appendRaw(context, out.toString())
    }

    internal fun appendRaw(context: Context, message: String) {
        try {
            val file = RuntimePaths.lastCrashReport(context)
            val parent: File? = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "Failed to create crash report directory: ${parent.absolutePath}")
                return
            }
            FileOutputStream(file, true).use { output ->
                output.write(message.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to write crash report", error)
        }
    }

    internal fun writeFileSafely(target: File, content: String) {
        try {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "Failed to create directory for ${target.absolutePath}")
                return
            }
            FileOutputStream(target, false).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to write ${target.absolutePath}", error)
        }
    }

    internal fun nowString(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return formatter.format(Date())
    }
}
