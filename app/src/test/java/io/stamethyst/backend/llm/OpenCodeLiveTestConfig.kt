package io.stamethyst.backend.llm

import org.json.JSONObject
import java.io.File

/** Test-only credential lookup. Never print configuration, URLs, keys or HTTP request bodies. */
internal object OpenCodeLiveTestConfig {
    fun load(model: String = System.getenv("STS_AI_CACHE_MODEL")?.takeIf(String::isNotBlank)
        ?: "deepseek-v4-flash"): OpenAiCompatibleModelConfig {
        val home = File(requireNotNull(System.getProperty("user.home")))
        val configHome = System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
        val dataHome = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        val file = System.getenv("STS_AI_CACHE_OPENCODE_CONFIG")?.let(::File)
            ?: File(configHome, "opencode/opencode.json")
        check(file.isFile) { "OpenCode JSON configuration not found; set STS_AI_CACHE_OPENCODE_CONFIG." }
        val config = JSONObject(file.readText())
        val providers = config.optJSONObject("providers") ?: config.optJSONObject("provider") ?: JSONObject()
        val providerId = selectProvider(config, model, System.getenv("STS_AI_CACHE_PROVIDER"))
        val provider = providers.optJSONObject(providerId) ?: error("Selected OpenCode provider is missing.")
        val settings = provider.optJSONObject("settings") ?: provider.optJSONObject("options") ?: JSONObject()
        val authFile = File(dataHome, "opencode/auth.json")
        val auth = if (authFile.isFile) JSONObject(authFile.readText()).optJSONObject(providerId) else null
        fun resolve(value: String): String = when {
            value.startsWith("{env:") && value.endsWith("}") -> System.getenv(value.substring(5, value.length - 1)).orEmpty()
            value.startsWith("{file:") && value.endsWith("}") -> {
                val path = value.substring(6, value.length - 1)
                val target = if (path.startsWith("~/")) File(home, path.drop(2)) else File(path).let {
                    if (it.isAbsolute) it else File(file.parentFile, path)
                }
                target.readText().trim()
            }
            else -> value
        }
        val envKey = provider.optJSONArray("env")?.let { names ->
            (0 until names.length()).firstNotNullOfOrNull { System.getenv(names.getString(it))?.takeIf(String::isNotBlank) }
        }
        val database = System.getenv("STS_AI_CACHE_OPENCODE_DB")?.let(::File)
            ?: System.getenv("OPENCODE_DB")?.let(::File) ?: File(dataHome, "opencode/opencode.db")
        val key = resolve(settings.optString("apiKey"))
            .ifBlank { activeDatabaseKey(database, providerId) }
            .ifBlank { auth?.optString("key").orEmpty() }.ifBlank { envKey.orEmpty() }
        val baseUrl = resolve(settings.optString("baseURL"))
        check(baseUrl.isNotBlank() && key.isNotBlank()) { "OpenCode provider needs an endpoint and API credential." }
        return OpenAiCompatibleModelConfig(baseUrl.trimEnd('/'), key, model, requestTimeoutSeconds = 300)
    }

    /** Resolve only declared models; never silently replace an explicitly requested test model. */
    internal fun selectProvider(config: JSONObject, model: String, explicit: String? = null): String {
        val providers = config.optJSONObject("providers") ?: config.optJSONObject("provider") ?: JSONObject()
        val candidates = providers.keys().asSequence().filter {
            providers.optJSONObject(it)?.optJSONObject("models")?.has(model) == true
        }.toList()
        val selected = (config.optString("model").substringBefore('/')).takeIf { it in candidates }
        if (explicit != null) {
            check(explicit in candidates) { "Selected provider does not declare the requested cache-test model." }
            return explicit
        }
        return selected ?: candidates.singleOrNull()
            ?: error("Select a provider declaring the requested model with STS_AI_CACHE_PROVIDER.")
    }

    /** V2 stores credentials in SQLite, not auth.json. Python's stdlib avoids an app/JDBC dependency. */
    internal fun activeDatabaseKey(database: File, providerId: String): String {
        if (!database.isFile) return ""
        val process = ProcessBuilder("python3", "-c", """
            import json, pathlib, sqlite3, sys
            try:
                db = sqlite3.connect(pathlib.Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
                row = db.execute('SELECT value FROM credential WHERE integration_id = ? AND active = 1 ORDER BY time_updated DESC LIMIT 1', (sys.argv[2],)).fetchone()
                value = json.loads(row[0]) if row else {}
                key = value.get('key', '') if value.get('type') in ('key', 'api') else ''
                sys.stdout.write(key if isinstance(key, str) else '')
                db.close()
            except Exception:
                sys.exit(2)
        """.trimIndent(), database.absolutePath, providerId).start()
        // stdout is captured in memory only; do not inheritIO or include output in assertions.
        val key = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.close()
        check(process.waitFor() == 0) { "Cannot read active OpenCode API credential via read-only SQLite." }
        return key
    }
}
