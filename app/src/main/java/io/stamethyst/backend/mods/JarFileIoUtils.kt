package io.stamethyst.backend.mods

import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal object JarFileIoUtils {
    @Throws(IOException::class)
    fun copyFileReplacing(source: File?, target: File?) {
        if (source == null || !source.isFile) {
            throw IOException("Source file not found")
        }
        if (target == null) {
            throw IOException("Target file is null")
        }

        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val temp = File(target.absolutePath + ".tmpcopy")
        FileInputStream(source).use { input ->
            FileOutputStream(temp, false).use { output ->
                copyStream(input, output)
            }
        }

        if (target.exists() && !target.delete()) {
            if (temp.exists()) {
                temp.delete()
            }
            throw IOException("Failed to replace ${target.absolutePath}")
        }
        if (!temp.renameTo(target)) {
            if (temp.exists()) {
                temp.delete()
            }
            throw IOException("Failed to move ${temp.absolutePath} -> ${target.absolutePath}")
        }
        target.setLastModified(
            if (source.lastModified() > 0L) source.lastModified() else System.currentTimeMillis()
        )
    }

    @Throws(IOException::class)
    fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val backup = File(target.absolutePath + ".bak")
        if (backup.exists() && !backup.delete()) {
            throw IOException("Failed to clear stale backup: ${backup.absolutePath}")
        }
        if (target.exists() && !target.renameTo(backup)) {
            throw IOException(
                "Failed to back up ${target.absolutePath}; targetExists=${target.exists()} " +
                    "targetLength=${target.length()} usableSpace=${target.usableSpace} " +
                    "freeSpace=${target.freeSpace} totalSpace=${target.totalSpace}"
            )
        }
        var moved = false
        try {
            if (source.renameTo(target)) {
                moved = true
            } else {
                FileInputStream(source).use { input ->
                    FileOutputStream(target, false).use { output ->
                        copyStream(input, output)
                        output.fd.sync()
                    }
                }
                if (!source.delete()) {
                    throw IOException(
                        "Failed to remove temporary file: ${source.absolutePath}; " +
                            "sourceExists=${source.exists()} sourceLength=${source.length()} " +
                            "targetExists=${target.exists()} targetLength=${target.length()} " +
                            "usableSpace=${target.usableSpace} freeSpace=${target.freeSpace} totalSpace=${target.totalSpace}"
                    )
                }
                moved = true
            }
        } finally {
            if (!moved) {
                if (target.exists()) {
                    target.delete()
                }
                if (backup.exists() && !backup.renameTo(target)) {
                    throw IOException(
                        "Failed to restore ${backup.absolutePath} -> ${target.absolutePath}; " +
                            "backupExists=${backup.exists()} targetExists=${target.exists()} " +
                            "usableSpace=${target.usableSpace} freeSpace=${target.freeSpace} totalSpace=${target.totalSpace}"
                    )
                }
            }
        }
        if (backup.exists() && !backup.delete()) {
            throw IOException("Failed to delete backup: ${backup.absolutePath}")
        }
        target.setLastModified(System.currentTimeMillis())
    }

    @Throws(IOException::class)
    fun copyStream(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
    }

    @Throws(IOException::class)
    fun readEntry(zipFile: ZipFile, entry: ZipEntry): String {
        zipFile.getInputStream(entry).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                return output.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    @Throws(IOException::class)
    fun readAll(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    @Throws(IOException::class)
    fun forEachZipEntry(jarFile: File, visitor: (ZipEntry, InputStream) -> Boolean) {
        FileInputStream(jarFile).use { input ->
            forEachZipEntry(input, visitor)
        }
    }

    @Throws(IOException::class)
    fun forEachZipEntry(input: InputStream, visitor: (ZipEntry, InputStream) -> Boolean) {
        ZipInputStream(BufferedInputStream(input)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                val keepGoing = visitor(entry, zipInput)
                zipInput.closeEntry()
                if (!keepGoing) {
                    break
                }
            }
        }
    }

    fun hasZipEntry(jarFile: File, entryName: String): Boolean {
        if (!jarFile.isFile) {
            return false
        }
        return try {
            var found = false
            forEachZipEntry(jarFile) { entry, _ ->
                if (!entry.isDirectory && entry.name == entryName) {
                    found = true
                    false
                } else {
                    true
                }
            }
            found
        } catch (_: Throwable) {
            false
        }
    }

    @Throws(IOException::class)
    fun readEntryBytes(zipFile: ZipFile, entry: ZipEntry): ByteArray {
        zipFile.getInputStream(entry).use { input ->
            return readAll(input)
        }
    }

    fun readJarEntryBytes(jarFile: File?, entryName: String?): ByteArray? {
        if (jarFile == null || entryName.isNullOrBlank()) {
            return null
        }
        return try {
            ZipFile(jarFile).use { zipFile ->
                val entry = zipFile.getEntry(entryName)
                if (entry == null || entry.isDirectory) {
                    null
                } else {
                    readEntryBytes(zipFile, entry)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun findEntryIgnoreCase(zipFile: ZipFile, entryName: String): ZipEntry? {
        val target = entryName.lowercase(Locale.ROOT)
        return zipFile
            .stream()
            .filter { entry -> entry.name.lowercase(Locale.ROOT).endsWith(target) }
            .findFirst()
            .orElse(null)
    }
}
