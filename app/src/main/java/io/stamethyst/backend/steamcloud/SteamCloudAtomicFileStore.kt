package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SteamCloudAtomicFileStore {
    fun backupFile(file: File): File = File(file.parentFile, file.name + ".bak")

    @Throws(IOException::class)
    fun writeText(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${parent.absolutePath}")
        }
        val tempFile = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(text.toByteArray(charset))
                output.fd.sync()
            }
            if (file.isFile) {
                runCatching { file.copyTo(backupFile(file), overwrite = true) }
            }
            moveReplacing(tempFile, file)
        } finally {
            tempFile.delete()
        }
    }

    @Throws(IOException::class)
    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
