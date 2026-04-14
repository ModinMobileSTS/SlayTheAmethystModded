package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.github.GithubAcceleratedHttp
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONTokener

data class ModSuggestionSnapshot(
    val localeKey: String,
    val rawJson: String,
    val suggestions: Map<String, String>
)

data class ModSuggestionSyncResult(
    val snapshot: ModSuggestionSnapshot,
    val contentChanged: Boolean
)

object ModSuggestionService {
    private const val SUGGESTION_CN_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/refs/heads/main/suggestion/suggestion-cn.json"
    private const val SUGGESTION_EN_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/refs/heads/main/suggestion/suggestion-en.json"
    private const val LOCALE_KEY_CN = "cn"
    private const val LOCALE_KEY_EN = "en"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "SlayTheAmethyst-ModSuggestion"

    fun currentLocaleKey(context: Context): String {
        val language = context.resources.configuration.locales[0]
            ?.language
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return if (language.startsWith("en")) {
            LOCALE_KEY_EN
        } else {
            LOCALE_KEY_CN
        }
    }

    fun loadCachedSuggestionMap(context: Context): Map<String, String> {
        return loadCachedSnapshot(context).suggestions
    }

    fun loadCachedSnapshot(context: Context): ModSuggestionSnapshot {
        val localeKey = currentLocaleKey(context)
        val rawJson = readCacheFile(RuntimePaths.modSuggestionCacheFile(context, localeKey)).orEmpty()
        return ModSuggestionSnapshot(
            localeKey = localeKey,
            rawJson = rawJson,
            suggestions = parseSuggestionMap(rawJson)
        )
    }

    @Throws(IOException::class)
    fun sync(context: Context, source: UpdateSource): ModSuggestionSyncResult {
        RuntimePaths.ensureBaseDirs(context)
        val localeKey = currentLocaleKey(context)
        val cacheFile = RuntimePaths.modSuggestionCacheFile(context, localeKey)
        val existingRawJson = readCacheFile(cacheFile)
        val clients = GithubAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
        val fetchedRawJson = GithubMirrorFallback.run(source) { candidate ->
            requestText(
                clients.pick(candidate.usesGithubAcceleration),
                candidate.buildUrl(resolveSuggestionUrl(localeKey))
            )
        }.value
        val contentChanged = hasContentChanged(existingRawJson, fetchedRawJson)
        if (contentChanged) {
            writeCacheFile(cacheFile, fetchedRawJson)
        }
        return ModSuggestionSyncResult(
            snapshot = ModSuggestionSnapshot(
                localeKey = localeKey,
                rawJson = fetchedRawJson,
                suggestions = parseSuggestionMap(fetchedRawJson)
            ),
            contentChanged = contentChanged
        )
    }

    internal fun parseSuggestionMap(rawJson: String): Map<String, String> {
        val sanitizedJson = sanitizeJsonForParsing(rawJson)
        val parsed = runCatching {
            JSONTokener(sanitizedJson).nextValue() as? JSONArray
        }.getOrNull() ?: return emptyMap()

        val suggestions = LinkedHashMap<String, String>()
        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index) ?: continue
            val modId = ModManager.normalizeModId(
                firstNonBlank(
                    item.optString("modid").trim(),
                    item.optString("modId").trim(),
                    item.optString("id").trim()
                )
            )
            val message = firstNonBlank(
                item.optString("notification").trim(),
                item.optString("suggestion").trim(),
                item.optString("message").trim(),
                item.optString("text").trim()
            )
            if (modId.isEmpty() || message.isEmpty()) {
                continue
            }
            suggestions[modId] = message
        }
        return suggestions
    }

    internal fun hasContentChanged(existingRawJson: String?, fetchedRawJson: String): Boolean {
        val normalizedExisting = existingRawJson?.let(::normalizeRawJsonForComparison)
        val normalizedFetched = normalizeRawJsonForComparison(fetchedRawJson)
        return normalizedExisting == null || normalizedExisting != normalizedFetched
    }

    private fun resolveSuggestionUrl(localeKey: String): String {
        return if (localeKey == LOCALE_KEY_EN) {
            SUGGESTION_EN_URL
        } else {
            SUGGESTION_CN_URL
        }
    }

    private fun sanitizeJsonForParsing(rawJson: String): String {
        return rawJson
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex(",(?=\\s*[}\\]])"), "")
    }

    private fun normalizeRawJsonForComparison(rawJson: String): String {
        return rawJson
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    private fun firstNonBlank(vararg values: String): String {
        values.forEach { value ->
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun readCacheFile(file: File): String? {
        if (!file.isFile) {
            return null
        }
        return runCatching {
            file.readText(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Throws(IOException::class)
    private fun writeCacheFile(file: File, rawJson: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileOutputStream(file, false).use { output ->
            output.write(rawJson.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Throws(IOException::class)
    private fun requestText(client: OkHttpClient, requestUrl: String): String {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body.bytes().toString(StandardCharsets.UTF_8)
        }
    }
}
