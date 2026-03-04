/*
 * Derived from PojavLauncher project sources.
 * Source: https://github.com/AngelAuraMC/Amethyst-Android (branch: v3_openjdk)
 * License: LGPL-3.0
 * Modifications: adapted for the SlayTheAmethystModded Android integration.
 */

package net.kdt.pojavlaunch.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.system.ErrnoException;
import android.system.Os;

import io.stamethyst.backend.render.RendererBackend;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JREUtils {

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
            int windowHeight
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

        env.put("AMETHYST_RENDERER", RendererBackend.OPENGL_ES2.rendererId());
        env.put("LIBGL_ES", supportsGles3(context) ? "3" : "2");

        for (Map.Entry<String, String> entry : env.entrySet()) {
            try {
                Os.setenv(entry.getKey(), entry.getValue(), true);
            } catch (Throwable ignored) {}
        }

        File serverFile = new File(javaHome + "/" + runtimeLibDir + "/server/libjvm.so");
        jvmLibraryPath = javaHome + "/" + runtimeLibDir + "/" + (serverFile.exists() ? "server" : "client");
        setLdLibraryPath(jvmLibraryPath + ":" + LD_LIBRARY_PATH);
    }

    private static void clearEnv(String key) {
        try {
            Os.unsetenv(key);
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

    private static boolean supportsGles3(Context context) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return false;
            }
            ConfigurationInfo info = activityManager.getDeviceConfigurationInfo();
            if (info == null) {
                return false;
            }
            return info.reqGlEsVersion >= 0x00030000;
        } catch (Throwable ignored) {
            return false;
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
