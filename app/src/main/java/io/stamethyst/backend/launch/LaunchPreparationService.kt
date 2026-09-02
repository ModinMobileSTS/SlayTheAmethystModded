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
import io.stamethyst.backend.resources.ExternalResourcePackService
import io.stamethyst.backend.runtime.RuntimePackInstaller
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
    fun prepare(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?
    ): ModManager.LaunchModSnapshot? {
        MemoryDiagnosticsLogger.logEvent(
            context,
            "launch_preparation_started",
            mapOf("launchMode" to launchMode)
        )
        throwIfInterrupted()
        reportProgress(
            progressCallback,
            2,
            context.progressText(R.string.startup_progress_checking_external_resources)
        )
        ExternalResourcePackService.ensureAvailable(context, mapProgressRange(progressCallback, 2, 20))

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            21,
            context.progressText(R.string.startup_progress_installing_launcher_components)
        )
        ComponentInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 21, 43))
        ExternalResourcePackService.installNativeLibraries(context)

        throwIfInterrupted()
        reportProgress(
            progressCallback,
            44,
            context.progressText(R.string.startup_progress_preparing_java_runtime)
        )
        RuntimePackInstaller.ensureInstalled(context, mapProgressRange(progressCallback, 44, 76))

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

            throwIfInterrupted()
            reportProgress(
                progressCallback,
                96,
                context.progressText(R.string.startup_progress_resolving_enabled_mod_launch_list)
            )
            val launchSnapshot = ModManager.buildLaunchModSnapshot(context)
            OptionalModStorageCoordinator.prepareMtsModFileList(context, launchSnapshot)
            MemoryDiagnosticsLogger.logModSnapshot(
                context = context,
                event = "launch_preparation_resolved_launch_mods",
                launchMode = launchMode,
                enabledLibraryFiles = launchSnapshot.enabledLibraryFiles,
                runtimeModFiles = launchSnapshot.launchModFiles,
                launchModIds = launchSnapshot.launchModIds
            )
            finishPreparation(context, launchMode, progressCallback)
            return launchSnapshot
        }

        finishPreparation(context, launchMode, progressCallback)
        return null
    }

    private fun finishPreparation(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback?
    ) {
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
