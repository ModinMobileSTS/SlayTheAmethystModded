package io.stamethyst.backend.render

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object RendererConfig {
    private const val TAG = "RendererConfig"
    private const val LIB_GLX_SHIM = "libglxshim.so"
    private const val LIB_EGL_MESA = "libEGL_mesa.so"
    private const val LIB_GLAPI = "libglapi.so"
    private const val LIB_EGL_ANGLE = "libEGL_angle.so"
    private const val LIB_GLESV2_ANGLE = "libGLESv2_angle.so"
    private const val LIB_MOBILEGLUES = "libmobileglues.so"

    @JvmStatic
    fun readPreferredBackend(context: Context): RendererBackend {
        val configFile = RuntimePaths.rendererConfigFile(context)
        if (!configFile.exists()) {
            return RendererBackend.OPENGL_ES2
        }
        return try {
            FileInputStream(configFile).use { input ->
                val bytes = ByteArray(configFile.length().coerceAtMost(128L).toInt())
                val read = input.read(bytes)
                if (read <= 0) {
                    return RendererBackend.OPENGL_ES2
                }
                val value = String(bytes, 0, read, StandardCharsets.UTF_8).trim()
                RendererBackend.fromRendererId(value)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read renderer config, fallback to OpenGL ES2", error)
            RendererBackend.OPENGL_ES2
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writePreferredBackend(context: Context, backend: RendererBackend) {
        val configFile = RuntimePaths.rendererConfigFile(context)
        val parent = configFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create renderer config directory: ${parent.absolutePath}")
        }
        FileOutputStream(configFile, false).use { output ->
            output.write(backend.rendererId().toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
        }
    }

    @JvmStatic
    fun resolveEffectiveBackend(context: Context, preferred: RendererBackend?): ResolutionResult {
        val safePreferred = preferred ?: RendererBackend.OPENGL_ES2
        if (safePreferred == RendererBackend.OPENGL_ES2) {
            return ResolutionResult(safePreferred, safePreferred, null)
        }

        val reasons = ArrayList<String>()
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        if (safePreferred == RendererBackend.KOPPER_ZINK) {
            if (!supportsVulkan(context)) {
                reasons.add("Device Vulkan feature is unavailable")
            }

            val glxShim = File(nativeLibDir, LIB_GLX_SHIM)
            if (!glxShim.isFile) {
                reasons.add("Missing $LIB_GLX_SHIM")
            }

            val mesaEgl = File(nativeLibDir, LIB_EGL_MESA)
            if (!mesaEgl.isFile) {
                reasons.add("Missing $LIB_EGL_MESA")
            }

            val glapi = File(nativeLibDir, LIB_GLAPI)
            if (!glapi.isFile) {
                reasons.add("Missing $LIB_GLAPI")
            }
        } else if (safePreferred == RendererBackend.ANGLE) {
            val angleEgl = File(nativeLibDir, LIB_EGL_ANGLE)
            if (!angleEgl.isFile) {
                reasons.add("Missing $LIB_EGL_ANGLE")
            }

            val angleGlesv2 = File(nativeLibDir, LIB_GLESV2_ANGLE)
            if (!angleGlesv2.isFile) {
                reasons.add("Missing $LIB_GLESV2_ANGLE")
            }
        } else if (safePreferred == RendererBackend.MOBILEGLUES) {
            val mobileGlues = File(nativeLibDir, LIB_MOBILEGLUES)
            if (!mobileGlues.isFile) {
                reasons.add("Missing $LIB_MOBILEGLUES")
            }
        }

        if (reasons.isEmpty()) {
            return ResolutionResult(safePreferred, safePreferred, null)
        }

        return ResolutionResult(
            safePreferred,
            RendererBackend.OPENGL_ES2,
            joinReasons(reasons)
        )
    }

    private fun joinReasons(reasons: List<String>): String {
        val builder = StringBuilder()
        for (reason in reasons) {
            if (reason.trim().isEmpty()) {
                continue
            }
            if (builder.isNotEmpty()) {
                builder.append("; ")
            }
            builder.append(reason.trim())
        }
        return if (builder.isEmpty()) "Unknown reason" else builder.toString()
    }

    private fun supportsVulkan(context: Context): Boolean {
        return try {
            val packageManager = context.packageManager
            val hasVulkanLevel =
                packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val hasVulkan10 = packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                0x00400000
            )
            hasVulkanLevel || hasVulkan10
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to detect Vulkan support", error)
            false
        }
    }

    class ResolutionResult(
        @JvmField val preferred: RendererBackend,
        @JvmField val effective: RendererBackend,
        @JvmField val reason: String?
    ) {
        val isFallback: Boolean
            get() = preferred != effective

        fun toLogText(): String {
            val builder = StringBuilder()
            builder.append("renderer preferred=").append(preferred.rendererId())
            builder.append(", effective=").append(effective.rendererId())
            if (reason != null && reason.trim().isNotEmpty()) {
                builder.append(", reason=").append(reason.trim())
            }
            return builder.toString()
        }
    }
}
