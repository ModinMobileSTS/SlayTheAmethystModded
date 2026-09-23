package io.stamethyst.backend.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class NewApiModel(
    val id: String,
    val owned_by: String? = null,
)

@Serializable
private data class NewApiModelsResponse(
    val data: List<NewApiModel> = emptyList(),
)

class NewApiModelService {
    private val json = Json { ignoreUnknownKeys = true }

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
            return json.decodeFromString<NewApiModelsResponse>(body).data
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
                .sortedBy { it.id.lowercase() }
        }
    }
}
