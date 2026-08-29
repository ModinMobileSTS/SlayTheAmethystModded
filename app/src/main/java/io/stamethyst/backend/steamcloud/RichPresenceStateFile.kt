package io.stamethyst.backend.steamcloud

import java.io.File

internal object RichPresenceStateFile {
    fun read(file: File): Map<String, String>? {
        val payload = try {
            if (file.isFile) file.readText() else ""
        } catch (_: Throwable) {
            return null
        }
        return parse(payload)
    }

    fun parse(payload: String): Map<String, String>? {
        if (payload.isBlank()) return null
        val values = LinkedHashMap<String, String>()
        payload.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (key.isNotEmpty()) values[key] = unescapeValue(line.substring(separator + 1))
        }
        return values.takeIf {
            !it["status"].isNullOrBlank() && !it["steam_display"].isNullOrBlank()
        }
    }

    private fun unescapeValue(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> result.append('\n')
                    '=' -> result.append('=')
                    '\\' -> result.append('\\')
                    else -> result.append('\\').append(value[index + 1])
                }
                index += 2
            } else {
                result.append(value[index])
                index += 1
            }
        }
        return result.toString()
    }
}
