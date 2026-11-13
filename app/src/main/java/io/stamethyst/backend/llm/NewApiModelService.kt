package io.stamethyst.backend.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class NewApiModel(
    val id: String,
    val owned_by: String? = null,
    // These optional fields are not standardized across compatible providers. Accept unfamiliar
    // shapes without failing the catalog, and use only positive integral token counts.
    val context_window: JsonElement? = null,
    val context_length: JsonElement? = null,
    val max_input_tokens: JsonElement? = null,
    val max_output_tokens: JsonElement? = null,
) {
    val limits: AgentModelLimits get() = AgentModelLimits(
        contextWindow = context_window.tokenCount(2) ?: context_length.tokenCount(2),
        maxInputTokens = max_input_tokens.tokenCount(),
        maxOutputTokens = max_output_tokens.tokenCount(),
    )
}

private fun JsonElement?.tokenCount(minimum: Int = 1): Int? =
    (this as? JsonPrimitive)?.intOrNull?.takeIf { it >= minimum }

@Serializable
private data class NewApiModelsResponse(
    val data: List<NewApiModel> = emptyList(),
)

class NewApiModelService {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun parseModels(body: String): List<NewApiModel> =
        json.decodeFromString<NewApiModelsResponse>(body).data
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.id.lowercase() }

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int = 30,
    ): List<NewApiModel> {
        val endpoint = baseUrl.trim().trimEnd('/') + "/models"
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "NewAPI model request failed (${response.code}): ${body.take(240)}"
            }
            return parseModels(body)
        }
    }
}
