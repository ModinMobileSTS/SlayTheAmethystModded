package io.stamethyst.tools.workshop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import top.apricityx.workshop.workshop.*

class WorkshopAgentTest {
    private fun payload(extra: String = "", app: Int = 646570) = """{"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"123","consumer_app_id":$app,"title":"Test &amp; Mod","file_type":0,"file_size":"3"$extra}]}}"""
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun withServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().build()
            try { block(server, client) } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun searchUsesLauncherQueryParametersAndParsesLegacyPage() = withServer { server, client ->
        server.enqueue(response("""
            <div class="workshopItem" data-publishedfileid="123">
              <a data-appid="646570" data-publishedfileid="123"></a>
              <div class="workshopItemTitle">角色 &amp; Cards</div>
              <div class="workshopItemAuthorName">作者：<a>Author</a></div>
            </div>
            <script>SharedFileBindMouseHover("sharedfile_123", false, {"description":"A &lt; B"});</script>
            <a class='pagebtn' href="?appid=646570&amp;p=3">Next</a>
        """))
        val result = runBlocking { WorkshopAgent(client, server.url("/")).execute(AgentRequest("search", query = "中文 & Cards", page = 2, category = "Character", sort = "trend", days = 30)) }
        val url = server.takeRequest().url
        assertEquals("中文 & Cards", url.queryParameter("searchtext"))
        assertEquals("646570", url.queryParameter("appid"))
        assertEquals("Character", url.queryParameter("requiredtags[]"))
        assertEquals("trend", url.queryParameter("actualsort"))
        assertEquals("30", url.queryParameter("days"))
        assertEquals("schinese", url.queryParameter("l"))
        val item = result.getValue("items").jsonArray.single().jsonObject
        assertEquals("角色 & Cards", item.text("title"))
        assertEquals("Author", item.text("author"))
        assertEquals("A < B", item.text("description"))
        assertEquals("true", result.text("hasNextPage"))
        assertEquals("3", result.text("nextPage"))
    }

    @Test fun parsesBothSsrFormatsWithEscapesAndUint64Ids() {
        val queryData = """{"queries":[{"queryKey":["PlayerLinkDetails","76561198000000001"],"state":{"data":{"public_data":{"persona_name":"作者"}}}},{"state":{"data":{"current_page":2,"total_pages":4,"results":[{"publishedfileid":"18446744073709551615","consumer_appid":646570,"creator":"76561198000000001","title":"Mod","short_description":"Video.play(\"intro\")","file_size":"42"}]}}}]}"""
        val context = buildJsonObject { put("queryData", queryData) }
        val pages = listOf(
            "<script id='valve-ssr-data'>${buildJsonObject { put("renderContext", context) }}</script>",
            "<script>window.SSR.renderContext=JSON.parse(${JsonPrimitive(context.toString())});</script>",
        )
        pages.forEach { html ->
            val result = WorkshopBrowse.parse(html, 1, 646570u)
            val item = result.getValue("items").jsonArray.single().jsonObject
            assertEquals("18446744073709551615", item.text("publishedFileId"))
            assertEquals("作者", item.text("author"))
            assertEquals("Video.play(\"intro\")", item.text("description"))
            assertEquals("42", item.text("fileSizeBytes"))
            assertEquals("2", result.text("page"))
            assertEquals("3", result.text("nextPage"))
        }
    }

    @Test fun distinguishesEmptyResultsFromInvalidPages() {
        assertTrue(WorkshopBrowse.parse("<div class=\"workshopBrowseItems\"></div>", 1, 646570u).getValue("items").jsonArray.isEmpty())
        for (html in listOf("", "<html>Login to WiFi</html>", "<div data-publishedfileid=\"123\">Changed markup</div>")) {
            assertThrows(IllegalArgumentException::class.java) { WorkshopBrowse.parse(html, 1, 646570u) }
        }
    }

    @Test fun metadataExposesDownloadSourceAndChildren() = withServer { server, client ->
        server.enqueue(response(payload(",\"hcontent_file\":\"123456789\",\"creator\":\"76561198000000001\",\"children\":[{\"publishedfileid\":\"42\"}]")))
        val result = runBlocking { WorkshopAgent(client, api = server.url("/")).execute(AgentRequest("details", publishedFileId = "123")) }
        assertEquals("ugc", result.text("downloadSource"))
        assertEquals("42", result.getValue("children").jsonArray.single().jsonObject.text("publishedfileid"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.body!!.utf8().contains("includechildren=true"))
    }

    @Test fun rejectsWrongAppPrivateItemAndHttpFailure() = withServer { server, client ->
        val agent = WorkshopAgent(client, community = server.url("/"), api = server.url("/"))
        for (body in listOf(payload(app = 480), """{"response":{"publishedfiledetails":[{"result":9}]}}""")) {
            server.enqueue(response(body))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { agent.execute(AgentRequest("details", publishedFileId = "123")) } }
        }
        server.enqueue(response("busy", 429))
        assertThrows(IllegalStateException::class.java) { runBlocking { agent.execute(AgentRequest("search")) } }
    }

    @Test fun realEngineDirectDownloadProducesFilesHashAndLogs() = withServer { server, client ->
        val output = Files.createTempDirectory("workshop-direct-").toFile()
        try {
            val details = payload(",\"file_url\":\"${server.url("/mod.jar")}\",\"filename\":\"mod.jar\"")
            server.enqueue(response(details)) // preflight
            server.enqueue(response(details)) // shared engine resolver
            server.enqueue(response("jar"))
            val engineClient = client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val agent = WorkshopAgent(engineClient, api = server.url("/"))
            val result = runBlocking { agent.execute(AgentRequest("download", publishedFileId = "123", outputDir = output.path)) }
            assertEquals("jar", File(output, "mod.jar").readText())
            val file = result.getValue("files").jsonArray.single().jsonObject
            assertEquals("0163f1eea7894350060624d315234d40c508ab251ba121714e234503045faadd", file.text("sha256"))
            assertEquals("3", file.text("sizeBytes"))
            assertTrue(File(result.text("metadataPath")).isFile)
            assertTrue(File(result.text("logPath")).readText().contains("file_url"))
        } finally { output.deleteRecursively() }
    }

    @Test fun failedAndIncompleteEngineStreamsNeverReportSuccess() = withServer { server, client ->
        val output = Files.createTempDirectory("workshop-failure-").toFile()
        try {
            for (events in listOf(flowOf(DownloadEvent.Failed("CDN unavailable")), flowOf(DownloadEvent.StateChanged(DownloadState.Success)))) {
                server.enqueue(response(payload()))
                val agent = WorkshopAgent(client, api = server.url("/"), download = { _, _ -> events })
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { agent.execute(AgentRequest("download", publishedFileId = "123", outputDir = output.path)) }
                }
            }
        } finally { output.deleteRecursively() }
    }

    @Test fun preservesExistingDownloadAndRejectsCollectionBeforeEngine() = withServer { server, client ->
        val output = Files.createTempDirectory("workshop-existing-").toFile()
        try {
            File(output, "keep.jar").writeText("keep")
            val agent = WorkshopAgent(client, api = server.url("/"), download = { _, _ -> error("must not download") })
            for (body in listOf(payload(), payload().replace("\"file_type\":0", "\"file_type\":2"))) {
                server.enqueue(response(body))
                assertThrows(IllegalArgumentException::class.java) { runBlocking { agent.execute(AgentRequest("download", publishedFileId = "123", outputDir = output.path)) } }
            }
            assertEquals("keep", File(output, "keep.jar").readText())
        } finally { output.deleteRecursively() }
    }

    @Test fun requestValidationRejectsInvalidBoundsBeforeNetwork() {
        for (request in listOf(AgentRequest("search", page = 0), AgentRequest("search", pageSize = 31), AgentRequest("search", sort = "random"), AgentRequest("search", days = 0), AgentRequest("details", publishedFileId = "18446744073709551616"), AgentRequest("download", publishedFileId = "123"))) {
            assertThrows(IllegalArgumentException::class.java) { request.validate() }
        }
    }

    @Test fun directMetadataSizeMismatchIsNotASuccessfulDownload() = withServer { server, client ->
        val output = Files.createTempDirectory("workshop-short-").toFile()
        try {
            server.enqueue(response(payload(",\"file_url\":\"https://example.invalid/mod.jar\"")))
            val agent = WorkshopAgent(client, api = server.url("/"), download = { _, dir ->
                File(dir, "mod.jar").writeText("x")
                flowOf(DownloadEvent.Completed(listOf(DownloadedFileInfo("mod.jar", 1, 0))))
            })
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { agent.execute(AgentRequest("download", publishedFileId = "123", outputDir = output.path)) }
            }
            assertTrue(error.message!!.contains("size does not match"))
        } finally { output.deleteRecursively() }
    }
}
