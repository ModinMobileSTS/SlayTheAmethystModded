package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ToolChoice
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Excluded from ordinary test/check/build tasks. Only :app:aiCompactionAcceptanceTest enables it. */
class AgentCompactionNetworkAcceptanceTest {
    @Test
    fun deepSeekOffSendsAnExplicitThinkingDisable() {
        requireManualRun()
        MockWebServer().use { server ->
            server.start()
            listOf(
                "deepseek-v4-flash" to LlmReasoningEffort.OFF,
                "deepseek/deepseek-v4-flash" to LlmReasoningEffort.OFF,
                "deepseek-v4-flash" to LlmReasoningEffort.HIGH,
                "test-model" to LlmReasoningEffort.OFF,
            ).forEach { (name, effort) ->
                server.enqueue(MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"Memory"},"finish_reason":"stop"}]}""")
                    .build())
                val model = AgentChatModelFactory.create(OpenAiCompatibleModelConfig(
                    baseUrl = server.url("/v1/").toString(),
                    apiKey = "local-test-key",
                    modelName = name,
                    reasoningEffort = effort,
                ))
                model.chat("Summarize the verified facts.")
                val request = JSONObject(server.takeRequest().body!!.utf8())
                if (name.contains("deepseek") && effort == LlmReasoningEffort.OFF) {
                    assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
                } else {
                    assertFalse("Do not change HIGH effort or send DeepSeek fields to other models", request.has("thinking"))
                }
                if (effort == LlmReasoningEffort.HIGH) assertEquals("high", request.getString("reasoning_effort"))
            }
        }
    }

    @Test
    fun summaryCanWaitLongerThanTheLibraryDefaultSixtySeconds() {
        requireManualRun()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                private var firstFragment: String? = null

                @Synchronized
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body!!.utf8()
                    if (firstFragment == null) firstFragment = body
                    // Retried requests for the first fragment must also wait. Otherwise a hidden
                    // 60-second timeout followed by a fast retry could make this test pass falsely.
                    return MockResponse.Builder()
                        .addHeader("Content-Type", "application/json")
                        .headersDelay(if (body == firstFragment) 65 else 0, TimeUnit.SECONDS)
                        .body("""{"choices":[{"index":0,"message":{"role":"assistant","content":"Verified memory: patch-alpha; compilation pending."},"finish_reason":"stop"}]}""")
                        .build()
                }
            }
            server.start()
            val original = AgentContextState(messages = listOf(
                AgentContextMessage(1, "user", "Inspect patch-alpha; do not enable it."),
                AgentContextMessage(2, "assistant", "x".repeat(48_000)),
                AgentContextMessage(3, "user", "What remains to be verified?"),
            ))
            val model = AgentChatModelFactory.create(OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(),
                apiKey = "local-test-key",
                modelName = "test-model",
                requestTimeoutSeconds = 120,
            ))
            val manager = AgentContextManager(original, "system", "", AgentContextBudget(96_000), 4, model, {})
            val start = System.nanoTime()
            manager.prepare(force = true)
            assertEquals(1, manager.state.compactionCount)
            assertEquals(original.messages, manager.state.messages)
            assertTrue(manager.state.summary.isNotBlank())
            assertTrue("The delayed response must actually be consumed", elapsedMs(start) >= 65_000)
            println("Slow summary passed: elapsedMs=${elapsedMs(start)}, configuredTimeoutSeconds=120, firstResponseDelaySeconds=65")
        }
    }

    @Test
    fun liveSummaryPreservesMemoryAndToolProtocolAndCanContinueAfterRestore() {
        requireManualRun()
        val config = liveConfig()
        val realModel = AgentChatModelFactory.create(config)
        var summaryCalls = 0
        val timedModel = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                assertTrue("Summary requests must not expose tools", request.toolSpecifications().isEmpty())
                val start = System.nanoTime()
                val call = ++summaryCalls
                println("Summary fragment $call started; timeoutSeconds=${config.requestTimeoutSeconds}")
                return realModel.chat(request).also {
                    println("Summary fragment $call completed: elapsedMs=${elapsedMs(start)}, finish=${it.finishReason()}, inputTokens=${it.tokenUsage()?.inputTokenCount()}, outputTokens=${it.tokenUsage()?.outputTokenCount()}")
                }
            }
        }
        val facts = "Patch ID: patch-alpha-42. Source path: patch_source/patch-alpha-42/src/main/java/demo/GuardPatch.java. " +
            "Never enable or package this patch. Compilation and smoke testing have NOT been run. "
        val latestRequest = "Inspect the source using read_workspace_file, then reply as a JSON object only with " +
            "patch_id, source_path, compiled (boolean), enabled (boolean). Report verified facts about whether " +
            "compilation or enabling has actually occurred. Do not write, package or enable anything."
        // Some gateways require thinking alongside historical tool calls even when thinking is
        // disabled in the new request. Seed with a real provider message, not fabricated protocol.
        val seedStart = System.nanoTime()
        val seed = realModel.chat(ChatRequest.builder()
            .messages(SystemMessage.from(facts), UserMessage.from(latestRequest))
            .toolSpecifications(ToolSpecification.builder().name("read_workspace_file")
                .description("Inspect the source file without changing anything.")
                .parameters(JsonObjectSchema.builder().additionalProperties(false).build()).build())
            .toolChoice(ToolChoice.REQUIRED)
            .maxOutputTokens(2048)
            .build()).aiMessage()
        val recentCall = seed.toolExecutionRequests().single()
        assertEquals("read_workspace_file", recentCall.name())
        println("Real tool-call history seeded: elapsedMs=${elapsedMs(seedStart)}, hasThinking=${!seed.thinking().isNullOrBlank()}")
        val original = AgentContextState(
            activePatchId = "patch-alpha-42",
            messages = listOf(
                AgentContextMessage(1, "user", facts),
                AgentContextMessage(2, "assistant", calls = listOf(AgentContextToolCall("old-read", "read_workspace_file", "{}"))),
                AgentContextMessage(2, "tool", facts + "Repeated obsolete diagnostic: the source file was read, not compiled.\n".repeat(750) + facts,
                    callId = "old-read", toolName = "read_workspace_file"),
                AgentContextMessage(3, "user", latestRequest),
                AgentContextMessage(4, "assistant", seed.text().orEmpty(),
                    calls = listOf(AgentContextToolCall(recentCall.id(), recentCall.name(), recentCall.arguments())),
                    thinking = seed.thinking()),
                AgentContextMessage(4, "tool", "Verified signature: public void update(). No files were changed.",
                    callId = recentCall.id(), toolName = "read_workspace_file"),
            ),
        )
        val checkpoints = ArrayList<AgentContextState>()
        val manager = AgentContextManager(original, "You are a mod-editing agent. Report verified facts only.", "",
            AgentContextBudget(96_000), 5, timedModel, checkpoints::add)
        val before = manager.requestEstimate()
        val prepared = manager.prepare(force = true)
        val state = manager.state
        assertTrue("The real summarizer must merge multiple fragments", summaryCalls > 1)
        assertEquals(original.messages, state.messages)
        assertEquals(3, state.summarizedCount)
        assertEquals(1, state.compactionCount)
        assertEquals("patch-alpha-42", state.activePatchId)
        assertTrue(state.summary.contains("patch-alpha-42"))
        assertTrue(state.summary.contains("patch_source/patch-alpha-42/src/main/java/demo/GuardPatch.java"))
        val estimatedTokens = requireNotNull(state.estimatedTokens)
        assertTrue(estimatedTokens < before)
        assertTrue(estimatedTokens < manager.budget.inputLimit)
        assertEquals(state, checkpoints.last())
        assertEquals(original.messages[3].text, (prepared[1] as UserMessage).singleText())
        assertEquals(recentCall.id(), (prepared[2] as AiMessage).toolExecutionRequests().single().id())
        assertEquals(seed.thinking(), (prepared[2] as AiMessage).thinking())
        assertEquals(recentCall.id(), (prepared[3] as ToolExecutionResultMessage).id())
        assertTrue((prepared[0] as SystemMessage).text().contains(state.summary))

        val restored = Json.decodeFromString<AgentContextState>(Json.encodeToString(state))
        val restoredManager = AgentContextManager(restored, "You are a mod-editing agent. Report verified facts only.", "",
            AgentContextBudget(96_000), 6, timedModel, {})
        val continued = restoredManager.prepare()
        assertEquals(prepared, continued)
        val start = System.nanoTime()
        val reply = realModel.chat(ChatRequest.builder().messages(continued).maxOutputTokens(1024).build())
        val text = reply.aiMessage().text().orEmpty()
        assertTrue("Provider must accept the compacted tool protocol and return visible text", text.isNotBlank())
        assertFalse(reply.aiMessage().hasToolExecutionRequests())
        assertTrue("Continuation must remember the patch ID", text.contains("patch-alpha-42"))
        assertTrue("Continuation must remember the exact source path", text.contains("patch_source/patch-alpha-42/src/main/java/demo/GuardPatch.java"))
        val factsReply = JSONObject(text.substringAfter('{', "").substringBeforeLast('}').let { "{$it}" })
        assertFalse("Compilation must not be invented", factsReply.getBoolean("compiled"))
        assertFalse("Enabling must not be invented", factsReply.getBoolean("enabled"))
        println("Live compaction passed: model=${config.modelName}, summaryCalls=$summaryCalls, estimatedTokens=$before -> ${state.estimatedTokens}, continuationElapsedMs=${elapsedMs(start)}")
    }

    private fun requireManualRun() = assumeTrue(
        "Run ./gradlew :app:aiCompactionAcceptanceTest explicitly; this test may incur API charges.",
        System.getProperty("sts.runAiCompactionAcceptance") == "true",
    )

    /** Read secrets at runtime only; never put them in Gradle properties, reports or source control. */
    private fun liveConfig(): OpenAiCompatibleModelConfig {
        val home = File(requireNotNull(System.getProperty("user.home")))
        val configHome = System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
        val dataHome = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        val providerId = System.getenv("STS_AI_COMPACTION_PROVIDER") ?: "server"
        val modelId = System.getenv("STS_AI_COMPACTION_MODEL") ?: "deepseek-v4-flash"
        val configFile = System.getenv("STS_AI_COMPACTION_OPENCODE_CONFIG")?.let(::File)
            ?: File(configHome, "opencode/opencode.json")
        val local = if (configFile.isFile) JSONObject(configFile.readText()) else JSONObject()
        val provider = (local.optJSONObject("providers") ?: local.optJSONObject("provider"))
            ?.optJSONObject(providerId) ?: JSONObject()
        val settings = provider.optJSONObject("settings") ?: provider.optJSONObject("options") ?: JSONObject()
        val authFile = File(dataHome, "opencode/auth.json")
        val auth = if (authFile.isFile) JSONObject(authFile.readText()).optJSONObject(providerId) else null
        fun resolve(value: String): String = when {
            value.startsWith("{env:") && value.endsWith("}") -> System.getenv(value.substring(5, value.length - 1)).orEmpty()
            value.startsWith("{file:") && value.endsWith("}") -> File(value.substring(6, value.length - 1).replaceFirst("~/", "${home.path}/")).readText().trim()
            else -> value
        }
        val baseUrl = resolve(System.getenv("STS_AI_COMPACTION_BASE_URL") ?: settings.optString("baseURL"))
        val key = resolve(System.getenv("STS_AI_COMPACTION_API_KEY") ?: settings.optString("apiKey").ifBlank { auth?.optString("key").orEmpty() })
        check(baseUrl.isNotBlank() && key.isNotBlank()) {
            "Configure OpenCode provider '$providerId' or set STS_AI_COMPACTION_BASE_URL and STS_AI_COMPACTION_API_KEY."
        }
        return OpenAiCompatibleModelConfig(
            baseUrl = baseUrl.trimEnd('/'),
            apiKey = key,
            modelName = provider.optJSONObject("models")?.optJSONObject(modelId)?.optString("modelID")?.ifBlank { modelId } ?: modelId,
            requestTimeoutSeconds = System.getenv("STS_AI_COMPACTION_TIMEOUT_SECONDS")?.toInt() ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
            reasoningEffort = LlmReasoningEffort.OFF,
        )
    }

    private fun elapsedMs(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}
