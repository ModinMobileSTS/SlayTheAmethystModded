package io.stamethyst.backend.render

import android.content.Context
import io.stamethyst.config.RuntimePaths
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
    private const val DEFAULT_VSYNC = false
    private const val MIN_WIDTH = 800
    private const val MIN_HEIGHT = 450

    @JvmStatic
    @Throws(IOException::class)
    fun syncToCurrentResolution(context: Context, width: Int, height: Int, targetFpsLimit: Int) {
        val configFile = RuntimePaths.displayConfigFile(context)
        val lines = buildConfigLines(
            existingLines = readExistingLines(configFile),
            width = width,
            height = height,
            targetFpsLimit = targetFpsLimit
        )
        writeConfig(configFile, lines)
    }

    internal fun buildConfigLines(
        existingLines: List<String>?,
        width: Int,
        height: Int,
        targetFpsLimit: Int
    ): List<String> {
        val safeWidth = width.coerceAtLeast(MIN_WIDTH)
        val safeHeight = height.coerceAtLeast(MIN_HEIGHT)
        val normalizedTargetFpsLimit = normalizeTargetFpsLimit(targetFpsLimit)
        val state = readExisting(existingLines)
            .withFpsLimit(normalizedTargetFpsLimit)
            .withVsync(false)

        return ArrayList<String>(6).apply {
            add(safeWidth.toString())
            add(safeHeight.toString())
            add(state.fpsLimit.toString())
            add(java.lang.Boolean.toString(state.fullscreen))
            add(java.lang.Boolean.toString(state.windowedFullscreen))
            add(java.lang.Boolean.toString(state.vsync))
        }
    }

    private fun readExistingLines(configFile: File): List<String>? {
        if (!configFile.isFile) {
            return null
        }
        return try {
            Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readExisting(lines: List<String>?): DisplayConfigState {
        if (lines == null) {
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
    private fun writeConfig(configFile: File, lines: List<String>) {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
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

        fun withVsync(nextVsync: Boolean): DisplayConfigState {
            return DisplayConfigState(fpsLimit, fullscreen, windowedFullscreen, nextVsync)
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
