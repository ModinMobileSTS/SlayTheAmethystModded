package io.stamethyst.backend.workshop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reduced from the real Steam responses captured for the author/discussion prototype. */
internal object WorkshopSocialFixtures {
    const val FILE_ID = "1605833019"
    const val AUTHOR_ID = "76561197996637426"
    const val FORUM_OWNER = "29087962"
    const val FORUM_ID = "1743356517516996351"
    const val TOPIC_ID = "1743356517516996356"
    const val REPLY_OWNER = "103582791458609370"
    val author = WorkshopAuthor("Bug Kiooeht", "https://steamcommunity.com/profiles/$AUTHOR_ID", AUTHOR_ID)
    val topic = WorkshopDiscussionSummary(TOPIC_ID, "Bug reports", "https://steamcommunity.com/workshop/filedetails/discussion/$FILE_ID/$TOPIC_ID/")

    fun topicRow(id: String = TOPIC_ID, title: String = "Bug reports", file: String = FILE_ID) = """
        <div class="forum_topic unread" data-gidforumtopic="$id">
          <a class="forum_topic_overlay" href="https://steamcommunity.com/workshop/filedetails/discussion/$file/$id/"></a>
          <div class="forum_topic_reply_count"><img src="comment.png"> 58</div>
          <div class="forum_topic_lastpost" data-timestamp="1755924886">2025 年 8 月 22 日</div>
          <div class="forum_topic_name">$title</div><div class="forum_topic_op">Felix Hellborn</div>
        </div>
    """.trimIndent()

    fun forumPage(total: Int = 7, file: String = FILE_ID) = """
        <html><body>
        <script>InitializeForum("PublishedFile_${FORUM_OWNER}_$file", {"owner":"$FORUM_OWNER","type":"PublishedFile","feature":"$file","gidforum":"$FORUM_ID","permissions":{"can_view":1,"can_reply":1},"start":0,"total_count":$total,"pagesize":15}, "https://steamcommunity.com/forum/$FORUM_OWNER/PublishedFile/");</script>
        <div class="forum_topics">${if (total > 0) topicRow(file = file) else ""}</div>
        </body></html>
    """.trimIndent()

    fun reply(id: String = "1743356517529702689", developer: Boolean = true, content: String = "This is now fixed.") = """
        <div class="commentthread_comment responsive_body_text" id="comment_$id">
          <div class="commentthread_comment_content">
            <a class="commentthread_author_link${if (developer) " commentthread_author_developer" else ""}" href="https://steamcommunity.com/profiles/$AUTHOR_ID">Bug Kiooeht</a>
            ${if (developer) "<span class=\"commentthread_workshop_authorbadge\">[开发者]</span>" else ""}
            <div class="commentthread_comment_timestamp" data-timestamp="1546935182">2019 年 1 月 8 日</div>
            <div class="commentthread_comment_text" id="comment_content_$id">$content</div>
          </div>
        </div>
    """.trimIndent()

    fun topicPage(content: String = "A place to hopefully store all the bug reports in one place") = """
        <html><body>
        <div class="forum_op" id="forum_op_$TOPIC_ID">
          <a class="forum_op_author" href="https://steamcommunity.com/id/FelixHellborn">Felix Hellborn</a>
          <div class="date" data-timestamp="1546640439">2019 年 1 月 4 日</div>
          <div id="forum_op_topic_$TOPIC_ID">Bug reports</div>
          <div id="forum_op_content_$TOPIC_ID">$content</div>
        </div>
        <script>InitializeForumTopic({"owner":"$FORUM_OWNER","type":"PublishedFile","feature":"$FILE_ID","gidforum":"$FORUM_ID","permissions":{"can_view":1}}, "https://steamcommunity.com/forum/$FORUM_OWNER/PublishedFile/", "$TOPIC_ID", {"author":"Felix Hellborn","text":"Example"});</script>
        <script>InitializeCommentThread("ForumTopic", "ForumTopic_${REPLY_OWNER}_${FORUM_ID}_$TOPIC_ID", {"feature":"$FORUM_ID","feature2":"$TOPIC_ID","owner":"$REPLY_OWNER","total_count":58,"start":0,"pagesize":15,"extended_data":"{\"nested\":{\"text\":\"brace } and quote \\\"\"}}","oldestfirst":true}, 15);</script>
        <div class="forumtopic_comments">${reply()}</div>
        </body></html>
    """.trimIndent()

    fun forumJson(start: Int = 15, total: Int = 31, topics: String = topicRow()) = buildJsonObject {
        put("success", 1); put("start", start); put("total_count", total); put("topics_html", topics)
    }.toString()

    fun repliesJson(start: Int = 15, name: String = "ForumTopic_${REPLY_OWNER}_${FORUM_ID}_$TOPIC_ID") = buildJsonObject {
        put("success", true); put("name", name); put("start", start)
        // Steam actually returns this value as a JSON string on the reply-render endpoint.
        put("pagesize", "15"); put("total_count", 58); put("comments_html", reply(id = "1743356517529702690"))
    }.toString()

    val creatorHtml = """
        <div class="creatorsBlock">
          <div class="friendBlock persona offline" data-miniprofile="36371698">
            <a class="friendBlockLinkOverlay" href="https://steamcommunity.com/profiles/$AUTHOR_ID"></a>
            <div class="playerAvatar"><img src="https://avatars.fastly.steamstatic.com/example_medium.jpg"></div>
            <div class="friendBlockContent">Bug Kiooeht<br><span class="friendSmallText">离线</span></div>
          </div>
          <div class="friendBlock" data-miniprofile="98487738">
            <a class="friendBlockLinkOverlay" href="https://steamcommunity.com/id/FelixHellborn"></a>
            <div class="friendBlockContent">Felix Hellborn<br><span>在线</span></div>
          </div>
        </div>
    """.trimIndent()

    fun authorPage(page: Int = 1, total: Int = 18) = """
        <div id="HeaderUserBreadcrumbs">
          <span id="HeaderUserInfoName"><a href="https://steamcommunity.com/profiles/$AUTHOR_ID">Bug Kiooeht</a></span>
          <span class="HeaderUserInfoSection"><a href="https://steamcommunity.com/profiles/$AUTHOR_ID/myworkshopfiles/?appid=646570">Slay the Spire</a></span>
        </div>
        <div class="workshopBrowsePagingInfo">正在显示第 ${(page - 1) * 30 + 1} - ${minOf(page * 30, total)} 项，共 $total 项条目</div>
        <div class="workshopBrowseItems"><div class="workshopItem">
          <a class="ugc" data-appid="646570" data-publishedfileid="$FILE_ID">
            <img class="workshopItemPreviewImage" src="https://images.steamusercontent.com/example.jpg">
          </a>
          <a class="item_link"><div class="workshopItemTitle ellipsis">BaseMod</div></a>
        </div></div>
    """.trimIndent()
}
