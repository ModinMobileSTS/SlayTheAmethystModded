package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.components.ComponentInstaller
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.runtime.RuntimePackInstaller
import java.io.IOException

object LaunchPreparationService {
    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, launchMode: String) {
        prepare(context, launchMode, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, launchMode: String, progressCallback: StartupProgressCallback?) {
        reportProgress(progressCallback, 3, "Installing launcher components...")
        ComponentInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 5, 35))

        reportProgress(progressCallback, 36, "Preparing Java runtime...")
        RuntimePackInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 36, 76))

        reportProgress(progressCallback, 78, "Ensuring runtime directories...")
        RuntimePaths.ensureBaseDirs(context)

        reportProgress(progressCallback, 86, "Validating desktop-1.0.jar...")
        StsJarValidator.validate(RuntimePaths.importedStsJar(context))

        if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
            reportProgress(progressCallback, 90, "Validating required mod jars...")
            ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(context))
            ModJarSupport.validateBaseModJar(RuntimePaths.importedBaseModJar(context))
            ModJarSupport.validateStsLibJar(RuntimePaths.importedStsLibJar(context))

            reportProgress(progressCallback, 95, "Preparing MTS classpath...")
            ModJarSupport.prepareMtsClasspath(context)
            ModManager.resolveLaunchModIds(context)
        }

        reportProgress(progressCallback, 100, "Launch preparation complete")
    }

    private fun mapProgressRange(
        callback: StartupProgressCallback?,
        startPercent: Int,
        endPercent: Int
    ): StartupProgressCallback? {
        if (callback == null) {
            return null
        }
        val safeStart = clampPercent(startPercent)
        val safeEnd = clampPercent(endPercent)
        return StartupProgressCallback { percent, message ->
            callback.onProgress(mapRangeProgress(percent, safeStart, safeEnd), message)
        }
    }

    private fun mapRangeProgress(percent: Int, startPercent: Int, endPercent: Int): Int {
        val bounded = clampPercent(percent)
        val ratio = bounded / 100f
        return startPercent + Math.round((endPercent - startPercent) * ratio)
    }

    private fun clampPercent(value: Int): Int {
        return Math.max(0, Math.min(100, value))
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        callback?.onProgress(clampPercent(percent), message)
    }
}
