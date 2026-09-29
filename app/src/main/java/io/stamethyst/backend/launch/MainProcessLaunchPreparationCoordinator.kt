package io.stamethyst.backend.launch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.process.AppProcess
import java.io.IOException

object MainProcessLaunchPreparationCoordinator {
    private const val COMMON_PREPARATION_END_PERCENT = 60
    private const val BODY_PATCH_END_PERCENT = 82
    private const val MTS_WARMUP_START_PERCENT = 83
    private const val LOGCAT_TAG = "STS-LaunchPrep"

    @JvmStatic
    @Throws(IOException::class)
    fun prepareBeforeLaunch(
        context: Context,
        launchMode: String,
        progressCallback: StartupProgressCallback? = null,
        launchSnapshotOverride: ModManager.LaunchModSnapshot? = null
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val appContext = context.applicationContext
        StartupTraceEvents.append(
            appContext,
            "main_process_prepare_start",
            mapOf("launchMode" to launchMode)
        )
        try {
            if (!AppProcess.isDefaultProcess(appContext)) {
                throw IOException("Game launch preparation must run in the main process before game launch")
            }

            if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
                val launchSnapshot = measureStep(appContext, "common_preparation") {
                    LaunchPreparationService.prepare(
                        context = appContext,
                        launchMode = launchMode,
                        progressCallback = mapProgressRange(
                            progressCallback,
                            0,
                            COMMON_PREPARATION_END_PERCENT
                        ),
                        launchSnapshotOverride = launchSnapshotOverride
                    )
                }
                measureStep(appContext, "game_body_patch") {
                    MainProcessGameBodyPatchCoordinator.prepareBeforeLaunch(
                        context = appContext,
                        launchMode = launchMode,
                        progressCallback = mapProgressRange(
                            progressCallback,
                            COMMON_PREPARATION_END_PERCENT + 1,
                            BODY_PATCH_END_PERCENT
                        ),
                        assumeComponentInstallComplete = true
                    )
                }
                measureStep(appContext, "mts_warmup") {
                    MtsClasspathWarmupCoordinator.prepareForLaunch(
                        context = appContext,
                        progressCallback = mapProgressRange(
                            progressCallback,
                            MTS_WARMUP_START_PERCENT,
                            100
                        ),
                        launchSnapshot = launchSnapshot,
                        assumeCommonPreparationDone = true
                    )
                }
                return
            }

            measureStep(appContext, "common_preparation") {
                LaunchPreparationService.prepare(
                    context = appContext,
                    launchMode = launchMode,
                    progressCallback = progressCallback
                )
            }
        } finally {
            StartupTraceEvents.append(
                appContext,
                "main_process_prepare_end",
                mapOf(
                    "launchMode" to launchMode,
                    "tookMs" to (SystemClock.elapsedRealtime() - startedAtMs)
                )
            )
            Log.i(
                LOGCAT_TAG,
                "launch preparation total launchMode=$launchMode tookMs=" +
                    (SystemClock.elapsedRealtime() - startedAtMs)
            )
        }
    }

    private fun <T> measureStep(context: Context, label: String, block: () -> T): T {
        val startedAtMs = SystemClock.elapsedRealtime()
        StartupTraceEvents.append(
            context = context,
            event = "main_process_prepare_step_start",
            extras = mapOf("step" to label)
        )
        try {
            return block()
        } finally {
            StartupTraceEvents.append(
                context = context,
                event = "main_process_prepare_step_end",
                extras = mapOf(
                    "step" to label,
                    "tookMs" to (SystemClock.elapsedRealtime() - startedAtMs)
                )
            )
            Log.i(
                LOGCAT_TAG,
                "launch preparation step=$label tookMs=" +
                    (SystemClock.elapsedRealtime() - startedAtMs)
            )
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
}
