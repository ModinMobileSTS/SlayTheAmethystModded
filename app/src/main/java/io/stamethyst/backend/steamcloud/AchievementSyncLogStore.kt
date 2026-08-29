package io.stamethyst.backend.steamcloud

import android.content.Context
import android.util.Log
import io.stamethyst.backend.diag.RollingTextLogWriter
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, credential-free event log for the runtime-to-Steam achievement pipeline. */
internal object AchievementSyncLogStore {
    private const val TAG = "SteamAchievementSync"
    private const val MAX_BYTES_PER_FILE = 256L * 1024L
    private const val MAX_FILES = 3
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

    private fun sanitize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .take(1000)

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
