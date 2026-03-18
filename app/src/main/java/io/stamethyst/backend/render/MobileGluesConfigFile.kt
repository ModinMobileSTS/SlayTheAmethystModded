package io.stamethyst.backend.render

import android.content.Context
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.json.JSONTokener

object MobileGluesConfigFile {
    private const val CONFIG_FILE_NAME = "config.json"
    private const val CONFIG_KEY_ENABLE_ANGLE = "enableANGLE"
    private const val CONFIG_KEY_ENABLE_NO_ERROR = "enableNoError"
    private const val CONFIG_KEY_ENABLE_EXT_COMPUTE_SHADER = "enableExtComputeShader"
    private const val LEGACY_CONFIG_KEY_ENABLE_EXT_GL43 = "enableExtGL43"
    private const val CONFIG_KEY_ENABLE_EXT_TIMER_QUERY = "enableExtTimerQuery"
    private const val CONFIG_KEY_ENABLE_EXT_DIRECT_STATE_ACCESS = "enableExtDirectStateAccess"
    private const val CONFIG_KEY_MAX_GLSL_CACHE_SIZE = "maxGlslCacheSize"
    private const val CONFIG_KEY_MULTIDRAW_MODE = "multidrawMode"
    private const val CONFIG_KEY_ANGLE_DEPTH_CLEAR_FIX_MODE = "angleDepthClearFixMode"
    private const val CONFIG_KEY_CUSTOM_GL_VERSION = "customGLVersion"
    private const val CONFIG_KEY_FSR1_SETTING = "fsr1Setting"

    @Throws(IOException::class)
    fun syncFromLauncherPreferences(context: Context) {
        write(
            file = configFile(context),
            settings = LauncherConfig.readMobileGluesSettings(context)
        )
    }

    @Throws(IOException::class)
    fun write(file: File, settings: MobileGluesSettings) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val root = mergeConfig(readJsonObject(file), settings)
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.fd.sync()
        }
    }

    internal fun mergeConfig(
        existing: JSONObject?,
        settings: MobileGluesSettings
    ): JSONObject {
        val merged = JSONObject()
        if (existing != null) {
            val keys = existing.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, existing.opt(key))
            }
        }
        merged.remove(LEGACY_CONFIG_KEY_ENABLE_EXT_GL43)
        merged.put(CONFIG_KEY_ENABLE_ANGLE, settings.anglePolicy.mobileGluesConfigValue)
        merged.put(CONFIG_KEY_ENABLE_NO_ERROR, settings.noErrorPolicy.mobileGluesConfigValue)
        merged.put(
            CONFIG_KEY_ENABLE_EXT_COMPUTE_SHADER,
            if (settings.extComputeShaderEnabled) 1 else 0
        )
        merged.put(
            CONFIG_KEY_ENABLE_EXT_TIMER_QUERY,
            if (settings.extTimerQueryEnabled) 1 else 0
        )
        merged.put(
            CONFIG_KEY_ENABLE_EXT_DIRECT_STATE_ACCESS,
            if (settings.extDirectStateAccessEnabled) 1 else 0
        )
        merged.put(CONFIG_KEY_MAX_GLSL_CACHE_SIZE, settings.glslCacheSizePreset.megabytes)
        merged.put(CONFIG_KEY_MULTIDRAW_MODE, settings.multidrawMode.mobileGluesConfigValue)
        merged.put(
            CONFIG_KEY_ANGLE_DEPTH_CLEAR_FIX_MODE,
            settings.angleDepthClearFixMode.mobileGluesConfigValue
        )
        merged.put(CONFIG_KEY_CUSTOM_GL_VERSION, settings.customGlVersion.mobileGluesConfigValue)
        merged.put(CONFIG_KEY_FSR1_SETTING, settings.fsr1QualityPreset.mobileGluesConfigValue)
        return merged
    }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                JSONObject()
            } else {
                val parsed = JSONTokener(text).nextValue()
                parsed as? JSONObject
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun configFile(context: Context): File {
        return File(File(context.filesDir, "MobileGlues"), CONFIG_FILE_NAME)
    }
}
