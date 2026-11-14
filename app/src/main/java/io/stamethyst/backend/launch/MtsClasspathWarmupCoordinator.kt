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

object MtsClasspathWarmupCoordinator {
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
    ): Boolean = prepareForLaunch(
        context, progressCallback, launchSnapshot, assumeCommonPreparationDone,
        verifiedGameBodyPatch = null
    )

    @Throws(IOException::class)
    internal fun prepareForLaunch(
        context: Context,
        progressCallback: StartupProgressCallback?,
        launchSnapshot: ModManager.LaunchModSnapshot?,
        assumeCommonPreparationDone: Boolean,
        verifiedGameBodyPatch: GameBodyPatchVerification?
    ): Boolean {
        return prepareIfReady(
            context = context,
            progressCallback = progressCallback,
            skipWhenCacheCurrent = true,
            launchSnapshot = launchSnapshot,
            assumeCommonPreparationDone = assumeCommonPreparationDone,
            verifiedGameBodyPatch = verifiedGameBodyPatch
        )
    }

    @Throws(IOException::class)
    private fun prepareIfReady(
        context: Context,
        progressCallback: StartupProgressCallback?,
        skipWhenCacheCurrent: Boolean,
        launchSnapshot: ModManager.LaunchModSnapshot?,
        assumeCommonPreparationDone: Boolean,
        verifiedGameBodyPatch: GameBodyPatchVerification? = null
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
        // Mod-list preparation does not change the five core jars or the generated
        // classpath jars. Reuse the whole verdict until the rebuild boundary, including
        // a failed deep check, instead of checking the same inputs twice.
        var cacheCurrent: Boolean? = null
        fun isCacheCurrentForPreparation(): Boolean {
            return cacheCurrent ?: run {
                val fingerprints = captureFingerprints(context)
                isCacheCurrent(
                    context, buildCacheMarkerValue(context, fingerprints),
                    verifiedGameBodyPatch, fingerprints
                ).also { cacheCurrent = it }
            }
        }
        if (skipWhenCacheCurrent && launchSnapshot != null && isCacheCurrentForPreparation()) {
            reportProgress(
                progressCallback,
                100,
                context.progressText(R.string.startup_progress_using_prepared_mts_classpath_cache)
            )
            return true
        }
        val snapshot = OptionalModStorageCoordinator.prepareMtsModFileList(context, launchSnapshot)
        if (skipWhenCacheCurrent && isCacheCurrentForPreparation()) {
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
        rebuildCache(context) {
            ModJarSupport.prepareMtsClasspath(
                context,
                mapProgressRange(progressCallback, 50, 96),
                snapshot
            )
        }
        reportProgress(
            progressCallback,
            98,
            context.progressText(R.string.startup_progress_mts_classpath_cache_ready)
        )
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_mts_startup_cache_ready)
        )
        return true
    }

    @JvmStatic
    fun isCacheCurrent(context: Context, markerValue: String = buildCacheMarkerValue(context)): Boolean =
        isCacheCurrent(context, markerValue, verifiedGameBodyPatch = null, fingerprints = null)

    internal fun isCacheCurrent(
        context: Context,
        markerValue: String,
        verifiedGameBodyPatch: GameBodyPatchVerification?,
        fingerprints: LaunchJarFingerprints?,
        isPatched: (File, File) -> Boolean = StsDesktopJarPatcher::isPatchedWithCurrentPatch
    ): Boolean {
        val markerFile = RuntimePaths.mtsClasspathCacheMarker(context)
        val actualMarker = try {
            markerFile.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (markerValue.isBlank() || markerValue.trim() != actualMarker) {
            return false
        }
        val stsJar = RuntimePaths.importedStsJar(context)
        val patchJar = RuntimePaths.gdxPatchJar(context)
        val alreadyVerified = fingerprints != null &&
            verifiedGameBodyPatch?.matches(stsJar, patchJar, fingerprints) == true
        if (!alreadyVerified && !isPatched(stsJar, patchJar)) {
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

    /** The builder can patch core jars; never write its pre-build fingerprint snapshot. */
    @Throws(IOException::class)
    internal fun rebuildCache(context: Context, rebuild: () -> Unit) {
        rebuild()
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
    internal fun buildCacheMarkerValue(
        context: Context,
        fingerprints: LaunchJarFingerprints = captureFingerprints(context)
    ): String = buildString {
        append("schema|4").append('\n')
        append(fingerprints.line("desktop", RuntimePaths.importedStsJar(context))).append('\n')
        append(fingerprints.line("modthespire", RuntimePaths.importedMtsJar(context))).append('\n')
        append(fingerprints.line("basemod", RuntimePaths.importedBaseModJar(context))).append('\n')
        append(fingerprints.line("stslib", RuntimePaths.importedStsLibJar(context))).append('\n')
        append(fingerprints.line("gdxpatch", RuntimePaths.gdxPatchJar(context))).append('\n')
    }

    internal fun captureFingerprints(context: Context): LaunchJarFingerprints =
        LaunchJarFingerprints.capture(listOf(
            RuntimePaths.importedStsJar(context),
            RuntimePaths.importedMtsJar(context),
            RuntimePaths.importedBaseModJar(context),
            RuntimePaths.importedStsLibJar(context),
            RuntimePaths.gdxPatchJar(context)
        ))
}
