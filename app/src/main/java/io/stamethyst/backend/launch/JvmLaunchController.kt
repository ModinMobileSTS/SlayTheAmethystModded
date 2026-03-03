package io.stamethyst.backend.launch

import android.util.Log
import com.oracle.dalvik.VMLauncher
import io.stamethyst.BootOverlayController
import io.stamethyst.StsGameActivity
import io.stamethyst.backend.crash.CrashDiagnostics
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.backend.runtime.RuntimePackInstaller
import io.stamethyst.config.RuntimePaths
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File
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
    private val onLaunchComplete: (exitCode: Int, waitForMainMenu: Boolean, mainMenuReadySignaled: Boolean) -> Unit,
    private val onLaunchFailed: (Throwable) -> Unit,
    private val onRuntimeReady: () -> Unit,
    private val onSurfaceSizeSync: () -> Unit,
    private val getWindowWidth: () -> Int,
    private val getWindowHeight: () -> Int
) {
    companion object {
        private const val TAG = "JvmLaunchController"
        const val CRASH_CODE_BOOT_FAILURE = -2
        const val CRASH_CODE_OUT_OF_MEMORY = -8
    }

    @Volatile
    var vmStarted = false
        private set

    @Volatile
    var runtimeLifecycleReady = false
        private set

    @Volatile
    private var jvmLaunchThread: Thread? = null

    private var jvmLogListenerRegistered = false

    private val jvmLogcatListener = Logger.eventLogListener { text ->
        // Log listener will be handled by BootOverlayController
    }

    fun start(
        javaHome: File,
        waitForMainMenu: Boolean,
        bootOverlayController: BootOverlayController?,
        logListener: (String?) -> Unit
    ) {
        if (vmStarted) return
        vmStarted = true
        runtimeLifecycleReady = false

        onProgressUpdate(8, "Starting JVM...")
        CrashDiagnostics.clear(activity)

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
                val logFile = RuntimePaths.latestLog(activity)
                val logParent = logFile.parentFile
                if (logParent != null && !logParent.exists() && !logParent.mkdirs()) {
                    throw IllegalStateException("Failed to create log directory: ${logParent.absolutePath}")
                }
                if (!logFile.exists() && !logFile.createNewFile()) {
                    throw IllegalStateException("Failed to create log file: ${logFile.absolutePath}")
                }

                Logger.begin(logFile.absolutePath)
                try {
                    Logger.addLogListener(object : Logger.eventLogListener {
                        override fun onEventLogged(text: String?) {
                            logListener(text)
                        }
                    })
                    jvmLogListenerRegistered = true
                } catch (_: Throwable) {
                    jvmLogListenerRegistered = false
                }

                Logger.appendToLog("Launching STS with java home: ${resolvedJavaHome.absolutePath}")
                Logger.appendToLog("Launch mode: $launchMode")

                val virtualFboPocEnabled = CompatibilitySettings.isVirtualFboPocEnabled(activity)
                val globalAtlasFilterCompatEnabled = CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(activity)
                val forceLinearMipmapFilterEnabled = CompatibilitySettings.isForceLinearMipmapFilterEnabled(activity)
                Logger.appendToLog(
                    "Compat settings: virtualFboPoc=$virtualFboPocEnabled, globalAtlasFilterCompat=$globalAtlasFilterCompatEnabled, " +
                        "forceLinearMipmapFilter=$forceLinearMipmapFilterEnabled"
                )

                val effectiveRenderer = RendererBackend.OPENGL_ES2
                Logger.appendToLog("Renderer fixed: ${effectiveRenderer.rendererId()}")
                Logger.appendToLog("Renderer GL library expected: ${effectiveRenderer.lwjglOpenGlLibName()}")

                if (StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode) {
                    ModJarSupport.appendCompatDiagnosticSnapshot(activity, "game_pre_jvm")
                }

                onSurfaceSizeSync()

                Logger.appendToLog("Target FPS limit: $targetFps")

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

                Logger.appendToLog(
                    "Launch arg check: " +
                        findLaunchArgValue(launchArgs, "-Dorg.lwjgl.opengl.libname=") + ", " +
                        findLaunchArgValue(launchArgs, "-Damethyst.gdx.virtual_fbo_poc=") + ", " +
                        findLaunchArgValue(launchArgs, "-Damethyst.gdx.global_atlas_filter_compat=") + ", " +
                        findLaunchArgValue(launchArgs, "-Damethyst.gdx.force_linear_mipmap_filter=") + ", " +
                        findLaunchArgValue(launchArgs, "-Dorg.lwjgl.librarypath=")
                )
                Logger.appendToLog("Launch args: $launchArgs")

                val exitCode = VMLauncher.launchJVM(launchArgs.toTypedArray())
                Logger.appendToLog("Java Exit code: $exitCode")

                // Use a local variable to capture the state at exit time
                val wasReady = runtimeLifecycleReady
                onLaunchComplete(exitCode, waitForMainMenu, bootOverlayController?.let { false } ?: false)

            } catch (t: Throwable) {
                runtimeLifecycleReady = false
                Log.e(TAG, "Launch failed", t)
                CrashDiagnostics.recordThrowable(activity, "game_launch_thread", t)
                try {
                    Logger.appendToLog("Launch failed: $t")
                } catch (_: Throwable) {}
                onLaunchFailed(t)
            } finally {
                runtimeLifecycleReady = false
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
        runtimeLifecycleReady = false

        if (jvmLogListenerRegistered) {
            try {
                Logger.removeLogListener(jvmLogcatListener)
            } catch (_: Throwable) {}
            jvmLogListenerRegistered = false
        }
    }

    private fun findLaunchArgValue(args: List<String>?, keyPrefix: String?): String {
        if (args == null || keyPrefix.isNullOrEmpty()) return "${keyPrefix}<invalid>"
        for (arg in args) {
            if (arg.startsWith(keyPrefix)) return arg
        }
        return "${keyPrefix}<missing>"
    }
}