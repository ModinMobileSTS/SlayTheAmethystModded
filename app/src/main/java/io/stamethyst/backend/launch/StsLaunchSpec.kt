package io.stamethyst.backend.launch

import android.content.Context
import android.os.Build
import io.stamethyst.backend.mods.CompatibilitySettings
import io.stamethyst.backend.mods.ModManager
import io.stamethyst.backend.render.RendererBackendResolver
import io.stamethyst.backend.render.RendererDecision
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import net.kdt.pojavlaunch.AWTCanvasView
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone

object StsLaunchSpec {
    private const val DEFAULT_G1_MAX_PAUSE_MILLIS = 50
    const val LAUNCH_MODE_VANILLA = "vanilla"
    const val LAUNCH_MODE_MTS = "mts"
    // Legacy alias kept only so old debug scripts still resolve to the single MTS mode.
    const val LAUNCH_MODE_MTS_BASEMOD = "mts_basemod"

    @JvmStatic
    fun isMtsLaunchMode(launchMode: String?): Boolean {
        return launchMode == LAUNCH_MODE_MTS || launchMode == LAUNCH_MODE_MTS_BASEMOD
    }

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File): List<String> {
        return buildArgs(
            context = context,
            javaHome = javaHome,
            launchMode = LAUNCH_MODE_VANILLA,
            rendererDecision = resolveRendererDecision(context),
            forceJvmCrash = false
        )
    }

    @JvmStatic
    fun buildArgs(context: Context, javaHome: File, launchMode: String): List<String> {
        return buildArgs(
            context = context,
            javaHome = javaHome,
            launchMode = launchMode,
            rendererDecision = resolveRendererDecision(context),
            forceJvmCrash = false
        )
    }

    @JvmStatic
    fun buildArgs(
        context: Context,
        javaHome: File,
        launchMode: String,
        rendererDecision: RendererDecision,
        forceJvmCrash: Boolean = false
    ): List<String> {
        val stsRoot = RuntimePaths.stsRoot(context)
        val stsHome = RuntimePaths.stsHome(context)
        if (!stsHome.exists()) {
            stsHome.mkdirs()
        }
        val forceInterpreterFlag = File(stsRoot, "compat_xint.flag")
        val classTraceFlag = File(stsRoot, "classload_trace.flag")
        val is64BitRuntime = is64BitRuntime(javaHome)

        val args = ArrayList<String>()
        // Performance-first by default, with a compatibility fallback file switch.
        // Create <stsRoot>/compat_xint.flag to force interpreted mode on unstable devices.
        if (forceInterpreterFlag.exists()) {
            args.add("-Xint")
        } else {
            args.add("-XX:+TieredCompilation")
        }
        if (is64BitRuntime) {
            val useCompressedPointers = LauncherConfig.isJvmCompressedPointersEnabled(context)
            // Some OpenJDK 8 aarch64 builds crash in VM init with compressed pointers on newer Android stacks.
            // Disable compressed pointers to prefer startup stability over peak performance.
            if (useCompressedPointers) {
                args.add("-XX:+UseCompressedOops")
                args.add("-XX:+UseCompressedClassPointers")
            } else {
                args.add("-XX:-UseCompressedOops")
                args.add("-XX:-UseCompressedClassPointers")
            }
        }
        val heapMaxMb = LauncherConfig.readJvmHeapMaxMb(context)
        val heapStartMb = heapMaxMb
        args.add("-Xms${heapStartMb}M")
        args.add("-Xmx${heapMaxMb}M")
        args.add(
            "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors().coerceAtLeast(1)}"
        )
        args.add("-XX:+DisableExplicitGC")
        if (is64BitRuntime) {
            // Reduce periodic frame hitching from stop-the-world pauses.
            args.add("-XX:+UseG1GC")
            args.add("-XX:MaxGCPauseMillis=$DEFAULT_G1_MAX_PAUSE_MILLIS")
            args.add("-XX:+ParallelRefProcEnabled")
            if (LauncherConfig.isJvmStringDeduplicationEnabled(context)) {
                args.add("-XX:+UseStringDeduplication")
            } else {
                args.add("-XX:-UseStringDeduplication")
            }
        }
        args.add("-XX:ErrorFile=/dev/null")
        if (LauncherConfig.isGamePerformanceOverlayEnabled(context)) {
            args.add("-XX:+UnlockDiagnosticVMOptions")
            args.add("-verbose:gc")
            args.add("-Xloggc:${RuntimePaths.jvmGcLog(context).absolutePath}")
        }
        if (isMtsLaunchMode(launchMode)) {
            // BaseMod bytecode can fail verification on some Android/OpenJDK 8 combos after MTS patching.
            args.add("-noverify")
        }
        if (classTraceFlag.exists()) {
            args.add("-verbose:class")
        }
        val enableLwjglDebug = LauncherConfig.isLwjglDebugEnabled(context)
        val enableGdxPadCursorDebug = LauncherConfig.isGdxPadCursorDebugEnabled(context)
        val enableGlBridgeSwapHeartbeatDebug =
            LauncherConfig.isGlBridgeSwapHeartbeatDebugEnabled(context)
        args.add("-Dorg.lwjgl.util.Debug=${if (enableLwjglDebug) "true" else "false"}")
        args.add("-Dorg.lwjgl.util.DebugLoader=${if (enableLwjglDebug) "true" else "false"}")
        args.add("-Damethyst.debug.gdx_pad_cursor=${if (enableGdxPadCursorDebug) "true" else "false"}")
        args.add(
            "-Damethyst.debug.glbridge_swap_heartbeat=" +
                if (enableGlBridgeSwapHeartbeatDebug) "true" else "false"
        )
        args.add("-Djava.home=${javaHome.absolutePath}")
        args.add("-Djava.io.tmpdir=${context.cacheDir.absolutePath}")
        args.add("-Duser.home=${stsHome.absolutePath}")
        args.add("-Duser.dir=${stsRoot.absolutePath}")
        args.add(
            "-Damethyst.touchscreen_enabled=" +
                if (LauncherConfig.readTouchscreenEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.mobile_hud_enabled=" +
                if (LauncherConfig.readMobileHudEnabled(context)) "true" else "false"
        )
        args.add("-Duser.language=${Locale.getDefault().language}")
        args.add("-Duser.timezone=${TimeZone.getDefault().id}")
        args.add("-Dos.name=Linux")
        args.add("-Dos.version=Android-${Build.VERSION.RELEASE}")
        args.add("-Djdk.lang.Process.launchMechanism=FORK")
        args.add("-Dorg.lwjgl.opengl.libname=${rendererDecision.effectiveBackend.lwjglOpenGlLibName()}")
        // Clamp reported GL capability to a conservative baseline on GLES bridges.
        // This avoids exposing desktop GL3.3 paths with missing entry points.
        args.add("-Dorg.lwjgl.opengl.maxVersion=3.0")
        args.add("-Dorg.lwjgl.opengles.maxVersion=3.0")
        if (enableLwjglDebug) {
            args.add("-Dorg.lwjgl.util.DebugFunctions=true")
        }
        args.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        args.add("-Dorg.lwjgl.libname=${context.applicationInfo.nativeLibraryDir}/liblwjgl.so")
        args.add("-Dorg.lwjgl.openal.libname=${context.applicationInfo.nativeLibraryDir}/libopenal.so")
        args.add("-Dorg.lwjgl.librarypath=${context.applicationInfo.nativeLibraryDir}")
        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${context.applicationInfo.nativeLibraryDir}")
        args.add("-Dorg.lwjgl.system.EmulateSystemLoadLibrary=true")
        args.add("-Damethyst.renderer.selection_mode=${rendererDecision.selectionMode.persistedValue}")
        args.add("-Damethyst.renderer.auto_backend=${rendererDecision.automaticBackend.rendererId()}")
        args.add("-Damethyst.renderer.effective_backend=${rendererDecision.effectiveBackend.rendererId()}")
        args.add("-Damethyst.renderer.requested_surface=${rendererDecision.requestedSurfaceBackend.persistedValue}")
        args.add("-Damethyst.renderer.effective_surface=${rendererDecision.effectiveSurfaceBackend.persistedValue}")
        if (rendererDecision.effectiveBackend == RendererBackend.OPENGL_ES2_GL4ES) {
            args.add("-Damethyst.lwjgl.force_default_framebuffer=true")
        }
        rendererDecision.fallbackSummary()?.let {
            args.add("-Damethyst.renderer.fallback_reason=$it")
        }
        args.add("-Damethyst.gdx.native_dir=${RuntimePaths.gdxPatchNativesDir(context).absolutePath}")
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
            "-Damethyst.gdx.virtual_fbo_poc=" +
                if (CompatibilitySettings.isVirtualFboPocEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.global_atlas_filter_compat=" +
                if (CompatibilitySettings.isGlobalAtlasFilterCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.runtime_texture_compat=" +
                if (CompatibilitySettings.isRuntimeTextureCompatEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.force_linear_mipmap_filter=" +
                if (CompatibilitySettings.isForceLinearMipmapFilterEnabled(context)) "true" else "false"
        )
        args.add(
            "-Damethyst.gdx.non_renderable_fbo_format_compat=" +
                if (CompatibilitySettings.isNonRenderableFboFormatCompatEnabled(context)) "true" else "false"
        )
        val bridgeDelegateMainClass = if (isMtsLaunchMode(launchMode)) {
            "com.evacipated.cardcrawl.modthespire.Loader"
        } else {
            "com.megacrit.cardcrawl.desktop.DesktopLauncher"
        }
        args.add("-Damethyst.bridge.delegate=$bridgeDelegateMainClass")
        args.add("-Damethyst.bridge.mode=$launchMode")
        args.add("-Damethyst.debug.force_jvm_crash=${if (forceJvmCrash) "true" else "false"}")
        args.add("-Damethyst.bridge.events=${RuntimePaths.bootBridgeEventsLog(context).absolutePath}")
        if (LauncherConfig.isGamePerformanceOverlayEnabled(context)) {
            args.add("-Damethyst.bridge.heap_snapshot=${RuntimePaths.jvmHeapSnapshot(context).absolutePath}")
            args.add("-Damethyst.bridge.gc_histogram_dir=${RuntimePaths.jvmHistogramsDir(context).absolutePath}")
        }

        addCacioBootClasspath(args, RuntimePaths.cacioDir(context))

        args.add("-javaagent:${RuntimePaths.lwjgl2InjectorJar(context).absolutePath}")
        args.add("-cp")
        if (isMtsLaunchMode(launchMode)) {
            val classpathEntries = arrayListOf(
                RuntimePaths.bootBridgeJar(context).absolutePath,
                RuntimePaths.lwjglJar(context).absolutePath,
                RuntimePaths.mtsGdxApiJar(context).absolutePath
            )
            if (RuntimePaths.bundledLog4jApiJar(context).isFile) {
                classpathEntries.add(RuntimePaths.bundledLog4jApiJar(context).absolutePath)
            }
            if (RuntimePaths.bundledLog4jCoreJar(context).isFile) {
                classpathEntries.add(RuntimePaths.bundledLog4jCoreJar(context).absolutePath)
            }
            classpathEntries.add(RuntimePaths.mtsStsResourcesJar(context).absolutePath)
            classpathEntries.add(RuntimePaths.mtsBaseModResourcesJar(context).absolutePath)
            classpathEntries.add(RuntimePaths.importedMtsJar(context).absolutePath)
            args.add(classpathEntries.joinToString(":"))
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
                RuntimePaths.bootBridgeJar(context).absolutePath +
                    ":" + RuntimePaths.gdxPatchJar(context).absolutePath +
                    ":" + RuntimePaths.lwjglJar(context).absolutePath +
                    ":" + RuntimePaths.importedStsJar(context).absolutePath
            )
            args.add("io.stamethyst.bridge.BootBridgeLauncher")
        }
        return args
    }

    private fun resolveRendererDecision(context: Context): RendererDecision {
        return RendererBackendResolver.resolve(
            context = context,
            requestedSurfaceBackend = LauncherConfig.readRenderSurfaceBackend(context),
            selectionMode = LauncherConfig.readRendererSelectionMode(context),
            manualBackend = LauncherConfig.readManualRendererBackend(context)
        )
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
