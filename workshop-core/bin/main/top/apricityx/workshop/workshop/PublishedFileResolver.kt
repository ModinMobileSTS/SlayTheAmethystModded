package top.apricityx.workshop.workshop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class PublishedFileResolver(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: HttpUrl = "https://api.steampowered.com/".toHttpUrl(),
    private val language: String? = null,
) {
    suspend fun resolve(
        appId: UInt,
        publishedFileId: ULong,
    ): ResolvedWorkshopItem = withContext(Dispatchers.IO) {
        val requestBody = FormBody.Builder()
            .add("itemcount", "1")
            .add("publishedfileids[0]", publishedFileId.toString())
            .add("includechildren", "true")
            .add("appid", appId.toString())
            .apply {
                language?.takeIf(String::isNotBlank)?.let { add("language", it) }
            }
            .build()

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("ISteamRemoteStorage/GetPublishedFileDetails/v1/").build())
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WorkshopDownloadException("GetPublishedFileDetails failed: ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            val envelope = json.decodeFromString<GetPublishedFileDetailsEnvelope>(payload)
            val details = envelope.response.publishedFileDetails.firstOrNull()
                ?: throw WorkshopDownloadException("Steam did not return workshop details")

            if (details.result != 1) {
                throw WorkshopDownloadException("Steam returned result=${details.result} for published file")
            }

            val fileType = details.fileType ?: WORKSHOP_FILE_TYPE_COMMUNITY

            if (fileType == WORKSHOP_FILE_TYPE_COLLECTION) {
                throw WorkshopDownloadException("Collections are not supported in this demo")
            }

            if (fileType !in supportedFileTypes) {
                throw WorkshopDownloadException("Unsupported workshop file type: $fileType")
            }

            val title = details.title.takeIf(String::isNotBlank) ?: "Workshop $publishedFileId"
            val filename = details.filename.substringAfterLast('/').ifBlank { "${publishedFileId}.bin" }

            when {
                !details.fileUrl.isNullOrBlank() -> ResolvedWorkshopItem.DirectUrlItem(
                    fileName = filename,
                    fileUrl = details.fileUrl,
                    size = details.fileSize,
                    title = title,
                    metadataJson = payload,
                )

                details.hcontentFile != null && details.hcontentFile > 0 -> ResolvedWorkshopItem.UgcManifestItem(
                    manifestId = details.hcontentFile.toULong(),
                    depotId = (details.consumerAppId?.takeIf { it > 0 } ?: appId.toLong()).toUInt(),
                    title = title,
                    metadataJson = payload,
                )

                else -> throw WorkshopDownloadException("Unable to resolve workshop file_url or hcontent_file")
            }
        }
    }

    private companion object {
        const val WORKSHOP_FILE_TYPE_COMMUNITY = 0
        const val WORKSHOP_FILE_TYPE_COLLECTION = 2
        val supportedFileTypes = setOf(0, 3, 5, 10, 11, 12)
    }
}

@Serializable
private data class GetPublishedFileDetailsEnvelope(
    val response: GetPublishedFileDetailsResponse,
)

@Serializable
private data class GetPublishedFileDetailsResponse(
    @SerialName("publishedfiledetails")
    val publishedFileDetails: List<PublishedFileDetailsDto> = emptyList(),
)

@Serializable
private data class PublishedFileDetailsDto(
    val result: Int = 0,
    val title: String = "",
    val filename: String = "",
    @SerialName("file_url")
    val fileUrl: String? = null,
    @SerialName("file_size")
    val fileSize: Long? = null,
    @SerialName("file_type")
    val fileType: Int? = null,
    @SerialName("hcontent_file")
    val hcontentFile: Long? = null,
    @SerialName("consumer_app_id")
    val consumerAppId: Long? = null,
)
