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

    fun start(
        javaHome: File,
        waitForMainMenu: Boolean,
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
                onLaunchComplete(exitCode, waitForMainMenu, bootOverlayController?.let { false } ?: false)

            } catch (t: Throwable) {
                runtimeLifecycleReady = false
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
    }
}
