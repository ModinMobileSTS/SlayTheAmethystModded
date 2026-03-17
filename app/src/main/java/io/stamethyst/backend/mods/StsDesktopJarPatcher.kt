package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.launch.progressText
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
import kotlin.math.roundToInt

internal object StsDesktopJarPatcher {
    internal const val LEGACY_FULL_CLASS_UI_PATCH_MARKER =
        "STS_LEGACY_FULL_CLASS_UI_PATCH_REIMPORT_REQUIRED"

    internal fun detectLegacyWholeClassUiPatch(
        stsJar: File?,
        patchJar: File?
    ): String? {
        if (stsJar == null || !stsJar.isFile || patchJar == null || !patchJar.isFile) {
            return null
        }
        return try {
            val patchEntries = loadPatchClassEntries(patchJar)
            if (!patchEntries.keys.containsAll(REQUIRED_STS_PATCH_CLASSES)) {
                return null
            }
            findLegacyWholeClassUiPatch(stsJar, patchEntries)
        } catch (_: Throwable) {
            null
        }
    }

    @Throws(IOException::class)
    fun ensurePatchedStsJar(
        context: Context,
        stsJar: File?,
        patchJar: File?,
        progressCallback: ModClasspathJarBuilder.BuildProgressCallback? = null
    ) {
        reportProgress(
            progressCallback,
            0,
            context.progressText(R.string.startup_progress_patching_desktop_jar_for_mts)
        )
        if (stsJar == null || !stsJar.isFile) {
            throw IOException("desktop-1.0.jar not found")
        }
        if (patchJar == null || !patchJar.isFile) {
            throw IOException("gdx-patch.jar not found")
        }

        reportProgress(
            progressCallback,
            6,
            context.progressText(R.string.startup_progress_loading_desktop_patch_files)
        )
        val patchEntries = loadPatchClassEntries(patchJar)
        if (!patchEntries.keys.containsAll(REQUIRED_STS_PATCH_CLASSES)) {
            throw IOException("gdx-patch.jar is missing required patched classes")
        }
        reportProgress(
            progressCallback,
            12,
            context.progressText(R.string.startup_progress_checking_desktop_patch_state)
        )
        val legacyPatchedClass = findLegacyWholeClassUiPatch(stsJar, patchEntries)
        if (legacyPatchedClass != null) {
            throw IOException(
                "$LEGACY_FULL_CLASS_UI_PATCH_MARKER:$legacyPatchedClass"
            )
        }
        if (isStsPatched(stsJar, patchEntries)) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_patched_desktop_jar_ready)
            )
            return
        }

        val tempJar = File(stsJar.absolutePath + ".patching.tmp")
        val seenNames: MutableSet<String> = HashSet()
        val sourceJarLength = stsJar.length().coerceAtLeast(1L)
        var lastReportedPercent = -1
        FileInputStream(stsJar).use { fileInput ->
            ZipInputStream(fileInput).use { zipIn ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        reportProgress(
                            progressCallback,
                            16,
                            context.progressText(R.string.startup_progress_rewriting_desktop_jar_for_mts)
                        )
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
                            if (patchBytes != null &&
                                StsUiTouchCompatPatcher.isMethodMergeClassEntry(name)
                            ) {
                                val originalBytes = JarFileIoUtils.readAll(zipIn)
                                val mergedBytes = StsUiTouchCompatPatcher.mergePatchedClass(
                                    entryName = name,
                                    targetClassBytes = originalBytes,
                                    donorClassBytes = patchBytes
                                )
                                zipOut.write(mergedBytes)
                            } else if (patchBytes != null) {
                                zipOut.write(patchBytes)
                            } else {
                                JarFileIoUtils.copyStream(zipIn, zipOut)
                            }
                            zipOut.closeEntry()
                            zipIn.closeEntry()
                            val sourceProgress = ((fileInput.channel.position() * 100L) / sourceJarLength)
                                .toInt()
                                .coerceIn(1, 99)
                            if (sourceProgress >= lastReportedPercent + 2) {
                                lastReportedPercent = sourceProgress
                                val mappedPercent = (18 + sourceProgress * 0.70f).roundToInt()
                                    .coerceIn(18, 88)
                                reportProgress(
                                    progressCallback,
                                    mappedPercent,
                                    context.progressText(
                                        R.string.startup_progress_rewriting_desktop_jar_for_mts_percent,
                                        sourceProgress
                                    )
                                )
                            }
                        }

                        for ((name, data) in patchEntries) {
                            if (seenNames.contains(name)) {
                                continue
                            }
                            if (StsUiTouchCompatPatcher.isMethodMergeClassEntry(name)) {
                                throw IOException("desktop-1.0.jar is missing required class: $name")
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

        reportProgress(
            progressCallback,
            92,
            context.progressText(R.string.startup_progress_verifying_patched_desktop_jar)
        )
        if (!isStsPatched(tempJar, patchEntries)) {
            if (tempJar.exists()) {
                tempJar.delete()
            }
            throw IOException("Failed to patch desktop-1.0.jar with gdx-patch classes")
        }

        reportProgress(
            progressCallback,
            97,
            context.progressText(R.string.startup_progress_finishing_desktop_jar_patch)
        )
        if (stsJar.exists() && !stsJar.delete()) {
            throw IOException("Failed to replace ${stsJar.absolutePath}")
        }
        if (!tempJar.renameTo(stsJar)) {
            throw IOException("Failed to move ${tempJar.absolutePath} -> ${stsJar.absolutePath}")
        }
        stsJar.setLastModified(System.currentTimeMillis())
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_patched_desktop_jar_ready)
        )
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
            STS_PATCH_TYPE_HELPER_CLASS == entryName ||
            STS_PATCH_RENAME_POPUP_CLASS == entryName ||
            STS_PATCH_SEED_PANEL_CLASS == entryName ||
            STS_PATCH_SINGLE_CARD_VIEW_POPUP_CLASS == entryName ||
            STS_PATCH_GL_TEXTURE_CLASS == entryName ||
            STS_PATCH_GL_FRAMEBUFFER_CLASS == entryName ||
            STS_PATCH_COLOR_TAB_BAR_CLASS == entryName ||
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
                    if (StsUiTouchCompatPatcher.isMethodMergeClassEntry(className)) {
                        if (actual.contentEquals(expected)) {
                            return false
                        }
                        val merged = StsUiTouchCompatPatcher.mergePatchedClass(
                            entryName = className,
                            targetClassBytes = actual,
                            donorClassBytes = expected
                        )
                        if (!actual.contentEquals(merged)) {
                            return false
                        }
                    } else if (!actual.contentEquals(expected)) {
                        return false
                    }
                }
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun findLegacyWholeClassUiPatch(
        stsJar: File,
        patchEntries: Map<String, ByteArray>
    ): String? {
        return try {
            ZipFile(stsJar).use { zipFile ->
                REQUIRED_STS_PATCH_CLASSES.firstOrNull { className ->
                    if (!StsUiTouchCompatPatcher.isMethodMergeClassEntry(className)) {
                        return@firstOrNull false
                    }
                    val entry = zipFile.getEntry(className) ?: return@firstOrNull false
                    val expected = patchEntries[className] ?: return@firstOrNull false
                    val actual = JarFileIoUtils.readEntryBytes(zipFile, entry)
                    actual.contentEquals(expected)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun extractLegacyWholeClassUiPatchClass(message: String?): String? {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val suffix = text.substringAfter(LEGACY_FULL_CLASS_UI_PATCH_MARKER, "")
        if (suffix.isEmpty()) {
            return null
        }
        return suffix
            .substringBefore('\n')
            .trim()
            .removePrefix(":")
            .ifEmpty { null }
    }

    private fun reportProgress(
        progressCallback: ModClasspathJarBuilder.BuildProgressCallback?,
        percent: Int,
        message: String
    ) {
        progressCallback?.onProgress(percent.coerceIn(0, 100), message)
    }
}
