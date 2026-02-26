package io.stamethyst.backend.mods

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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
