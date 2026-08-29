package io.stamethyst.backend.steamcloud

import java.io.File
import java.io.FileInputStream
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
        writeText(file, text, charset, preservePreviousAsBackup = true)
    }

    /**
     * Atomically replaces sensitive state without retaining a recoverable prior value.
     */
    @Throws(IOException::class)
    fun writeTextWithoutBackup(file: File, text: String, charset: Charset = Charsets.UTF_8) {
        writeText(file, text, charset, preservePreviousAsBackup = false)
    }

    @Throws(IOException::class)
    private fun writeText(
        file: File,
        text: String,
        charset: Charset,
        preservePreviousAsBackup: Boolean,
    ) {
        val target = file.absoluteFile
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${parent.absolutePath}")
        }
        if (!preservePreviousAsBackup) {
            backupFile(target).delete()
        }
        val tempFile = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(text.toByteArray(charset))
                output.fd.sync()
            }
            if (preservePreviousAsBackup && target.isFile) {
                runCatching {
                    val backup = backupFile(target)
                    target.copyTo(backup, overwrite = true)
                    syncFile(backup)
                }
            }
            replaceFile(tempFile, target)
        } finally {
            tempFile.delete()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun replaceFile(source: File, target: File) {
        if (!source.isFile) {
            throw IOException("Steam Cloud replacement source is missing: ${source.absolutePath}")
        }

        val resolvedTarget = target.absoluteFile
        val parent = resolvedTarget.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Failed to create Steam Cloud output directory: ${parent.absolutePath}")
        }

        syncFile(source)
        moveReplacing(source, resolvedTarget)
        syncDirectory(parent)
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

    @Throws(IOException::class)
    private fun syncFile(file: File) {
        FileInputStream(file).use { input ->
            input.fd.sync()
        }
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) {
            return
        }
        runCatching {
            FileInputStream(directory).use { input ->
                input.fd.sync()
            }
        }
    }
}
