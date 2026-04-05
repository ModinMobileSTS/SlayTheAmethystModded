package io.stamethyst.backend.diag

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.stamethyst.config.RuntimePaths

class LauncherLogcatCaptureService : Service() {
    companion object {
        const val ACTION_START_CAPTURE = "io.stamethyst.action.START_LAUNCHER_LOGCAT_CAPTURE"
        const val ACTION_STOP_CAPTURE = "io.stamethyst.action.STOP_LAUNCHER_LOGCAT_CAPTURE"
        const val ACTION_STOP_AND_CLEAR_CAPTURE =
            "io.stamethyst.action.STOP_AND_CLEAR_LAUNCHER_LOGCAT_CAPTURE"
        private const val STOP_AFTER_NO_TRACKED_PROCESSES_MS = 2_000L
    }

    private var captureWorker: PackageLogcatCaptureWorker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                ensureCaptureWorker().start(restartIfRunning = false)
                return START_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                ensureCaptureWorker().stop(waitForThread = true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_AND_CLEAR_CAPTURE -> {
                ensureCaptureWorker().stopAndClear()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        captureWorker?.stop(waitForThread = false)
        super.onDestroy()
    }

    private fun ensureCaptureWorker(): PackageLogcatCaptureWorker {
        val existing = captureWorker
        if (existing != null) {
            return existing
        }
        return PackageLogcatCaptureWorker(
            applicationContext = applicationContext,
            config = PackageLogcatCaptureConfig(
                captureLabel = "launcher logcat capture",
                appBaseFile = RuntimePaths.launcherLogcatAppCaptureLog(applicationContext),
                systemBaseFile = RuntimePaths.launcherLogcatSystemCaptureLog(applicationContext),
                listCaptureFiles = { RuntimePaths.listLauncherLogcatCaptureFiles(applicationContext) },
                trackedProcessMatcher = { processName, packageName ->
                    processName == packageName
                },
                clearCaptureFilesOnStart = false,
                stopWhenNoTrackedProcessesIdleMs = STOP_AFTER_NO_TRACKED_PROCESSES_MS
            ),
            onCaptureFinished = { stopSelf() }
        ).also { worker ->
            captureWorker = worker
        }
    }
}
