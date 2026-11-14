package io.stamethyst.backend.llm

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewApiModelServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun optionalCatalogLimitsAcceptNumbersStringsAndMissingMetadata() {
        val models = NewApiModelService().parseModels("""{"data":[
            {"id":"a","context_window":64000,"max_input_tokens":60000,"max_output_tokens":"8000"},
            {"id":"b","context_length":32000},
            {"id":"c","unrelated_capability":true}
        ]}""")
        assertEquals(AgentModelLimits(64_000, 60_000, 8_000), models[0].limits)
        assertEquals(AgentModelLimits(contextWindow = 32_000), models[1].limits)
        assertEquals(AgentModelLimits(), models[2].limits)
    }

    @Test
    fun malformedOptionalMetadataDoesNotBreakCatalogOrInventLimits() {
        val models = NewApiModelService().parseModels("""{"data":[
            {"id":"a","context_window":{},"max_input_tokens":-1,"max_output_tokens":true},
            {"id":"b","context_window":1,"context_length":null,"max_output_tokens":"unknown"},
            {"id":"c","context_window":999999999999,"max_input_tokens":2.5,"max_output_tokens":[]}
        ]}""")
        models.forEach { assertEquals(AgentModelLimits(), it.limits) }
    }

    @Test
    fun fetchModels_readsNewApiModelsAndSendsBearerKey() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data":[{"id":"z-model","owned_by":"team"},{"id":"a-model"},{"id":"a-model"}]}""")
                .build(),
        )

        val models = NewApiModelService().fetchModels(server.url("/v1").toString(), "secret")

        assertEquals(listOf("a-model", "z-model"), models.map { it.id })
        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
        assertTrue(request.method == "GET")
    }
}
