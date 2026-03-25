package io.stamethyst.backend.render

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

object DisplayConfigSync {
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val DEFAULT_FPS_LIMIT = 60
    private const val DEFAULT_FULLSCREEN = false
    private const val DEFAULT_WINDOWED_FULLSCREEN = false
    private const val DEFAULT_VSYNC = true
    private const val MIN_WIDTH = 1
    private const val MIN_HEIGHT = 1
    private val SUPPORTED_FPS_LIMITS = intArrayOf(24, 30, 60, 120, 240)

    @JvmStatic
    @Throws(IOException::class)
    fun syncToCurrentResolution(
        context: Context,
        width: Int,
        height: Int,
        targetFpsLimitOverride: Int? = null
    ) {
        val configFile = RuntimePaths.displayConfigFile(context)
        val lines = buildConfigLines(
            existingLines = readExistingLines(configFile),
            width = width,
            height = height,
            targetFpsLimitOverride = targetFpsLimitOverride
        )
        writeConfig(configFile, lines)
    }

    @JvmStatic
    fun readTargetFpsLimit(context: Context): Int {
        val configFile = RuntimePaths.displayConfigFile(context)
        return normalizeTargetFpsLimit(readExisting(readExistingLines(configFile)).fpsLimit)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveTargetFpsLimit(context: Context, targetFpsLimit: Int) {
        val configFile = RuntimePaths.displayConfigFile(context)
        val lines = buildTargetFpsConfigLines(
            existingLines = readExistingLines(configFile),
            targetFpsLimit = targetFpsLimit
        )
        writeConfig(configFile, lines)
    }

    internal fun buildConfigLines(
        existingLines: List<String>?,
        width: Int,
        height: Int,
        targetFpsLimitOverride: Int? = null
    ): List<String> {
        // Keep the display config aligned with the actual scaled render surface.
        // If this file clamps to a larger minimum than the active surface/window size,
        // the game can render into only a corner of the visible output.
        val safeWidth = width.coerceAtLeast(MIN_WIDTH)
        val safeHeight = height.coerceAtLeast(MIN_HEIGHT)
        val state = readExisting(existingLines)
        val fpsLimit = if (targetFpsLimitOverride != null) {
            normalizeTargetFpsLimit(targetFpsLimitOverride)
        } else {
            state.fpsLimit
        }

        return ArrayList<String>(6).apply {
            add(safeWidth.toString())
            add(safeHeight.toString())
            add(fpsLimit.toString())
            add(java.lang.Boolean.toString(state.fullscreen))
            add(java.lang.Boolean.toString(state.windowedFullscreen))
            add(java.lang.Boolean.toString(state.vsync))
        }
    }

    internal fun buildTargetFpsConfigLines(
        existingLines: List<String>?,
        targetFpsLimit: Int
    ): List<String> {
        val normalizedTargetFpsLimit = normalizeTargetFpsLimit(targetFpsLimit)
        val state = readExisting(existingLines).withFpsLimit(normalizedTargetFpsLimit)
        val width =
            if (existingLines != null && existingLines.isNotEmpty()) {
                parsePositiveInt(existingLines[0], DEFAULT_WIDTH)
            } else {
                DEFAULT_WIDTH
            }
        val height =
            if (existingLines != null && existingLines.size > 1) {
                parsePositiveInt(existingLines[1], DEFAULT_HEIGHT)
            } else {
                DEFAULT_HEIGHT
            }

        return ArrayList<String>(6).apply {
            add(width.toString())
            add(height.toString())
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
        return if (SUPPORTED_FPS_LIMITS.contains(targetFpsLimit)) {
            targetFpsLimit
        } else {
            DEFAULT_FPS_LIMIT
        }
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
