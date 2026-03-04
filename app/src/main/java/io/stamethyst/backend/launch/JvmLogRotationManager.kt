package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JvmLogSession(
    val logFile: File,
    val configFile: File
)

object JvmLogRotationManager {
    const val MAX_LOG_SLOTS = 5

    private const val LOG_FILE_PREFIX = "jvm_log_"
    private const val LOG_FILE_SUFFIX = ".log"

    @JvmStatic
    @Throws(IOException::class)
    fun prepareLogSession(context: Context): JvmLogSession {
        synchronized(this) {
            val logsDir = RuntimePaths.jvmLogsDir(context)
            ensureDirectory(logsDir)

            val logFile = createLogFile(logsDir)
            pruneOldLogs(logsDir)

            val configFile = RuntimePaths.log4j2JvmConfigFile(context)
            writeLog4j2Config(configFile)

            return JvmLogSession(
                logFile = logFile,
                configFile = configFile
            )
        }
    }

    @JvmStatic
    fun listLogFiles(context: Context): List<File> {
        val logsDir = RuntimePaths.jvmLogsDir(context)
        if (!logsDir.isDirectory) {
            return emptyList()
        }
        return enumerateManagedLogFiles(logsDir)
            .sortedByDescending { it.name }
            .take(MAX_LOG_SLOTS)
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
        throw IOException("Failed to allocate JVM log file in ${logsDir.absolutePath}")
    }

    private fun pruneOldLogs(logsDir: File) {
        val files = enumerateManagedLogFiles(logsDir).sortedBy { it.name }
        if (files.size <= MAX_LOG_SLOTS) {
            return
        }
        val removeCount = files.size - MAX_LOG_SLOTS
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

    @Throws(IOException::class)
    private fun writeLog4j2Config(configFile: File) {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create log config directory: ${parent.absolutePath}")
        }
        configFile.writeText(buildLog4j2ConfigXml(), StandardCharsets.UTF_8)
    }

    private fun buildLog4j2ConfigXml(): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="WARN">
              <Appenders>
                <File name="JvmFile"
                      fileName="${'$'}{sys:amethyst.log4j2.file}"
                      append="true"
                      immediateFlush="true">
                  <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger - %msg%n%throwable"/>
                </File>
              </Appenders>
              <Loggers>
                <Root level="info">
                  <AppenderRef ref="JvmFile"/>
                </Root>
              </Loggers>
            </Configuration>
        """.trimIndent()
    }
}

