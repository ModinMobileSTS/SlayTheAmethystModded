package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModClasspathJarBuilder
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.mods.StsDesktopJarPatcher
import io.stamethyst.backend.mods.BASEMOD_RESOURCE_SENTINEL
import io.stamethyst.backend.mods.STS_RESOURCE_SENTINEL
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

object MtsClasspathWarmupCoordinator {
    private val SEPARATOR_BYTE = byteArrayOf('|'.code.toByte())

    @JvmStatic
    @Throws(IOException::class)
    fun prewarmIfReady(context: Context, progressCallback: StartupProgressCallback? = null): Boolean {
        return prepareIfReady(
            context = context,
            progressCallback = progressCallback,
            skipWhenCacheCurrent = false,
            launchSnapshot = null,
            assumeCommonPreparationDone = false
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepareForLaunch(
        context: Context,
        progressCallback: StartupProgressCallback? = null,
        launchSnapshot: ModManager.LaunchModSnapshot? = null,
        assumeCommonPreparationDone: Boolean = false
    ): Boolean {
        return prepareIfReady(
            context = context,
            progressCallback = progressCallback,
            skipWhenCacheCurrent = true,
            launchSnapshot = launchSnapshot,
            assumeCommonPreparationDone = assumeCommonPreparationDone
        )
    }

    @Throws(IOException::class)
    private fun prepareIfReady(
        context: Context,
        progressCallback: StartupProgressCallback?,
        skipWhenCacheCurrent: Boolean,
        launchSnapshot: ModManager.LaunchModSnapshot?,
        assumeCommonPreparationDone: Boolean
    ): Boolean {
        if (!RuntimePaths.importedStsJar(context).isFile) {
            return false
        }

        if (assumeCommonPreparationDone) {
            reportProgress(
                progressCallback,
                0,
                context.progressText(R.string.startup_progress_preparing_mts_startup_cache)
            )
        } else {
            reportProgress(
                progressCallback,
                0,
                context.progressText(R.string.startup_progress_preparing_mts_startup_cache)
            )
            ComponentInstaller.ensureInstalled(
                context,
                mapProgressRange(progressCallback, 1, 40)
            )
            reportProgress(
                progressCallback,
                42,
                context.progressText(R.string.startup_progress_validating_desktop_jar)
            )
            StsJarValidator.validate(RuntimePaths.importedStsJar(context))
        }
        reportProgress(
            progressCallback,
            45,
            context.progressText(R.string.startup_progress_resolving_enabled_mod_launch_list)
        )
        // Both cache-current evaluations below read the same five core files, none of
        // which prepareMtsModFileList touches, so they must reach the same verdict.
        // Computing the marker once turns the second evaluation into a marker-file
        // comparison instead of a second full central-directory digest pass over the
        // desktop jar and every imported component jar.
        var markerValue: String? = null
        fun isCacheCurrentWithMemoizedMarker(): Boolean {
            val computed = markerValue ?: buildCacheMarkerValue(context).also { markerValue = it }
            return isCacheCurrent(context, computed)
        }
        if (skipWhenCacheCurrent && launchSnapshot != null && isCacheCurrentWithMemoizedMarker()) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_using_prepared_mts_classpath_cache)
            )
            return true
        }
        val snapshot = OptionalModStorageCoordinator.prepareMtsModFileList(context, launchSnapshot)
        if (skipWhenCacheCurrent && isCacheCurrentWithMemoizedMarker()) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_using_prepared_mts_classpath_cache)
            )
            return true
        }
        reportProgress(
            progressCallback,
            50,
            context.progressText(R.string.startup_progress_preparing_mts_classpath_cache)
        )
        ModJarSupport.prepareMtsClasspath(
            context,
            mapProgressRange(progressCallback, 50, 96),
            snapshot
        )
        reportProgress(
            progressCallback,
            98,
            context.progressText(R.string.startup_progress_mts_classpath_cache_ready)
        )
        writeCacheMarker(context)
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_mts_startup_cache_ready)
        )
        return true
    }

    @JvmStatic
    fun isCacheCurrent(context: Context, markerValue: String = buildCacheMarkerValue(context)): Boolean {
        val markerFile = RuntimePaths.mtsClasspathCacheMarker(context)
        val actualMarker = try {
            markerFile.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (markerValue.isEmpty() || markerValue != actualMarker) {
            return false
        }
        if (!StsDesktopJarPatcher.isPatchedWithCurrentPatch(
                RuntimePaths.importedStsJar(context),
                RuntimePaths.gdxPatchJar(context)
            )
        ) {
            return false
        }
        return ModClasspathJarBuilder.hasRequiredGdxApi(RuntimePaths.mtsGdxApiJar(context)) &&
            ModClasspathJarBuilder.hasRequiredResource(
                RuntimePaths.mtsStsResourcesJar(context),
                STS_RESOURCE_SENTINEL
            ) &&
            ModClasspathJarBuilder.hasRequiredResource(
                RuntimePaths.mtsBaseModResourcesJar(context),
                BASEMOD_RESOURCE_SENTINEL
            )
    }

    @JvmStatic
    fun invalidateCache(context: Context) {
        runCatching {
            RuntimePaths.mtsClasspathCacheMarker(context).delete()
        }
    }

    private fun mapProgressRange(
        callback: StartupProgressCallback?,
        startPercent: Int,
        endPercent: Int
    ): StartupProgressCallback? {
        if (callback == null) {
            return null
        }
        val safeStart = startPercent.coerceIn(0, 100)
        val safeEnd = endPercent.coerceIn(0, 100)
        return StartupProgressCallback { percent, message ->
            val bounded = percent.coerceIn(0, 100)
            val mapped = safeStart + (((safeEnd - safeStart) * bounded) / 100f).toInt()
            callback.onProgress(mapped.coerceIn(0, 100), message)
        }
    }

    private fun reportProgress(
        callback: StartupProgressCallback?,
        percent: Int,
        message: String
    ) {
        callback?.onProgress(percent.coerceIn(0, 100), message)
    }

    @Throws(IOException::class)
    private fun writeCacheMarker(context: Context) {
        val markerFile = RuntimePaths.mtsClasspathCacheMarker(context)
        val parent = markerFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create MTS cache marker directory: ${parent.absolutePath}")
        }
        markerFile.writeText(buildCacheMarkerValue(context), StandardCharsets.UTF_8)
    }

    /**
     * The leading schema line is a version guard: adding or reordering a field below
     * changes the marker format, and without it an older marker written by a previous
     * build could still compare equal to a newer one field-for-field.
     */
    private fun buildCacheMarkerValue(context: Context): String = buildString {
        append("schema|4").append('\n')
        append(jarFingerprint("desktop", RuntimePaths.importedStsJar(context))).append('\n')
        append(jarFingerprint("modthespire", RuntimePaths.importedMtsJar(context))).append('\n')
        append(jarFingerprint("basemod", RuntimePaths.importedBaseModJar(context))).append('\n')
        append(jarFingerprint("stslib", RuntimePaths.importedStsLibJar(context))).append('\n')
        append(jarFingerprint("gdxpatch", RuntimePaths.gdxPatchJar(context))).append('\n')
    }

    /**
     * Mirrors the patch cache's jar fingerprint: size plus a digest over the central
     * directory (entry names, sizes, CRC32s) instead of mtime, so a jar rebuilt in place
     * with an unchanged size cannot pass as current, and a copy that only moves mtime
     * does not force a needless rebuild. Degrades to size and mtime for unreadable zips.
     */
    private fun jarFingerprint(label: String, file: File): String {
        if (!file.isFile) {
            return "$label|${file.absolutePath}|-1|-1"
        }
        val entryDigest = try {
            ZipFile(file).use { zip ->
                val digest = MessageDigest.getInstance("SHA-256")
                for (entry in zip.entries()) {
                    digest.update(entry.name.toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.size.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.crc.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                }
                digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            }
        } catch (_: Throwable) {
            null
        }
        if (entryDigest == null) {
            return "$label|${file.absolutePath}|${file.length()}|${file.lastModified()}|nozip"
        }
        return "$label|${file.absolutePath}|${file.length()}|$entryDigest"
    }
}
