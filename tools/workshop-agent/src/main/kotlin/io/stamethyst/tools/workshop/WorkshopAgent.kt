package io.stamethyst.tools.workshop

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import top.apricityx.workshop.workshop.*

@Serializable
internal data class AgentRequest(
    val action: String,
    val appId: UInt = 646570u,
    val query: String = "",
    val page: Int = 1,
    val pageSize: Int = 20,
    val sort: String = "totaluniquesubscribers",
    val category: String = "",
    val days: Int = 7,
    val language: String = "schinese",
    val publishedFileId: String = "",
    val outputDir: String = "",
    val threads: Int = 8,
) {
    fun validate() {
        require(action in setOf("search", "details", "download")) { "Unknown action: $action" }
        require(appId > 0u && page > 0 && pageSize in 1..30 && threads in 1..32)
        require(query.length <= 200 && category.length <= 80 && language.matches(Regex("[a-z_]{2,32}")))
        require(sort in setOf("trend", "mostrecent", "lastupdated", "totaluniquesubscribers"))
        require(days in setOf(-1, 1, 7, 30, 90, 180, 365))
        if (action != "search") require(publishedFileId.toULongOrNull()?.let { it > 0uL } == true) { "Invalid publishedFileId" }
        if (action == "download") require(outputDir.isNotBlank()) { "outputDir is required" }
    }
}

internal class WorkshopAgent(
    private val client: OkHttpClient,
    private val community: HttpUrl = "https://steamcommunity.com/".toHttpUrl(),
    private val api: HttpUrl = "https://api.steampowered.com/".toHttpUrl(),
    private val download: (AgentRequest, File) -> Flow<DownloadEvent> = { input, output ->
        // Same anonymous engine, direct URL / UGC routing and CDN fallback as the launcher.
        WorkshopDownloadEngine.createDefault(
            client = client,
            maxConcurrentChunks = input.threads,
            allowPublicCdnFallbackOnSessionFailure = true,
            publishedFileLanguage = input.language,
        ).download(WorkshopDownloadRequest(input.appId, input.publishedFileId.toULong(), output))
    },
) {
    suspend fun execute(input: AgentRequest, progress: (JsonObject) -> Unit = {}): JsonObject {
        input.validate()
        return when (input.action) {
            "search" -> search(input)
            "details" -> details(input)
            else -> download(input, progress)
        }
    }

    private fun search(input: AgentRequest): JsonObject {
        val url = community.newBuilder().addPathSegments("workshop/browse/")
            .addQueryParameter("appid", input.appId.toString())
            .addQueryParameter("searchtext", input.query)
            .addQueryParameter("childpublishedfileid", "0")
            .addQueryParameter("l", input.language)
            .addQueryParameter("browsesort", input.sort)
            .addQueryParameter("actualsort", input.sort)
            .addQueryParameter("section", "readytouseitems")
            .addQueryParameter("p", input.page.toString())
            .addQueryParameter("numperpage", input.pageSize.toString())
            .apply {
                if (input.category.isNotBlank()) addQueryParameter("requiredtags[]", input.category)
                if (input.sort == "trend") addQueryParameter("days", input.days.toString())
            }.build()
        return buildJsonObject {
            WorkshopBrowse.parse(http(Request.Builder().url(url).build()), input.page, input.appId).forEach { (key, value) -> put(key, value) }
            put("query", input.query)
            put("sourceUrl", url.toString())
            put("pageSize", input.pageSize)
        }
    }

    private fun details(input: AgentRequest): JsonObject {
        val request = Request.Builder().url(api.resolve("ISteamRemoteStorage/GetPublishedFileDetails/v1/")!!)
            .post(FormBody.Builder().add("itemcount", "1").add("publishedfileids[0]", input.publishedFileId)
                .add("includechildren", "true").add("appid", input.appId.toString()).add("language", input.language).build()).build()
        val item = Json.parseToJsonElement(http(request)).jsonObject["response"]?.jsonObject
            ?.get("publishedfiledetails")?.jsonArray?.firstOrNull()?.jsonObject ?: error("Steam returned no details")
        require(item.text("result") == "1") { "Steam details failed: result=${item.text("result")}" }
        require(item.text("publishedfileid") == input.publishedFileId) { "Steam returned a different workshop item" }
        val consumer = item.text("consumer_app_id").ifBlank { item.text("consumer_appid") }
        require(consumer.toUIntOrNull() == input.appId) { "Workshop item belongs to app $consumer, not ${input.appId}" }
        return buildJsonObject {
            put("publishedFileId", input.publishedFileId)
            put("appId", input.appId.toLong())
            put("title", item.text("title"))
            put("creatorSteamId", item.text("creator"))
            val description = plain(item.text("description"))
            put("description", description.take(12000))
            put("descriptionTruncated", description.length > 12000)
            put("fileSizeBytes", item.text("file_size").toLongOrNull() ?: 0)
            put("updatedAtSeconds", item.text("time_updated").toLongOrNull() ?: 0)
            put("fileType", item.text("file_type").toIntOrNull() ?: 0)
            put("downloadSource", when {
                item.text("file_url").isNotBlank() -> "direct"
                item.text("hcontent_file").toULongOrNull()?.let { it > 0uL } == true -> "ugc"
                else -> "unavailable"
            })
            put("tags", item["tags"] ?: JsonArray(emptyList()))
            put("children", item["children"] ?: JsonArray(emptyList()))
            put("url", itemUrl(input.publishedFileId))
        }
    }

    private suspend fun download(input: AgentRequest, progress: (JsonObject) -> Unit): JsonObject {
        // Validate app ownership before invoking the shared resolver, which accepts a fallback appId.
        val metadata = details(input)
        require(metadata.text("fileType") != "2") { "Collections are not downloadable mods; select individual items" }
        val output = File(input.outputDir).canonicalFile
        require(!output.exists() || (output.isDirectory && output.list().orEmpty().isEmpty())) { "outputDir must be empty" }
        require(output.isDirectory || output.mkdirs()) { "Cannot create outputDir" }
        var completed: List<DownloadedFileInfo>? = null
        var failure: String? = null
        var lastProgress = 0L
        download(input, output).collect { event ->
            when (event) {
                is DownloadEvent.Completed -> completed = event.files
                is DownloadEvent.Failed -> failure = event.message
                is DownloadEvent.StateChanged -> progress(buildJsonObject { put("state", event.state.name) })
                is DownloadEvent.Progress -> {
                    val now = System.nanoTime()
                    if (now - lastProgress >= 500_000_000L) {
                        lastProgress = now
                        progress(buildJsonObject {
                            put("writtenBytes", event.writtenBytes)
                            event.totalBytes?.let { put("totalBytes", it) }
                        })
                    }
                }
                else -> Unit // Full engine logs stay on disk rather than filling the model context.
            }
        }
        failure?.let { error(it) }
        val files = completed?.takeIf { it.isNotEmpty() } ?: error("Download ended without completed files")
        val expectedSize = metadata.text("fileSizeBytes").toLongOrNull() ?: 0
        if (metadata.text("downloadSource") == "direct" && expectedSize > 0) {
            check(files.size == 1 && files.single().sizeBytes == expectedSize) { "Direct download size does not match Steam metadata" }
        }
        val artifacts = files.map { info ->
            val file = File(output, info.relativePath).canonicalFile
            require(file.toPath().startsWith(output.toPath()) && file.isFile && file.length() == info.sizeBytes) { "Invalid downloaded artifact: ${info.relativePath}" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            buildJsonObject {
                put("path", file.absolutePath)
                put("relativePath", info.relativePath)
                put("sizeBytes", file.length())
                put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        return buildJsonObject {
            put("publishedFileId", input.publishedFileId)
            put("title", metadata.text("title"))
            put("outputDir", output.absolutePath)
            put("files", JsonArray(artifacts))
            put("metadataPath", File(output, "metadata.json").absolutePath)
            put("logPath", File(output, "download.log").absolutePath)
        }
    }

    private fun http(request: Request): String = client.newCall(request.newBuilder()
        .header("User-Agent", "Mozilla/5.0 SlayTheAmethyst-WorkshopAgent/1.0").build()).execute().use { response ->
        check(response.isSuccessful) { "Steam request failed: HTTP ${response.code}" }
        response.body.string()
    }
}

/** One JSON request on stdin; NDJSON progress/result on stdout, no credentials or Android. */
fun main() {
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    var status = 0
    try {
        val input = Json.decodeFromString<AgentRequest>(System.`in`.bufferedReader().readText())
        val result = runBlocking {
            WorkshopAgent(client).execute(input) { update ->
                println(buildJsonObject { put("type", "progress"); put("progress", update) })
            }
        }
        println(buildJsonObject { put("type", "result"); put("success", true); put("result", result) })
    } catch (error: Exception) {
        status = 1
        println(buildJsonObject { put("type", "result"); put("success", false); put("error", error.message ?: error.javaClass.simpleName) })
    } finally {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
    exitProcess(status)
}
