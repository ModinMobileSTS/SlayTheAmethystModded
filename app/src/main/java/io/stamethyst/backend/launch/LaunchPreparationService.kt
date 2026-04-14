package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.R
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.launch.ComponentInstaller
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.mods.OptionalModStorageCoordinator
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.runtime.RuntimePackInstaller
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object LaunchPreparationService {
    @Throws(IOException::class)
    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("Launch preparation cancelled")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, launchMode: String) {
        prepare(context, launchMode, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, launchMode: String, progressCallback: StartupProgressCallback?) {
        MemoryDiagnosticsLogger.logEvent(
            context,
            "launch_preparation_started",
            mapOf("launchMode" to launchMode)
        )
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            3,
            context.progressText(R.string.startup_progress_installing_launcher_components)
        )
        ComponentInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 5, 35))

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            36,
            context.progressText(R.string.startup_progress_preparing_java_runtime)
        )
        RuntimePackInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 36, 76))

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            78,
            context.progressText(R.string.startup_progress_ensuring_runtime_directories)
        )
        RuntimePaths.ensureBaseDirs(context)

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            86,
            context.progressText(R.string.startup_progress_validating_desktop_jar)
        )
        StsJarValidator.validate(RuntimePaths.importedStsJar(context))

        if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                90,
                context.progressText(R.string.startup_progress_validating_required_mod_jars)
            )
            ModJarSupport.validateMtsJar(RuntimePaths.importedMtsJar(context))
            ModJarSupport.validateBaseModJar(RuntimePaths.importedBaseModJar(context))
            ModJarSupport.validateStsLibJar(RuntimePaths.importedStsLibJar(context))
            OptionalModStorageCoordinator.syncEnabledOptionalModsToRuntime(context)

            throwIfInterrupted()
            if (MtsClasspathWarmupCoordinator.isCacheCurrent(context)) {
                reportProgress(
                    progressCallback,
                    95,
                    context.progressText(R.string.startup_progress_using_prepared_mts_classpath_cache)
                )
            } else {
                reportProgress(
                    progressCallback,
                    95,
                    context.progressText(R.string.startup_progress_preparing_mts_classpath)
                )
                ModJarSupport.prepareMtsClasspath(
                    context,
                    mapProgressRange(progressCallback, 95, 99)
                )
                MtsClasspathWarmupCoordinator.markPrepared(context)
            }
            throwIfInterrupted()
            reportProgress(
                progressCallback,
                99,
                context.progressText(R.string.startup_progress_resolving_enabled_mod_launch_list)
            )
            val launchModIds = ModManager.resolveLaunchModIds(context)
            MemoryDiagnosticsLogger.logModSnapshot(
                context = context,
                event = "launch_preparation_resolved_launch_mods",
                launchMode = launchMode,
                enabledLibraryFiles = ModManager.listEnabledOptionalModFiles(context),
                runtimeModFiles = RuntimePaths.modsDir(context).listFiles().orEmpty().filter(File::isFile),
                launchModIds = launchModIds
            )
        }

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            100,
            context.progressText(R.string.startup_progress_launch_preparation_complete)
        )
        MemoryDiagnosticsLogger.logEvent(
            context,
            "launch_preparation_completed",
            mapOf("launchMode" to launchMode)
        )
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
        return startPercent + ((endPercent - startPercent) * ratio).roundToInt()
    }

    private fun clampPercent(value: Int): Int {
        return value.coerceIn(0, 100)
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        callback?.onProgress(clampPercent(percent), message)
    }
}
