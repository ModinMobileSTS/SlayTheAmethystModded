package io.stamethyst.backend.workshop

import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.apricityx.workshop.steam.protocol.applyDefaultHttpTimeouts

internal class BaiduAiTextTranslationClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .applyDefaultHttpTimeouts()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: HttpUrl = "https://fanyi-api.baidu.com/".toHttpUrl(),
) {
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        credentials: BaiduTranslationCredentials,
        reference: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(credentials.isConfigured()) {
            "请先在设置中配置百度大模型文本翻译的 AppID 和 API Key。"
        }

        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return@withContext normalizedText
        }
        val normalizedReference = reference
            ?.trim()
            ?.takeIf(String::isNotBlank)

        val requestBody = buildJsonObject {
            put("appid", credentials.appId)
            put("from", sourceLanguage)
            put("to", targetLanguage)
            put("q", normalizedText)
            put("model_type", MODEL_TYPE_LLM)
            normalizedReference?.let { put("reference", it) }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("ait/api/aiTextTranslate").build())
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(requestBody)
            .build()

        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use { response ->
            val payload = response.body.string()
            val root = parsePayload(payload)
            val errorCode = root["error_code"]?.jsonPrimitive?.contentOrNull
            if (!response.isSuccessful || !isSuccessfulErrorCode(errorCode)) {
                throw BaiduTranslationApiException(
                    errorCode = errorCode,
                    errorMessage = root["error_msg"]?.jsonPrimitive?.contentOrNull,
                    message = buildErrorMessage(errorCode, root["error_msg"]?.jsonPrimitive?.contentOrNull),
                )
            }

            extractTranslatedText(root)?.let { translatedText ->
                return@withContext translatedText
            }

            throw IllegalStateException("百度大模型文本翻译返回了无法识别的结果。")
        }
    }

    private fun parsePayload(payload: String): JsonObject {
        if (payload.isBlank()) {
            throw IllegalStateException("百度大模型文本翻译返回了空响应。")
        }
        return runCatching {
            json.parseToJsonElement(payload).jsonObject
        }.getOrElse {
            throw IllegalStateException("百度大模型文本翻译返回了无法解析的响应。")
        }
    }

    private fun buildErrorMessage(
        errorCode: String?,
        errorMessage: String?,
    ): String {
        val normalizedMessage = errorMessage?.takeIf(String::isNotBlank) ?: "请求失败"
        return if (errorCode.isNullOrBlank()) {
            "百度大模型文本翻译失败：$normalizedMessage。"
        } else {
            "百度大模型文本翻译失败：$normalizedMessage（错误码：$errorCode）。"
        }
    }

    private fun isSuccessfulErrorCode(errorCode: String?): Boolean =
        errorCode.isNullOrBlank() || errorCode == "0" || errorCode == "52000"

    private fun extractTranslatedText(root: JsonObject): String? {
        val data = root["data"]
        val directDataText = data
            ?.takeIf { it !is JsonObject && it !is JsonArray }
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
        if (directDataText != null) {
            return directDataText
        }

        val preferredMatches = collectPreferredTranslatedTexts(data).ifEmpty {
            collectPreferredTranslatedTexts(root)
        }
        return preferredMatches
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n")
    }

    private fun collectPreferredTranslatedTexts(element: JsonElement?): List<String> {
        return when (element) {
            is JsonObject -> {
                val directMatches = PREFERRED_RESULT_KEYS.mapNotNull { key ->
                    val value = element[key]
                    if (value == null || value is JsonObject || value is JsonArray) {
                        null
                    } else {
                        value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
                    }
                }
                if (directMatches.isNotEmpty()) {
                    return directMatches
                }

                element.values.flatMap(::collectPreferredTranslatedTexts)
            }

            is JsonArray -> element.flatMap(::collectPreferredTranslatedTexts)
            else -> emptyList()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MODEL_TYPE_LLM = "llm"
        private val PREFERRED_RESULT_KEYS = listOf(
            "dst",
            "translation",
            "translatedText",
            "targetText",
            "target_text",
            "result",
            "output",
        )
    }
}

internal class BaiduTranslationApiException(
    val errorCode: String?,
    val errorMessage: String?,
    message: String,
) : IllegalStateException(message)

internal fun mapLocaleLanguageToBaiduLanguage(locale: Locale): String? =
    when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> "zh"
        "en" -> "en"
        "ja" -> "jp"
        "ko" -> "kor"
        "de" -> "de"
        "ru" -> "ru"
        "es" -> "spa"
        "fr" -> "fra"
        "it" -> "it"
        "pt" -> "pt"
        "ar" -> "ara"
        "fi" -> "fin"
        "sk" -> "sk"
        "sv" -> "swe"
        else -> null
    }
