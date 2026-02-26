/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import net.kdt.pojavlaunch.Logger;

import io.stamethyst.backend.RendererBackend;
import io.stamethyst.backend.RuntimePaths;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JREUtils {
    private static final String TAG = "JREUtils";

    public static String LD_LIBRARY_PATH;
    public static String jvmLibraryPath;
    private static String runtimeLibDir;

    private JREUtils() {
    }

    public static ArrayList<File> locateLibs(File path) {
        ArrayList<File> output = new ArrayList<>();
        File[] list = path.listFiles();
        if (list == null) {
            return output;
        }
        for (File file : list) {
            if (file.isFile() && file.getName().endsWith(".so")) {
                output.add(file);
            } else if (file.isDirectory()) {
                output.addAll(locateLibs(file));
            }
        }
        return output;
    }

    public static String findInLdLibPath(String libName) {
        String ldPath = safeGetEnv("LD_LIBRARY_PATH");
        if (ldPath == null || ldPath.isEmpty()) {
            return libName;
        }
        for (String path : ldPath.split(":")) {
            File candidate = new File(path, libName);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return libName;
    }

    public static void relocateLibPath(String nativeLibDir, String javaHome) {
        runtimeLibDir = findRuntimeLibDir(javaHome);
        StringBuilder ldPath = new StringBuilder();
        ldPath.append(javaHome).append("/").append(runtimeLibDir).append("/jli").append(":");
        ldPath.append(javaHome).append("/").append(runtimeLibDir).append(":");
        if (is64BitRuntimeLibDir(runtimeLibDir)) {
            ldPath.append("/system/lib64:/vendor/lib64:/vendor/lib64/hw:");
        } else {
            ldPath.append("/system/lib:/vendor/lib:/vendor/lib/hw:");
        }
        ldPath.append(nativeLibDir);
        LD_LIBRARY_PATH = ldPath.toString();

        try {
            Os.setenv("LD_LIBRARY_PATH", LD_LIBRARY_PATH, true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setJavaEnvironment(
            Context context,
            String javaHome,
            int windowWidth,
            int windowHeight,
            RendererBackend renderer
    ) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POJAV_NATIVEDIR", context.getApplicationInfo().nativeLibraryDir);
        env.put("JAVA_HOME", javaHome);
        env.put("HOME", context.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", LD_LIBRARY_PATH);
        env.put("PATH", javaHome + "/bin:" + safeGetEnv("PATH"));
        env.put("FORCE_VSYNC", "false");
        env.put("LIBGL_VSYNC", "0");
        env.put("LIBGL_SHADERNOGLES", "1");
        env.put("LIBGL_NOHIGHP", "1");
        env.put("AWTSTUB_WIDTH", Integer.toString(Math.max(1, windowWidth)));
        env.put("AWTSTUB_HEIGHT", Integer.toString(Math.max(1, windowHeight)));
        env.put("MESA_GLSL_CACHE_DIR", context.getCacheDir().getAbsolutePath());

        clearEnv("POJAVEXEC_EGL");
        clearEnv("MESA_LOADER_DRIVER_OVERRIDE");
        clearEnv("MESA_GL_VERSION_OVERRIDE");
        clearEnv("MESA_GLSL_VERSION_OVERRIDE");
        clearEnv("LIBGL_EGL");
        clearEnv("LIBGL_GLES");
        clearEnv("GALLIUM_DRIVER");
        clearEnv("MESA_ANDROID_NO_KMS_SWRAST");
        clearEnv("POJAV_RENDERER");
        clearEnv("MG_PLUGIN_STATUS");
        clearEnv("MG_DIR_PATH");

        RendererBackend effectiveRenderer = renderer == null
                ? RendererBackend.OPENGL_ES2
                : renderer;
        switch (effectiveRenderer) {
            case KOPPER_ZINK:
                env.put("AMETHYST_RENDERER", RendererBackend.KOPPER_ZINK.rendererId());
                env.put("LIBGL_ES", "3");
                env.put("POJAVEXEC_EGL", "libEGL_mesa.so");
                env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
                env.put("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
                env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
                break;
            case ANGLE:
                env.put("AMETHYST_RENDERER", RendererBackend.ANGLE.rendererId());
                env.put("LIBGL_ES", "2");
                env.put("LIBGL_GLES", "libGLESv2_angle.so");
                env.put("POJAVEXEC_EGL", "libEGL_angle.so");
                break;
            case MOBILEGLUES:
                env.put("AMETHYST_RENDERER", RendererBackend.MOBILEGLUES.rendererId());
                env.put("LIBGL_ES", "3");
                env.put("POJAV_RENDERER", "opengles3");
                env.put("POJAVEXEC_EGL", "libmobileglues.so");
                env.put("LIBGL_EGL", "libmobileglues.so");
                env.put("MG_PLUGIN_STATUS", "1");
                File mobileGluesDir = new File(RuntimePaths.stsRoot(context), "mg");
                if (!mobileGluesDir.exists() && !mobileGluesDir.mkdirs()) {
                    Log.w(TAG, "Failed to create MobileGlues dir: " + mobileGluesDir.getAbsolutePath());
                }
                env.put("MG_DIR_PATH", mobileGluesDir.getAbsolutePath());
                break;
            case OPENGL_ES2:
            default:
                env.put("AMETHYST_RENDERER", RendererBackend.OPENGL_ES2.rendererId());
                env.put("LIBGL_ES", "2");
                break;
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            try {
                Os.setenv(entry.getKey(), entry.getValue(), true);
                Logger.appendToLog("env " + entry.getKey() + "=" + entry.getValue());
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set env " + entry.getKey(), t);
            }
        }

        File serverFile = new File(javaHome + "/" + runtimeLibDir + "/server/libjvm.so");
        jvmLibraryPath = javaHome + "/" + runtimeLibDir + "/" + (serverFile.exists() ? "server" : "client");
        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    private static void clearEnv(String key) {
        try {
            Os.unsetenv(key);
            Logger.appendToLog("env unset " + key);
        } catch (Throwable ignored) {
        }
    }

    public static void initJavaRuntime(String javaHome) {
        dlopen(findInLdLibPath("libjli.so"));
        if (!dlopen("libjvm.so")) {
            dlopen(jvmLibraryPath + "/libjvm.so");
        }
        dlopen(findInLdLibPath("libverify.so"));
        dlopen(findInLdLibPath("libjava.so"));
        dlopen(findInLdLibPath("libnet.so"));
        dlopen(findInLdLibPath("libnio.so"));
        dlopen(findInLdLibPath("libawt.so"));
        dlopen(findInLdLibPath("libawt_headless.so"));
        dlopen(findInLdLibPath("libfontmanager.so"));
        dlopen(findInLdLibPath("libfreetype.so"));

        for (File file : locateLibs(new File(javaHome, runtimeLibDir))) {
            dlopen(file.getAbsolutePath());
        }
        dlopen(findInLdLibPath("libopenal.so"));
    }

    private static String findRuntimeLibDir(String javaHome) {
        String[] candidates = {
                "lib/aarch64",
                "lib/arm64",
                "lib/aarch32",
                "lib/arm32",
                "lib/armeabi-v7a",
                "lib/arm",
                "lib"
        };
        for (String candidate : candidates) {
            File dir = new File(javaHome, candidate);
            if (dir.isDirectory()) {
                File server = new File(dir, "server/libjvm.so");
                File client = new File(dir, "client/libjvm.so");
                if (server.exists() || client.exists()) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Could not find runtime lib directory in " + javaHome);
    }

    private static boolean is64BitRuntimeLibDir(String runtimeDir) {
        if (runtimeDir == null) {
            return false;
        }
        return runtimeDir.contains("aarch64") || runtimeDir.contains("arm64") || runtimeDir.contains("x86_64");
    }

    private static String safeGetEnv(String key) {
        try {
            String value = Os.getenv(key);
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    public static native int chdir(String path);

    public static native boolean dlopen(String libPath);

    public static native void setLdLibraryPath(String ldLibraryPath);

    public static native void setupBridgeWindow(Object surface);

    public static native void releaseBridgeWindow();

    public static native void initializeHooks();

    public static native void setupExitMethod(Context context);

    public static native int[] renderAWTScreenFrame();

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }
}
