package io.stamethyst.backend.diag

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists render-window diagnostics independently of the logcat capture lifecycle. */
internal object WindowDiagnosticsLogStore {
    private const val MAX_BYTES_PER_FILE = 256L * 1024L
    private const val MAX_FILES = 3
    private val lock = Any()

    private var writer: RollingTextLogWriter? = null
    private var writerFile: File? = null

    fun append(context: Context, message: String) {
        runCatching {
            synchronized(lock) {
                val file = RuntimePaths.windowDiagnosticsLog(context.applicationContext)
                val activeWriter = if (writerFile == file) {
                    writer
                } else {
                    writer?.close()
                    RollingTextLogWriter(
                        baseFile = file,
                        maxBytesPerFile = MAX_BYTES_PER_FILE,
                        maxFiles = MAX_FILES
                    ).also {
                        writer = it
                        writerFile = file
                    }
                } ?: return
                activeWriter.appendLine("${timestamp()} $message")
                activeWriter.flush()
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
