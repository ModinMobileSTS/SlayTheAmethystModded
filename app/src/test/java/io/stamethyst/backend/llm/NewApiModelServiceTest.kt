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
