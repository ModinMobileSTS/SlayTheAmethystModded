package io.stamethyst.backend.launch

import android.content.Context
import android.os.Build
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.render.RendererBackend
import net.kdt.pojavlaunch.AWTCanvasView
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone

object StsLaunchSpec {
    const val LAUNCH_MODE_VANILLA = "vanilla"
    const val LAUNCH_MODE_MTS_BASEMOD = "mts_basemod"

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File): List<String> {
        return buildArgs(context, javaHome, LAUNCH_MODE_VANILLA, RendererBackend.OPENGL_ES2)
    }

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File, launchMode: String): List<String> {
        return buildArgs(context, javaHome, launchMode, RendererBackend.OPENGL_ES2)
    }

    @JvmStatic
    fun buildArgs(
        context: Context,
        javaHome: File,
        launchMode: String,
        renderer: RendererBackend?
    ): List<String> {
        val stsRoot = RuntimePaths.stsRoot(context)
        val stsHome = File(stsRoot, "home")
        if (!stsHome.exists()) {
            stsHome.mkdirs()
        }
        val hsErrFile = File(stsRoot, "hs_err_pid%p.log")
        val jvmOutputFile = File(stsRoot, "jvm_output.log")
        val forceInterpreterFlag = File(stsRoot, "compat_xint.flag")
        val classTraceFlag = File(stsRoot, "classload_trace.flag")
        val is64BitRuntime = is64BitRuntime(javaHome)

        val args = ArrayList<String>()
        // Performance-first by default, with a compatibility fallback file switch.
        // Create files/sts/compat_xint.flag to force interpreted mode on unstable devices.
        if (forceInterpreterFlag.exists()) {
            args.add("-Xint")
        } else {
            args.add("-XX:+TieredCompilation")
        }
        if (is64BitRuntime) {
            // Some OpenJDK 8 aarch64 builds crash in VM init with compressed pointers on newer Android stacks.
            // Disable compressed pointers to prefer startup stability over peak performance.
            args.add("-XX:-UseCompressedOops")
            args.add("-XX:-UseCompressedClassPointers")
        }
        args.add("-Xms512M")
        args.add("-Xmx1024M")
        args.add("-XX:+DisableExplicitGC")
        if (is64BitRuntime) {
            // Reduce periodic frame hitching from stop-the-world pauses.
            args.add("-XX:+UseG1GC")
            args.add("-XX:MaxGCPauseMillis=25")
            args.add("-XX:+ParallelRefProcEnabled")
        }
        args.add("-XX:ErrorFile=${hsErrFile.absolutePath}")
        args.add("-XX:+UnlockDiagnosticVMOptions")
        args.add("-XX:+LogVMOutput")
        args.add("-XX:LogFile=${jvmOutputFile.absolutePath}")
        if (LAUNCH_MODE_MTS_BASEMOD == launchMode) {
            // BaseMod bytecode can fail verification on some Android/OpenJDK 8 combos after MTS patching.
            args.add("-noverify")
        }
        if (classTraceFlag.exists()) {
            args.add("-verbose:class")
        }
        args.add("-Dorg.lwjgl.util.Debug=true")
        args.add("-Dorg.lwjgl.util.DebugLoader=true")
        args.add("-Djava.home=${javaHome.absolutePath}")
        args.add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
        args.add("-Duser.home=${stsHome.absolutePath}")
        args.add("-Duser.dir=${stsRoot.absolutePath}")
        args.add("-Duser.language=${Locale.getDefault().language}")
        args.add("-Duser.timezone=${TimeZone.getDefault().id}")
        args.add("-Dos.name=Linux")
        args.add("-Dos.version=Android-${Build.VERSION.RELEASE}")
        args.add("-Djdk.lang.Process.launchMechanism=FORK")
        val effectiveRenderer = renderer ?: RendererBackend.OPENGL_ES2
        args.add("-Dorg.lwjgl.opengl.libname=${effectiveRenderer.lwjglOpenGlLibName()}")
        if (effectiveRenderer == RendererBackend.OPENGL_ES2) {
            // Avoid desktop OpenGL 3.3 capability probing on GLES backends.
            args.add("-Dorg.lwjgl.opengl.maxVersion=3.2")
        }
        args.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        args.add("-Dorg.lwjgl.libname=${context.applicationInfo.nativeLibraryDir}/liblwjgl.so")
        args.add("-Dorg.lwjgl.openal.libname=${context.applicationInfo.nativeLibraryDir}/libopenal.so")
        args.add("-Dorg.lwjgl.librarypath=${context.applicationInfo.nativeLibraryDir}")
        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${context.applicationInfo.nativeLibraryDir}")
        args.add("-Dorg.lwjgl.system.EmulateSystemLoadLibrary=true")
        args.add("-Dglfwstub.windowWidth=${Math.max(1, CallbackBridge.windowWidth)}")
        args.add("-Dglfwstub.windowHeight=${Math.max(1, CallbackBridge.windowHeight)}")
        args.add("-Dglfwstub.initEgl=false")
        args.add("-Djava.awt.headless=false")
        args.add("-Dcacio.managed.screensize=${AWTCanvasView.AWT_CANVAS_WIDTH}x${AWTCanvasView.AWT_CANVAS_HEIGHT}")
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        args.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel")
        args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
        args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
        args.add(
            "-Damethyst.gdx.fbo_fallback=" +
                if (CompatibilitySettings.isOriginalFboPatchEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.virtual_fbo_poc=" +
                if (CompatibilitySettings.isVirtualFboPocEnabled(context)) "true" else "false"
        )
        args.add("-Damethyst.bridge.events=${RuntimePaths.bootBridgeEventsFile(context).absolutePath}")
        args.add("-Damethyst.bridge.delegate=com.evacipated.cardcrawl.modthespire.Loader")
        args.add("-Damethyst.bridge.mode=$launchMode")

        addCacioBootClasspath(args, RuntimePaths.cacioDir(context))

        args.add("-javaagent:${RuntimePaths.lwjgl2InjectorJar(context).absolutePath}")
        args.add("-cp")
        if (LAUNCH_MODE_MTS_BASEMOD == launchMode) {
            args.add(
                RuntimePaths.bootBridgeJar(context).absolutePath +
                    ":" + RuntimePaths.lwjglJar(context).absolutePath +
                    ":" + RuntimePaths.mtsGdxApiJar(context).absolutePath +
                    ":" + RuntimePaths.mtsStsResourcesJar(context).absolutePath +
                    ":" + RuntimePaths.mtsBaseModResourcesJar(context).absolutePath +
                    ":" + RuntimePaths.importedMtsJar(context).absolutePath
            )
            args.add("io.stamethyst.bridge.BootBridgeLauncher")
            // Prevent ModTheSpire from attempting desktop-style self-restart via jre1.8.0_51
            // and exiting the Android process immediately.
            args.add("--jre51")
            args.add("--skip-launcher")
            val launchMods: List<String> = try {
                ModManager.resolveLaunchModIds(context)
            } catch (_: Exception) {
                Arrays.asList(ModManager.MOD_ID_BASEMOD, ModManager.MOD_ID_STSLIB)
            }
            args.add("--mods")
            args.add(joinModIds(launchMods))
        } else {
            args.add(
                RuntimePaths.gdxPatchJar(context).absolutePath +
                    ":" + RuntimePaths.lwjglJar(context).absolutePath +
                    ":" + RuntimePaths.importedStsJar(context).absolutePath
            )
            args.add("com.megacrit.cardcrawl.desktop.DesktopLauncher")
        }
        return args
    }

    private fun joinModIds(modIds: List<String>): String {
        val builder = StringBuilder()
        for (modId in modIds) {
            val value = modId.trim()
            if (value.isEmpty()) {
                continue
            }
            if (builder.isNotEmpty()) {
                builder.append(",")
            }
            builder.append(value)
        }
        return builder.toString()
    }

    private fun addCacioBootClasspath(args: MutableList<String>, cacioDir: File) {
        val files = cacioDir.listFiles()
            ?: throw IllegalStateException("Missing caciocavallo directory: ${cacioDir.absolutePath}")
        val jars = ArrayList<File>()
        for (file in files) {
            if (file.isFile && file.name.endsWith(".jar")) {
                jars.add(file)
            }
        }
        if (jars.isEmpty()) {
            throw IllegalStateException("No caciocavallo jars found in ${cacioDir.absolutePath}")
        }
        jars.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }

        val boot = StringBuilder("-Xbootclasspath/p")
        for (jar in jars) {
            boot.append(":").append(jar.absolutePath)
        }
        args.add(boot.toString())
    }

    private fun is64BitRuntime(javaHome: File): Boolean {
        return File(javaHome, "lib/aarch64").isDirectory ||
            File(javaHome, "lib/arm64").isDirectory ||
            File(javaHome, "lib/x86_64").isDirectory
    }
}
