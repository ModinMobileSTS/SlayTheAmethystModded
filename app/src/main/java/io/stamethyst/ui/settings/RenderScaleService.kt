package io.stamethyst.ui.settings

import android.app.Activity
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object RenderScaleService {
    const val DEFAULT_RENDER_SCALE = 1.0f
    const val MIN_RENDER_SCALE = 0.50f
    const val MAX_RENDER_SCALE = 1.00f

    fun readValue(host: Activity): Float {
        val config = configFile(host)
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE
        }
        return try {
            FileInputStream(config).use { input ->
                val bytes = ByteArray(minOf(config.length().toInt(), 64))
                val read = input.read(bytes)
                if (read <= 0) {
                    return DEFAULT_RENDER_SCALE
                }
                val value = String(bytes, 0, read, StandardCharsets.UTF_8)
                    .trim()
                    .replace(',', '.')
                if (value.isEmpty()) {
                    return DEFAULT_RENDER_SCALE
                }
                val parsed = value.toFloat()
                when {
                    parsed < MIN_RENDER_SCALE -> MIN_RENDER_SCALE
                    parsed > MAX_RENDER_SCALE -> MAX_RENDER_SCALE
                    else -> parsed
                }
            }
        } catch (_: Throwable) {
            DEFAULT_RENDER_SCALE
        }
    }

    @Throws(IOException::class)
    fun reset(host: Activity) {
        val config = configFile(host)
        if (config.exists() && !config.delete()) {
            throw IOException("Failed to reset render scale")
        }
    }

    @Throws(IOException::class)
    fun save(host: Activity, value: Float): String {
        val config = configFile(host)
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create config directory")
        }

        val normalized = format(value)
        FileOutputStream(config, false).use { out ->
            out.write(normalized.toByteArray(StandardCharsets.UTF_8))
        }
        return normalized
    }

    fun format(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun configFile(host: Activity): File {
        return File(RuntimePaths.stsRoot(host), "render_scale.txt")
    }
}
