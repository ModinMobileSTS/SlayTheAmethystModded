package io.stamethyst;

import android.content.Context;
import android.os.Build;

import net.kdt.pojavlaunch.AWTCanvasView;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class StsLaunchSpec {
    public static final String LAUNCH_MODE_VANILLA = "vanilla";
    public static final String LAUNCH_MODE_MTS_BASEMOD = "mts_basemod";

    private StsLaunchSpec() {
    }

    public static List<String> buildArgs(Context context, File javaHome) {
        return buildArgs(context, javaHome, LAUNCH_MODE_VANILLA, RendererBackend.OPENGL_ES2);
    }

    public static List<String> buildArgs(Context context, File javaHome, String launchMode) {
        return buildArgs(context, javaHome, launchMode, RendererBackend.OPENGL_ES2);
    }

    public static List<String> buildArgs(
            Context context,
            File javaHome,
            String launchMode,
            RendererBackend renderer
    ) {
        File stsRoot = RuntimePaths.stsRoot(context);
        File stsHome = new File(stsRoot, "home");
        if (!stsHome.exists()) {
            stsHome.mkdirs();
        }
        File hsErrFile = new File(stsRoot, "hs_err_pid%p.log");
        File jvmOutputFile = new File(stsRoot, "jvm_output.log");
        File forceInterpreterFlag = new File(stsRoot, "compat_xint.flag");
        File classTraceFlag = new File(stsRoot, "classload_trace.flag");
        File lwjglDebugFlag = new File(stsRoot, "lwjgl_debug.flag");
        boolean is64BitRuntime = is64BitRuntime(javaHome);

        List<String> args = new ArrayList<>();
        // Performance-first by default, with a compatibility fallback file switch.
        // Create files/sts/compat_xint.flag to force interpreted mode on unstable devices.
        if (forceInterpreterFlag.exists()) {
            args.add("-Xint");
        } else {
            args.add("-XX:+TieredCompilation");
        }
        if (is64BitRuntime) {
            // Some OpenJDK 8 aarch64 builds crash in VM init with compressed pointers on newer Android stacks.
            // Disable compressed pointers to prefer startup stability over peak performance.
            args.add("-XX:-UseCompressedOops");
            args.add("-XX:-UseCompressedClassPointers");
        }
        args.add("-Xms512M");
        args.add("-Xmx1024M");
        args.add("-XX:+DisableExplicitGC");
        if (is64BitRuntime) {
            // Reduce periodic frame hitching from stop-the-world pauses.
            args.add("-XX:+UseG1GC");
            args.add("-XX:MaxGCPauseMillis=25");
            args.add("-XX:+ParallelRefProcEnabled");
        }
        args.add("-XX:ErrorFile=" + hsErrFile.getAbsolutePath());
        args.add("-XX:+UnlockDiagnosticVMOptions");
        args.add("-XX:+LogVMOutput");
        args.add("-XX:LogFile=" + jvmOutputFile.getAbsolutePath());
        if (LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
            // BaseMod bytecode can fail verification on some Android/OpenJDK 8 combos after MTS patching.
            args.add("-noverify");
        }
        if (classTraceFlag.exists()) {
            args.add("-verbose:class");
        }
        if (lwjglDebugFlag.exists()) {
            args.add("-Dorg.lwjgl.util.Debug=true");
            args.add("-Dorg.lwjgl.util.DebugLoader=true");
        }
        args.add("-Djava.home=" + javaHome.getAbsolutePath());
        args.add("-Djava.io.tmpdir=" + context.getCacheDir().getAbsolutePath());
        args.add("-Duser.home=" + stsHome.getAbsolutePath());
        args.add("-Duser.dir=" + stsRoot.getAbsolutePath());
        args.add("-Duser.language=" + Locale.getDefault().getLanguage());
        args.add("-Duser.timezone=" + TimeZone.getDefault().getID());
        args.add("-Dos.name=Linux");
        args.add("-Dos.version=Android-" + Build.VERSION.RELEASE);
        args.add("-Djdk.lang.Process.launchMechanism=FORK");
        RendererBackend effectiveRenderer = renderer == null
                ? RendererBackend.OPENGL_ES2
                : renderer;
        args.add("-Dorg.lwjgl.opengl.libname=" + effectiveRenderer.lwjglOpenGlLibName());
        args.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so");
        args.add("-Dorg.lwjgl.libname=" + context.getApplicationInfo().nativeLibraryDir + "/liblwjgl.so");
        args.add("-Dorg.lwjgl.openal.libname=" + context.getApplicationInfo().nativeLibraryDir + "/libopenal.so");
        args.add("-Dorg.lwjgl.librarypath=" + context.getApplicationInfo().nativeLibraryDir);
        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + context.getApplicationInfo().nativeLibraryDir);
        args.add("-Dorg.lwjgl.system.EmulateSystemLoadLibrary=true");
        args.add("-Dglfwstub.windowWidth=" + Math.max(1, CallbackBridge.windowWidth));
        args.add("-Dglfwstub.windowHeight=" + Math.max(1, CallbackBridge.windowHeight));
        args.add("-Dglfwstub.initEgl=false");
        args.add("-Djava.awt.headless=false");
        args.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT);
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        args.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel");
        args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
        args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
        args.add("-Damethyst.gdx.fbo_fallback="
                + (CompatibilitySettings.isOriginalFboPatchEnabled(context) ? "true" : "false"));
        args.add("-Damethyst.gdx.virtual_fbo_poc="
                + (CompatibilitySettings.isVirtualFboPocEnabled(context) ? "true" : "false"));
        args.add("-Damethyst.bridge.events=" + RuntimePaths.bootBridgeEventsFile(context).getAbsolutePath());
        args.add("-Damethyst.bridge.delegate=com.evacipated.cardcrawl.modthespire.Loader");
        args.add("-Damethyst.bridge.mode=" + launchMode);

        addCacioBootClasspath(args, RuntimePaths.cacioDir(context));

        args.add("-javaagent:" + RuntimePaths.lwjgl2InjectorJar(context).getAbsolutePath());
        args.add("-cp");
        if (LAUNCH_MODE_MTS_BASEMOD.equals(launchMode)) {
            args.add(
                    RuntimePaths.bootBridgeJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.lwjglJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.mtsGdxApiJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.mtsStsResourcesJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.mtsBaseModResourcesJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.importedMtsJar(context).getAbsolutePath()
            );
            args.add("io.stamethyst.bridge.BootBridgeLauncher");
            // Prevent ModTheSpire from attempting desktop-style self-restart via jre1.8.0_51
            // and exiting the Android process immediately.
            args.add("--jre51");
            args.add("--skip-launcher");
            List<String> launchMods;
            try {
                launchMods = ModManager.resolveLaunchModIds(context);
            } catch (Exception ignored) {
                launchMods = Arrays.asList(ModManager.MOD_ID_BASEMOD, ModManager.MOD_ID_STSLIB);
            }
            args.add("--mods");
            args.add(joinModIds(launchMods));
        } else {
            args.add(
                    RuntimePaths.gdxPatchJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.lwjglJar(context).getAbsolutePath()
                            + ":" + RuntimePaths.importedStsJar(context).getAbsolutePath()
            );
            args.add("com.megacrit.cardcrawl.desktop.DesktopLauncher");
        }
        return args;
    }

    private static String joinModIds(List<String> modIds) {
        StringBuilder builder = new StringBuilder();
        for (String modId : modIds) {
            if (modId == null) {
                continue;
            }
            String value = modId.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static void addCacioBootClasspath(List<String> args, File cacioDir) {
        File[] files = cacioDir.listFiles();
        if (files == null) {
            throw new IllegalStateException("Missing caciocavallo directory: " + cacioDir.getAbsolutePath());
        }
        List<File> jars = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                jars.add(file);
            }
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("No caciocavallo jars found in " + cacioDir.getAbsolutePath());
        }
        Collections.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        StringBuilder boot = new StringBuilder("-Xbootclasspath/p");
        for (File jar : jars) {
            boot.append(":").append(jar.getAbsolutePath());
        }
        args.add(boot.toString());
    }

    private static boolean is64BitRuntime(File javaHome) {
        return new File(javaHome, "lib/aarch64").isDirectory()
                || new File(javaHome, "lib/arm64").isDirectory()
                || new File(javaHome, "lib/x86_64").isDirectory();
    }
}
