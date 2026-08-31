package io.stamethyst.ui.settings.files

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object StsJarImportLogStore {
    private const val MAX_LOG_SLOTS = 10
    private const val PREFIX = "sts_jar_import_"
    private const val SUFFIX = ".log"
    private val lock = Any()

    fun create(context: Context): File = synchronized(lock) {
        val directory = RuntimePaths.stsJarImportLogsDir(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create STS JAR import log directory: ${directory.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var index = 0
        while (index < 20) {
            val suffix = "-${index.toString().padStart(2, '0')}"
            val file = File(directory, "$PREFIX$stamp$suffix$SUFFIX")
            if (file.createNewFile()) {
                prune(directory)
                return file
            }
            index++
        }
        throw IOException("Failed to allocate STS JAR import log slot")
    }

    fun append(file: File?, message: String) {
        if (file == null || message.isBlank()) return
        runCatching {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText("${timestamp()} ${message.trim()}\n", StandardCharsets.UTF_8)
            }
        }
    }

    fun list(context: Context): List<File> = RuntimePaths.stsJarImportLogsDir(context)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
        .sortedByDescending { it.name }

    private fun prune(directory: File) {
        listFiles(directory).sortedBy { it.name }
            .dropLast(MAX_LOG_SLOTS)
            .forEach { it.delete() }
    }

    private fun listFiles(directory: File): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
