package io.stamethyst.backend.mods

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Enumeration
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object ModClasspathJarBuilder {
    @Throws(IOException::class)
    fun ensureStsResourceJar(stsJar: File?, targetJar: File?) {
        ensureResourceJar(stsJar, targetJar, STS_RESOURCE_SENTINEL, "STS")
    }

    @Throws(IOException::class)
    fun ensureBaseModResourceJar(baseModJar: File?, targetJar: File?) {
        ensureResourceJar(baseModJar, targetJar, BASEMOD_RESOURCE_SENTINEL, "BaseMod")
    }

    @Throws(IOException::class)
    fun ensureResourceJar(
        sourceJar: File?,
        targetJar: File?,
        requiredEntry: String,
        label: String
    ) {
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
            return
        }

        val parent = targetJar.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val tempJar = File(targetJar.absolutePath + ".tmp")
        var copiedEntries = 0
        val seenNames: MutableSet<String> = HashSet()
        FileInputStream(sourceJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
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
    fun ensureGdxApiJar(stsJar: File?, targetJar: File?) {
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
            return
        }

        val parent = targetJar.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val tempJar = File(targetJar.absolutePath + ".tmp")
        var copiedClasses = 0
        val seenNames: MutableSet<String> = HashSet()
        FileInputStream(stsJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory ||
                                !name.endsWith(".class") ||
                                !REQUIRED_GDX_CLASSES.contains(name) ||
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
}
