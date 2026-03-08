package io.stamethyst.backend.mods

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Enumeration
import java.util.HashSet
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object ModClasspathJarBuilder {
    fun interface BuildProgressCallback {
        fun onProgress(percent: Int, message: String)
    }

    @Throws(IOException::class)
    fun ensureStsResourceJar(
        stsJar: File?,
        targetJar: File?,
        progressCallback: BuildProgressCallback? = null
    ) {
        ensureResourceJar(
            sourceJar = stsJar,
            targetJar = targetJar,
            requiredEntry = STS_RESOURCE_SENTINEL,
            label = "STS",
            progressCallback = progressCallback
        )
    }

    @Throws(IOException::class)
    fun ensureBaseModResourceJar(
        baseModJar: File?,
        targetJar: File?,
        progressCallback: BuildProgressCallback? = null
    ) {
        ensureResourceJar(
            sourceJar = baseModJar,
            targetJar = targetJar,
            requiredEntry = BASEMOD_RESOURCE_SENTINEL,
            label = "BaseMod",
            progressCallback = progressCallback
        )
    }

    @Throws(IOException::class)
    fun ensureResourceJar(
        sourceJar: File?,
        targetJar: File?,
        requiredEntry: String,
        label: String,
        progressCallback: BuildProgressCallback? = null
    ) {
        reportProgress(progressCallback, 0, "Checking $label resources classpath jar...")
        if (sourceJar == null || !sourceJar.isFile) {
            throw IOException("$label jar not found")
        }
        if (targetJar == null) {
            throw IOException("Target jar is null")
        }
        if (targetJar.isFile &&
            targetJar.length() > 0 &&
            targetJar.lastModified() >= sourceJar.lastModified() &&
            hasRequiredResource(targetJar, requiredEntry)
        ) {
            reportProgress(progressCallback, 100, "$label resources classpath jar ready")
            return
        }

        val parent = targetJar.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val tempJar = File(targetJar.absolutePath + ".tmp")
        var copiedEntries = 0
        val seenNames: MutableSet<String> = HashSet()
        var lastReportedPercent = -1
        val sourceJarLength = sourceJar.length().coerceAtLeast(1L)
        FileInputStream(sourceJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        zipOut.setLevel(Deflater.BEST_SPEED)
                        reportProgress(progressCallback, 1, "Building $label resources classpath jar...")
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory ||
                                name.startsWith("META-INF/") ||
                                name.endsWith(".class") ||
                                !seenNames.add(name)
                            ) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            JarFileIoUtils.copyStream(zipIn, zipOut)
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                            copiedEntries++
                            val sourceProgress = ((fileInput.channel.position() * 100L) / sourceJarLength)
                                .toInt()
                                .coerceIn(1, 99)
                            if (sourceProgress >= lastReportedPercent + 2) {
                                lastReportedPercent = sourceProgress
                                reportProgress(
                                    progressCallback,
                                    sourceProgress,
                                    "Building $label resources classpath jar... $sourceProgress%"
                                )
                            }
                        }
                    }
                }
            }
        }

        if (copiedEntries == 0 || !hasRequiredResource(tempJar, requiredEntry)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to build $label resources classpath jar")
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw IOException("Failed to replace ${targetJar.absolutePath}")
        }
        if (!tempJar.renameTo(targetJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${targetJar.absolutePath}")
        }
        targetJar.setLastModified(sourceJar.lastModified())
        reportProgress(progressCallback, 100, "$label resources classpath jar ready")
    }

    fun hasRequiredResource(jarFile: File?, requiredEntry: String?): Boolean {
        if (jarFile == null || requiredEntry == null) {
            return false
        }
        return try {
            ZipFile(jarFile).use { zipFile -> zipFile.getEntry(requiredEntry) != null }
        } catch (_: Throwable) {
            false
        }
    }

    @Throws(IOException::class)
    fun ensureGdxApiJar(
        stsJar: File?,
        targetJar: File?,
        progressCallback: BuildProgressCallback? = null
    ) {
        reportProgress(progressCallback, 0, "Checking gdx API classpath jar...")
        if (stsJar == null || !stsJar.isFile) {
            throw IOException("desktop-1.0.jar not found")
        }
        if (targetJar == null) {
            throw IOException("Target jar is null")
        }
        if (targetJar.isFile &&
            targetJar.length() > 0 &&
            targetJar.lastModified() >= stsJar.lastModified() &&
            hasRequiredGdxApi(targetJar)
        ) {
            reportProgress(progressCallback, 100, "gdx API classpath jar ready")
            return
        }

        val parent = targetJar.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val tempJar = File(targetJar.absolutePath + ".tmp")
        var copiedClasses = 0
        ZipFile(stsJar).use { zipFile ->
            FileOutputStream(tempJar, false).use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    reportProgress(progressCallback, 5, "Building gdx API classpath jar...")
                    REQUIRED_GDX_CLASSES.forEach { classEntry ->
                        val entry = zipFile.getEntry(classEntry) ?: return@forEach
                        if (entry.isDirectory) {
                            return@forEach
                        }
                        val outEntry = ZipEntry(classEntry)
                        if (entry.time > 0) {
                            outEntry.time = entry.time
                        }
                        zipOut.putNextEntry(outEntry)
                        zipFile.getInputStream(entry).use { input ->
                            JarFileIoUtils.copyStream(input, zipOut)
                        }
                        zipOut.closeEntry()
                        copiedClasses++
                    }
                }
            }
        }

        if (copiedClasses == 0 || !hasRequiredGdxApi(tempJar)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("No gdx classes found in desktop-1.0.jar")
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw IOException("Failed to replace ${targetJar.absolutePath}")
        }
        if (!tempJar.renameTo(targetJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${targetJar.absolutePath}")
        }
        targetJar.setLastModified(stsJar.lastModified())
        reportProgress(progressCallback, 100, "gdx API classpath jar ready")
    }

    fun hasRequiredGdxApi(jarFile: File?): Boolean {
        if (jarFile == null) {
            return false
        }
        return try {
            ZipFile(jarFile).use { zipFile ->
                val foundRequired: MutableSet<String> = HashSet()
                val entries: Enumeration<out ZipEntry> = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        continue
                    }
                    val name = entry.name
                    if (REQUIRED_GDX_CLASSES.contains(name)) {
                        foundRequired.add(name)
                        continue
                    }
                    if (name.startsWith(GDX_BACKEND_PREFIX) &&
                        name.endsWith(".class") &&
                        !ALLOWED_PARENT_BACKEND_CLASSES.contains(name)
                    ) {
                        return false
                    }
                }
                foundRequired.containsAll(REQUIRED_GDX_CLASSES)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Throws(IOException::class)
    fun ensureGdxBridgeJar(sourcePatchJar: File?, targetJar: File?) {
        if (sourcePatchJar == null || !sourcePatchJar.isFile) {
            throw IOException("gdx-patch.jar not found")
        }
        if (targetJar == null) {
            throw IOException("Target jar is null")
        }
        if (targetJar.isFile &&
            targetJar.length() > 0 &&
            targetJar.lastModified() >= sourcePatchJar.lastModified() &&
            hasRequiredGdxBridge(targetJar)
        ) {
            return
        }

        val parent = targetJar.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val tempJar = File(targetJar.absolutePath + ".tmp")
        var copiedClasses = 0
        val seenNames: MutableSet<String> = HashSet()
        FileInputStream(sourcePatchJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory ||
                                (!GDX_BRIDGE_CLASSES.contains(name) &&
                                    !(name.startsWith(GDX_BRIDGE_LWJGL_INPUT_PREFIX) &&
                                        name.endsWith(".class"))) ||
                                !seenNames.add(name)
                            ) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            JarFileIoUtils.copyStream(zipIn, zipOut)
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                            copiedClasses++
                        }
                    }
                }
            }
        }

        if (copiedClasses < GDX_BRIDGE_CLASSES.size || !hasRequiredGdxBridge(tempJar)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to prepare MTS gdx bridge jar")
        }

        if (targetJar.exists() && !targetJar.delete()) {
            throw IOException("Failed to replace ${targetJar.absolutePath}")
        }
        if (!tempJar.renameTo(targetJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${targetJar.absolutePath}")
        }
        targetJar.setLastModified(sourcePatchJar.lastModified())
    }

    fun hasRequiredGdxBridge(jarFile: File?): Boolean {
        if (jarFile == null) {
            return false
        }
        return try {
            ZipFile(jarFile).use { zipFile ->
                val found: MutableSet<String> = HashSet()
                val entries: Enumeration<out ZipEntry> = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        continue
                    }
                    val name = entry.name
                    if (GDX_BRIDGE_CLASSES.contains(name)) {
                        found.add(name)
                    }
                }
                found.containsAll(GDX_BRIDGE_CLASSES)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun reportProgress(
        progressCallback: BuildProgressCallback?,
        percent: Int,
        message: String
    ) {
        progressCallback?.onProgress(percent.coerceIn(0, 100), message)
    }
}
