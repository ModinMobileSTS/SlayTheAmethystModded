package io.stamethyst.backend.mods

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

object StsJarValidator {
    private const val DESKTOP_LAUNCHER_CLASS =
        "com/megacrit/cardcrawl/desktop/DesktopLauncher.class"
    private const val DESKTOP_LAUNCHER_MAIN =
        "com.megacrit.cardcrawl.desktop.DesktopLauncher"

    @JvmStatic
    @Throws(IOException::class)
    fun validate(jarFile: File) {
        if (!jarFile.exists() || jarFile.length() == 0L) {
            throw IOException("desktop-1.0.jar is missing or empty")
        }

        try {
            validateStrict(jarFile)
            return
        } catch (zipError: ZipException) {
            val message = zipError.message
            if (message == null || !message.lowercase().contains("duplicate")) {
                throw zipError
            }
        }

        // Fallback for jars with duplicate ZIP entries (some modded/packed jars have this).
        validateLenient(jarFile)
    }

    @JvmStatic
    fun isValid(jarFile: File?): Boolean {
        if (jarFile == null) {
            return false
        }
        return try {
            validate(jarFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Throws(IOException::class)
    private fun validateStrict(jarFile: File) {
        JarFile(jarFile).use { jar ->
            val manifest: Manifest? = jar.manifest
            if (manifest != null) {
                val mainClass = manifest.mainAttributes.getValue("Main-Class")
                if (mainClass != null && DESKTOP_LAUNCHER_MAIN != mainClass.trim()) {
                    throw IOException("Main-Class mismatch: $mainClass")
                }
            }

            if (jar.getEntry(DESKTOP_LAUNCHER_CLASS) == null) {
                throw IOException("DesktopLauncher class not found in jar")
            }
        }
    }

    @Throws(IOException::class)
    private fun validateLenient(jarFile: File) {
        var manifestMainClass: String? = null
        var hasDesktopLauncher = false

        ZipInputStream(BufferedInputStream(FileInputStream(jarFile))).use { zipInput ->
            var entry: ZipEntry?
            while (zipInput.nextEntry.also { entry = it } != null) {
                val name = entry!!.name
                if (DESKTOP_LAUNCHER_CLASS == name) {
                    hasDesktopLauncher = true
                } else if ("META-INF/MANIFEST.MF".equals(name, ignoreCase = true)) {
                    val manifestBytes = readCurrentEntry(zipInput)
                    val manifest = Manifest(ByteArrayInputStream(manifestBytes))
                    manifestMainClass = manifest.mainAttributes.getValue("Main-Class")
                }
                zipInput.closeEntry()
            }
        }

        if (manifestMainClass != null && DESKTOP_LAUNCHER_MAIN != manifestMainClass.trim()) {
            throw IOException("Main-Class mismatch: $manifestMainClass")
        }

        if (!hasDesktopLauncher) {
            throw IOException("DesktopLauncher class not found in jar")
        }
    }

    @Throws(IOException::class)
    private fun readCurrentEntry(zipInput: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var read: Int
        while (zipInput.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
