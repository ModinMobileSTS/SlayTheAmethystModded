package io.stamethyst.backend.diag

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal class RollingTextLogWriter(
    private val baseFile: File,
    private val maxBytesPerFile: Long,
    private val maxFiles: Int
) : Closeable {
    private var output: OutputStream? = null
    private var currentBytes: Long = 0L

    init {
        require(maxBytesPerFile > 0L) { "maxBytesPerFile must be > 0" }
        require(maxFiles >= 1) { "maxFiles must be >= 1" }
        val parent = baseFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        openFreshOutput()
    }

    fun appendLine(line: String) {
        val text = line + '\n'
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (currentBytes > 0L && currentBytes + bytes.size > maxBytesPerFile) {
            rotate()
        }
        output?.write(bytes)
        output?.flush()
        currentBytes += bytes.size.toLong()
    }

    override fun close() {
        val current = output
        output = null
        current?.flush()
        current?.close()
    }

    private fun rotate() {
        close()
        if (maxFiles > 1) {
            val oldest = rotatedFile(maxFiles - 1)
            if (oldest.exists()) {
                oldest.delete()
            }
            for (index in (maxFiles - 2) downTo 0) {
                val source = if (index == 0) baseFile else rotatedFile(index)
                if (!source.exists()) {
                    continue
                }
                val target = rotatedFile(index + 1)
                if (target.exists()) {
                    target.delete()
                }
                source.renameTo(target)
            }
        } else if (baseFile.exists()) {
            baseFile.delete()
        }
        openFreshOutput()
    }

    private fun openFreshOutput() {
        output = FileOutputStream(baseFile, false)
        currentBytes = 0L
    }

    private fun rotatedFile(index: Int): File = File(baseFile.parentFile, baseFile.name + "." + index)
}
