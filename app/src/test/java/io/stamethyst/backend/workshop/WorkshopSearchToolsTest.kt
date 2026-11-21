package io.stamethyst.backend.workshop

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import io.stamethyst.backend.llm.AgentToolRegistry
import io.stamethyst.backend.llm.PolicyGatedAgentGateway
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkshopSearchToolsTest {
    private val quote = "Right click on map nodes to highlight them."
    private val item = WorkshopItemSummary(123uL, SEARCH_APP_ID, "Paths", "", quote)
    private val metadata = """{"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"123","consumer_app_id":646570,"file_type":0,"title":"Paths"}]}}"""
    private val detail = WorkshopItemDetails(item, hcontentFile = 456uL, jsonMetadata = metadata)
    private fun tools(
        read: WorkshopItemDetails = detail, supplied: Set<ULong> = emptySet(),
        browse: suspend (WorkshopBrowseQuery) -> WorkshopBrowseResult = {
            WorkshopBrowseResult(listOf(item, item, item.copy(appId = 2u)), 3, 1, 12, false)
        },
    ) = WorkshopSearchTools(browse, { _, _ -> read }, supplied)
    private fun WorkshopSearchTools.call(name: String, args: String) =
        Json.parseToJsonElement(tools.single { it.specification.name() == name }.execute(args)).jsonObject
    private val search = """{"query":"map","category":"Qol","page":1}"""
    private val read = """{"id":"123"}"""
    private fun cite(s: String) = buildJsonObject { put("id", "123"); put("quote", s); put("match", "partial") }.toString()

    @Test fun gatewaySearchReadCiteProducesOrderedGroundedReplyWithPreviousTurn() {
        val lookup = tools()
        val registry = AgentToolRegistry(lookup.tools)
        val calls = listOf("search_workshop" to search, "read_workshop" to read, "cite_workshop" to cite(quote))
        var round = 0
        val model = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                assertEquals(calls.map { it.first }.toSet(), request.toolSpecifications().map { it.name() }.toSet())
                assertEquals(UserMessage.from("An earlier question"), request.messages()[1])
                if (round == 3) assertTrue(request.messages().last().toString().contains("[[mod:1]]"))
                val message = calls.getOrNull(round++)?.let { (name, args) ->
                    AiMessage.builder().toolExecutionRequests(listOf(ToolExecutionRequest.builder().id(name).name(name).arguments(args).build())).build()
                } ?: AiMessage.from("Touch is unconfirmed.\n[[mod:1]]\nOnly right click is documented.")
                return ChatResponse.builder().aiMessage(message).build()
            }
        }
        val reply = PolicyGatedAgentGateway(model, registry, previousTurns = listOf(UserMessage.from("An earlier question"),
            AiMessage.from("Which feature matters?"))).respond("Workshop assistant", "Does touch work?")
        val blocks = lookup.answer(reply.text)
        assertEquals(4, round)
        assertEquals(listOf(WorkshopAnswerBlock.Text("Touch is unconfirmed."),
            WorkshopAnswerBlock.Card(WorkshopCitation(item, quote, true)),
            WorkshopAnswerBlock.Text("Only right click is documented.")), blocks)
    }

    @Test fun discoveryIsNotEvidenceAndQuotesMustBeInTheReadPassage() {
        val long = tools(detail.copy(summary = item.copy(description = "x".repeat(3200) + quote)))
        assertNotNull(long.call("read_workshop", read)["error"])
        assertEquals(1, long.call("search_workshop", search)["candidates"]!!.jsonArray.size)
        assertNotNull(long.call("cite_workshop", cite(quote))["error"])
        assertEquals(3200, long.call("read_workshop", read)["next_offset"]!!.jsonPrimitive.int)
        assertNotNull(long.call("cite_workshop", cite(quote))["error"])
        long.call("read_workshop", """{"id":"123","offset":3200}""")
        assertNotNull(long.call("cite_workshop", cite("Touch is supported."))["error"])
        assertNotNull(long.call("cite_workshop", cite(quote))["marker"])
        assertEquals(1, long.answer("[[mod:1]] [[mod:1]] [[mod:999]]").size)
        assertTrue(tools().answer("[[mod:1]]").isEmpty())
    }

    @Test fun requireSuccessfulOriginalMetadataNotRequestLabelsOrCollectionOrSnippet() {
        val bad = listOf(
            detail.copy(jsonMetadata = metadata.replace("646570", "1")),
            detail.copy(jsonMetadata = metadata.replace("\"123\"", "\"321\"")),
            detail.copy(jsonMetadata = metadata.replace("\"result\":1", "\"result\":9")),
            detail.copy(jsonMetadata = metadata.replace("\"file_type\":0", "\"file_type\":2")),
            detail.copy(hcontentFile = null), detail.copy(jsonMetadata = ""),
            detail.copy(fullDescriptionUnavailable = true), detail.copy(summary = item.copy(description = "")),
        )
        bad.forEach { d ->
            val lookup = tools(d, supplied = setOf(item.publishedFileId))
            assertNotNull(lookup.call("read_workshop", read)["error"])
            assertNotNull(lookup.call("cite_workshop", cite(quote))["error"])
        }
    }

    @Test fun boundedRetrievalUsesSteamFiltersAndCachesDuplicateRequests() {
        val seen = mutableListOf<WorkshopBrowseQuery>()
        val lookup = tools { seen += it; WorkshopBrowseResult(emptyList(), 0, it.page, it.pageSize, false) }
        assertNotNull(lookup.call("search_workshop", """{"query":"map","page":4}""")["error"])
        assertNotNull(lookup.call("search_workshop", """{"query":"map","category":"Android"}""")["error"])
        repeat(2) { assertEquals(0, lookup.call("search_workshop", search)["candidates"]!!.jsonArray.size) }
        assertEquals(WorkshopBrowseQuery(SEARCH_APP_ID, "map", WorkshopBrowseSort.MostPopular,
            WorkshopBrowseTimeFilter.AllTime, WorkshopModCategory.Qol, 1, 12), seen.single())
        repeat(5) { lookup.call("search_workshop", """{"query":"other $it"}""") }
        assertNotNull(lookup.call("search_workshop", """{"query":"one too many"}""")["error"])
        assertEquals(6, seen.size)
    }

    @Test fun retrievalFailureIsNotZeroResultsAndDiagnosticsDoNotReachTheModel() {
        val lookup = tools { throw java.io.IOException("transport details containing a private credential") }
        val result = lookup.call("search_workshop", search)
        assertEquals("search_unavailable_not_an_empty_result", result["error"]?.jsonPrimitive?.content)
        assertNull(result["candidates"])
        assertFalse(result.toString().contains("private credential"))
    }
}
