package io.stamethyst.tools.workshop

import kotlinx.serialization.json.*

/** Mirrors the launcher's legacy + SSR browse formats without depending on Android. */
internal object WorkshopBrowse {
    private val flags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private fun regex(pattern: String) = Regex(pattern, flags)

    fun parse(html: String, page: Int, appId: UInt): JsonObject {
        val descriptions = regex("""SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""")
            .findAll(html).associate { match ->
                match.groupValues[1] to runCatching {
                    Json.parseToJsonElement(match.groupValues[2]).jsonObject.text("description")
                }.getOrDefault("")
            }
        val matches = regex("""data-publishedfileid=["'](\d+)["']""").findAll(html).toList()
        val items = matches.mapIndexedNotNull { index, match ->
            val block = html.substring(match.range.first, matches.getOrNull(index + 1)?.range?.first ?: html.length)
            val title = field(block, "workshopItemTitle")
            if (title.isBlank()) return@mapIndexedNotNull null
            val id = match.groupValues[1]
            val authorBlock = regex("""class=["'][^"']*\bworkshopItemAuthorName\b[^"']*["'][^>]*>(.*?)</div>""")
                .find(block)?.groupValues?.get(1).orEmpty()
            val author = plain(regex("""<a\b[^>]*>(.*?)</a>""").find(authorBlock)?.groupValues?.get(1) ?: authorBlock)
                .replace(Regex("^(?:by\\s+|作者[：:]\\s*)", RegexOption.IGNORE_CASE), "").trim()
            buildJsonObject {
                put("publishedFileId", id)
                put("appId", appId.toLong())
                put("title", title)
                put("author", author)
                put("description", plain(descriptions[id].orEmpty()).take(1000))
                put("url", itemUrl(id))
            }
        }.distinctBy { it.text("publishedFileId") }
        if (items.isNotEmpty()) {
            val hasNext = regex("""class=["'][^"']*\bpagebtn\b""").containsMatchIn(html) &&
                regex("""(?:&|&amp;)p=${page + 1}(?:[&"'#]|$)""").containsMatchIn(html)
            return result(items, page, hasNext)
        }

        val script = regex("""<script\b(?=[^>]*\sid\s*=\s*["']valve-ssr-data["'])[^>]*>(.*?)</script\s*>""")
            .find(html)?.groupValues?.get(1)
        val context = if (script != null) {
            Json.parseToJsonElement(script).jsonObject["renderContext"]?.jsonObject
        } else {
            val literal = regex("""window\.SSR\.renderContext\s*=\s*JSON\.parse\(\s*("(?:\\.|[^"\\])*")""")
                .find(html)?.groupValues?.get(1)
            literal?.let { Json.parseToJsonElement(Json.parseToJsonElement(it).jsonPrimitive.content).jsonObject }
        }
        if (context != null) {
            val queryData = context["queryData"] ?: error("Steam SSR queryData is missing")
            val queries = (if (queryData is JsonPrimitive) Json.parseToJsonElement(queryData.content) else queryData)
                .jsonObject["queries"]?.jsonArray.orEmpty().map { it.jsonObject }
            val authors = queries.mapNotNull { query ->
                val key = query["queryKey"]?.jsonArray ?: return@mapNotNull null
                if (key.firstOrNull()?.jsonPrimitive?.content != "PlayerLinkDetails") return@mapNotNull null
                val data = query["state"]?.jsonObject?.get("data")?.jsonObject
                key.getOrNull(1)?.jsonPrimitive?.content?.let { it to data?.get("public_data")?.jsonObject?.text("persona_name").orEmpty() }
            }.toMap()
            val browse = queries.mapNotNull { it["state"]?.jsonObject?.get("data") as? JsonObject }
                .firstOrNull { "current_page" in it && "total_pages" in it && "results" in it }
                ?: error("Unrecognized Steam SSR browse response")
            val current = browse.text("current_page").toInt()
            val total = browse.text("total_pages").toInt()
            val ssrItems = browse.getValue("results").jsonArray.map { entry ->
                val item = entry.jsonObject
                val id = item.text("publishedfileid")
                require(id.toULongOrNull()?.let { it > 0uL } == true) { "Steam returned an invalid workshop ID" }
                buildJsonObject {
                    put("publishedFileId", id)
                    put("appId", item.text("consumer_appid").toLongOrNull() ?: appId.toLong())
                    put("title", item.text("title"))
                    put("author", authors[item.text("creator")].orEmpty())
                    put("description", plain(item.text("short_description")).take(1000))
                    put("fileSizeBytes", item.text("file_size").toLongOrNull() ?: 0)
                    put("subscriptions", item.text("subscriptions").toLongOrNull() ?: 0)
                    put("url", itemUrl(id))
                }
            }
            return result(ssrItems.distinctBy { it.text("publishedFileId") }, current, current < total)
        }
        // Do not turn login pages, network portals or changed markup into a successful empty search.
        require(matches.isEmpty() && regex("""workshopBrowseItems|workshopBrowseNoItems|workshopBrowsePagingInfo""").containsMatchIn(html)) {
            "Unrecognized Steam browse page (login, network interception, or changed markup)"
        }
        return result(emptyList(), page, false)
    }

    private fun field(html: String, name: String): String = plain(
        regex("""class=["'][^"']*\b$name\b[^"']*["'][^>]*>(.*?)</div>""").find(html)?.groupValues?.get(1).orEmpty(),
    )

    private fun result(items: List<JsonObject>, page: Int, next: Boolean) = buildJsonObject {
        put("items", JsonArray(items))
        put("page", page)
        put("hasNextPage", next)
        if (next) put("nextPage", page + 1)
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun itemUrl(id: String) = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
internal fun plain(html: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").replace(
    html.replace(Regex("<[^>]*>"), " "),
) { match ->
    val entity = match.groupValues[1]
    when (entity) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
        else -> runCatching {
            String(Character.toChars(if (entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt()))
        }.getOrDefault(match.value)
    }
}.replace(Regex("\\s+"), " ").trim()
