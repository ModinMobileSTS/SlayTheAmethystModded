package io.stamethyst;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RendererConfig {
    private static final String TAG = "RendererConfig";
    private static final String LIB_GLX_SHIM = "libglxshim.so";
    private static final String LIB_EGL_MESA = "libEGL_mesa.so";
    private static final String LIB_GLAPI = "libglapi.so";

    private RendererConfig() {
    }

    @NonNull
    public static RendererBackend readPreferredBackend(Context context) {
        File configFile = RuntimePaths.rendererConfigFile(context);
        if (!configFile.exists()) {
            return RendererBackend.OPENGL_ES2;
        }
        try (FileInputStream input = new FileInputStream(configFile)) {
            byte[] bytes = new byte[(int) Math.min(configFile.length(), 128)];
            int read = input.read(bytes);
            if (read <= 0) {
                return RendererBackend.OPENGL_ES2;
            }
            String value = new String(bytes, 0, read, StandardCharsets.UTF_8).trim();
            return RendererBackend.fromRendererId(value);
        } catch (Throwable error) {
            Log.w(TAG, "Failed to read renderer config, fallback to OpenGL ES2", error);
            return RendererBackend.OPENGL_ES2;
        }
    }

    public static void writePreferredBackend(Context context, RendererBackend backend) throws IOException {
        File configFile = RuntimePaths.rendererConfigFile(context);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create renderer config directory: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(configFile, false)) {
            output.write(backend.rendererId().getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        }
    }

    @NonNull
    public static ResolutionResult resolveEffectiveBackend(Context context, RendererBackend preferred) {
        if (preferred != RendererBackend.KOPPER_ZINK) {
            return new ResolutionResult(preferred, preferred, null);
        }

        List<String> reasons = new ArrayList<>();
        if (!supportsVulkan(context)) {
            reasons.add("Device Vulkan feature is unavailable");
        }

        File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File glxShim = new File(nativeLibDir, LIB_GLX_SHIM);
        if (!glxShim.isFile()) {
            reasons.add("Missing " + LIB_GLX_SHIM);
        }

        File mesaEgl = new File(nativeLibDir, LIB_EGL_MESA);
        if (!mesaEgl.isFile()) {
            reasons.add("Missing " + LIB_EGL_MESA);
        }

        File glapi = new File(nativeLibDir, LIB_GLAPI);
        if (!glapi.isFile()) {
            reasons.add("Missing " + LIB_GLAPI);
        }

        if (reasons.isEmpty()) {
            return new ResolutionResult(preferred, preferred, null);
        }

        return new ResolutionResult(preferred, RendererBackend.OPENGL_ES2, joinReasons(reasons));
    }

    @NonNull
    private static String joinReasons(List<String> reasons) {
        StringBuilder builder = new StringBuilder();
        for (String reason : reasons) {
            if (reason == null || reason.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(reason.trim());
        }
        return builder.length() == 0 ? "Unknown reason" : builder.toString();
    }

    private static boolean supportsVulkan(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            boolean hasVulkanLevel = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
            boolean hasVulkan10 = packageManager.hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                    0x00400000
            );
            return hasVulkanLevel || hasVulkan10;
        } catch (Throwable error) {
            Log.w(TAG, "Failed to detect Vulkan support", error);
            return false;
        }
    }

    public static final class ResolutionResult {
        public final RendererBackend preferred;
        public final RendererBackend effective;
        @Nullable
        public final String reason;

        public ResolutionResult(RendererBackend preferred, RendererBackend effective, @Nullable String reason) {
            this.preferred = preferred;
            this.effective = effective;
            this.reason = reason;
        }

        public boolean isFallback() {
            return preferred != effective;
        }

        @NonNull
        public String toLogText() {
            StringBuilder builder = new StringBuilder();
            builder.append("renderer preferred=").append(preferred.rendererId());
            builder.append(", effective=").append(effective.rendererId());
            if (reason != null && !reason.trim().isEmpty()) {
                builder.append(", reason=").append(reason.trim());
            }
            return builder.toString();
        }
    }
}
