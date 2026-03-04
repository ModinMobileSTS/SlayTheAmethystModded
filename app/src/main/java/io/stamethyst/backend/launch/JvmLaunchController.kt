package io.stamethyst.backend.launch

import com.oracle.dalvik.VMLauncher
import io.stamethyst.BootOverlayController
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.RuntimePaths
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayList

/**
 * Manages JVM launch lifecycle: preparation, argument building, and thread management.
 */
class JvmLaunchController(
    private val activity: StsGameActivity,
    private val launchMode: String,
    private val targetFps: Int,
    private val forceJvmCrash: Boolean,
    private val onProgressUpdate: (Int, String) -> Unit,
    private val onLaunchComplete: (exitCode: Int) -> Unit,
    private val onLaunchFailed: (Throwable) -> Unit,
    private val onRuntimeReady: () -> Unit,
    private val onSurfaceSizeSync: () -> Unit,
    private val getWindowWidth: () -> Int,
    private val getWindowHeight: () -> Int
) {
    companion object {
        const val CRASH_CODE_BOOT_FAILURE = -2
        const val CRASH_CODE_OUT_OF_MEMORY = -8
        private const val LATEST_LOG_MAX_BYTES = 64L * 1024L * 1024L
        private const val LATEST_LOG_KEEP_BYTES = 8L * 1024L * 1024L
        private const val LATEST_LOG_MONITOR_INTERVAL_MS = 12_000L
    }

    @Volatile
    var vmStarted = false
        private set

    @Volatile
    var runtimeLifecycleReady = false
        private set

    @Volatile
    private var jvmLaunchThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorRunning = false

    fun start(
        javaHome: File,
        bootOverlayController: BootOverlayController?
    ) {
        if (vmStarted) return
        vmStarted = true
        runtimeLifecycleReady = false

        onProgressUpdate(8, "Starting JVM...")

        val launchThread = Thread({
            try {
                LaunchPreparationService.prepare(activity, launchMode) { percent, message ->
                    onProgressUpdate(
                        bootOverlayController?.mapBootOverlayPreparationProgress(percent) ?: percent,
                        message
                    )
                }

                val runtimeRoot = RuntimePaths.runtimeRoot(activity)
                val resolvedJavaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot)
                    ?: javaHome.takeIf { it.exists() }
                    ?: throw IllegalStateException("No Java home found in ${runtimeRoot.absolutePath}")

                RuntimePaths.ensureBaseDirs(activity)
                try {
                    JvmLogRotationManager.prepareForNewSession(activity)
                } catch (_: Throwable) {
                }
                val latestLogFile = RuntimePaths.latestLog(activity)
                try {
                    JREUtils.redirectStdioToFile(latestLogFile.absolutePath, false)
                } catch (_: Throwable) {
                }
                startLatestLogCapMonitor(latestLogFile)

                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(activity, "game_pre_jvm")
                }

                onSurfaceSizeSync()

                JREUtils.relocateLibPath(activity.applicationInfo.nativeLibraryDir, resolvedJavaHome.absolutePath)
                JREUtils.setJavaEnvironment(
                    activity,
                    resolvedJavaHome.absolutePath,
                    getWindowWidth().coerceAtLeast(1),
                    getWindowHeight().coerceAtLeast(1)
                )
                JREUtils.initJavaRuntime(resolvedJavaHome.absolutePath)
                JREUtils.setupExitMethod(activity.applicationContext)
                JREUtils.initializeHooks()
                JREUtils.chdir(RuntimePaths.stsRoot(activity).absolutePath)

                CallbackBridge.nativeSetUseInputStackQueue(true)
                CallbackBridge.nativeSetInputReady(true)

                runtimeLifecycleReady = true
                onRuntimeReady()

                val launchArgs = ArrayList<String>()
                launchArgs.add("java")
                launchArgs.addAll(
                    StsLaunchSpec.buildArgs(
                        activity,
                        resolvedJavaHome,
                        launchMode,
                        forceJvmCrash
                    )
                )

                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    onProgressUpdate(28, "Launching ModTheSpire...")
                } else {
                    onProgressUpdate(85, "Launching game...")
                }

                val exitCode = VMLauncher.launchJVM(launchArgs.toTypedArray())
                onLaunchComplete(exitCode)

            } catch (t: Throwable) {
                runtimeLifecycleReady = false
                onLaunchFailed(t)
            } finally {
                runtimeLifecycleReady = false
                stopLatestLogCapMonitor()
                jvmLaunchThread = null
            }
        }, "STS-JVM-Thread")

        jvmLaunchThread = launchThread
        launchThread.start()
    }

    fun interrupt() {
        jvmLaunchThread?.interrupt()
    }

    fun cleanup() {
        val launchThread = jvmLaunchThread
        jvmLaunchThread = null
        launchThread?.interrupt()
        stopLatestLogCapMonitor()
        runtimeLifecycleReady = false
    }

    private fun startLatestLogCapMonitor(logFile: File) {
        stopLatestLogCapMonitor()
        latestLogCapMonitorRunning = true
        val monitorThread = Thread({
            while (latestLogCapMonitorRunning && !Thread.currentThread().isInterrupted) {
                try {
                    enforceLatestLogCap(logFile)
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(LATEST_LOG_MONITOR_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "STS-LatestLogCap")
        monitorThread.isDaemon = true
        latestLogCapMonitorThread = monitorThread
        monitorThread.start()
    }

    private fun stopLatestLogCapMonitor() {
        latestLogCapMonitorRunning = false
        val monitorThread = latestLogCapMonitorThread
        latestLogCapMonitorThread = null
        monitorThread?.interrupt()
    }

    private fun enforceLatestLogCap(logFile: File) {
        if (!logFile.isFile) {
            return
        }
        val length = logFile.length()
        if (length <= LATEST_LOG_MAX_BYTES) {
            return
        }

        val keepBytes = minOf(LATEST_LOG_KEEP_BYTES, length).toInt()
        RandomAccessFile(logFile, "rw").use { raf ->
            if (keepBytes <= 0) {
                raf.setLength(0)
                return
            }
            val startOffset = length - keepBytes
            raf.seek(startOffset)
            val buffer = ByteArray(keepBytes)
            raf.readFully(buffer)
            raf.setLength(0)
            raf.seek(0)
            raf.write(buffer)
            raf.fd.sync()
        }
    }
}
