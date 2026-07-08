package top.apricityx.workshop.steam.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class SteamDirectoryClient(
    private val client: OkHttpClient = newDefaultOkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val apiBaseUrl: HttpUrl = "https://api.steampowered.com/".toHttpUrl(),
) {
    suspend fun loadServers(cellId: UInt = 0u, maxCount: UInt = 20u): List<CmServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(
                apiBaseUrl.newBuilder()
                    .addPathSegments("ISteamDirectory/GetCMListForConnect/v1/")
                    .addQueryParameter("format", "json")
                    .addQueryParameter("cellid", cellId.toString())
                    .addQueryParameter("maxcount", maxCount.toString())
                    .build(),
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SteamProtocolException("Steam directory request failed: ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<CmDirectoryEnvelope>(payload)
            parsed.response.serverList
                .filter { it.type == "websockets" }
                .map { CmServer(endpoint = it.endpoint, type = it.type) }
                .distinctBy(CmServer::websocketUri)
        }
    }

    suspend fun loadContentServers(cellId: UInt = 0u, maxCount: UInt = 20u): List<CdnServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(
                apiBaseUrl.newBuilder()
                    .addPathSegments("IContentServerDirectoryService/GetServersForSteamPipe/v1/")
                    .addQueryParameter("cell_id", cellId.toString())
                    .addQueryParameter("max_servers", maxCount.toString())
                    .build(),
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SteamProtocolException("Steam content directory request failed: ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ContentServerEnvelope>(payload)
            parsed.response.servers.map {
                CdnServer(
                    type = it.type,
                    sourceId = it.sourceId,
                    cellId = it.cellId ?: cellId.toInt(),
                    load = it.load,
                    weightedLoad = it.weightedLoad,
                    numEntriesInClientList = it.numEntriesInClientList,
                    steamChinaOnly = it.steamChinaOnly,
                    host = it.host,
                    vHost = it.vHost,
                    useAsProxy = it.useAsProxy,
                    proxyRequestPathTemplate = it.proxyRequestPathTemplate,
                    httpsSupport = it.httpsSupport,
                    allowedAppIds = it.allowedAppIds.map(Int::toUInt),
                    priorityClass = it.priorityClass.toUInt(),
                )
            }
        }
    }
}

@Serializable
private data class CmDirectoryEnvelope(
    val response: CmDirectoryResponse,
)

@Serializable
private data class CmDirectoryResponse(
    @SerialName("serverlist")
    val serverList: List<CmServerDto>,
)

@Serializable
private data class CmServerDto(
    val endpoint: String,
    val type: String,
)

@Serializable
private data class ContentServerEnvelope(
    val response: ContentServerResponse,
)

@Serializable
private data class ContentServerResponse(
    val servers: List<CdnServerDto>,
)

@Serializable
private data class CdnServerDto(
    val type: String,
    @SerialName("source_id")
    val sourceId: Int,
    @SerialName("cell_id")
    val cellId: Int? = null,
    val load: Int = 0,
    @SerialName("weighted_load")
    val weightedLoad: Float = 0f,
    @SerialName("num_entries_in_client_list")
    val numEntriesInClientList: Int = 0,
    @SerialName("steam_china_only")
    val steamChinaOnly: Boolean = false,
    val host: String,
    val vhost: String,
    @SerialName("use_as_proxy")
    val useAsProxy: Boolean = false,
    @SerialName("proxy_request_path_template")
    val proxyRequestPathTemplate: String? = null,
    @SerialName("https_support")
    val httpsSupport: String = "",
    @SerialName("allowed_app_ids")
    val allowedAppIds: List<Int> = emptyList(),
    @SerialName("priority_class")
    val priorityClass: Int = 0,
) {
    val vHost: String get() = vhost
}
