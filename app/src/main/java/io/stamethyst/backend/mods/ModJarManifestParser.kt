package io.stamethyst.backend.mods

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipFile

internal object ModJarManifestParser {
    @Throws(IOException::class)
    fun readModManifest(modJar: File?): ModJarSupport.ModManifestInfo {
        if (modJar == null || !modJar.isFile) {
            throw IOException("Mod jar not found")
        }
        ZipFile(modJar).use { zipFile ->
            val modInfo = JarFileIoUtils.findEntryIgnoreCase(zipFile, "ModTheSpire.json")
                ?: throw IOException("ModTheSpire.json not found in ${modJar.name}")
            val json = JarFileIoUtils.readEntry(zipFile, modInfo)
            val manifest = parseManifest(json)
            if (manifest == null || manifest.normalizedModId.isEmpty()) {
                throw IOException("modid not found in ${modJar.name}")
            }
            return manifest
        }
    }

    @Throws(IOException::class)
    fun resolveModId(modJar: File?): String {
        return readModManifest(modJar).modId
    }

    fun normalizeModId(modId: String?): String {
        return modId?.trim()?.lowercase(Locale.ROOT) ?: ""
    }

    private fun parseManifest(json: String?): ModJarSupport.ModManifestInfo? {
        if (json == null) {
            return null
        }
        val obj = tryParseManifestObject(json)
        val modId = readManifestText(obj, json, MOD_ID_JSON_KEYS, MOD_ID_PATTERN)
        val normalizedModId = normalizeModId(modId)
        if (normalizedModId.isEmpty()) {
            return null
        }

        var resolvedModId = sanitizeManifestText(modId)
        if (resolvedModId.isEmpty()) {
            resolvedModId = normalizedModId
        }
        val name = readManifestText(obj, json, MOD_NAME_JSON_KEYS, MOD_NAME_PATTERN)
        val version = readManifestText(obj, json, MOD_VERSION_JSON_KEYS, MOD_VERSION_PATTERN)
        val description = readManifestText(obj, json, MOD_DESCRIPTION_JSON_KEYS, MOD_DESCRIPTION_PATTERN)
        val dependencies = readManifestDependencies(obj)

        var resolvedName = sanitizeManifestText(name)
        if (resolvedName.isEmpty()) {
            resolvedName = resolvedModId
        }
        return ModJarSupport.ModManifestInfo(
            modId = resolvedModId,
            normalizedModId = normalizedModId,
            name = resolvedName,
            version = sanitizeManifestText(version),
            description = sanitizeManifestText(description),
            dependencies = dependencies
        )
    }

    private fun readManifestDependencies(obj: JSONObject?): List<String> {
        if (obj == null) {
            return ArrayList()
        }
        val dependencies: LinkedHashSet<String> = LinkedHashSet()
        for (key in MOD_DEPENDENCIES_JSON_KEYS) {
            if (key.isEmpty()) {
                continue
            }
            if (obj.has(key) && !obj.isNull(key)) {
                addManifestDependenciesFromValue(obj.opt(key), dependencies)
            }
            val matchedKey = findJsonKeyIgnoreCase(obj, key)
            if (matchedKey != null &&
                matchedKey != key &&
                obj.has(matchedKey) &&
                !obj.isNull(matchedKey)
            ) {
                addManifestDependenciesFromValue(obj.opt(matchedKey), dependencies)
            }
        }
        return ArrayList(dependencies)
    }

    private fun addManifestDependenciesFromValue(value: Any?, output: MutableSet<String>?) {
        if (output == null || value == null || value === JSONObject.NULL) {
            return
        }
        if (value is JSONArray) {
            for (i in 0 until value.length()) {
                addManifestDependenciesFromValue(value.opt(i), output)
            }
            return
        }
        if (value is JSONObject) {
            return
        }
        val text = sanitizeManifestText(value.toString())
        if (text.isEmpty()) {
            return
        }
        val parts = text.split(Regex("[,;\\n]"))
        for (part in parts) {
            val normalized = sanitizeManifestText(part)
            if (normalized.isNotEmpty()) {
                output.add(normalized)
            }
        }
    }

    private fun tryParseManifestObject(json: String): JSONObject? {
        try {
            val root = JSONTokener(json).nextValue()
            if (root is JSONObject) {
                return root
            }
            if (root is JSONArray) {
                for (i in 0 until root.length()) {
                    val item = root.opt(i)
                    if (item is JSONObject) {
                        return item
                    }
                }
            }
        } catch (_: JSONException) {
        }
        return null
    }

    private fun readManifestText(
        obj: JSONObject?,
        rawJson: String?,
        jsonKeys: Array<String>?,
        fallbackPattern: Pattern?
    ): String {
        if (obj != null && jsonKeys != null) {
            for (key in jsonKeys) {
                val direct = readJsonFieldAsText(obj, key)
                if (direct.isNotEmpty()) {
                    return direct
                }
            }
            for (key in jsonKeys) {
                val matchedKey = findJsonKeyIgnoreCase(obj, key) ?: continue
                val fallbackCase = readJsonFieldAsText(obj, matchedKey)
                if (fallbackCase.isNotEmpty()) {
                    return fallbackCase
                }
            }
        }
        if (rawJson == null || fallbackPattern == null) {
            return ""
        }
        val matcher = fallbackPattern.matcher(rawJson)
        if (!matcher.find()) {
            return ""
        }
        return unescapeJsonString(matcher.group(1))
    }

    private fun readJsonFieldAsText(obj: JSONObject?, key: String?): String {
        if (obj == null || key.isNullOrEmpty() || !obj.has(key) || obj.isNull(key)) {
            return ""
        }
        val value = obj.opt(key)
        return sanitizeManifestText(stringifyJsonValue(value))
    }

    private fun findJsonKeyIgnoreCase(obj: JSONObject?, key: String?): String? {
        if (obj == null || key.isNullOrEmpty()) {
            return null
        }
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (current != null && current.equals(key, ignoreCase = true)) {
                return current
            }
        }
        return null
    }

    private fun stringifyJsonValue(value: Any?): String {
        if (value == null || value === JSONObject.NULL) {
            return ""
        }
        if (value is JSONArray) {
            val text = StringBuilder()
            for (i in 0 until value.length()) {
                val itemText = stringifyJsonValue(value.opt(i)).trim()
                if (itemText.isEmpty()) {
                    continue
                }
                if (text.isNotEmpty()) {
                    text.append(", ")
                }
                text.append(itemText)
            }
            return text.toString()
        }
        if (value is JSONObject) {
            return ""
        }
        return value.toString()
    }

    private fun unescapeJsonString(text: String?): String {
        if (text.isNullOrEmpty()) {
            return ""
        }
        return text
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .trim()
    }

    private fun sanitizeManifestText(text: String?): String {
        return text?.trim() ?: ""
    }
}
