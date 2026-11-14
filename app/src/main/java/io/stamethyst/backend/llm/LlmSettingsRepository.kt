package io.stamethyst.backend.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.WeakHashMap

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

/** Null means untouched, including an empty API-key input (which keeps the saved key). */
data class LlmSettingsPatch(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val modelName: String? = null,
    val endpoint: LlmEndpoint? = null,
    val requestTimeoutSeconds: Int? = null,
    val reasoningEffort: LlmReasoningEffort? = null,
    val models: List<String>? = null,
    val supportsImageInput: Boolean? = null,
) {
    internal fun normalized() = copy(
        apiKey = apiKey?.trim()?.takeIf(String::isNotEmpty),
        baseUrl = baseUrl?.trim()?.trimEnd('/'),
        modelName = modelName?.trim(),
        requestTimeoutSeconds = requestTimeoutSeconds?.coerceIn(
            MIN_LLM_REQUEST_TIMEOUT_SECONDS, MAX_LLM_REQUEST_TIMEOUT_SECONDS,
        ),
        models = models?.map(String::trim)?.filter(String::isNotEmpty)?.distinct(),
    )

    // Stable field positions are only used by this process's edit-order fence, never persisted.
    internal fun applyTo(settings: LlmSettings, include: (Int) -> Boolean = { true }): LlmSettings {
        fun <T> field(index: Int, value: T?, current: T): T =
            if (value != null && include(index)) value else current
        return settings.copy(
            apiKey = field(0, apiKey, settings.apiKey),
            baseUrl = field(1, baseUrl, settings.baseUrl),
            modelName = field(2, modelName, settings.modelName),
            endpoint = field(3, endpoint, settings.endpoint),
            requestTimeoutSeconds = field(4, requestTimeoutSeconds, settings.requestTimeoutSeconds),
            reasoningEffort = field(5, reasoningEffort, settings.reasoningEffort),
            models = field(6, models, settings.models),
            supportsImageInput = field(7, supportsImageInput, settings.supportsImageInput),
        )
    }
}

internal data class LlmSettingsEdit(val patch: LlmSettingsPatch, val revision: Long)

class LlmSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val encryptedPrefs by lazy { createEncryptedPrefsOrNull(appContext) }
    private val fallbackPrefs by lazy {
        appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun get(): LlmSettings = synchronized(editLock) {
        read(encryptedPrefs) ?: read(fallbackPrefs) ?: LlmSettings()
    }

    fun set(settings: LlmSettings) = synchronized(editLock) {
        val order = editOrder()
        order.unpersistedFields.addAll(order.latest.indices)
        persist(settings)
        // An explicit whole-settings replacement also supersedes older queued editor writes.
        order.latest.fill(++order.revision)
    }

    internal fun newEdit(patch: LlmSettingsPatch): LlmSettingsEdit? = synchronized(editLock) {
        val normalized = patch.normalized()
        val order = editOrder()
        val revision = ++order.revision
        var changed = false
        normalized.applyTo(LlmSettings()) { field ->
            order.latest[field] = revision
            changed = true
            true
        }
        if (changed) LlmSettingsEdit(normalized, revision) else null
    }

    internal fun discardPendingApiKey(edits: List<LlmSettingsEdit>) = synchronized(editLock) {
        val order = editOrder()
        // Clearing the input before debounce keeps the saved key. Do not cancel another
        // editor's newer key, nor replay a superseded key when this editor later retries.
        if (edits.any { it.patch.apiKey != null && it.revision == order.latest[0] }) {
            order.latest[0] = ++order.revision
        }
    }

    internal fun preview(settings: LlmSettings, edits: List<LlmSettingsEdit>): LlmSettings =
        synchronized(editLock) { mergeEdits(settings, edits) }

    // Callers hold editLock; only fields still owned by these edits participate in retries.
    private fun mergeEdits(
        settings: LlmSettings,
        edits: List<LlmSettingsEdit>,
        appliedFields: MutableSet<Int>? = null,
    ): LlmSettings {
        val latest = editOrder().latest
        return edits.fold(settings) { current, edit ->
            edit.patch.applyTo(current) { field ->
                (latest[field] == edit.revision).also { applied ->
                    if (applied) appliedFields?.add(field)
                }
            }
        }
    }

    /** Read/merge/write is atomic across both editor repositories, including their IO workers. */
    internal fun update(edits: List<LlmSettingsEdit>): LlmSettings = synchronized(editLock) {
        val current = get()
        val order = editOrder()
        val appliedFields = mutableSetOf<Int>()
        val merged = mergeEdits(current, edits, appliedFields)
        // A failed SharedPreferences commit can already have changed its in-memory values.
        // Equality is only sufficient when these fields have no unconfirmed disk write.
        if (merged != current || appliedFields.any { it in order.unpersistedFields }) {
            order.unpersistedFields.addAll(appliedFields)
            persist(merged)
        }
        merged
    }

    private fun editOrder(): EditOrder = editOrders.getOrPut(appContext) { EditOrder() }

    private fun persist(settings: LlmSettings) {
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
        // Every successful write persists a whole snapshot, including other editors' dirty fields.
        editOrder().unpersistedFields.clear()
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
        private class EditOrder(
            var revision: Long = 0,
            val latest: LongArray = LongArray(8),
            val unpersistedFields: MutableSet<Int> = mutableSetOf(),
        )
        private val editLock = Any()
        private val editOrders = WeakHashMap<Context, EditOrder>()
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
