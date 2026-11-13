package io.stamethyst.backend.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com/v1"
private const val DEFAULT_LLM_MODEL_NAME = "deepseek-flash"

data class LlmSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_LLM_BASE_URL,
    val modelName: String = DEFAULT_LLM_MODEL_NAME,
    val endpoint: LlmEndpoint = LlmEndpoint.CHAT_COMPLETIONS,
    val requestTimeoutSeconds: Int = DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.LOW,
    val models: List<String> = listOf(modelName),
    /**
     * Whether the endpoint accepts image content parts. The agent sends screenshots as images only
     * when this is on; otherwise it describes them in text so a text-only model still answers.
     */
    val supportsImageInput: Boolean = true,
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank() && modelName.isNotBlank() && baseUrl.isNotBlank()
}

enum class LlmReasoningEffort {
    OFF,
    LOW,
    HIGH,
    MAX;

    companion object {
        val selectable = listOf(OFF, LOW, HIGH, MAX)

        fun fromStored(value: String?): LlmReasoningEffort = when (value) {
            "OFF" -> OFF
            "LOW" -> LOW
            "MAX" -> MAX
            "HIGH", "MEDIUM" -> HIGH // Preserve the legacy MEDIUM migration.
            else -> LOW
        }
    }
}

class LlmSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val encryptedPrefs by lazy { createEncryptedPrefsOrNull(appContext) }
    private val fallbackPrefs by lazy {
        appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun get(): LlmSettings = read(encryptedPrefs) ?: read(fallbackPrefs) ?: LlmSettings()

    fun set(settings: LlmSettings) {
        val normalized = settings.copy(
            apiKey = settings.apiKey.trim(),
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            modelName = settings.modelName.trim(),
            models = settings.models.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
        val savedEncrypted = encryptedPrefs?.let { write(it, normalized) } == true
        if (savedEncrypted) {
            fallbackPrefs.edit().clear().apply()
        } else {
            check(write(fallbackPrefs, normalized)) { "Unable to save LLM settings" }
        }
    }

    private fun read(prefs: SharedPreferences?): LlmSettings? = prefs?.let {
        runCatching {
            LlmSettings(
                apiKey = it.getString(KEY_API_KEY, "").orEmpty(),
                baseUrl = it.getString(KEY_BASE_URL, DEFAULT_LLM_BASE_URL).orEmpty(),
                modelName = it.getString(KEY_MODEL_NAME, DEFAULT_LLM_MODEL_NAME).orEmpty(),
                models = it.getString("models", null)?.let { value ->
                    val array = org.json.JSONArray(value)
                    List(array.length()) { index -> array.getString(index) }
                } ?: listOf(it.getString(KEY_MODEL_NAME, DEFAULT_LLM_MODEL_NAME).orEmpty()),
                endpoint = it.getString(KEY_ENDPOINT, LlmEndpoint.CHAT_COMPLETIONS.name)
                    ?.let { value -> runCatching { LlmEndpoint.valueOf(value) }.getOrDefault(LlmEndpoint.CHAT_COMPLETIONS) }
                    ?: LlmEndpoint.CHAT_COMPLETIONS,
                requestTimeoutSeconds = it.getInt(
                    KEY_REQUEST_TIMEOUT_SECONDS,
                    DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
                ).coerceIn(MIN_LLM_REQUEST_TIMEOUT_SECONDS, MAX_LLM_REQUEST_TIMEOUT_SECONDS),
                reasoningEffort = LlmReasoningEffort.fromStored(it.getString(KEY_REASONING_EFFORT, null)),
                supportsImageInput = it.getBoolean(KEY_SUPPORTS_IMAGE_INPUT, true),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read LLM settings.", error)
            null
        }
    }

    private fun write(prefs: SharedPreferences, settings: LlmSettings): Boolean = runCatching {
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL_NAME, settings.modelName)
            .putString("models", org.json.JSONArray(settings.models).toString())
            .putString(KEY_ENDPOINT, settings.endpoint.name)
            .putInt(
                KEY_REQUEST_TIMEOUT_SECONDS,
                settings.requestTimeoutSeconds.coerceIn(
                    MIN_LLM_REQUEST_TIMEOUT_SECONDS,
                    MAX_LLM_REQUEST_TIMEOUT_SECONDS,
                ),
            )
            .putString(KEY_REASONING_EFFORT, settings.reasoningEffort.name)
            .putBoolean(KEY_SUPPORTS_IMAGE_INPUT, settings.supportsImageInput)
            .commit()
    }.getOrElse { error ->
        Log.w(TAG, "Unable to save LLM settings.", error)
        false
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "llm_settings"
        private const val FALLBACK_PREFS_NAME = "llm_settings_fallback"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_SUPPORTS_IMAGE_INPUT = "supports_image_input"
        private const val TAG = "LlmSettings"
    }
}

const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 300
const val MIN_LLM_REQUEST_TIMEOUT_SECONDS = 60
const val MAX_LLM_REQUEST_TIMEOUT_SECONDS = 1200

private fun createEncryptedPrefsOrNull(context: Context): SharedPreferences? = runCatching {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    EncryptedSharedPreferences.create(
        context,
        "llm_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}.getOrElse { error ->
    Log.w("LlmSettings", "Encrypted storage unavailable; using fallback storage.", error)
    null
}
