package io.stamethyst.ui.settings

import android.app.Activity
import io.stamethyst.backend.core.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.json.JSONTokener

internal object GameplaySettingsService {
    const val DEFAULT_TOUCHSCREEN_ENABLED = true

    private const val GAMEPLAY_SETTINGS_FILE_NAME = "STSGameplaySettings"
    private const val GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN = "Touchscreen Enabled"
    private const val GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH =
        "components/default_saves/preferences/STSGameplaySettings"

    fun readTouchscreenEnabled(host: Activity): Boolean {
        val files = arrayOf(
            File(RuntimePaths.betaPreferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
            File(RuntimePaths.preferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME)
        )
        for (file in files) {
            val value = readGameplaySettingsBoolean(file, GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN)
            if (value != null) {
                return value
            }
        }
        return DEFAULT_TOUCHSCREEN_ENABLED
    }

    @Throws(IOException::class)
    fun saveTouchscreenEnabled(host: Activity, enabled: Boolean) {
        val value = if (enabled) "true" else "false"
        writeGameplaySettingsValue(
            host,
            File(RuntimePaths.betaPreferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
            GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
            value
        )
        writeGameplaySettingsValue(
            host,
            File(RuntimePaths.preferencesDir(host), GAMEPLAY_SETTINGS_FILE_NAME),
            GAMEPLAY_SETTINGS_KEY_TOUCHSCREEN,
            value
        )
    }

    private fun readGameplaySettingsBoolean(file: File, key: String): Boolean? {
        val objectValue = readJsonObject(file) ?: return null
        if (!objectValue.has(key)) {
            return null
        }
        return parseBooleanLike(objectValue.opt(key), DEFAULT_TOUCHSCREEN_ENABLED)
    }

    @Throws(IOException::class)
    private fun writeGameplaySettingsValue(host: Activity, file: File, key: String, value: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = mergeJsonObjects(
            readBundledGameplaySettingsDefaults(host),
            readJsonObject(file)
        )
        root.put(key, value)
        FileOutputStream(file, false).use { out ->
            out.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
        }
    }

    private fun readBundledGameplaySettingsDefaults(host: Activity): JSONObject? {
        return try {
            host.assets.open(GAMEPLAY_SETTINGS_DEFAULT_ASSET_PATH).use { input ->
                val text = input.readBytes().toString(StandardCharsets.UTF_8).trim()
                if (text.isEmpty()) {
                    JSONObject()
                } else {
                    val parsed = JSONTokener(text).nextValue()
                    parsed as? JSONObject ?: JSONObject()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun mergeJsonObjects(base: JSONObject?, override: JSONObject?): JSONObject {
        val merged = JSONObject()
        if (base != null) {
            val keys = base.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, base.opt(key))
            }
        }
        if (override != null) {
            val keys = override.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, override.opt(key))
            }
        }
        return merged
    }

    private fun readJsonObject(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                return JSONObject()
            }
            val parsed = JSONTokener(text).nextValue()
            parsed as? JSONObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseBooleanLike(value: Any?, fallback: Boolean): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                val normalized = value.trim()
                when {
                    normalized.equals("true", ignoreCase = true) || normalized == "1" -> true
                    normalized.equals("false", ignoreCase = true) || normalized == "0" -> false
                    else -> fallback
                }
            }

            else -> fallback
        }
    }
}
