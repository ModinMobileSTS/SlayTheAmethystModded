package io.stamethyst.backend.workshop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopBrowseParserTest {
    @Test
    fun parsesPublishedFileIdTitleAuthorAndPreview() {
        val html = """
            <div class="workshopItem" data-publishedfileid="123456">
              <a class="ugc" data-appid="646570" data-publishedfileid="123456">
                <img class="workshopItemPreviewImage" src="https://cdn.example/preview.jpg" />
                <div class="workshopItemTitle">Test Mod</div>
                <div class="workshopItemAuthorName">by &nbsp; Author</div>
              </a>
            </div>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)
        val items = page.items

        assertFalse(page.hasNextPage)
        assertEquals(1, items.size)
        assertEquals(123456uL, items.single().publishedFileId)
        assertEquals(646570u, items.single().appId)
        assertEquals("Test Mod", items.single().title)
        assertEquals("Author", items.single().authorName)
        assertEquals("https://cdn.example/preview.jpg", items.single().previewUrl)
        assertTrue(items.single().description.isNotBlank())
    }

    @Test
    fun ignoresEntriesWithoutTitle() {
        val html = "<a data-publishedfileid=\"42\"></a>"

        val items = WorkshopBrowseParser.parse(html)

        assertTrue(items.isEmpty())
    }

    @Test
    fun parsesChineseMarkupDescriptionsAndNextPage() {
        val html = """
            <div data-panel="{&quot;type&quot;:&quot;PanelGroup&quot;}" class="workshopItem">
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3680514339&searchtext=" class="ugc" data-appid="646570" data-publishedfileid="3680514339">
                <div id="sharedfile_3680514339" class="workshopItemPreviewHolder ">
                  <img class="workshopItemPreviewImage " src="https://example.com/vibration.png">
                </div>
              </a>
              <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3680514339&searchtext=" class="item_link"><div class="workshopItemTitle ellipsis">手柄振动支持</div></a>
              <div class="workshopItemAuthorName ellipsis">作者：&nbsp;<a class="workshop_author_link" href="https://steamcommunity.com/profiles/1/myworkshopfiles/?appid=646570">Apricityx_</a></div>
            </div>
            <script>
              SharedFileBindMouseHover( "sharedfile_3680514339", false, {"id":"3680514339","title":"手柄振动支持","description":"中文描述"} );
            </script>
            <a class='pagebtn' href="https://steamcommunity.com/workshop/browse/?appid=646570&p=2">&gt;</a>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertTrue(page.hasNextPage)
        assertEquals(1, page.items.size)
        assertEquals(3680514339uL, page.items.single().publishedFileId)
        assertEquals("手柄振动支持", page.items.single().title)
        assertEquals("Apricityx_", page.items.single().authorName)
        assertEquals("中文描述", page.items.single().description)
    }

    @Test
    fun parsesCurrentSteamLegacySearchMarkup() {
        val html = """
            <div class="workshopBrowseItems">
              <div data-panel="{&quot;type&quot;:&quot;PanelGroup&quot;}" class="workshopItem">
                <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019&searchtext=BaseMod" class="ugc" data-appid="646570" data-publishedfileid="1605833019">
                  <div id="sharedfile_1605833019" class="workshopItemPreviewHolder ">
                    <img class="workshopItemPreviewImage " src="https://images.steamusercontent.com/ugc/base/preview.jpg">
                  </div>
                </a>
                <img class="fileRating" src="https://community.akamai.steamstatic.com/public/images/sharedfiles/4-star.png?v=2" />
                <a data-panel="{&quot;focusable&quot;:false}" href="https://steamcommunity.com/sharedfiles/filedetails/?id=1605833019&searchtext=BaseMod" class="item_link"><div class="workshopItemTitle ellipsis">BaseMod</div></a>
                <div class="workshopItemAuthorName ellipsis">作者：&nbsp;<a class="workshop_author_link" href="https://steamcommunity.com/profiles/76561197996637426/myworkshopfiles/?appid=646570">Bug Kiooeht</a></div>
                <div style="clear: both"></div>
              </div>
              <script>
                SharedFileBindMouseHover( "sharedfile_1605833019", false, {"id":"1605833019","title":"BaseMod","description":"BaseMod description","appid":646570} );
              </script>
            </div>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertEquals(1, page.items.size)
        assertEquals(1605833019uL, page.items.single().publishedFileId)
        assertEquals("BaseMod", page.items.single().title)
        assertEquals("Bug Kiooeht", page.items.single().authorName)
        assertEquals("BaseMod description", page.items.single().description)
        assertEquals(4, page.items.single().rating?.score)
        assertEquals(5, page.items.single().rating?.maxScore)
    }

    @Test
    fun parsesSsrRenderContext() {
        assertSsrPage(useJsonScript = false)
    }

    @Test
    fun parsesValveSsrDataJsonScript() {
        assertSsrPage(useJsonScript = true)
    }

    private fun assertSsrPage(useJsonScript: Boolean) {
        val queryData = """
            {
              "mutations": [],
              "queries": [
                {
                  "state": {
                    "data": {
                      "public_data": {
                        "steamid": "76561198000000001",
                        "persona_name": "apricity"
                      }
                    }
                  },
                  "queryKey": ["PlayerLinkDetails", "76561198000000001"]
                },
                {
                  "state": {
                    "data": {
                      "current_page": 2,
                      "total_pages": 4,
                      "results": [
                        {
                          "publishedfileid": "3677098410",
                          "creator": "76561198000000001",
                          "consumer_appid": "646570",
                          "preview_url": "https://example.com/skip.png",
                          "title": "Skip The Spire",
                          "short_description": "A fun mod",
                          "file_size": "123456",
                          "subscriptions": "98765",
                          "vote_data": { "score": 0.72 }
                        },
                        {
                          "publishedfileid": "3747700458",
                          "creator": "76561198000000001",
                          "consumer_appid": 646570,
                          "preview_url": "https://example.com/mint.png",
                          "title": "MintModSkinNew",
                          "short_description": "Current Steam SSR shape",
                          "file_size": "368739826",
                          "subscriptions": 2408,
                          "star_rating": 4,
                          "total_votes": 56
                        },
                        {
                          "publishedfileid": "3747848415",
                          "creator": "76561198000000001",
                          "consumer_appid": 646570,
                          "preview_url": "https://example.com/unrated.png",
                          "title": "Unrated SSR Item",
                          "short_description": "Not enough votes",
                          "file_size": 1024,
                          "subscriptions": 23,
                          "star_rating": -1,
                          "total_votes": 1
                        }
                      ]
                    }
                  },
                  "queryKey": ["workshop_browse", 646570, "trend"]
                }
              ]
            }
        """.trimIndent()
        val renderContext = """{"queryData":${Json.encodeToString(queryData)}}"""
        val script = if (useJsonScript) {
            """<script type="application/json" id="valve-ssr-data" nonce="test">{"renderContext":$renderContext}</script>"""
        } else {
            """<script>window.SSR.renderContext=JSON.parse(${Json.encodeToString(renderContext)});</script>"""
        }
        val html = """
            <html>
              <head>$script</head>
              <body></body>
            </html>
        """.trimIndent()

        val page = WorkshopBrowseParser.parsePage(html, page = 1)

        assertEquals(2, page.page)
        assertTrue(page.hasNextPage)
        assertEquals(3, page.items.size)
        val voteDataItem = page.items[0]
        assertEquals(3677098410uL, voteDataItem.publishedFileId)
        assertEquals("Skip The Spire", voteDataItem.title)
        assertEquals("apricity", voteDataItem.authorName)
        assertEquals("A fun mod", voteDataItem.description)
        assertEquals(123456L, voteDataItem.fileSizeBytes)
        assertEquals(98765L, voteDataItem.downloadCount)
        assertEquals(4, voteDataItem.rating?.score)
        assertEquals(5, voteDataItem.rating?.maxScore)
        val topLevelRatingItem = page.items[1]
        assertEquals(3747700458uL, topLevelRatingItem.publishedFileId)
        assertEquals("MintModSkinNew", topLevelRatingItem.title)
        assertEquals(368739826L, topLevelRatingItem.fileSizeBytes)
        assertEquals(2408L, topLevelRatingItem.downloadCount)
        assertEquals(4, topLevelRatingItem.rating?.score)
        assertEquals(5, topLevelRatingItem.rating?.maxScore)
        val unratedItem = page.items[2]
        assertEquals(3747848415uL, unratedItem.publishedFileId)
        assertEquals("Unrated SSR Item", unratedItem.title)
        assertEquals(1024L, unratedItem.fileSizeBytes)
        assertEquals(23L, unratedItem.downloadCount)
        assertEquals(null, unratedItem.rating)
    }

    @Test
    fun parsesSsrContextWhenItemDescriptionContainsEscapedQuotes() {
        val queryData =
            """
            {
              "mutations": [],
              "queries": [
                {
                  "state": {
                    "data": {
                      "current_page": 1,
                      "total_pages": 1,
                      "results": [
                        {
                          "publishedfileid": "3769585454",
                          "creator": "76561199212854088",
                          "consumer_appid": 646570,
                          "preview_url": "https://example.com/video-core.png",
                          "title": "Sts Video Core",
                          "short_description": "VideoApi.playFullscreen(\"examplemod/videos/intro.webm\")"
                        }
                      ]
                    }
                  },
                  "queryKey": ["workshop_browse", { "appid": 646570 }, 1]
                }
              ]
            }
            """.trimIndent()
        val renderContext = """{"queryData":${Json.encodeToString(queryData)}}"""
        val scripts = listOf(
            """<script>window.SSR.renderContext=JSON.parse(${Json.encodeToString(renderContext)});</script>""",
            """<script nonce='test' id = 'valve-ssr-data' type='application/json'>{"renderContext":$renderContext}</script>""",
        )
        scripts.forEach { html ->
            val page = WorkshopBrowseParser.parsePage(html, page = 1)

            assertEquals(1, page.items.size)
            assertEquals(3769585454uL, page.items.single().publishedFileId)
            assertEquals("Sts Video Core", page.items.single().title)
            assertEquals("VideoApi.playFullscreen(\"examplemod/videos/intro.webm\")", page.items.single().description)
            assertFalse(page.hasNextPage)
        }
    }

    @Test
    fun malformedValveSsrDataReturnsEmptyPageWithoutThrowing() {
        listOf("not json", "[]", "{}", """{"renderContext":null}""").forEach { data ->
            val page = WorkshopBrowseParser.parsePage(
                """<script id="valve-ssr-data" type="application/json">$data</script>""",
                page = 3,
            )
            assertTrue(page.items.isEmpty())
            assertEquals(3, page.page)
            assertFalse(page.hasNextPage)
        }
    }
}
