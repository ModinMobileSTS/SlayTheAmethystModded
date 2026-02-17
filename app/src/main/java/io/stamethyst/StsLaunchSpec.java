package io.stamethyst;

import android.content.Context;
import android.os.Build;

import net.kdt.pojavlaunch.AWTCanvasView;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class StsLaunchSpec {
    private StsLaunchSpec() {
    }

    public static List<String> buildArgs(Context context, File javaHome) {
        File stsRoot = RuntimePaths.stsRoot(context);
        File stsHome = new File(stsRoot, "home");
        if (!stsHome.exists()) {
            stsHome.mkdirs();
        }
        File forceInterpreterFlag = new File(stsRoot, "compat_xint.flag");

        List<String> args = new ArrayList<>();
        // Performance-first by default, with a compatibility fallback file switch.
        // Create files/sts/compat_xint.flag to force interpreted mode on unstable devices.
        if (forceInterpreterFlag.exists()) {
            args.add("-Xint");
        } else {
            args.add("-XX:+TieredCompilation");
        }
        // Some OpenJDK 8 aarch64 builds crash in VM init with compressed pointers on newer Android stacks.
        // Disable compressed pointers to prefer startup stability over peak performance.
        args.add("-XX:-UseCompressedOops");
        args.add("-XX:-UseCompressedClassPointers");
        args.add("-Xms512M");
        args.add("-Xmx1024M");
        // Reduce periodic frame hitching from stop-the-world pauses.
        args.add("-XX:+UseG1GC");
        args.add("-XX:MaxGCPauseMillis=25");
        args.add("-XX:+DisableExplicitGC");
        args.add("-XX:+ParallelRefProcEnabled");
        args.add("-XX:+UseStringDeduplication");
        args.add("-Djava.home=" + javaHome.getAbsolutePath());
        args.add("-Djava.io.tmpdir=" + context.getCacheDir().getAbsolutePath());
        args.add("-Duser.home=" + stsHome.getAbsolutePath());
        args.add("-Duser.dir=" + stsRoot.getAbsolutePath());
        args.add("-Duser.language=" + Locale.getDefault().getLanguage());
        args.add("-Duser.timezone=" + TimeZone.getDefault().getID());
        args.add("-Dos.name=Linux");
        args.add("-Dos.version=Android-" + Build.VERSION.RELEASE);
        args.add("-Djdk.lang.Process.launchMechanism=FORK");
        args.add("-Dorg.lwjgl.opengl.libname=libGLESv2.so");
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

        addCacioBootClasspath(args, RuntimePaths.cacioDir(context));

        args.add("-javaagent:" + RuntimePaths.lwjgl2InjectorJar(context).getAbsolutePath());
        args.add("-cp");
        args.add(
                RuntimePaths.gdxPatchJar(context).getAbsolutePath()
                        + ":" + RuntimePaths.lwjglJar(context).getAbsolutePath()
                        + ":" + RuntimePaths.importedStsJar(context).getAbsolutePath()
        );
        args.add("com.megacrit.cardcrawl.desktop.DesktopLauncher");
        return args;
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
}
