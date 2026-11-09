package io.stamethyst.backend.workshop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkshopSocialParserTest {
    private val f = WorkshopSocialFixtures

    @Test fun preservesMultipleCreatorsAndVanityProfileIdentityWithoutOnlineStatus() {
        val creators = WorkshopSocialParser.creators(f.creatorHtml)
        assertEquals(listOf("Bug Kiooeht", "Felix Hellborn"), creators.map { it.name })
        assertEquals(f.AUTHOR_ID, creators[0].steamId)
        assertEquals("76561198058753466", creators[1].steamId)
        assertEquals("https://steamcommunity.com/id/FelixHellborn", creators[1].profileUrl)
        assertTrue(creators[0].avatarUrl.endsWith("example_medium.jpg"))
    }

    @Test fun rejectsExternalOrCredentialBearingProfileAndTopicUrls() {
        assertNull(WorkshopSocialParser.author("x", "https://example.com/profiles/${f.AUTHOR_ID}"))
        assertNull(WorkshopSocialParser.author("x", "https://secret@steamcommunity.com/profiles/${f.AUTHOR_ID}"))
        assertNull(WorkshopSocialParser.topicUrl("javascript:alert(1)", f.FILE_ID.toULong(), f.TOPIC_ID))
        assertNull(WorkshopSocialParser.topicUrl(f.topic.url, 42uL, f.TOPIC_ID))
    }

    @Test fun authorCardsInheritKnownAuthorAndUseRealPaginationTotal() {
        val result = WorkshopSocialParser.authorPage(f.authorPage(), 646570u, f.author, 1, 30)
        assertEquals(18, result.total)
        assertFalse(result.hasNextPage)
        assertEquals(f.author, result.items.single().author)
        assertEquals(f.author.name, result.items.single().authorName)
        assertTrue(WorkshopSocialParser.authorPage(f.authorPage(total = 31), 646570u, f.author, 1, 30).hasNextPage)
    }

    @Test fun rejectsAuthorPagesForOtherProfilesGamesAndMissingIdentity() {
        listOf(
            f.authorPage().replace(f.AUTHOR_ID, "76561198058753466"),
            f.authorPage().replace("appid=646570", "appid=42"),
            "<div class=workshopBrowseItems></div>",
        ).forEach { html ->
            assertThrows(IllegalStateException::class.java) { WorkshopSocialParser.authorPage(html, 646570u, f.author, 1, 30) }
        }
    }

    @Test fun parsesRealForumContextWithoutConfusingItsOwnerWithAuthorSteamId() {
        val result = WorkshopSocialParser.discussionPage(f.forumPage(), f.FILE_ID.toULong())
        assertEquals(f.FORUM_OWNER, result.forum.owner)
        assertEquals(f.FORUM_ID, result.forum.forumId)
        assertEquals(7, result.total)
        assertEquals("Bug reports", result.topics.single().title)
        assertEquals(f.TOPIC_ID, result.topics.single().id)
        assertEquals(58, result.topics.single().replyCount)
        assertFalse(result.hasNextPage)
    }

    @Test fun forumPaginationUsesRequestedPageSizeBecauseResponseDoesNotContainIt() {
        val first = WorkshopSocialParser.discussionPage(f.forumPage(total = 31), f.FILE_ID.toULong())
        val next = WorkshopSocialParser.discussionPageJson(f.forumJson(), first)
        assertEquals(15, next.pageSize)
        assertEquals(2, next.page)
        assertTrue(next.hasNextPage)
        val last = WorkshopSocialParser.discussionPageJson(f.forumJson(start = 30), next)
        assertEquals(3, last.page)
        assertFalse(last.hasNextPage)
    }

    @Test fun acceptsGenuineEmptyForumButNotHomepageLoginPageOrDifferentWorkshop() {
        assertTrue(WorkshopSocialParser.discussionPage(f.forumPage(total = 0), f.FILE_ID.toULong()).topics.isEmpty())
        listOf("<html>Steam Community</html>", "<html><form id=loginForm>Sign in</form></html>", f.forumPage(file = "42")).forEach { html ->
            assertThrows(IllegalStateException::class.java) { WorkshopSocialParser.discussionPage(html, f.FILE_ID.toULong()) }
        }
    }

    @Test fun topicUsesReplyOwnerAndNestedEscapedJsonAndPreservesSafeFormatting() {
        val result = WorkshopSocialParser.discussionDetails(f.topicPage("Hello<br>world <a href=\"https://example.com\">reference</a><script>alert(1)</script><a href=\"javascript:alert(2)\">safe text</a>"), f.FILE_ID.toULong(), f.topic)
        assertEquals(f.REPLY_OWNER, result.replyContext.owner)
        assertEquals(f.FORUM_ID, result.replyContext.forumId)
        assertEquals(f.TOPIC_ID, result.replyContext.topicId)
        assertNotNull(Json.parseToJsonElement(result.replyContext.extendedData!!))
        assertTrue(result.content.contains("Hello\nworld"))
        assertTrue(result.content.contains("[reference](https://example.com/)"))
        assertFalse(result.content.contains("alert"))
        assertEquals(58, result.firstReplyPage.total)
        assertTrue(result.firstReplyPage.replies.single().isDeveloper)
    }

    @Test fun rejectsMismatchedTopicAndReplyThread() {
        val html = f.topicPage().replace("\"feature2\":\"${f.TOPIC_ID}\"", "\"feature2\":\"42\"")
        assertThrows(IllegalStateException::class.java) { WorkshopSocialParser.discussionDetails(html, f.FILE_ID.toULong(), f.topic) }
        val details = WorkshopSocialParser.discussionDetails(f.topicPage(), f.FILE_ID.toULong(), f.topic)
        assertThrows(IllegalStateException::class.java) { WorkshopSocialParser.replyPageJson(f.repliesJson(name = "PublishedFile_Public_42"), details.replyContext) }
    }

    @Test fun replyPaginationAcceptsStringPageSizeAndLargeStringIds() {
        val context = WorkshopSocialParser.discussionDetails(f.topicPage(), f.FILE_ID.toULong(), f.topic).replyContext
        val result = WorkshopSocialParser.replyPageJson(f.repliesJson(), context)
        assertEquals(2, result.page)
        assertEquals(15, result.pageSize)
        assertTrue(result.hasNextPage)
        assertEquals("1743356517529702690", result.replies.single().comment.id)
    }
}
