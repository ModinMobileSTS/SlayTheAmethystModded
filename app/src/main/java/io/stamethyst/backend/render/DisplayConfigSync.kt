package io.stamethyst.backend.render

import android.content.Context
import io.stamethyst.backend.core.RuntimePaths
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

object DisplayConfigSync {
    private const val DEFAULT_FPS_LIMIT = LauncherConfig.DEFAULT_TARGET_FPS
    private const val DEFAULT_FULLSCREEN = false
    private const val DEFAULT_WINDOWED_FULLSCREEN = false
    private const val DEFAULT_VSYNC = true
    private const val MIN_WIDTH = 800
    private const val MIN_HEIGHT = 450

    @JvmStatic
    @Throws(IOException::class)
    fun syncToCurrentResolution(context: Context, width: Int, height: Int, targetFpsLimit: Int) {
        val safeWidth = width.coerceAtLeast(MIN_WIDTH)
        val safeHeight = height.coerceAtLeast(MIN_HEIGHT)
        val normalizedTargetFpsLimit = normalizeTargetFpsLimit(targetFpsLimit)

        val configFile = RuntimePaths.displayConfigFile(context)
        val state = readExisting(configFile).withFpsLimit(normalizedTargetFpsLimit)
        writeConfig(configFile, safeWidth, safeHeight, state)
    }

    private fun readExisting(configFile: File): DisplayConfigState {
        if (!configFile.isFile) {
            return DisplayConfigState.defaults()
        }
        val lines: List<String> = try {
            Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return DisplayConfigState.defaults()
        }

        val fps =
            if (lines.size > 2) parsePositiveInt(lines[2], DEFAULT_FPS_LIMIT) else DEFAULT_FPS_LIMIT
        val fullscreen =
            if (lines.size > 3) parseBoolean(lines[3], DEFAULT_FULLSCREEN) else DEFAULT_FULLSCREEN
        val wfs =
            if (lines.size > 4) parseBoolean(lines[4], DEFAULT_WINDOWED_FULLSCREEN) else DEFAULT_WINDOWED_FULLSCREEN
        val vsync =
            if (lines.size > 5) parseBoolean(lines[5], DEFAULT_VSYNC) else DEFAULT_VSYNC
        return DisplayConfigState(fps, fullscreen, wfs, vsync)
    }

    @Throws(IOException::class)
    private fun writeConfig(configFile: File, width: Int, height: Int, state: DisplayConfigState) {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }

        val lines = ArrayList<String>(6)
        lines.add(width.toString())
        lines.add(height.toString())
        lines.add(state.fpsLimit.toString())
        lines.add(java.lang.Boolean.toString(state.fullscreen))
        lines.add(java.lang.Boolean.toString(state.windowedFullscreen))
        lines.add(java.lang.Boolean.toString(state.vsync))
        Files.write(configFile.toPath(), lines, StandardCharsets.UTF_8)
    }

    private fun parsePositiveInt(raw: String?, fallback: Int): Int {
        if (raw == null) {
            return fallback
        }
        return try {
            val value = raw.trim().toInt()
            if (value > 0) value else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun parseBoolean(raw: String?, fallback: Boolean): Boolean {
        if (raw == null) {
            return fallback
        }
        val normalized = raw.trim().lowercase(Locale.US)
        if ("true" == normalized) {
            return true
        }
        if ("false" == normalized) {
            return false
        }
        return fallback
    }

    private fun normalizeTargetFpsLimit(targetFpsLimit: Int): Int {
        return LauncherConfig.normalizeTargetFps(targetFpsLimit)
    }

    private data class DisplayConfigState(
        val fpsLimit: Int,
        val fullscreen: Boolean,
        val windowedFullscreen: Boolean,
        val vsync: Boolean
    ) {
        fun withFpsLimit(nextFpsLimit: Int): DisplayConfigState {
            return DisplayConfigState(nextFpsLimit, fullscreen, windowedFullscreen, vsync)
        }

        companion object {
            fun defaults(): DisplayConfigState {
                return DisplayConfigState(
                    DEFAULT_FPS_LIMIT,
                    DEFAULT_FULLSCREEN,
                    DEFAULT_WINDOWED_FULLSCREEN,
                    DEFAULT_VSYNC
                )
            }
        }
    }
}
