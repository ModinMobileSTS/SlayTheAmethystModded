package io.stamethyst.backend.workshop

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.stamethyst.backend.llm.AgentTool
import io.stamethyst.backend.llm.AgentToolSafety
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

internal const val SEARCH_APP_ID = 646570u

internal data class WorkshopCitation(val item: WorkshopItemSummary, val quote: String, val partial: Boolean)

internal sealed interface WorkshopAnswerBlock {
    data class Text(val value: String) : WorkshopAnswerBlock
    data class Card(val citation: WorkshopCitation) : WorkshopAnswerBlock
}

/** Network operations are injected; the agent never receives raw API payloads or arbitrary URLs. */
internal class WorkshopSearchTools(
    private val browse: suspend (WorkshopBrowseQuery) -> WorkshopBrowseResult,
    private val details: suspend (ULong, WorkshopItemSummary?) -> WorkshopItemDetails,
    private val suppliedIds: Set<ULong>,
) {
    private val candidates = mutableMapOf<ULong, WorkshopItemSummary>()
    private val pages = mutableMapOf<WorkshopBrowseQuery, String>()
    private val sources = mutableMapOf<ULong, WorkshopItemSummary>()
    private val passages = mutableMapOf<ULong, MutableList<String>>()
    private val citations = mutableListOf<WorkshopCitation>()
    private var searches = 0
    private var reads = 0

    val tools: List<AgentTool> = listOf(
        tool("search_workshop", "Find candidates in Slay the Spire's Steam Workshop. Use short names or feature keywords; retry a synonym or English translation if needed. A hit is NOT evidence. Search up to 3 pages.",
            JsonObjectSchema.builder().addStringProperty("query", "One short keyword query, not an entire conversation.")
                .addStringProperty("category", "Optional Steam tag: ${WorkshopModCategory.entries.mapNotNull { it.requiredTag }.joinToString()}.")
                .addStringProperty("sort", "Optional: relevant (default all-time popularity), updated, or subscribers. Check updated_utc in detail for a date constraint.")
                .addIntegerProperty("page", "1 to 3; continue only when has_more is true.")
                .required("query").additionalProperties(false).build(), ::search),
        tool("read_workshop", "Read one candidate, a user-supplied ID, or an ID from an earlier recommendation. Returns a bounded original description passage. Continue at next_offset for additional text. Do not infer missing features.",
            JsonObjectSchema.builder().addStringProperty("id", "Exact published file ID as a string.")
                .addIntegerProperty("offset", "Start at zero; use next_offset for long descriptions.")
                .required("id").additionalProperties(false).build(), ::read),
        tool("cite_workshop", "Create a native mod-card reference from a verified detail. Supply a verbatim substring of an already returned description passage in its original language. Choose partial if any required feature lacks evidence. Use the returned marker in the final answer.",
            JsonObjectSchema.builder().addStringProperty("id", "Previously read published file ID.")
                .addStringProperty("quote", "Original supporting excerpt, 4–400 characters; not a translation.")
                .addStringProperty("match", "matched or partial").required("id", "quote", "match")
                .additionalProperties(false).build(), ::cite),
    )

    private fun tool(name: String, description: String, schema: JsonObjectSchema, action: suspend (JsonObject) -> String) =
        object : AgentTool {
            override val specification: ToolSpecification = ToolSpecification.builder().name(name)
                .description(description).parameters(schema).build()
            override val safety = AgentToolSafety.READ_ONLY
            // Default EXCLUSIVE: search → read → cite share a turn-local evidence ledger.
            override fun execute(arguments: String): String {
                val args = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonObject
                    ?: return errorResult("expected_json_object")
                return runBlocking { action(args) }
            }
        }

    private suspend fun search(args: JsonObject): String {
        val query = args.string("query")?.trim().orEmpty()
        val page = args.integer("page", 1)
        if (query.length !in 1..160 || page !in 1..3) return errorResult("invalid_query_or_page")
        val tag = args.string("category")?.trim().orEmpty()
        val category = if (tag.isEmpty()) WorkshopModCategory.All else WorkshopModCategory.entries
            .firstOrNull { it.requiredTag?.equals(tag, true) == true } ?: return errorResult("unknown_category")
        val sort = when (args.string("sort")) {
            null, "relevant" -> WorkshopBrowseSort.MostPopular
            "updated" -> WorkshopBrowseSort.LastUpdated
            "subscribers" -> WorkshopBrowseSort.MostSubscribed
            else -> return errorResult("unknown_sort")
        }
        val request = WorkshopBrowseQuery(SEARCH_APP_ID, query, sort,
            WorkshopBrowseTimeFilter.AllTime, category, page, 12)
        pages[request]?.let { return it }
        if (++searches > 50) return errorResult("search_budget_exhausted")
        val result = fetch { browse(request) } ?: return errorResult("search_unavailable_not_an_empty_result")
        return buildJsonObject {
            put("page", result.page); put("has_more", result.hasNextPage)
            putJsonArray("candidates") {
                result.items.asSequence().filter { it.appId == SEARCH_APP_ID && it.publishedFileId > 0uL }
                    .distinctBy { it.publishedFileId }.take(12).forEach { item ->
                        candidates[item.publishedFileId] = item
                        addJsonObject {
                            put("id", item.publishedFileId.toString()); put("title", item.title.take(200))
                            put("author", item.authorName.take(100)); put("snippet", item.description.take(240))
                        }
                    }
            }
        }.toString().also { pages[request] = it }
    }

    private suspend fun read(args: JsonObject): String {
        val id = args.string("id")?.toULongOrNull() ?: return errorResult("invalid_id")
        if (id !in candidates && id !in suppliedIds) return errorResult("unseen_id_search_first")
        val offset = args.integer("offset", 0)
        if (offset < 0) return errorResult("invalid_offset")
        val item = sources[id] ?: run {
            if (++reads > 8) return errorResult("detail_budget_exhausted")
            val detail = fetch { details(id, candidates[id]) } ?: return errorResult("detail_request_failed_not_evidence")
            if (!isSearchableDetail(id, detail)) return errorResult("not_a_verified_mod_of_this_game")
            if (detail.fullDescriptionUnavailable || detail.summary.description.isBlank()) return errorResult("description_unavailable")
            detail.summary.also { sources[id] = it }
        }
        val description = item.description
        if (offset >= description.length) return errorResult("invalid_offset")
        val passage = description.substring(offset, (offset + 3200L).coerceAtMost(description.length.toLong()).toInt())
        val shown = passages.getOrPut(id) { mutableListOf() }
        if (passage !in shown) {
            if (shown.size >= 5) return errorResult("passage_budget_exhausted")
            shown += passage
        }
        return buildJsonObject {
            put("id", id.toString()); put("title", item.title.take(200)); put("author", item.authorName.take(100))
            if (item.updatedAtMillis > 0) put("updated_utc", java.time.Instant.ofEpochMilli(item.updatedAtMillis).toString())
            put("description_passage", passage)
            if (offset + passage.length < description.length) put("next_offset", offset + passage.length)
        }.toString()
    }

    private fun cite(args: JsonObject): String {
        val id = args.string("id")?.toULongOrNull() ?: return errorResult("invalid_id")
        val item = sources[id] ?: return errorResult("read_detail_first")
        val quote = args.string("quote")?.trim().orEmpty()
        if (quote.length !in 4..400 || passages[id].orEmpty().none { quote in it }) return errorResult("quote_not_in_read_passage")
        val partial = when (args.string("match")) {
            "matched" -> false
            "partial" -> true
            else -> return errorResult("invalid_match")
        }
        val citation = WorkshopCitation(item, quote, partial)
        val existing = citations.indexOf(citation)
        if (existing < 0 && citations.size >= 6) return errorResult("card_budget_exhausted")
        val index = if (existing >= 0) existing else citations.size.also { citations += citation }
        return buildJsonObject { put("marker", "[[mod:${index + 1}]]") }.toString()
    }

    fun answer(text: String): List<WorkshopAnswerBlock> = buildList {
        var pos = 0
        val used = mutableSetOf<ULong>()
        fun addText(s: String) { if (s.isNotBlank()) add(WorkshopAnswerBlock.Text(s.trim())) }
        for (match in CARD.findAll(text)) {
            addText(text.substring(pos, match.range.first))
            match.groupValues[1].toIntOrNull()?.takeIf { it in 1..citations.size }?.let {
                val card = citations[it - 1]
                if (used.add(card.item.publishedFileId)) add(WorkshopAnswerBlock.Card(card))
            }
            pos = match.range.last + 1
        }
        addText(text.substring(pos))
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.integer(key: String, default: Int) = if (key !in this) default else (get(key) as? JsonPrimitive)?.intOrNull ?: -1
    private fun errorResult(reason: String) = buildJsonObject { put("error", reason) }.toString()

    // A transport exception may contain request headers/URLs. Never forward its diagnostic to a model.
    private suspend fun <T> fetch(load: suspend () -> T): T? = try { load() }
        catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
        catch (interrupted: InterruptedException) { throw interrupted }
        catch (_: Exception) { null }

    companion object {
        fun forService(service: WorkshopService, suppliedIds: Set<ULong>): WorkshopSearchTools =
            WorkshopSearchTools(
                service::browse,
                { id, fallback -> service.getDetails(SEARCH_APP_ID, id, fallback, includeDependencyData = false) },
                suppliedIds,
            )

        private val CARD = Regex("""\[\[mod:([^\]\r\n]*)]]""")
        // getDetails builds summary IDs from the *request*. Verify the actual response before trusting it.
        internal fun isSearchableDetail(id: ULong, detail: WorkshopItemDetails): Boolean {
            if (detail.summary.publishedFileId != id || detail.summary.appId != SEARCH_APP_ID) return false
            val raw = runCatching { Json.parseToJsonElement(detail.jsonMetadata).jsonObject["response"]?.jsonObject
                ?.get("publishedfiledetails")?.jsonArray?.singleOrNull()?.jsonObject }.getOrNull() ?: return false
            fun field(name: String) = (raw[name] as? JsonPrimitive)?.contentOrNull
            val type = field("file_type") ?: field("filetype")
            return field("result") == "1" && field("publishedfileid") == id.toString() &&
                field("consumer_app_id") == SEARCH_APP_ID.toString() && !field("title").isNullOrBlank() &&
                (type == null || type == "0") &&
                (!detail.fileUrl.isNullOrBlank() || (detail.hcontentFile ?: 0uL) > 0uL)
        }
    }
}
