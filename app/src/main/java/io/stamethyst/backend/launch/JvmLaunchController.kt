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
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
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
    private class LaunchCancelledException : IOException("Launch cancelled")

    companion object {
        const val CRASH_CODE_BOOT_FAILURE = -2
        const val CRASH_CODE_OUT_OF_MEMORY = -8
        private const val LATEST_LOG_MAX_BYTES = 64L * 1024L * 1024L
        private const val LATEST_LOG_KEEP_BYTES = 8L * 1024L * 1024L
        private const val LATEST_LOG_MONITOR_INTERVAL_MS = 12_000L
        private const val BOOT_BRIDGE_EVENT_POLL_INTERVAL_MS = 180L
        private const val BOOT_BRIDGE_EVENT_SCAN_MAX_BYTES = 32 * 1024
        private const val BOOT_BRIDGE_SPLASH_PROGRESS = 94
    }

    @Volatile
    var vmStarted = false
        private set

    @Volatile
    var runtimeLifecycleReady = false
        private set

    @Volatile
    internal var runtimeMemorySnapshot: JvmRuntimeMemorySnapshot? = null
        private set

    @Volatile
    private var jvmLaunchThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorRunning = false

    @Volatile
    private var bootBridgeEventMonitorThread: Thread? = null

    @Volatile
    private var bootBridgeEventMonitorRunning = false

    @Volatile
    private var bootBridgeDismissSignaled = false

    @Volatile
    private var cancelRequested = false

    private var bootBridgeEventOffset = 0L
    private var bootBridgeEventRemainder = ""

    fun start(
        javaHome: File,
        bootOverlayController: BootOverlayController?
    ) {
        if (vmStarted) return
        vmStarted = true
        runtimeLifecycleReady = false
        runtimeMemorySnapshot = null
        cancelRequested = false

        onProgressUpdate(8, "Starting JVM...")

        val launchThread = Thread({
            try {
                throwIfCancelled()
                LaunchPreparationService.prepare(activity, launchMode) { percent, message ->
                    throwIfCancelled()
                    onProgressUpdate(
                        bootOverlayController?.mapBootOverlayPreparationProgress(percent) ?: percent,
                        message
                    )
                }

                throwIfCancelled()
                val runtimeRoot = RuntimePaths.runtimeRoot(activity)
                val resolvedJavaHome = RuntimePackInstaller.locateJavaHome(runtimeRoot)
                    ?: javaHome.takeIf { it.exists() }
                    ?: throw IllegalStateException("No Java home found in ${runtimeRoot.absolutePath}")

                throwIfCancelled()
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
                startBootBridgeEventMonitor(
                    RuntimePaths.bootBridgeEventsLog(activity),
                    bootOverlayController
                )

                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(activity, "game_pre_jvm")
                }

                throwIfCancelled()
                onSurfaceSizeSync()

                throwIfCancelled()
                JREUtils.relocateLibPath(activity.applicationInfo.nativeLibraryDir, resolvedJavaHome.absolutePath)
                JREUtils.setJavaEnvironment(
                    activity,
                    resolvedJavaHome.absolutePath,
                    getWindowWidth().coerceAtLeast(1),
                    getWindowHeight().coerceAtLeast(1)
                )
                throwIfCancelled()
                JREUtils.initJavaRuntime(resolvedJavaHome.absolutePath)
                throwIfCancelled()
                JREUtils.setupExitMethod(activity.applicationContext)
                JREUtils.initializeHooks()
                JREUtils.chdir(RuntimePaths.stsRoot(activity).absolutePath)

                throwIfCancelled()
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

                throwIfCancelled()
                val exitCode = VMLauncher.launchJVM(launchArgs.toTypedArray())
                onLaunchComplete(exitCode)

            } catch (t: Throwable) {
                runtimeLifecycleReady = false
                onLaunchFailed(t)
            } finally {
                runtimeLifecycleReady = false
                runtimeMemorySnapshot = null
                stopLatestLogCapMonitor()
                stopBootBridgeEventMonitor()
                jvmLaunchThread = null
            }
        }, "STS-JVM-Thread")

        jvmLaunchThread = launchThread
        launchThread.start()
    }

    fun interrupt() {
        cancelRequested = true
        jvmLaunchThread?.interrupt()
    }

    fun cleanup() {
        cancelRequested = true
        val launchThread = jvmLaunchThread
        jvmLaunchThread = null
        launchThread?.interrupt()
        stopLatestLogCapMonitor()
        stopBootBridgeEventMonitor()
        runtimeLifecycleReady = false
        runtimeMemorySnapshot = null
    }

    @Throws(LaunchCancelledException::class)
    private fun throwIfCancelled() {
        if (cancelRequested || Thread.currentThread().isInterrupted) {
            throw LaunchCancelledException()
        }
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

    private fun startBootBridgeEventMonitor(eventsFile: File, bootOverlayController: BootOverlayController?) {
        stopBootBridgeEventMonitor()
        bootBridgeEventOffset = if (eventsFile.isFile) {
            eventsFile.length().coerceAtLeast(0L)
        } else {
            0L
        }
        bootBridgeEventRemainder = ""
        bootBridgeDismissSignaled = false
        bootBridgeEventMonitorRunning = true
        val monitorThread = Thread({
            while (bootBridgeEventMonitorRunning && !Thread.currentThread().isInterrupted) {
                try {
                    pollBootBridgeEvents(eventsFile, bootOverlayController)
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(BOOT_BRIDGE_EVENT_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "STS-BootBridgeEvents")
        monitorThread.isDaemon = true
        bootBridgeEventMonitorThread = monitorThread
        monitorThread.start()
    }

    private fun stopBootBridgeEventMonitor() {
        bootBridgeEventMonitorRunning = false
        val monitorThread = bootBridgeEventMonitorThread
        bootBridgeEventMonitorThread = null
        monitorThread?.interrupt()
        bootBridgeEventOffset = 0L
        bootBridgeEventRemainder = ""
        bootBridgeDismissSignaled = false
        runtimeMemorySnapshot = null
    }

    private fun pollBootBridgeEvents(eventsFile: File, bootOverlayController: BootOverlayController?) {
        if (!eventsFile.isFile) {
            return
        }
        val knownLength = eventsFile.length().coerceAtLeast(0L)
        if (bootBridgeEventOffset > knownLength) {
            bootBridgeEventOffset = 0L
            bootBridgeEventRemainder = ""
        }

        var startOffset = bootBridgeEventOffset
        var bytesToReadLong = knownLength - startOffset
        if (bytesToReadLong <= 0L) {
            return
        }
        if (bytesToReadLong > BOOT_BRIDGE_EVENT_SCAN_MAX_BYTES) {
            startOffset = knownLength - BOOT_BRIDGE_EVENT_SCAN_MAX_BYTES
            bytesToReadLong = BOOT_BRIDGE_EVENT_SCAN_MAX_BYTES.toLong()
            bootBridgeEventRemainder = ""
        }

        val bytesToRead = bytesToReadLong.toInt()
        if (bytesToRead <= 0) {
            return
        }

        RandomAccessFile(eventsFile, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            bootBridgeEventOffset = startOffset + bytesToRead

            var chunkText = String(buffer, StandardCharsets.UTF_8)
            if (bootBridgeEventRemainder.isNotEmpty()) {
                chunkText = bootBridgeEventRemainder + chunkText
            }

            val endsWithLineBreak = chunkText.endsWith("\n") || chunkText.endsWith("\r")
            val parts = chunkText.split('\n')
            val lines = if (endsWithLineBreak) {
                bootBridgeEventRemainder = ""
                parts
            } else {
                bootBridgeEventRemainder = parts.lastOrNull() ?: ""
                if (parts.isNotEmpty()) parts.dropLast(1) else emptyList()
            }

            for (line in lines) {
                val reachedTerminalEvent =
                    processBootBridgeEventLine(line, bootOverlayController)
                if (reachedTerminalEvent) {
                    stopBootBridgeEventMonitor()
                    break
                }
                if (!bootBridgeEventMonitorRunning || Thread.currentThread().isInterrupted) {
                    break
                }
            }
        }
    }

    private fun processBootBridgeEventLine(
        rawLine: String,
        bootOverlayController: BootOverlayController?
    ): Boolean {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return false
        }
        val parts = line.split('\t', limit = 3)
        if (parts.isEmpty()) {
            return false
        }
        val eventType = parts[0].trim().uppercase()
        val progress = parts.getOrNull(1)?.trim()?.toIntOrNull()
        val message = parts.getOrNull(2)?.trim().orEmpty()
        when (eventType) {
            "PHASE" -> {
                if (progress != null && progress >= 0) {
                    onProgressUpdate(
                        progress.coerceIn(0, 100),
                        message.ifEmpty { "Loading..." }
                    )
                }
            }

            "SPLASH" -> {
                val splashProgress = (progress ?: BOOT_BRIDGE_SPLASH_PROGRESS)
                    .coerceAtLeast(BOOT_BRIDGE_SPLASH_PROGRESS)
                    .coerceAtMost(100)
                onProgressUpdate(splashProgress, message.ifEmpty { "Game splash" })
                signalDismissFromBootBridge(bootOverlayController, message)
            }

            "READY" -> {
                onProgressUpdate(100, message.ifEmpty { "Game ready" })
                signalDismissFromBootBridge(
                    bootOverlayController,
                    message.ifEmpty { "Game ready" }
                )
                return true
            }

            "FAIL" -> {
                val detail = message.ifEmpty { "Boot bridge signaled failure" }
                bootOverlayController?.signalLaunchFailure(detail)
                return true
            }

            "MEM" -> {
                runtimeMemorySnapshot = parseRuntimeMemorySnapshot(message)
            }
        }
        return false
    }

    private fun parseRuntimeMemorySnapshot(message: String): JvmRuntimeMemorySnapshot? {
        if (message.isBlank()) {
            return null
        }
        var heapUsedBytes: Long? = null
        var heapMaxBytes: Long? = null
        val entries = message.split(';')
        for (entry in entries) {
            val separatorIndex = entry.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex >= entry.length - 1) {
                continue
            }
            val key = entry.substring(0, separatorIndex).trim()
            val value = entry.substring(separatorIndex + 1).trim().toLongOrNull() ?: continue
            when (key) {
                "heapUsed" -> heapUsedBytes = value.coerceAtLeast(0L)
                "heapMax" -> heapMaxBytes = value.coerceAtLeast(0L)
            }
        }
        val safeHeapUsedBytes = heapUsedBytes ?: return null
        val safeHeapMaxBytes = heapMaxBytes ?: return null
        return JvmRuntimeMemorySnapshot(
            heapUsedBytes = safeHeapUsedBytes,
            heapMaxBytes = safeHeapMaxBytes
        )
    }

    private fun signalDismissFromBootBridge(
        bootOverlayController: BootOverlayController?,
        message: String
    ) {
        if (bootBridgeDismissSignaled) {
            return
        }
        bootBridgeDismissSignaled = true
        bootOverlayController?.signalSplashPhase(message.ifEmpty { "Game splash" })
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
