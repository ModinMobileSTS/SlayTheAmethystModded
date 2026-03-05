package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JvmLogRotationManager {
    const val MAX_LOG_SLOTS = 5

    private const val LOG_FILE_PREFIX = "jvm_log_"
    private const val LOG_FILE_SUFFIX = ".log"
    private const val MAX_ARCHIVED_LOG_SLOTS = MAX_LOG_SLOTS - 1

    @JvmStatic
    @Throws(IOException::class)
    fun prepareForNewSession(context: Context) {
        synchronized(this) {
            val latestLog = RuntimePaths.latestLog(context)
            if (!latestLog.isFile || latestLog.length() <= 0L) {
                pruneOldLogs(RuntimePaths.jvmLogsDir(context))
                return
            }

            val logsDir = RuntimePaths.jvmLogsDir(context)
            ensureDirectory(logsDir)
            val archivedLog = createLogFile(logsDir)
            moveFileReplacing(latestLog, archivedLog)
            pruneOldLogs(logsDir)
        }
    }

    @JvmStatic
    fun listLogFiles(context: Context): List<File> {
        val result = ArrayList<File>(MAX_LOG_SLOTS)
        val latestLog = RuntimePaths.latestLog(context)
        if (latestLog.isFile) {
            result.add(latestLog)
        }

        val logsDir = RuntimePaths.jvmLogsDir(context)
        if (logsDir.isDirectory) {
            val archivedLogs = enumerateManagedLogFiles(logsDir).sortedByDescending { it.name }
            for (archived in archivedLogs) {
                if (result.size >= MAX_LOG_SLOTS) {
                    break
                }
                result.add(archived)
            }
        }

        return result
    }

    @Throws(IOException::class)
    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) {
            return
        }
        if (!directory.exists() && directory.mkdirs()) {
            return
        }
        if (directory.isDirectory) {
            return
        }
        throw IOException("Failed to create JVM log directory: ${directory.absolutePath}")
    }

    @Throws(IOException::class)
    private fun createLogFile(logsDir: File): File {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        val baseName = formatter.format(Date())
        var sequence = 0
        while (sequence < 20) {
            val suffix = if (sequence == 0) "" else "-$sequence"
            val candidate = File(logsDir, "$LOG_FILE_PREFIX$baseName$suffix$LOG_FILE_SUFFIX")
            if (candidate.createNewFile()) {
                return candidate
            }
            sequence++
        }
        throw IOException("Failed to allocate JVM log slot in ${logsDir.absolutePath}")
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace existing file: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }

        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        if (!source.delete()) {
            throw IOException("Failed to remove old latest.log: ${source.absolutePath}")
        }
    }

    private fun pruneOldLogs(logsDir: File) {
        if (!logsDir.isDirectory || MAX_ARCHIVED_LOG_SLOTS < 0) {
            return
        }
        val files = enumerateManagedLogFiles(logsDir).sortedBy { it.name }
        if (files.size <= MAX_ARCHIVED_LOG_SLOTS) {
            return
        }
        val removeCount = files.size - MAX_ARCHIVED_LOG_SLOTS
        for (index in 0 until removeCount) {
            val target = files[index]
            if (target.isFile) {
                target.delete()
            }
        }
    }

    private fun enumerateManagedLogFiles(logsDir: File): List<File> {
        val children = logsDir.listFiles() ?: return emptyList()
        val result = ArrayList<File>(children.size)
        for (file in children) {
            if (!file.isFile) {
                continue
            }
            val name = file.name
            if (!name.startsWith(LOG_FILE_PREFIX) || !name.endsWith(LOG_FILE_SUFFIX)) {
                continue
            }
            result.add(file)
        }
        return result
    }
}
