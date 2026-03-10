package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModClasspathJarBuilder
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.StsJarValidator
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
        if (!RuntimePaths.importedStsJar(context).isFile) {
            return false
        }

        reportProgress(progressCallback, 0, "Preparing MTS startup cache...")
        ComponentInstaller.ensureInstalled(
            context,
            mapProgressRange(progressCallback, 1, 40)
        )
        reportProgress(progressCallback, 42, "Validating desktop-1.0.jar...")
        StsJarValidator.validate(RuntimePaths.importedStsJar(context))
        reportProgress(progressCallback, 45, "Preparing MTS classpath cache...")
        ModJarSupport.prepareMtsClasspath(
            context,
            mapProgressRange(progressCallback, 45, 94)
        )
        reportProgress(progressCallback, 96, "Resolving enabled mod launch list...")
        ModManager.resolveLaunchModIds(context)
        writeCacheMarker(context)
        reportProgress(progressCallback, 100, "MTS startup cache ready")
        return true
    }

    @JvmStatic
    fun isCacheCurrent(context: Context): Boolean {
        val markerFile = RuntimePaths.mtsClasspathCacheMarker(context)
        val markerValue = try {
            markerFile.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (markerValue.isEmpty() || markerValue != buildCacheMarkerValue(context)) {
            return false
        }
        return ModClasspathJarBuilder.hasRequiredGdxApi(RuntimePaths.mtsGdxApiJar(context)) &&
            ModClasspathJarBuilder.hasRequiredLog4jRuntime(RuntimePaths.mtsLog4jRuntimeJar(context)) &&
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

    @JvmStatic
    fun markPrepared(context: Context) {
        runCatching {
            writeCacheMarker(context)
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

    private fun buildCacheMarkerValue(context: Context): String = buildString {
        append(fileFingerprint("desktop", RuntimePaths.importedStsJar(context))).append('\n')
        append(fileFingerprint("modthespire", RuntimePaths.importedMtsJar(context))).append('\n')
        append(fileFingerprint("basemod", RuntimePaths.importedBaseModJar(context))).append('\n')
        append(fileFingerprint("stslib", RuntimePaths.importedStsLibJar(context))).append('\n')
        append(fileFingerprint("gdxpatch", RuntimePaths.gdxPatchJar(context))).append('\n')
    }

    private fun fileFingerprint(label: String, file: File): String {
        val exists = file.isFile
        val length = if (exists) file.length() else -1L
        val lastModified = if (exists) file.lastModified() else -1L
        return "$label|${file.absolutePath}|$length|$lastModified"
    }
}
