package io.stamethyst.backend.easytier

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object EasyTierAtomicFileStore {
    fun backupFile(file: File): File = File(file.parentFile, file.name + ".bak")

    @Throws(IOException::class)
    fun writeText(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Failed to create output directory: ${parent.absolutePath}")
        val temporary = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(text.toByteArray(charset)); output.fd.sync() }
            if (file.isFile) runCatching { file.copyTo(backupFile(file), overwrite = true) }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
