package io.stamethyst.backend.launch

import android.os.SystemClock
import android.util.Log
import com.oracle.dalvik.VMLauncher
import io.stamethyst.BootOverlayController
import io.stamethyst.R
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.crash.LatestLogCrashDetector
import io.stamethyst.backend.diag.MemoryDiagnosticsLogger
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.render.MobileGluesConfigFile
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import kotlin.math.roundToInt

/**
 * Manages JVM launch lifecycle: preparation, argument building, and thread management.
 */
class JvmLaunchController(
    private val activity: StsGameActivity,
    private val launchMode: String,
    private val rendererDecision: RendererDecision,
    private val renderScale: Float,
    private val forceJvmCrash: Boolean,
    private val mirrorJvmLogsToLogcat: Boolean,
    private val onProgressUpdate: (Int, String) -> Unit,
    private val onLaunchComplete: (exitCode: Int) -> Unit,
    private val onLaunchFailed: (Throwable) -> Unit,
    private val onRuntimeCrashDetected: (detail: String) -> Unit,
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
        private const val LATEST_LOG_LOGCAT_POLL_INTERVAL_MS = 2_000L
        private const val LATEST_LOG_LOGCAT_SCAN_MAX_BYTES = 32 * 1024
        private const val BOOT_BRIDGE_EVENT_POLL_INTERVAL_MS = 180L
        private const val BOOT_BRIDGE_EVENT_SCAN_MAX_BYTES = 32 * 1024
        private const val BOOT_BRIDGE_SPLASH_PROGRESS = 94
        private const val RUNTIME_HEAP_SNAPSHOT_POLL_INTERVAL_MS = 1_000L
        private const val HEAP_PRESSURE_WARNING_RATIO = 0.90
        private const val LOGCAT_TAG = "STS-JVM"
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
    private var peakRuntimeMemorySnapshot: JvmRuntimeMemorySnapshot? = null

    @Volatile
    var jvmLaunchStartedElapsedMs = 0L
        private set

    @Volatile
    var bootInteractiveSignalSeen = false
        private set

    @Volatile
    private var jvmLaunchThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorThread: Thread? = null

    @Volatile
    private var latestLogCapMonitorRunning = false

    @Volatile
    private var latestLogLogcatMirrorThread: Thread? = null

    @Volatile
    private var latestLogLogcatMirrorRunning = false

    @Volatile
    private var latestLogRuntimeCrashDetected = false

    @Volatile
    private var bootBridgeEventMonitorThread: Thread? = null

    @Volatile
    private var bootBridgeEventMonitorRunning = false

    @Volatile
    private var bootBridgeDismissSignaled = false

    @Volatile
    private var runtimeHeapSnapshotMonitorThread: Thread? = null

    @Volatile
    private var runtimeHeapSnapshotMonitorRunning = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var lastBootPhaseProgress = 0

    @Volatile
    private var lastBootPhaseMessage = ""

    @Volatile
    private var lastLatestLogLine = ""

    @Volatile
    private var lastLoggedHeapPressureBucket = -1

    private var latestLogLogcatMirrorOffset = 0L
    private var latestLogLogcatMirrorRemainder = ""
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
        peakRuntimeMemorySnapshot = null
        jvmLaunchStartedElapsedMs = SystemClock.elapsedRealtime()
        bootInteractiveSignalSeen = false
        cancelRequested = false
        lastBootPhaseProgress = 8
        lastBootPhaseMessage = activity.progressText(R.string.boot_overlay_status_starting_jvm)
        lastLatestLogLine = ""
        lastLoggedHeapPressureBucket = -1

        latestLogRuntimeCrashDetected = false
        onProgressUpdate(8, activity.progressText(R.string.boot_overlay_status_starting_jvm))
        MemoryDiagnosticsLogger.logEvent(
            activity,
            "jvm_launch_controller_start",
            mapOf(
                "launchMode" to launchMode,
                "renderScale" to renderScale,
                "rendererBackend" to rendererDecision.effectiveBackend.rendererId(),
                "rendererSurface" to rendererDecision.effectiveSurfaceBackend.persistedValue,
                "mirrorJvmLogsToLogcat" to mirrorJvmLogsToLogcat
            )
        )

        val launchThread = Thread({
            try {
                throwIfCancelled()
                LaunchPreparationProcessClient.prepare(
                    context = activity,
                    launchMode = launchMode,
                    progressCallback = StartupProgressCallback { percent, message ->
                        onProgressUpdate(
                            bootOverlayController?.mapBootOverlayPreparationProgress(percent)
                                ?: percent,
                            message
                        )
                    },
                    throwIfCancelled = { throwIfCancelled() }
                )

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
                try {
                    val jvmGcLogFile = RuntimePaths.jvmGcLog(activity)
                    if (jvmGcLogFile.exists()) {
                        jvmGcLogFile.delete()
                    }
                } catch (_: Throwable) {
                }
                try {
                    val jvmHeapSnapshotFile = RuntimePaths.jvmHeapSnapshot(activity)
                    if (jvmHeapSnapshotFile.exists()) {
                        jvmHeapSnapshotFile.delete()
                    }
                } catch (_: Throwable) {
                }
                try {
                    val signalDumpFile = RuntimePaths.jvmSignalDump(activity)
                    if (signalDumpFile.exists()) {
                        signalDumpFile.delete()
                    }
                } catch (_: Throwable) {
                }
                val latestLogFile = RuntimePaths.latestLog(activity)
                try {
                    JREUtils.redirectStdioToFile(latestLogFile.absolutePath, false)
                } catch (_: Throwable) {
                }
                startLatestLogLogcatMirror(latestLogFile)
                startLatestLogCapMonitor(latestLogFile)
                startRuntimeHeapSnapshotMonitor(RuntimePaths.jvmHeapSnapshot(activity))
                startBootBridgeEventMonitor(
                    RuntimePaths.bootBridgeEventsLog(activity),
                    bootOverlayController
                )

                if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(activity, "game_pre_jvm")
                }

                throwIfCancelled()
                onSurfaceSizeSync()
                Log.i(
                    LOGCAT_TAG,
                    "Launch surface sync resolved " +
                        "window=${getWindowWidth().coerceAtLeast(1)}x${getWindowHeight().coerceAtLeast(1)}, " +
                        "bridgeWindow=${CallbackBridge.windowWidth.coerceAtLeast(1)}x" +
                        "${CallbackBridge.windowHeight.coerceAtLeast(1)}, " +
                        "bridgePhysical=${CallbackBridge.physicalWidth.coerceAtLeast(1)}x" +
                        "${CallbackBridge.physicalHeight.coerceAtLeast(1)}"
                )

                throwIfCancelled()
                val extraNativeLibraryDirs = NativeLibraryPathResolver
                    .collectAdditionalSearchDirectories(activity)
                    .map(File::getAbsolutePath)
                    .toTypedArray()
                JREUtils.relocateLibPath(
                    activity.applicationInfo.nativeLibraryDir,
                    resolvedJavaHome.absolutePath,
                    extraNativeLibraryDirs
                )
                if (rendererDecision.effectiveBackend == RendererBackend.OPENGL_ES_MOBILEGLUES) {
                    try {
                        MobileGluesConfigFile.syncFromLauncherPreferences(activity)
                    } catch (error: IOException) {
                        Log.w(LOGCAT_TAG, "Failed to sync MobileGlues config", error)
                    }
                }
                JREUtils.setJavaEnvironment(
                    activity,
                    resolvedJavaHome.absolutePath,
                    getWindowWidth().coerceAtLeast(1),
                    getWindowHeight().coerceAtLeast(1),
                    rendererDecision
                )
                throwIfCancelled()
                AdditionalNativeLibraryPreloader.preload(activity)
                throwIfCancelled()
                JREUtils.initJavaRuntime(activity.applicationContext, resolvedJavaHome.absolutePath)
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
                        rendererDecision,
                        renderScale,
                        forceJvmCrash
                    )
                )
                MemoryDiagnosticsLogger.logEvent(
                    activity,
                    "jvm_launch_args_ready",
                    mapOf(
                        "launchMode" to launchMode,
                        "javaHome" to resolvedJavaHome.absolutePath,
                        "argCount" to launchArgs.size,
                        "rendererBackend" to rendererDecision.effectiveBackend.rendererId(),
                        "rendererFallback" to rendererDecision.fallbackSummary()
                    )
                )
                Log.i(
                    LOGCAT_TAG,
                    "Renderer selection mode=${rendererDecision.selectionMode.persistedValue}, " +
                        "effective=${rendererDecision.effectiveBackend.rendererId()}, " +
                        "auto=${rendererDecision.automaticBackend.rendererId()}, " +
                        "surface=${rendererDecision.effectiveSurfaceBackend.persistedValue}, " +
                        "fallback=${rendererDecision.fallbackSummary() ?: "none"}"
                )

                if (StsLaunchSpec.isMtsLaunchMode(launchMode)) {
                    val launchingMessage = activity.progressText(R.string.startup_progress_launching_modthespire)
                    recordBootContext(28, launchingMessage)
                    onProgressUpdate(28, launchingMessage)
                } else {
                    val launchingMessage = activity.progressText(R.string.startup_progress_launching_game)
                    recordBootContext(85, launchingMessage)
                    onProgressUpdate(85, launchingMessage)
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
                peakRuntimeMemorySnapshot = null
                jvmLaunchStartedElapsedMs = 0L
                stopLatestLogLogcatMirror()
                stopLatestLogCapMonitor()
                stopRuntimeHeapSnapshotMonitor()
                stopBootBridgeEventMonitor()
                jvmLaunchThread = null
                lastLoggedHeapPressureBucket = -1
            }
        }, "STS-JVM-Thread")

        jvmLaunchThread = launchThread
        launchThread.start()
    }

    fun interrupt() {
        cancelRequested = true
        LaunchPreparationProcessClient.cancel(activity)
        jvmLaunchThread?.interrupt()
    }

    fun cleanup() {
        cancelRequested = true
        LaunchPreparationProcessClient.cancel(activity)
        val launchThread = jvmLaunchThread
        jvmLaunchThread = null
        launchThread?.interrupt()
        stopLatestLogLogcatMirror()
        stopLatestLogCapMonitor()
        stopRuntimeHeapSnapshotMonitor()
        stopBootBridgeEventMonitor()
        runtimeLifecycleReady = false
        runtimeMemorySnapshot = null
        peakRuntimeMemorySnapshot = null
        jvmLaunchStartedElapsedMs = 0L
        bootInteractiveSignalSeen = false
        lastLoggedHeapPressureBucket = -1
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

    private fun startLatestLogLogcatMirror(logFile: File) {
        stopLatestLogLogcatMirror()
        latestLogLogcatMirrorOffset = 0L
        latestLogLogcatMirrorRemainder = ""
        latestLogRuntimeCrashDetected = false
        latestLogLogcatMirrorRunning = true
        val monitorThread = Thread({
            while (latestLogLogcatMirrorRunning && !Thread.currentThread().isInterrupted) {
                try {
                    pollLatestLogForLogcat(logFile)
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(LATEST_LOG_LOGCAT_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "STS-LatestLogcat")
        monitorThread.isDaemon = true
        latestLogLogcatMirrorThread = monitorThread
        monitorThread.start()
    }

    private fun stopLatestLogCapMonitor() {
        latestLogCapMonitorRunning = false
        val monitorThread = latestLogCapMonitorThread
        latestLogCapMonitorThread = null
        monitorThread?.interrupt()
    }

    private fun stopLatestLogLogcatMirror() {
        latestLogLogcatMirrorRunning = false
        val monitorThread = latestLogLogcatMirrorThread
        latestLogLogcatMirrorThread = null
        monitorThread?.interrupt()
        flushLatestLogLogcatRemainder()
        latestLogLogcatMirrorOffset = 0L
        latestLogLogcatMirrorRemainder = ""
    }

    private fun startRuntimeHeapSnapshotMonitor(snapshotFile: File) {
        stopRuntimeHeapSnapshotMonitor()
        runtimeHeapSnapshotMonitorRunning = true
        val monitorThread = Thread({
            while (runtimeHeapSnapshotMonitorRunning && !Thread.currentThread().isInterrupted) {
                try {
                    recordRuntimeMemorySnapshot(readRuntimeHeapSnapshot(snapshotFile))
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(RUNTIME_HEAP_SNAPSHOT_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "STS-RuntimeHeap")
        monitorThread.isDaemon = true
        runtimeHeapSnapshotMonitorThread = monitorThread
        monitorThread.start()
    }

    private fun stopRuntimeHeapSnapshotMonitor() {
        runtimeHeapSnapshotMonitorRunning = false
        val monitorThread = runtimeHeapSnapshotMonitorThread
        runtimeHeapSnapshotMonitorThread = null
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
                val phaseProgress = progress?.takeIf { it >= 0 }
                val phaseMessage = StartupMessageResolver.resolveProgress(
                    activity,
                    message,
                    R.string.startup_progress_loading
                )
                recordBootContext(phaseProgress, phaseMessage)
                if (phaseProgress != null) {
                    onProgressUpdate(
                        phaseProgress.coerceIn(0, 100),
                        phaseMessage
                    )
                }
            }

            "SPLASH" -> {
                val splashProgress = (progress ?: BOOT_BRIDGE_SPLASH_PROGRESS)
                    .coerceAtLeast(BOOT_BRIDGE_SPLASH_PROGRESS)
                    .coerceAtMost(100)
                val splashMessage = StartupMessageResolver.resolveProgress(
                    activity,
                    message,
                    R.string.startup_progress_showing_game_splash
                )
                recordBootContext(splashProgress, splashMessage)
                bootInteractiveSignalSeen = true
                onProgressUpdate(splashProgress, splashMessage)
                signalDismissFromBootBridge(bootOverlayController, splashMessage)
            }

            "READY" -> {
                val readyMessage = StartupMessageResolver.resolveProgress(
                    activity,
                    message,
                    R.string.startup_progress_game_ready
                )
                recordBootContext(100, readyMessage)
                bootInteractiveSignalSeen = true
                onProgressUpdate(100, readyMessage)
                signalDismissFromBootBridge(
                    bootOverlayController,
                    readyMessage
                )
                return true
            }

            "FAIL" -> {
                val detail = StartupMessageResolver.resolveFailure(
                    activity,
                    message,
                    R.string.startup_failure_boot_bridge_signaled
                )
                recordBootContext(progress, detail)
                bootOverlayController?.signalLaunchFailure(detail)
                return true
            }

            "MEM" -> {
                recordRuntimeMemorySnapshot(parseRuntimeMemorySnapshot(message))
            }
        }
        return false
    }

    private fun pollLatestLogForLogcat(logFile: File) {
        if (!logFile.isFile) {
            return
        }
        val knownLength = logFile.length().coerceAtLeast(0L)
        if (latestLogLogcatMirrorOffset > knownLength) {
            latestLogLogcatMirrorOffset = 0L
            latestLogLogcatMirrorRemainder = ""
        }

        var startOffset = latestLogLogcatMirrorOffset
        var bytesToReadLong = knownLength - startOffset
        if (bytesToReadLong <= 0L) {
            return
        }
        if (bytesToReadLong > LATEST_LOG_LOGCAT_SCAN_MAX_BYTES) {
            val droppedBytes = bytesToReadLong - LATEST_LOG_LOGCAT_SCAN_MAX_BYTES
            startOffset = knownLength - LATEST_LOG_LOGCAT_SCAN_MAX_BYTES
            bytesToReadLong = LATEST_LOG_LOGCAT_SCAN_MAX_BYTES.toLong()
            latestLogLogcatMirrorRemainder = ""
            Log.w(LOGCAT_TAG, "Dropped $droppedBytes bytes while mirroring latest.log to logcat")
        }

        val bytesToRead = bytesToReadLong.toInt()
        if (bytesToRead <= 0) {
            return
        }

        RandomAccessFile(logFile, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray(bytesToRead)
            raf.readFully(buffer)
            latestLogLogcatMirrorOffset = startOffset + bytesToRead

            var chunkText = String(buffer, StandardCharsets.UTF_8)
            if (latestLogLogcatMirrorRemainder.isNotEmpty()) {
                chunkText = latestLogLogcatMirrorRemainder + chunkText
            }

            val endsWithLineBreak = chunkText.endsWith("\n") || chunkText.endsWith("\r")
            val parts = chunkText.split('\n')
            val lines = if (endsWithLineBreak) {
                latestLogLogcatMirrorRemainder = ""
                parts
            } else {
                latestLogLogcatMirrorRemainder = parts.lastOrNull() ?: ""
                if (parts.isNotEmpty()) parts.dropLast(1) else emptyList()
            }

            for (line in lines) {
                val normalized = line.trimEnd('\r')
                if (normalized.isEmpty()) {
                    continue
                }
                lastLatestLogLine = normalized
                if (mirrorJvmLogsToLogcat) {
                    Log.i(LOGCAT_TAG, normalized)
                }
                if (!latestLogRuntimeCrashDetected && isRuntimeCrashMarkerLine(normalized)) {
                    latestLogRuntimeCrashDetected = true
                    val summary = LatestLogCrashDetector.detect(logFile)
                    val detail = summary?.detail?.ifBlank { null } ?: normalized
                    onRuntimeCrashDetected(detail)
                    latestLogLogcatMirrorRunning = false
                    break
                }
            }
        }
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

    internal fun buildHeapPressureNotice(): JvmHeapPressureNotice? {
        val peakSnapshot = peakRuntimeMemorySnapshot ?: return null
        if (peakSnapshot.heapUsedBytes <= 0L || peakSnapshot.heapMaxBytes <= 0L) {
            return null
        }
        val peakUsageRatio = peakSnapshot.heapUsedBytes.toDouble() / peakSnapshot.heapMaxBytes.toDouble()
        if (peakUsageRatio < HEAP_PRESSURE_WARNING_RATIO) {
            return null
        }

        val currentHeapMaxMb = LauncherConfig.readJvmHeapMaxMb(activity)
        val suggestedHeapMaxMb = LauncherConfig.normalizeJvmHeapMaxMb(
            (currentHeapMaxMb + LauncherConfig.JVM_HEAP_STEP_MB)
                .coerceAtMost(LauncherConfig.MAX_JVM_HEAP_MAX_MB)
        )

        return JvmHeapPressureNotice(
            peakHeapUsedBytes = peakSnapshot.heapUsedBytes,
            peakHeapMaxBytes = peakSnapshot.heapMaxBytes,
            peakUsageRatio = peakUsageRatio,
            currentHeapMaxMb = currentHeapMaxMb,
            suggestedHeapMaxMb = suggestedHeapMaxMb
        )
    }

    fun buildExitedBeforeInteractiveDetail(): String {
        val context = ArrayList<String>(2)
        val phaseMessage = lastBootPhaseMessage.trim()
        if (phaseMessage.isNotEmpty()) {
            val normalizedProgress = lastBootPhaseProgress.coerceAtLeast(0)
            if (normalizedProgress > 0) {
                context.add(
                    activity.progressText(
                        R.string.startup_failure_context_last_phase_with_percent,
                        normalizedProgress,
                        phaseMessage
                    )
                )
            } else {
                context.add(
                    activity.progressText(
                        R.string.startup_failure_context_last_phase,
                        phaseMessage
                    )
                )
            }
        }
        val lastLogLine = lastLatestLogLine.trim()
        if (lastLogLine.isNotEmpty() &&
            !lastLogLine.equals(phaseMessage, ignoreCase = true)
        ) {
            context.add(
                activity.progressText(
                    R.string.startup_failure_context_last_log,
                    lastLogLine
                )
            )
        }
        if (context.isEmpty()) {
            return activity.progressText(R.string.startup_failure_jvm_exited_before_interactive)
        }
        return activity.progressText(
            R.string.startup_failure_jvm_exited_before_interactive_with_context,
            context.joinToString(activity.progressText(R.string.startup_failure_context_separator))
        )
    }

    private fun readRuntimeHeapSnapshot(snapshotFile: File): JvmRuntimeMemorySnapshot? {
        if (!snapshotFile.isFile) {
            return null
        }
        val snapshot = try {
            snapshotFile.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
        return parseRuntimeMemorySnapshot(snapshot)
    }

    private fun recordRuntimeMemorySnapshot(snapshot: JvmRuntimeMemorySnapshot?) {
        runtimeMemorySnapshot = snapshot
        if (snapshot == null || snapshot.heapUsedBytes <= 0L || snapshot.heapMaxBytes <= 0L) {
            return
        }

        val previousPeak = peakRuntimeMemorySnapshot
        if (previousPeak == null || shouldReplacePeakSnapshot(previousPeak, snapshot)) {
            peakRuntimeMemorySnapshot = snapshot
            maybeLogRuntimeHeapPressure(snapshot)
        }
    }

    private fun shouldReplacePeakSnapshot(
        currentPeak: JvmRuntimeMemorySnapshot,
        candidate: JvmRuntimeMemorySnapshot
    ): Boolean {
        if (candidate.heapUsedBytes <= 0L || candidate.heapMaxBytes <= 0L) {
            return false
        }
        if (currentPeak.heapUsedBytes <= 0L || currentPeak.heapMaxBytes <= 0L) {
            return true
        }

        val currentRatio = currentPeak.heapUsedBytes.toDouble() / currentPeak.heapMaxBytes.toDouble()
        val candidateRatio = candidate.heapUsedBytes.toDouble() / candidate.heapMaxBytes.toDouble()
        return candidateRatio > currentRatio ||
            (candidateRatio == currentRatio && candidate.heapUsedBytes > currentPeak.heapUsedBytes)
    }

    private fun signalDismissFromBootBridge(
        bootOverlayController: BootOverlayController?,
        message: String
    ) {
        if (bootBridgeDismissSignaled) {
            return
        }
        bootInteractiveSignalSeen = true
        bootBridgeDismissSignaled = true
        bootOverlayController?.signalSplashPhase(
            if (message.isEmpty()) {
                activity.progressText(R.string.startup_progress_showing_game_splash)
            } else {
                message
            }
        )
    }

    private fun recordBootContext(progress: Int? = null, message: String? = null) {
        val safeProgress = progress?.takeIf { it >= 0 }
        if (safeProgress != null) {
            lastBootPhaseProgress = safeProgress.coerceAtMost(100)
        }
        val safeMessage = message?.trim().orEmpty()
        if (safeMessage.isNotEmpty()) {
            lastBootPhaseMessage = safeMessage
        }
    }

    private fun maybeLogRuntimeHeapPressure(snapshot: JvmRuntimeMemorySnapshot) {
        if (snapshot.heapMaxBytes <= 0L) {
            return
        }
        val usageRatio = snapshot.heapUsedBytes.toDouble() / snapshot.heapMaxBytes.toDouble()
        val bucket = when {
            usageRatio >= 0.95 -> 95
            usageRatio >= 0.90 -> 90
            usageRatio >= 0.80 -> 80
            usageRatio >= 0.70 -> 70
            else -> -1
        }
        if (bucket < 0 || bucket <= lastLoggedHeapPressureBucket) {
            return
        }
        lastLoggedHeapPressureBucket = bucket
        MemoryDiagnosticsLogger.logJvmHeapSnapshot(
            activity,
            "jvm_heap_pressure_bucket_reached",
            snapshot,
            mapOf(
                "launchMode" to launchMode,
                "usagePercentBucket" to bucket,
                "usagePercent" to (usageRatio * 100.0).roundToInt()
            )
        )
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

    private fun flushLatestLogLogcatRemainder() {
        val remainder = latestLogLogcatMirrorRemainder.trimEnd('\r')
        if (remainder.isNotEmpty() && mirrorJvmLogsToLogcat) {
            Log.i(LOGCAT_TAG, remainder)
        }
    }

    private fun isRuntimeCrashMarkerLine(line: String): Boolean {
        return line.contains("Game crashed.", ignoreCase = true) ||
            line.contains("Exception occurred in CardCrawlGame render method!", ignoreCase = true) ||
            line.contains("Exception in thread \"LWJGL Application\"", ignoreCase = true)
    }
}
