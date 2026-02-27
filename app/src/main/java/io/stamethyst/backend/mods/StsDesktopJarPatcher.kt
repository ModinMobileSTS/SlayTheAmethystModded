package io.stamethyst.backend.mods

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.HashMap
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object StsDesktopJarPatcher {
    @Throws(IOException::class)
    fun ensurePatchedStsJar(stsJar: File?, patchJar: File?) {
        if (stsJar == null || !stsJar.isFile) {
            throw IOException("desktop-1.0.jar not found")
        }
        if (patchJar == null || !patchJar.isFile) {
            throw IOException("gdx-patch.jar not found")
        }

        val patchEntries = loadPatchClassEntries(patchJar)
        if (!patchEntries.keys.containsAll(REQUIRED_STS_PATCH_CLASSES)) {
            throw IOException("gdx-patch.jar is missing required patched classes")
        }
        if (isStsPatched(stsJar, patchEntries)) {
            return
        }

        val tempJar = File(stsJar.absolutePath + ".patching.tmp")
        val seenNames: MutableSet<String> = HashSet()
        FileInputStream(stsJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        while (true) {
                            val entry = zipIn.nextEntry ?: break
                            val name = entry.name
                            if (entry.isDirectory || !seenNames.add(name)) {
                                zipIn.closeEntry()
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            val patchBytes = patchEntries[name]
                            if (patchBytes != null) {
                                zipOut.write(patchBytes)
                            } else {
                                JarFileIoUtils.copyStream(zipIn, zipOut)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                        }

                        for ((name, data) in patchEntries) {
                            if (seenNames.contains(name)) {
                                continue
                            }
                            val outEntry = ZipEntry(name)
                            zipOut.putNextEntry(outEntry)
                            zipOut.write(data)
                            zipOut.closeEntry()
                        }
                    }
                }
            }
        }

        if (!isStsPatched(tempJar, patchEntries)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch desktop-1.0.jar with gdx-patch classes")
        }

        if (stsJar.exists() && !stsJar.delete()) {
            throw IOException("Failed to replace ${stsJar.absolutePath}")
        }
        if (!tempJar.renameTo(stsJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${stsJar.absolutePath}")
        }
        stsJar.setLastModified(System.currentTimeMillis())
    }

    @Throws(IOException::class)
    private fun loadPatchClassEntries(patchJar: File): Map<String, ByteArray> {
        val entries: MutableMap<String, ByteArray> = HashMap()
        FileInputStream(patchJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory ||
                        !shouldPatchStsEntry(name) ||
                        name.startsWith("META-INF/") ||
                        entries.containsKey(name)
                    ) {
                        zipIn.closeEntry()
                        continue
                    }
                    entries[name] = JarFileIoUtils.readAll(zipIn)
                    zipIn.closeEntry()
                }
            }
        }
        return entries
    }

    private fun shouldPatchStsEntry(entryName: String): Boolean {
        return STS_PATCH_BUILD_PROPERTIES == entryName ||
            STS_PATCH_PIXEL_SCALE_CLASS == entryName ||
            STS_PATCH_LWJGL_NATIVES_CLASS == entryName ||
            STS_PATCH_SHARED_LOADER_CLASS == entryName ||
            STS_PATCH_STEAM_UTILS_CLASS == entryName ||
            STS_PATCH_STEAM_UTILS_ENUM_CLASS == entryName ||
            STS_PATCH_STEAM_INPUT_HELPER_CLASS == entryName ||
            STS_PATCH_GL_FRAMEBUFFER_CLASS == entryName ||
            entryName.startsWith(STS_PATCH_DESKTOP_CONTROLLER_MANAGER_PREFIX) ||
            entryName.startsWith(STS_PATCH_LWJGL_APPLICATION_PREFIX) ||
            entryName.startsWith(STS_PATCH_LWJGL_GRAPHICS_PREFIX) ||
            entryName.startsWith(GDX_BRIDGE_LWJGL_INPUT_PREFIX)
    }

    private fun isStsPatched(stsJar: File, patchEntries: Map<String, ByteArray>): Boolean {
        return try {
            ZipFile(stsJar).use { zipFile ->
                for (className in REQUIRED_STS_PATCH_CLASSES) {
                    val entry = zipFile.getEntry(className)
                    val expected = patchEntries[className]
                    if (entry == null || expected == null) {
                        return false
                    }
                    val actual = JarFileIoUtils.readEntryBytes(zipFile, entry)
                    if (!actual.contentEquals(expected)) {
                        return false
                    }
                }
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
