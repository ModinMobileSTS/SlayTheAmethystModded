package io.stamethyst.backend.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Structural/offline evidence, not a simulation claiming to measure provider cache-hit rates. */
class AgentCacheWorkflowTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )

    @Test fun completeEditorSchemasAndLargeToolHistorySurviveGatewayAndRestoreOnTheWire() {
        val fixture = AgentCacheEditorFixture.request(temporary.newFolder())
        val specs = fixture.toolSpecifications()
        assertTrue("Use the full current editor, not a single synthetic schema", specs.size >= 25)
        assertEquals(specs.map { it.name() }.sorted(), specs.map { it.name() })
        val outputs = (1..3).associateWith(AgentCacheEditorFixture::toolResult)
        outputs.values.forEach { assertTrue(it.toByteArray().size <= 51_200) }
        var executions = 0
        val registry = AgentToolRegistry(specs.map { spec -> object : AgentTool {
            override val specification = spec
            override val safety = AgentToolSafety.READ_ONLY
            override val concurrency = AgentToolConcurrency.PARALLEL
            override fun execute(arguments: String): String {
                check(spec.name() == "read_agent_workspace_file") { "Unexpected fixture tool" }
                synchronized(outputs) { executions++ }
                return outputs.getValue(JSONObject(arguments).getInt("offset"))
            }
        } })
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"role":"assistant","content":"","reasoning_content":"Inspect synthetic sources.","tool_calls":[${call("read-1", 1)},${call("read-2", 2)}]}"""))
            server.enqueue(response("""{"role":"assistant","content":"","reasoning_content":"Read one more page.","tool_calls":[${call("read-3", 3)}]}"""))
            server.enqueue(response("""{"role":"assistant","content":"OK"}"""))
            server.enqueue(response("""{"role":"assistant","content":"Restored OK"}"""))
            val model = AgentChatModelFactory.create(OpenAiCompatibleModelConfig(
                server.url("/v1/").toString(), "synthetic-key", "deepseek-v4-flash", cacheAffinity = "stable-session",
            ))
            var manager = manager(fixture, AgentContextState(messages = listOf(
                AgentContextMessage(1, "user", "Read the synthetic sources."),
            )), model)
            assertEquals("OK", PolicyGatedAgentGateway(model, registry, contextManager = manager).respond("", "").text)
            assertEquals(3, executions)
            assertEquals(0, manager.state.compactionCount)
            assertTrue("History must outweigh the fixed prompt so lost history cannot hide behind it",
                manager.state.messages.sumOf { AgentTokenEstimate.message(it) } >
                    AgentTokenEstimate.text((fixture.messages().first() as SystemMessage).text()) +
                    AgentTokenEstimate.text(specs.joinToString("\n")))

            val before = manager.prepare()
            manager = manager(fixture, Json.decodeFromString(Json.encodeToString(manager.state)), model)
            assertEquals(before, manager.prepare())
            manager.appendInput(AgentContextMessage(3, "user", "Continue without reading again."), 4)
            assertEquals("Restored OK", PolicyGatedAgentGateway(model, registry, contextManager = manager).respond("", "").text)
            assertEquals("Restoring must not execute old tools again", 3, executions)

            val requests = (1..4).map {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("stable-session", request.headers["x-session-affinity"])
                JSONObject(request.body!!.utf8())
            }
            requests.zipWithNext().forEach { (old, next) ->
                assertEquals(old.getJSONArray("tools").toString(), next.getJSONArray("tools").toString())
                assertPrefix(old, next)
            }
            val restored = requests.last().getJSONArray("messages")
            val assistant = (0 until restored.length()).map { restored.getJSONObject(it) }
                .first { it.optString("role") == "assistant" }
            assertEquals("Inspect synthetic sources.", assistant.getString("reasoning_content"))
            assertEquals(listOf("read-1", "read-2", "read-3"),
                manager.state.messages.filter { it.role == "tool" }.map { it.callId })
            assertEquals(4L, manager.state.cacheStats.requests)
            assertNull("Missing provider statistics must remain unknown", manager.state.cacheStats.totalRate)
        }
    }

    @Test fun compactionResetsOnlyTheHistoryPrefixAndRestoredCheckpointThenGrowsAppendOnly() {
        val fixture = AgentCacheEditorFixture.request(temporary.newFolder())
        val original = AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Inspect synthetic source."),
            AgentContextMessage(2, "assistant", "Evidence:\n${AgentCacheEditorFixture.toolResult(1)}"),
            AgentContextMessage(3, "user", "Keep patch-alpha disabled."),
            AgentContextMessage(4, "assistant", "Understood."),
        ), activePatchId = "patch-alpha")
        var summaryCalls = 0
        val summaryModel = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                summaryCalls++
                assertEquals(fixture.toolSpecifications(), request.toolSpecifications())
                return ChatResponse.builder().aiMessage(AiMessage.from("## Objective\nKeep patch-alpha disabled.")).build()
            }
        }
        var manager = manager(fixture, original, summaryModel)
        val before = manager.prepare()
        val archived = manager.state.messages
        val compacted = manager.prepare(manual = true)
        assertEquals(1, summaryCalls)
        assertEquals(before.first(), compacted.first())
        assertNotEquals(before[1], compacted[1])
        assertTrue(compacted.filterIsInstance<UserMessage>().any { it.singleText().contains("<conversation-checkpoint>") })
        assertEquals(archived, manager.state.messages.take(archived.size))
        assertEquals(listOf("patch-alpha"), manager.state.messages.drop(archived.size).map { it.patchState })
        assertEquals("Summary usage is not a completed main-agent response", 0L, manager.state.cacheStats.requests)
        manager = manager(fixture, Json.decodeFromString(Json.encodeToString(manager.state)), summaryModel)
        assertEquals(compacted, manager.prepare())
        manager.appendInput(AgentContextMessage(5, "user", "Continue."), 6)
        assertEquals(compacted, manager.prepare().take(compacted.size))
        assertEquals(1, summaryCalls)
        assertEquals(1, manager.state.compactionCount)
    }

    @Test fun fullSchemaFixtureNeverOpensItsParentJarAndContainsNoLocalPaths() {
        val root = temporary.newFolder()
        val request = AgentCacheEditorFixture.request(root)
        val system = (request.messages().first() as SystemMessage).text()
        assertFalse(system.contains(root.absolutePath))
        assertFalse(File(root, "never-opened.jar").exists())
        assertTrue(request.toolSpecifications().any { it.name() == "edit_agent_workspace_file" })
        assertTrue(request.toolSpecifications().any { it.name() == "smoke_test_agent_patch_mod" })
    }

    private fun manager(fixture: ChatRequest, state: AgentContextState, model: ChatModel) =
        AgentContextManager(state, (fixture.messages().first() as SystemMessage).text(),
            fixture.toolSpecifications().joinToString("\n"), AgentContextBudget(outputTarget = 1024), 2, model, {},
            toolSpecifications = fixture.toolSpecifications())

    private fun assertPrefix(old: JSONObject, next: JSONObject) {
        val a = old.getJSONArray("messages")
        val b = next.getJSONArray("messages")
        assertTrue(b.length() > a.length())
        (0 until a.length()).forEach { index ->
            assertEquals("Historical wire message $index changed", a.getJSONObject(index).toString(), b.getJSONObject(index).toString())
        }
    }

    private fun call(id: String, offset: Int) =
        """{"id":"$id","type":"function","function":{"name":"read_agent_workspace_file","arguments":"{\"path\":\"/source/Synthetic.java\",\"offset\":\"$offset\"}"}}"""

    private fun response(message: String) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body("""{"choices":[{"index":0,"message":$message,"finish_reason":"stop"}]}""").build()
}
