package io.stamethyst.backend.workshop

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkshopServiceTest {
    private lateinit var browseServer: MockWebServer
    private lateinit var detailsServer: MockWebServer
    private lateinit var downloadServer: MockWebServer

    @Before
    fun setUp() {
        browseServer = MockWebServer()
        detailsServer = MockWebServer()
        downloadServer = MockWebServer()
        browseServer.start()
        detailsServer.start()
        downloadServer.start()
    }

    @After
    fun tearDown() {
        browseServer.close()
        detailsServer.close()
        downloadServer.close()
    }

    @Test
    fun browseParsesWorkshopItems() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItem" data-publishedfileid="123456">
                      <a class="ugc" data-publishedfileid="123456">
                        <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
                        <div class="workshopItemTitle">Test Mod</div>
                        <div class="workshopItemAuthorName">Author</div>
                      </a>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          { "publishedfileid": "123456", "file_size": 1234, "subscriptions": 42 }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val result = runBlocking {
            service.browse(
                WorkshopBrowseQuery(
                    searchText = "test",
                    timeFilter = WorkshopBrowseTimeFilter.ThirtyDays,
                    category = WorkshopModCategory.Character,
                )
            )
        }

        assertEquals(1, result.items.size)
        assertEquals("Test Mod", result.items.single().title)
        assertEquals(123456uL, result.items.single().publishedFileId)
        assertTrue(!result.hasNextPage)
        assertEquals(1, browseServer.requestCount)
        val browseRequest = browseServer.takeRequest()
        assertEquals("/workshop/browse/", browseRequest.url.encodedPath)
        assertEquals("646570", browseRequest.url.queryParameter("appid"))
        assertEquals("test", browseRequest.url.queryParameter("searchtext"))
        assertEquals("schinese", browseRequest.url.queryParameter("l"))
        assertEquals("zh-CN,zh;q=0.9", browseRequest.headers["Accept-Language"])
        assertEquals("trend", browseRequest.url.queryParameter("browsesort"))
        assertEquals("trend", browseRequest.url.queryParameter("actualsort"))
        assertEquals("readytouseitems", browseRequest.url.queryParameter("section"))
        assertEquals("30", browseRequest.url.queryParameter("numperpage"))
        assertEquals("30", browseRequest.url.queryParameter("days"))
        assertEquals("Character", browseRequest.url.queryParameter("requiredtags[]"))
        // Metadata backfill is decoupled from the page fetch: nothing hits the details API
        // until the caller asks for it.
        assertEquals(0, detailsServer.requestCount)

        val enriched = runBlocking { service.loadBrowseItemMetadata(result.items) }
        assertEquals(1234L, enriched.single().fileSizeBytes)
        assertEquals(42L, enriched.single().downloadCount)
        assertEquals(1, detailsServer.requestCount)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
    }

    @Test
    fun getDetailsParsesPublishedFileDetails() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "123456",
                            "title": "Detailed Mod",
                            "file_url": "${downloadServer.url("/mod.jar")}",
                            "file_size": 1234,
                            "hcontent_file": 9999,
                            "consumer_app_id": 646570,
                            "creator_name": "Author",
                            "description": "Details",
                            "time_updated": 1710000000,
                            "subscriptions": 42,
                            "preview_url": "https://cdn.example/preview.jpg",
                            "children": [
                              { "publishedfileid": "1605833019" }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>g_sessionID = "session123";</script>
                    <script>InitializeCommentThread("PublishedFile_Public", "0", {"owner":"123","feature":"456","feature2":"-1"}, 'https://steamcommunity.com/comment/PublishedFile_Public/');</script>
                    <script>
                    var rgFullScreenshotURLs = [
                      { 'previewid' : '0', 'url': 'https://images.steamusercontent.com/ugc/111/AAA/?imw=5000&amp;imh=5000&amp;ima=fit&amp;impolicy=Letterbox&amp;imcolor=%23000000&amp;letterbox=false' },
                      { 'previewid' : '1', 'url': 'https://images.steamusercontent.com/ugc/222/BBB/?imw=5000&imh=5000&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false' },
                    ];
                    </script>
                    <span id="commentthread_123_totalcount">7</span>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "1605833019",
                            "title": "BaseMod",
                            "consumer_app_id": 646570,
                            "creator_name": "Maintainer",
                            "description": "Required dependency",
                            "time_updated": 1710000100,
                            "preview_url": "https://cdn.example/basemod.jpg"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking {
            service.getDetails(646570u, 123456uL)
        }

        assertEquals("Detailed Mod", details.summary.title)
        assertEquals("Localized Details", details.summary.description)
        assertFalse(details.fullDescriptionUnavailable)
        assertEquals("Author", details.summary.authorName)
        assertEquals(42L, details.summary.downloadCount)
        assertEquals(downloadServer.url("/mod.jar").toString(), details.fileUrl)
        assertEquals(
            listOf(
                "https://images.steamusercontent.com/ugc/111/AAA/?imw=1280&imh=720&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false",
                "https://images.steamusercontent.com/ugc/222/BBB/?imw=1280&imh=720&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false",
                "https://cdn.example/preview.jpg",
            ),
            details.previewImageUrls,
        )
        assertEquals(7L, details.commentCount)
        assertTrue(details.hasNextCommentPage)
        assertEquals(1, details.dependencies.size)
        assertEquals("BaseMod", details.dependencies.single().title)
        assertEquals(1605833019uL, details.dependencies.single().publishedFileId)
        assertEquals(1, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", detailsServer.takeRequest().url.encodedPath)
    }

    @Test
    fun getSummariesBatchesIdsAndPreservesRequestedOrder() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          { "publishedfileid": "2", "title": "Second", "consumer_app_id": 646570 },
                          { "publishedfileid": "1", "title": "First", "consumer_app_id": 646570 }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val summaries = runBlocking {
            newService().getSummaries(646570u, listOf(1uL, 2uL, 1uL, 0uL))
        }

        assertEquals(listOf(1uL, 2uL), summaries.map { it.publishedFileId })
        assertEquals(listOf("First", "Second"), summaries.map { it.title })
        assertEquals(1, detailsServer.requestCount)
        val request = detailsServer.takeRequest()
        assertEquals("/ISteamRemoteStorage/GetPublishedFileDetails/v1/", request.url.encodedPath)
        assertTrue(requireNotNull(request.body).utf8().contains("itemcount=2"))
        assertTrue(requireNotNull(request.body).utf8().contains("publishedfileids%5B0%5D=1"))
        assertTrue(requireNotNull(request.body).utf8().contains("publishedfileids%5B1%5D=2"))
        assertTrue(requireNotNull(request.body).utf8().contains("language=schinese"))
        assertEquals(0, browseServer.requestCount)
    }

    @Test
    fun getSummaryBatchesEmitsCompletedBatchesBeforeRemainingIds() {
        val firstBatchDetails = (1..20).joinToString(",") { id ->
            """{ "publishedfileid": "$id", "title": "Mod $id" }"""
        }
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{ "response": { "publishedfiledetails": [$firstBatchDetails] } }""")
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    { "response": { "publishedfiledetails": [
                      { "publishedfileid": "21", "title": "Mod 21" }
                    ] } }
                    """.trimIndent(),
                )
                .build(),
        )

        val batches = runBlocking {
            newService().getSummaryBatches(646570u, (1uL..21uL).toList()).toList()
        }

        assertEquals(listOf(20, 1), batches.map { it.size })
        assertEquals(1uL, batches.first().first().publishedFileId)
        assertEquals(21uL, batches.last().single().publishedFileId)
        assertEquals(2, detailsServer.requestCount)
    }

    @Test
    fun getDetailsParsesOrderedWorkshopVideoAndScreenshotGallery() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "1610056683",
                            "title": "Downfall Expansion Mod - 6.0",
                            "consumer_app_id": 646570,
                            "description": "API details",
                            "preview_url": "https://images.steamusercontent.com/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/?imw=512&amp;&amp;ima=fit&amp;impolicy=Letterbox&amp;imcolor=%23000000&amp;letterbox=false"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemDescription" id="highlightContent">Localized details</div>
                    <div id="highlight_strip_scroll">
                      <div class="highlight_selector"></div>
                      <div class="highlight_strip_item highlight_strip_movie" id="thumb_movie_19652634">
                        <img class="movie_thumb" src="https://img.youtube.com/vi/vYthsh8a1Dc/default.jpg">
                        <div class="highlight_movie_marker"></div>
                      </div>
                      <div class="highlight_strip_item highlight_strip_screenshot" id="thumb_screenshot_0">
                        <img src="https://images.steamusercontent.com/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/?imw=116&amp;imh=65&amp;ima=fit&amp;impolicy=Letterbox&amp;imcolor=%23000000&amp;letterbox=true">
                      </div>
                    </div>
                    <script>
                      var rgMovieFlashvars = {
                        'movie_19652634': {
                          YOUTUBE_VIDEO_ID: "vYthsh8a1Dc",
                          MOVIE_NAME: ""
                        },
                        '' : ''
                      };
                      var rgFullScreenshotURLs = [
                        { 'previewid' : '0', 'url': 'https://images.steamusercontent.com/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/?imw=5000&amp;imh=5000&amp;ima=fit&amp;impolicy=Letterbox&amp;imcolor=%23000000&amp;letterbox=false' }
                      ];
                    </script>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 1610056683uL) }

        assertEquals(2, details.previewMedia.size)
        assertEquals(WorkshopPreviewMediaKind.YouTubeVideo, details.previewMedia[0].kind)
        assertEquals(WorkshopPreviewVideoSource.YouTube, details.previewMedia[0].videoSource)
        assertEquals("vYthsh8a1Dc", details.previewMedia[0].youtubeVideoId)
        assertEquals(
            "https://img.youtube.com/vi/vYthsh8a1Dc/hqdefault.jpg",
            details.previewMedia[0].thumbnailUrl,
        )
        assertEquals(WorkshopPreviewMediaKind.Image, details.previewMedia[1].kind)
        assertEquals(
            "https://images.steamusercontent.com/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/?imw=1280&imh=720&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false",
            details.previewMedia[1].imageUrl,
        )
        assertEquals(
            listOf(
                "https://images.steamusercontent.com/ugc/2482116236401327367/9E2E72339F73CC987617BBEC6BC45442C0DC748B/?imw=1280&imh=720&ima=fit&impolicy=Letterbox&imcolor=%23000000&letterbox=false",
            ),
            details.previewImageUrls,
        )
    }

    @Test
    fun getDetailsIgnoresSteamNativeMoviePreviewMediaWhenOnlyYoutubeIsSupported() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "123456789",
                            "title": "Steam Native Video Mod",
                            "consumer_app_id": 646570,
                            "description": "API details",
                            "preview_url": "https://images.steamusercontent.com/ugc/111/AAA/"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemDescription" id="highlightContent">Localized details</div>
                    <div id="highlight_strip_scroll">
                      <div class="highlight_selector"></div>
                      <div class="highlight_strip_item highlight_strip_movie" id="thumb_movie_2033154">
                        <img class="movie_thumb" src="https://images.steamusercontent.com/ugc/steam-video-thumb/POSTER/">
                        <div class="highlight_movie_marker"></div>
                      </div>
                    </div>
                    <script>
                      var rgMovieFlashvars = {
                        'movie_2033154': {
                          FILENAME: "https://cdn.akamai.steamstatic.com/steam/apps/2033154/movie480_vp9.webm",
                          MOVIE_NAME: "Steam hosted movie"
                        },
                        '' : ''
                      };
                    </script>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 123456789uL) }

        assertEquals(1, details.previewMedia.size)
        assertEquals(WorkshopPreviewMediaKind.Image, details.previewMedia[0].kind)
        assertTrue(details.previewMedia.none { it.kind == WorkshopPreviewMediaKind.SteamVideo })
    }

    @Test
    fun getDetailsFallsBackToCommunityPageAuthor() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "123456",
                            "title": "Detailed Mod",
                            "consumer_app_id": 646570,
                            "description": "Details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemAuthorName">By <a href="https://steamcommunity.com/id/author">Community Author</a></div>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 123456uL) }

        assertEquals("Community Author", details.summary.authorName)
        assertEquals(1, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsFallsBackToCreatorBlockAuthor() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "1605833019",
                            "title": "BaseMod",
                            "consumer_app_id": 646570,
                            "description": "Details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="rightSectionTopTitle condensed">创建者</div>
                    <div class="rightDetailsBlock">
                      <div class="creatorsBlock">
                        <div class="friendBlock persona offline">
                          <a class="friendBlockLinkOverlay" href="https://steamcommunity.com/profiles/76561197996637426"></a>
                          <div class="playerAvatar offline"><img src="avatar.jpg"></div>
                          <div class="friendBlockContent">
                            Bug Kiooeht<br>
                            <span class="friendSmallText">离线</span>
                          </div>
                        </div>
                      </div>
                    </div>
                    <div class="workshopItemDescription" id="highlightContent">Localized Details</div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 1605833019uL) }

        assertEquals("Bug Kiooeht", details.summary.authorName)
        assertEquals(1, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsParsesLiveWorkshopCommentInitShape() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "Caffé In-Spire."
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>g_sessionID = "session123";</script>
                    <div class="breadcrumbs">
                      <a href="https://steamcommunity.com/id/Temple9/myworkshopfiles/?appid=646570">tldyl 的创意工坊</a>
                    </div>
                    <div class="rightDetailsBlock">
                      <div class="creatorsBlock">
                        <div class="friendBlock persona online">
                          <div class="friendBlockContent">
                            tldyl<br>
                            <span class="friendSmallText">游戏中</span>
                          </div>
                        </div>
                      </div>
                    </div>
                    <div class="workshopItemDescription" id="highlightContent">一个皮肤mod<br>原版角色的咖啡厅制服皮肤</div>
                    <script>
                      InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539837", {"feature":"2906539837","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10,"extended_data":"{\"contributors\":[\"76561198808881876\",{},{}],\"appid\":646570}"}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                    </script>
                    <span id="commentthread_123_totalcount">34 条留言</span>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 2906539837uL) }

        assertEquals("一个皮肤mod\n原版角色的咖啡厅制服皮肤", details.summary.description)
        assertEquals("tldyl", details.summary.authorName)
        assertEquals(34L, details.commentCount)
        assertTrue(details.hasNextCommentPage)
        assertEquals("76561198808881876", details.commentThreadContext?.ownerId)
        assertEquals("2906539837", details.commentThreadContext?.featureId)
        assertEquals("-1", details.commentThreadContext?.feature2)
        assertEquals("session123", details.commentThreadContext?.sessionId)
        assertTrue(details.commentThreadContext?.extendedData.orEmpty().contains("\"appid\":646570"))
        assertEquals(1, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsTreatsZeroCommentCountWithoutThreadContextAsEmptyComments() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "Caffé In-Spire."
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemDescription" id="highlightContent">No comments yet</div>
                    <span id="commentthread_PublishedFile_Public_76561198808881876_2906539837_totalcount">0 条留言</span>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 2906539837uL) }

        assertEquals("No comments yet", details.summary.description)
        assertEquals(0L, details.commentCount)
        assertEquals(null, details.commentThreadContext)
        assertEquals(1, details.commentTotalPages)
        assertFalse(details.hasNextCommentPage)
    }

    @Test
    fun getDetailsParsesZeroCommentCountFromWorkshopTab() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "3736782029",
                            "title": "Removed Mod",
                            "consumer_app_id": 646570,
                            "description": "This item has been removed."
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="sectionTabs item responsive_hidden">
                        <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3736782029" class="sectionTab active description"><span>Description</span></a>
                        <a href="https://steamcommunity.com/sharedfiles/filedetails/discussions/3736782029" class="sectionTab discussions"><span>Discussions<span class="tabCount">0</span></span></a>
                        <a href="https://steamcommunity.com/sharedfiles/filedetails/comments/3736782029" class="sectionTab comments"><span>Comments<span class="tabCount">0</span></span></a>
                        <a href="https://steamcommunity.com/sharedfiles/filedetails/changelog/3736782029" class="sectionTab changelog"><span>Change Notes</span></a>
                    </div>
                    <div class="workshopItemDescription" id="highlightContent">Removed workshop item</div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 3736782029uL) }

        assertEquals(0L, details.commentCount)
        assertEquals(null, details.commentThreadContext)
        assertEquals(1, details.commentTotalPages)
        assertFalse(details.hasNextCommentPage)
    }

    @Test
    fun getDetailsKeepsFullDescriptionAroundNestedMarkup() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "Short API description"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="detailBox altFooter">
                      <div class="workshopItemDescriptionTitle">描述</div>
                      <div class="workshopItemDescription" id="highlightContent">
                        一个皮肤mod<br>
                        <div class="bb_h1">安装说明</div>
                        原版角色的咖啡厅制服皮肤<br>
                        如果使用此mod时出现了人物贴图变黑块/无法全屏/无法启动等问题，是电脑配置无法加载GIF导致，并非mod问题<br>
                        享受女仆装吧！
                      </div>
                    </div>
                    <div class="detailBox">
                      <script>
                        InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539837", {"feature":"2906539837","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                      </script>
                      <span id="commentthread_PublishedFile_Public_76561198808881876_2906539837_totalcount">34 条留言</span>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 2906539837uL) }

        assertTrue(details.summary.description.contains("一个皮肤mod"))
        assertTrue(details.summary.description.contains("原版角色的咖啡厅制服皮肤"))
        assertTrue(details.summary.description.contains("如果使用此mod时出现了人物贴图变黑块"))
        assertTrue(details.summary.description.contains("享受女仆装吧！"))
        assertEquals(34L, details.commentCount)
        assertEquals("76561198808881876", details.commentThreadContext?.ownerId)
        assertEquals("2906539837", details.commentThreadContext?.featureId)
        assertEquals("-1", details.commentThreadContext?.feature2)
    }

    @Test
    fun getDetailsRetriesCommunityPageBeforeFallingBackToShortDescription() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "短简介"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(MockResponse.Builder().code(500).body("temporary failure").build())
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="detailBox altFooter">
                      <div class="workshopItemDescriptionTitle">描述</div>
                      <div class="workshopItemDescription" id="highlightContent">
                        短简介<br>
                        第二次进入才拿到的完整简介<br>
                        包含更多安装说明和兼容性说明
                      </div>
                    </div>
                    <script>
                      InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539837", {"feature":"2906539837","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                    </script>
                    <span id="commentthread_PublishedFile_Public_76561198808881876_2906539837_totalcount">34 条留言</span>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking { service.getDetails(646570u, 2906539837uL) }

        assertTrue(details.summary.description.contains("第二次进入才拿到的完整简介"))
        assertTrue(details.summary.description.contains("包含更多安装说明和兼容性说明"))
        assertEquals(34L, details.commentCount)
        assertEquals("76561198808881876", details.commentThreadContext?.ownerId)
        assertEquals("2906539837", details.commentThreadContext?.featureId)
        assertEquals(2, browseServer.requestCount)
    }

    @Test
    fun getDetailsUsesApiCreatorForCommentsWhenCommunityPageIsRateLimitedButKeepsLaterRequests() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "creator": "76561198808881876",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "API description"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539838",
                            "title": "Second Mod",
                            "consumer_app_id": 646570,
                            "description": "Second API description"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(MockResponse.Builder().code(429).body("too many requests").build())
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>
                      InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539838", {"feature":"2906539838","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                    </script>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val first = runBlocking { service.getDetails(646570u, 2906539837uL) }
        val second = runBlocking { service.getDetails(646570u, 2906539838uL) }

        assertEquals("76561198808881876", first.commentThreadContext?.ownerId)
        assertEquals("2906539837", first.commentThreadContext?.featureId)
        assertEquals("-1", first.commentThreadContext?.feature2)
        assertEquals(null, first.commentCount)
        assertEquals("76561198808881876", second.commentThreadContext?.ownerId)
        assertEquals("2906539838", second.commentThreadContext?.featureId)
        assertEquals(2, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
    }

    @Test
    fun getDetailsReusesCommunityPageForRepeatedDetailsLoads() {
        repeat(2) {
            detailsServer.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """
                        {
                          "response": {
                            "publishedfiledetails": [
                              {
                                "publishedfileid": "2906539837",
                                "title": "Caffé In-Spire",
                                "consumer_app_id": 646570,
                                "description": "API description"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    .build(),
            )
        }
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>
                      InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539837", {"feature":"2906539837","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                    </script>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val first = runBlocking { service.getDetails(646570u, 2906539837uL) }
        val second = runBlocking { service.getDetails(646570u, 2906539837uL) }

        assertEquals("76561198808881876", first.commentThreadContext?.ownerId)
        assertEquals("76561198808881876", second.commentThreadContext?.ownerId)
        assertEquals(1, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
    }

    @Test
    fun getDetailsCanSkipCommunityPageForMetadataOnlyCalls() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "API description",
                            "children": [
                              { "publishedfileid": "1605833019" }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <script>
                      InitializeCommentThread( "PublishedFile_Public", "PublishedFile_Public_76561198808881876_2906539837", {"feature":"2906539837","feature2":-1,"owner":"76561198808881876","total_count":34,"start":0,"pagesize":10}, 'https://steamcommunity.com/comment/PublishedFile_Public/', 40 );
                    </script>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking {
            service.getDetails(
                appId = 646570u,
                publishedFileId = 2906539837uL,
                includeCommunityData = false,
                includeDependencyData = false,
            )
        }

        assertEquals("API description", details.summary.description)
        assertEquals(null, details.commentThreadContext)
        assertEquals(emptyList<WorkshopItemSummary>(), details.dependencies)
        assertEquals(0, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsUsesApiDescriptionBeforeCardSummaryWhenCommunityPageFails() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": "Caffé In-Spire."
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(MockResponse.Builder().code(500).body("temporary failure").build())
        browseServer.enqueue(MockResponse.Builder().code(500).body("temporary failure").build())

        val service = newService()
        val details = runBlocking {
            service.getDetails(
                appId = 646570u,
                publishedFileId = 2906539837uL,
                fallbackSummary = WorkshopItemSummary(
                    publishedFileId = 2906539837uL,
                    appId = 646570u,
                    title = "咖啡厅皮肤",
                    previewUrl = "https://cdn.example/preview.jpg",
                    description = "一个皮肤mod",
                    authorName = "tldyl",
                    fileSizeBytes = 1234L,
                    updatedAtMillis = 1710000000L,
                    downloadCount = 42L,
                ),
            )
        }

        assertEquals("Caffé In-Spire", details.summary.title)
        assertEquals("https://cdn.example/preview.jpg", details.summary.previewUrl)
        assertEquals("Caffé In-Spire.", details.summary.description)
        assertFalse(details.fullDescriptionUnavailable)
        assertEquals("tldyl", details.summary.authorName)
        assertEquals(1234L, details.summary.fileSizeBytes)
        assertEquals(1710000000L, details.summary.updatedAtMillis)
        assertEquals(42L, details.summary.downloadCount)
        assertEquals(null, details.commentThreadContext)
        assertEquals(2, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getDetailsKeepsCardSummaryWhenCommunityPageAndApiDescriptionsFail() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "2906539837",
                            "title": "Caffé In-Spire",
                            "consumer_app_id": 646570,
                            "description": ""
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(MockResponse.Builder().code(500).body("temporary failure").build())
        browseServer.enqueue(MockResponse.Builder().code(500).body("temporary failure").build())

        val service = newService()
        val details = runBlocking {
            service.getDetails(
                appId = 646570u,
                publishedFileId = 2906539837uL,
                fallbackSummary = WorkshopItemSummary(
                    publishedFileId = 2906539837uL,
                    appId = 646570u,
                    title = "咖啡厅皮肤",
                    previewUrl = "https://cdn.example/preview.jpg",
                    description = "一个皮肤mod",
                    authorName = "tldyl",
                    fileSizeBytes = 1234L,
                    updatedAtMillis = 1710000000L,
                    downloadCount = 42L,
                ),
            )
        }

        assertEquals("Caffé In-Spire", details.summary.title)
        assertEquals("https://cdn.example/preview.jpg", details.summary.previewUrl)
        assertEquals("一个皮肤mod", details.summary.description)
        assertTrue(details.fullDescriptionUnavailable)
        assertEquals("tldyl", details.summary.authorName)
        assertEquals(1234L, details.summary.fileSizeBytes)
        assertEquals(1710000000L, details.summary.updatedAtMillis)
        assertEquals(42L, details.summary.downloadCount)
        assertEquals(null, details.commentThreadContext)
        assertEquals(2, browseServer.requestCount)
        assertEquals(1, detailsServer.requestCount)
    }

    @Test
    fun getChangeNotesParsesSteamChangelogBlocks() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="changeLogCtn">
                      <div class="headline">May 10 @ 9:30am</div>
                      <p>Fixed crash<br><ul><li>Added action</li></ul></p>
                    </div>
                    <div class="changeLogCtn">
                      <div class="headline">May 1 @ 2:00pm</div>
                      <p>Initial workshop release</p>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val changeNotes = runBlocking { service.getChangeNotes(123456uL) }

        assertEquals(123456uL, changeNotes.publishedFileId)
        assertTrue(changeNotes.latestMarkdown.contains("### May 10 @ 9:30am"))
        assertTrue(changeNotes.latestMarkdown.contains("Fixed crash"))
        assertTrue(changeNotes.latestMarkdown.contains("- Added action"))
        assertTrue(changeNotes.markdown.contains("### May 1 @ 2:00pm"))
        val request = browseServer.takeRequest()
        assertEquals("/sharedfiles/filedetails/changelog/123456", request.url.encodedPath)
        assertEquals("schinese", request.url.queryParameter("l"))
    }

    @Test
    fun getDetailsParsesRequiredItemsWhenApiChildrenAreMissing() {
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          {
                            "publishedfileid": "3651739735",
                            "title": "SpearAndShield",
                            "consumer_app_id": 646570,
                            "description": "Expansion details"
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    <div class="workshopItemDescription" id="highlightContent">Expansion details</div>
                    <div class="panel">
                      <div class="rightSectionTopTitle condensed">Required items</div>
                      <div class="requiredItemsContainer" id="RequiredItems">
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1609158507" target="_blank">
                          <div class="requiredItem">StSLib</div>
                        </a>
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1605833019" target="_blank">
                          <div class="requiredItem">BaseMod</div>
                        </a>
                        <a href="https://steamcommunity.com/workshop/filedetails/?id=1610056683" target="_blank">
                          <div class="requiredItem">Downfall Expansion Mod - 6.0</div>
                        </a>
                      </div>
                    </div>
                    """.trimIndent(),
                )
                .build(),
        )
        detailsServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "response": {
                        "publishedfiledetails": [
                          { "publishedfileid": "1609158507", "title": "StSLib", "consumer_app_id": 646570 },
                          { "publishedfileid": "1605833019", "title": "BaseMod", "consumer_app_id": 646570 },
                          { "publishedfileid": "1610056683", "title": "Downfall Expansion Mod - 6.0", "consumer_app_id": 646570 }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val service = newService()
        val details = runBlocking {
            service.getDetails(646570u, 3651739735uL)
        }

        assertEquals(
            listOf(1609158507uL, 1605833019uL, 1610056683uL),
            details.dependencies.map { it.publishedFileId },
        )
        assertEquals("Downfall Expansion Mod - 6.0", details.dependencies.last().title)
        assertEquals(1, browseServer.requestCount)
        assertEquals(2, detailsServer.requestCount)
    }

    @Test
    fun getCommentsPageParsesCurrentCommentAuthorAndTimestampMarkup() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "total_count": 1,
                      "pagesize": 5,
                      "start": 0,
                      "comments_html": "<div class=\"commentthread_comment\" id=\"comment_1\"><div class=\"commentthread_comment_content\"><div class=\"commentthread_comment_author\"><div class=\"commentthread_comment_avatar playerAvatar offline\"><a href=\"https://steamcommunity.com/id/alice\"><img src=\"avatar.jpg\"></a></div><div class=\"author_name_group\"><div class=\"flex_row\"><a class=\"hoverunderline commentthread_author_link\" href=\"https://steamcommunity.com/id/alice\" data-miniprofile=\"1\"><bdi>Alice</bdi></a></div><div class=\"commentthread_comment_timestamp\" title=\"2026 年 4 月 3 日 上午 1:27:15 PDT\" data-timestamp=\"1775204835\">4 月 3 日 上午 1:27&nbsp;</div></div></div><div class=\"commentthread_comment_text\">Current markup</div></div></div>"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Commented Mod",
                previewUrl = "",
                description = "",
            ),
            commentThreadContext = WorkshopCommentThreadContext(
                ownerId = "123",
                featureId = "456",
            ),
        )

        val service = newService()
        val page = runBlocking { service.getCommentsPage(details, page = 1) }

        assertEquals(1, page.comments.size)
        assertEquals("Alice", page.comments.single().authorName)
        assertEquals(1775204835L, page.comments.single().postedEpochSeconds)
        assertEquals("4 月 3 日 上午 1:27", page.comments.single().postedDisplayText)
        assertEquals("Current markup", page.comments.single().content)
    }

    @Test
    fun getCommentsPageParsesFiveComments() {
        browseServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "total_count": 6,
                      "pagesize": 5,
                      "start": 5,
                      "comments_html": "<div class=\"commentthread_comment\" id=\"comment_1\"><a class=\"commentthread_author_link\" href=\"https://steamcommunity.com/id/a\">Alice</a><span class=\"commentthread_comment_timestamp\" data-timestamp=\"1710000000\">Mar 9</span><div class=\"commentthread_comment_text\">Second page<br>comment</div></div>"
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Commented Mod",
                previewUrl = "",
                description = "",
            ),
            commentThreadContext = WorkshopCommentThreadContext(
                ownerId = "123",
                featureId = "456",
            ),
        )

        val service = newService()
        val page = runBlocking { service.getCommentsPage(details, page = 2) }

        assertEquals(2, page.page)
        assertEquals(2, page.totalPages)
        assertTrue(page.hasPreviousPage)
        assertTrue(!page.hasNextPage)
        assertEquals(1, page.comments.size)
        assertEquals("Alice", page.comments.single().authorName)
        assertEquals("Second page\ncomment", page.comments.single().content)
        val request = browseServer.takeRequest()
        assertEquals("/comment/PublishedFile_Public/render/123/456/", request.url.encodedPath)
        assertTrue(requireNotNull(request.body).utf8().contains("start=5"))
    }

    @Test
    fun directDownloadWritesFile() {
        downloadServer.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("jar-bytes")
                .build(),
        )

        val service = newService()
        val outputDir = Files.createTempDirectory("workshop-download").toFile()
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 123456uL,
                appId = 646570u,
                title = "Downloaded Mod",
                previewUrl = "",
                description = "",
            ),
            fileUrl = downloadServer.url("/mod.jar").toString(),
        )

        val events = mutableListOf<WorkshopDownloadEvent>()
        runBlocking {
            service.download(WorkshopDownloadRequest(details, outputDir)).collect { events += it }
        }

        assertTrue(events.any { it is WorkshopDownloadEvent.Completed })
        assertTrue(File(outputDir, "Downloaded Mod.jar").isFile)
        assertEquals("jar-bytes", File(outputDir, "Downloaded Mod.jar").readText(StandardCharsets.UTF_8))
        assertEquals(1, downloadServer.requestCount)
    }

    @Test
    fun hcontentDownloadUsesInjectedDownloader() {
        val service = newService(
            downloaderFactory = { _ ->
                WorkshopContentDownloader { details, _ ->
                    flowOf(
                        WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Resolving),
                        WorkshopDownloadEvent.StateChanged(WorkshopDownloadState.Downloading),
                        WorkshopDownloadEvent.Completed(
                            listOf(
                                WorkshopDownloadedArtifact(
                                    relativePath = "mod.jar",
                                    sizeBytes = 7,
                                    modifiedAtMillis = 1234L,
                                )
                            )
                        ),
                    )
                }
            }
        )
        val outputDir = Files.createTempDirectory("workshop-download-hcontent").toFile()
        val details = WorkshopItemDetails(
            summary = WorkshopItemSummary(
                publishedFileId = 99uL,
                appId = 646570u,
                title = "Hcontent Mod",
                previewUrl = "",
                description = "",
            ),
            hcontentFile = 5555uL,
        )

        val events = mutableListOf<WorkshopDownloadEvent>()
        runBlocking {
            service.download(WorkshopDownloadRequest(details, outputDir)).collect { events += it }
        }

        assertTrue(events.any { it is WorkshopDownloadEvent.StateChanged })
        assertTrue(events.any { it is WorkshopDownloadEvent.Completed })
    }

    private fun newService(
        downloaderFactory: ((WorkshopService) -> WorkshopContentDownloader)? = null,
    ): WorkshopService {
        val context = TestRoots.create("workshop-service").context
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val target = when (request.url.host) {
                    "steamcommunity.com" -> browseServer.url(request.url.encodedPath + querySuffix(request))
                    "api.steampowered.com" -> detailsServer.url(request.url.encodedPath + querySuffix(request))
                    else -> downloadServer.url(request.url.encodedPath + querySuffix(request))
                }
                chain.proceed(
                    Request.Builder()
                        .url(target)
                        .method(request.method, request.body)
                        .headers(request.headers)
                        .build()
                )
            }
            .build()
        return WorkshopService(context, client, downloaderFactory)
    }

    private fun querySuffix(request: Request): String = request.url.encodedQuery?.let { "?$it" }.orEmpty()

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context,
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val cacheDir = File(rootDir, "cache").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getCacheDir(): File = cacheDir

                        override fun getApplicationContext(): Context = this

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
