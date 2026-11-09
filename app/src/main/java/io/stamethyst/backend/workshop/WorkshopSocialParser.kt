package io.stamethyst.backend.workshop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Steam serves HTML, JSON-wrapped HTML, and JSON initialization arguments on the same pages. */
internal object WorkshopSocialParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val BASE = "https://steamcommunity.com"
    private val profilePath = Regex("^/(profiles/([0-9]+)|id/[^/]+)/?$")
    private val topicPath = Regex("^/(?:workshop|sharedfiles)/filedetails/discussion/([0-9]+)/([0-9]+)/?$")

    fun author(name: String, profileUrl: String, avatarUrl: String = "", steamId: String? = null): WorkshopAuthor? {
        val url = profileUrl.toHttpUrlOrNull() ?: return null
        if (url.host != "steamcommunity.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val path = url.encodedPath.substringBefore("/myworkshopfiles").trimEnd('/')
        val match = profilePath.matchEntire(path) ?: return null
        val id = match.groupValues[2].takeIf { it.toULongOrNull()?.let { n -> n > 0uL } == true }
            ?: steamId?.takeIf { it.toULongOrNull()?.let { n -> n > 0uL } == true }
        return WorkshopAuthor(name.trim(), "$BASE$path", id, safeLink(avatarUrl).orEmpty())
    }

    fun apiAuthor(steamId: String?, name: String): WorkshopAuthor? = steamId
        ?.takeIf { it.toULongOrNull()?.let { n -> n > 0uL } == true }
        ?.let { author(name, "$BASE/profiles/$it") }

    fun creators(html: String): List<WorkshopAuthor> = Jsoup.parse(html, BASE)
        .select(".creatorsBlock .friendBlock").mapNotNull { block ->
            val profile = block.selectFirst("a.friendBlockLinkOverlay[href]")?.absUrl("href") ?: return@mapNotNull null
            val name = block.selectFirst(".friendBlockContent")?.ownText().orEmpty()
            val accountId = block.attr("data-miniprofile").toULongOrNull()
            val steamId = accountId?.takeIf { it <= UInt.MAX_VALUE.toULong() }
                ?.let { (76561197960265728uL + it).toString() }
            author(name, profile, block.selectFirst(".playerAvatar img")?.absUrl("src").orEmpty(), steamId)
        }.distinctBy(WorkshopAuthor::identity)

    fun cardAuthor(html: String, name: String): WorkshopAuthor? = Jsoup.parseBodyFragment(html, BASE)
        .selectFirst(".workshopItemAuthorName a[href]")?.let { author(name, it.absUrl("href")) }

    fun authorPage(html: String, appId: UInt, author: WorkshopAuthor, page: Int, pageSize: Int): WorkshopAuthorPage {
        val doc = Jsoup.parse(html, BASE)
        // Unlike forum pages, author pages have no initialization object. Use the profile header
        // and selected game's breadcrumb, not an arbitrary card or the final URL's spelling.
        val expected = author(author.name, author.profileUrl, steamId = author.steamId)
            ?: error("Invalid Steam author profile")
        val actual = doc.selectFirst("#HeaderUserInfoName a[href]")?.let { author(it.text(), it.absUrl("href")) }
            ?: error("Steam author identity was missing")
        check(actual.profileUrl == expected.profileUrl || (expected.steamId != null && actual.steamId == expected.steamId)) {
            "Steam returned a different author workshop"
        }
        check(doc.select("#HeaderUserBreadcrumbs .HeaderUserInfoSection a[href]").any { link ->
            link.absUrl("href").toHttpUrlOrNull()?.queryParameter("appid") == appId.toString()
        }) { "Steam returned a different author workshop game" }
        val parsed = WorkshopBrowseParser.parsePage(html, page)
        check(parsed.items.isNotEmpty() || doc.selectFirst(".workshopBrowseItems, .workshopBrowsePagingInfo") != null) {
            "Steam returned an invalid author workshop page"
        }
        val counts = Regex("""\d+(?:[,.\u00a0]\d{3})*""").findAll(doc.selectFirst(".workshopBrowsePagingInfo")?.text().orEmpty())
            .mapNotNull { it.value.filter(Char::isDigit).toIntOrNull() }.toList()
        val total = counts.lastOrNull()?.takeIf { counts.size >= 3 || it == 0 }
        val hasNextLink = doc.select(".workshopBrowsePagingControls a[href]").any { link ->
            link.absUrl("href").toHttpUrlOrNull()?.queryParameter("p")?.toIntOrNull()?.let { it > page } == true
        }
        return WorkshopAuthorPage(
            author, appId,
            parsed.items.filter { it.appId == appId }.map { item ->
                item.copy(authorName = item.authorName.ifBlank { author.name }, author = item.author ?: author)
            },
            page, pageSize, total,
            hasNextPage = total?.let { page.toLong() * pageSize < it } ?: (hasNextLink || parsed.hasNextPage),
        )
    }

    fun discussionPage(html: String, publishedFileId: ULong): WorkshopDiscussionPage {
        val data = callObject(html, "InitializeForum", 1)
        check(data.text("type") == "PublishedFile" && data.id("feature") == publishedFileId.toString()) {
            "Steam returned a different workshop forum"
        }
        val forum = WorkshopDiscussionForum(data.id("owner"), data.id("feature"), data.id("gidforum"))
        val start = data.nonNegativeInt("start")
        val count = data.positiveInt("pagesize")
        val total = data.nonNegativeInt("total_count")
        val topics = topics(html, publishedFileId)
        check(total == 0 || topics.isNotEmpty()) { "Steam forum topics were missing" }
        return WorkshopDiscussionPage(publishedFileId, forum, topics, start, count, total)
    }

    fun discussionPageJson(payload: String, previous: WorkshopDiscussionPage): WorkshopDiscussionPage {
        val data = responseObject(payload)
        val html = data.requiredText("topics_html")
        val total = data.nonNegativeInt("total_count")
        val start = data.nonNegativeInt("start")
        val topics = topics(html, previous.publishedFileId)
        check(total <= start || topics.isNotEmpty()) { "Steam forum topics were missing" }
        // Steam does not return pagesize here. Preserve the requested count instead of guessing.
        return previous.copy(topics = topics, start = start, total = total)
    }

    private fun topics(html: String, publishedFileId: ULong): List<WorkshopDiscussionSummary> = Jsoup.parse(html, BASE)
        .select(".forum_topic[data-gidforumtopic]").mapNotNull { row ->
            val id = row.attr("data-gidforumtopic").takeIf { it.toULongOrNull() != null } ?: return@mapNotNull null
            val title = row.selectFirst(".forum_topic_name")?.text().orEmpty()
            val url = topicUrl(row.selectFirst(".forum_topic_overlay[href]")?.absUrl("href").orEmpty(), publishedFileId, id)
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            WorkshopDiscussionSummary(
                id, title, url, row.selectFirst(".forum_topic_op")?.text().orEmpty(),
                row.selectFirst(".forum_topic_reply_count")?.text()?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
                row.selectFirst(".forum_topic_lastpost[data-timestamp]")?.attr("data-timestamp")?.toLongOrNull(),
            )
        }.distinctBy(WorkshopDiscussionSummary::id)

    fun topicUrl(raw: String, publishedFileId: ULong, topicId: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.host != "steamcommunity.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val match = topicPath.matchEntire(url.encodedPath) ?: return null
        if (match.groupValues[1] != publishedFileId.toString() || match.groupValues[2] != topicId) return null
        return url.newBuilder().scheme("https").query(null).fragment(null).build().toString()
    }

    fun discussionDetails(html: String, publishedFileId: ULong, topic: WorkshopDiscussionSummary): WorkshopDiscussionDetails {
        val doc = Jsoup.parse(html, BASE)
        val op = doc.getElementById("forum_op_${topic.id}") ?: error("Steam discussion was missing")
        val title = doc.getElementById("forum_op_topic_${topic.id}")?.text()?.takeIf(String::isNotBlank)
            ?: error("Steam discussion title was missing")
        val content = doc.getElementById("forum_op_content_${topic.id}") ?: error("Steam discussion body was missing")
        val forum = callObject(html, "InitializeForumTopic", 0)
        check(forum.id("feature") == publishedFileId.toString() && forum.text("type") == "PublishedFile") {
            "Steam returned a different discussion"
        }
        val data = callObject(html, "InitializeCommentThread", 2, "ForumTopic")
        check(data.id("feature2") == topic.id && data.id("feature") == forum.id("gidforum")) {
            "Steam returned a different reply thread"
        }
        val ctx = WorkshopDiscussionReplyContext(
            data.id("owner"), data.id("feature"), data.id("feature2"), data.text("extended_data"),
            data.text("oldestfirst") != "false",
        )
        val replyPage = WorkshopDiscussionReplyPage(
            replies(html), data.nonNegativeInt("start"), data.positiveInt("pagesize"), data.nonNegativeInt("total_count"),
        )
        return WorkshopDiscussionDetails(
            topic.copy(title = title, authorName = op.selectFirst(".forum_op_author")?.text().orEmpty(), replyCount = replyPage.total),
            safeLink(op.selectFirst(".forum_op_author")?.absUrl("href").orEmpty()).orEmpty(), markdown(content),
            op.selectFirst(".date[data-timestamp]")?.attr("data-timestamp")?.toLongOrNull(), ctx, replyPage,
        )
    }

    fun replyPageJson(payload: String, context: WorkshopDiscussionReplyContext): WorkshopDiscussionReplyPage {
        val data = responseObject(payload)
        data.text("name")?.let { name ->
            check(name == "ForumTopic_${context.owner}_${context.forumId}_${context.topicId}") { "Steam returned a different reply page" }
        }
        val html = data.requiredText("comments_html")
        val start = data.nonNegativeInt("start")
        val total = data.nonNegativeInt("total_count")
        val replies = replies(html)
        check(total <= start || replies.isNotEmpty()) { "Steam discussion replies were missing" }
        return WorkshopDiscussionReplyPage(replies, start, data.positiveInt("pagesize"), total)
    }

    private fun replies(html: String): List<WorkshopDiscussionReply> = Jsoup.parse(html, BASE)
        .select(".commentthread_comment[id]").mapNotNull { row ->
            val content = row.selectFirst(".commentthread_comment_text") ?: return@mapNotNull null
            val author = row.selectFirst(".commentthread_author_link")
            val time = row.selectFirst("[data-timestamp]")
            WorkshopDiscussionReply(
                WorkshopComment(
                    row.id().removePrefix("comment_"), author?.text().orEmpty(),
                    safeLink(author?.absUrl("href").orEmpty()).orEmpty(), markdown(content),
                    time?.attr("data-timestamp")?.toLongOrNull(), time?.text().orEmpty(),
                ), row.selectFirst(".commentthread_workshop_authorbadge, .commentthread_author_developer") != null,
            )
        }.distinctBy { it.comment.id }

    /** Render safe readable Markdown, never execute downloaded HTML/scripts or javascript: links. */
    private fun markdown(element: Element): String {
        fun render(node: Node): String = when (node) {
            is TextNode -> node.wholeText.replace(Regex("[\\t\\r\\n ]+"), " ")
            is Element -> {
                val tag = node.normalName()
                val body by lazy { node.childNodes().joinToString("") { render(it) } }
                when {
                    tag in setOf("script", "style", "iframe", "button") -> ""
                    tag == "br" -> "\n"
                    tag == "pre" -> "\n\n```\n${node.wholeText().replace("```", "` ` `")}\n```\n\n"
                    tag == "a" -> safeLink(node.absUrl("href"))?.let { "[${body.trim()}]($it)" } ?: body
                    tag == "img" -> node.attr("alt")
                    tag == "blockquote" || node.hasClass("bb_quote") -> "\n\n${body.trim().lines().joinToString("\n") { "> $it" }}\n\n"
                    tag == "li" -> "\n- ${body.trim()}\n"
                    tag in setOf("div", "p", "ul", "ol", "h1", "h2", "h3") -> "\n${body.trim()}\n"
                    else -> body
                }
            }
            else -> ""
        }
        return element.childNodes().joinToString("") { render(it) }.lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun safeLink(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    /** Extract a JSON argument using balanced braces, not a regex that stops at nested objects. */
    private fun callObject(html: String, function: String, stringsBeforeObject: Int, firstString: String? = null): JsonObject {
        val stringArg = "[\"'][^\"']*[\"']\\s*,\\s*"
        val prefix = Regex("\\b${Regex.escape(function)}\\s*\\(\\s*" + stringArg.repeat(stringsBeforeObject))
        val scripts = Jsoup.parse(html).select("script").joinToString("\n") { it.data() }
        val match = prefix.findAll(scripts).firstOrNull { firstString == null || it.value.contains("\"$firstString\"") || it.value.contains("'$firstString'") }
            ?: error("Steam $function context was missing")
        val start = match.range.last + 1
        check(scripts.getOrNull(start) == '{') { "Invalid Steam $function context" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until scripts.length) {
            val char = scripts[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return json.parseToJsonElement(scripts.substring(start, index + 1)) as? JsonObject
                        ?: error("Invalid Steam context")
                }
            }
        }
        error("Unterminated Steam $function context")
    }

    private fun responseObject(payload: String): JsonObject {
        val data = json.parseToJsonElement(payload) as? JsonObject ?: error("Steam returned an invalid JSON response")
        check(data.text("success") in setOf("1", "true")) { "Steam rejected the discussion request" }
        return data
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredText(key: String): String = text(key) ?: error("Steam response missing $key")
    private fun JsonObject.id(key: String): String = requiredText(key).also {
        check(it.toULongOrNull()?.let { id -> id > 0uL } == true) { "Invalid Steam $key" }
    }
    private fun JsonObject.nonNegativeInt(key: String): Int = text(key)?.toIntOrNull()?.takeIf { it >= 0 }
        ?: error("Invalid Steam $key")
    private fun JsonObject.positiveInt(key: String): Int = nonNegativeInt(key).also { check(it > 0) { "Invalid Steam $key" } }
}
