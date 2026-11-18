package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ToolChoice
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata
import io.stamethyst.ui.aimod.buildAiEditorSystemPrompt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real, billable API test. Ordinary test/check/build never runs this class. */
class AgentCacheNetworkAcceptanceTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )

    @Test fun completeEditorToolHistoryRestoreAndCompactionReportMeasuredCacheReuse() {
        assumeTrue("Run :app:aiCacheAcceptanceTest explicitly; this test consumes API quota.",
            System.getProperty("sts.runAiCacheAcceptance") == "true")
        val marker = UUID.randomUUID().toString()
        val config = try {
            OpenCodeLiveTestConfig.load().copy(cacheAffinity = marker,
                reasoningEffort = LlmReasoningEffort.LOW, requestTimeoutSeconds = 120)
        } catch (_: Exception) {
            throw AssertionError("Cannot select the requested cache-test model and its local credential.")
        }
        val fixture = AgentCacheEditorFixture.request(temporary.newFolder())
        val tools = fixture.toolSpecifications()
        val system = (fixture.messages().first() as SystemMessage).text()
        var attempts = 0
        val allResponses = ArrayList<ChatResponse>()
        // Includes summary calls/retries, not merely successful main responses. No unbounded probes.
        fun countedModel(actual: ChatModel) = object : ChatModel {
            override fun doChat(request: ChatRequest): ChatResponse {
                check(++attempts <= 10) { "Live cache workflow exceeded its ten-request safety limit." }
                check(request.parameters().maxOutputTokens() in 1..2048)
                return try { parseAgentThinkingContent(actual.chat(request)).also {
                    allResponses += it
                    println("Editor cache raw attempt=$attempts ${numericUsage(it)}")
                } }
                catch (error: Exception) { throw safeFailure("Full editor workflow request", error) }
            }
        }
        val model = countedModel(AgentChatModelFactory.create(config))
        val summaryModel = countedModel(AgentChatModelFactory.create(config.copy(reasoningEffort = LlmReasoningEffort.OFF)))
        val budget = AgentContextBudget(outputTarget = 2048)
        fun manager(state: AgentContextState) = AgentContextManager(state, system, tools.joinToString("\n"),
            budget, 2, summaryModel, {}, toolSpecifications = tools)
        var context = manager(AgentContextState(messages = listOf(AgentContextMessage(1, "user",
            "Synthetic cache experiment $marker. No real Mod or files exist. Reply only OK; do not call tools."))))
        var previous = emptyList<ChatMessage>()
        var nextId = 3L
        val samples = ArrayList<Pair<String, AgentCacheUsage>>()
        fun input(text: String) {
            context.appendInput(AgentContextMessage(nextId++, "user", text), nextId++)
        }
        fun request(label: String, expectTool: Boolean = false, reset: Boolean = false): ChatResponse {
            val prepared = context.prepare()
            val shared = prepared.zip(previous).takeWhile { (a, b) -> a == b }.size
            if (!reset) assertEquals("Non-compaction request must preserve the entire prior input",
                previous, prepared.take(previous.size))
            val started = System.nanoTime()
            val response = model.chat(ChatRequest.builder().messages(prepared).toolSpecifications(tools)
                .maxOutputTokens(budget.outputReserve).build())
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            context.observe(response, context.requestEstimate())
            val usage = AgentCacheUsage.from(response, context.state.cacheStats.inputExcludesCached)
            samples += label to usage
            // Only numeric/boolean diagnostics. Never print the response, request, endpoint or key.
            val baseline = samples.first().second.input
            val historyRead = if (baseline != null && usage.read != null) (usage.read - baseline).coerceAtLeast(0) else null
            println("Editor cache probe stage=$label input=${usage.input} read=${usage.read} write=${usage.write} " +
                "rate=${context.state.cacheStats.lastRate} historyReadBeyondInitialInput=$historyRead " +
                "sharedMessages=$shared elapsedMs=$elapsed inputExcludesCached=${usage.inputExcludesCached}")
            assertNotNull("Provider must report input tokens for $label", usage.input)
            // Some gateways omit cache fields on cold/miss responses. Keep those unknown, not 0,
            // but require actual statistics on the warm checkpoints used to judge cache reuse.
            if (label in setOf("restored-warm", "tool-result-2", "post-compaction-warm"))
                assertNotNull("Provider must report cache reads for warm checkpoint $label", usage.read)
            assertEquals("Unexpected tool behavior at $label", expectTool, response.aiMessage().hasToolExecutionRequests())
            if (!expectTool) assertTrue("Expected visible output at $label", response.aiMessage().text().orEmpty().isNotBlank())
            context.append(response.aiMessage())
            previous = prepared
            return response
        }
        fun readSource(index: Int) {
            input("Synthetic protocol experiment: call read_agent_workspace_file exactly once with " +
                "path=/source/Synthetic$index.java, offset=1, limit=400. Do not call any other tool. " +
                "The test harness will provide generated source, not a real file.")
            val response = request("tool-call-$index", expectTool = true)
            val calls = response.aiMessage().toolExecutionRequests()
            assertEquals("One bounded synthetic read only", 1, calls.size)
            val call = calls.single()
            assertEquals("Never execute a real editor tool in live cache tests", "read_agent_workspace_file", call.name())
            val result = AgentCacheEditorFixture.toolResult(index)
            assertTrue(result.toByteArray().size <= 51_200)
            context.append(ToolExecutionResultMessage.from(call, result))
            context.setActivePatch("synthetic-patch-$index")
            input("The generated source is complete test data. No edit, compile, package or further read is needed. Reply only OK.")
            request("tool-result-$index")
        }

        request("initial")
        readSource(1)
        val beforeRestore = context.prepare()
        context = manager(Json.decodeFromString(Json.encodeToString(context.state)))
        assertEquals(beforeRestore, context.prepare())
        input("Restored synthetic session; reply only OK without calling tools.")
        request("restored-warm")
        readSource(2)
        val uncompacted = context.prepare()
        val archived = context.state.messages
        context.prepare(manual = true)
        assertEquals("Compaction must preserve every archived record", archived, context.state.messages.take(archived.size))
        assertTrue("Only a fresh active-patch notice may be appended after compaction",
            context.state.messages.drop(archived.size).all { it.patchState == "synthetic-patch-2" })
        assertEquals(1, context.state.compactionCount)
        assertEquals("Summary calls must not enter main-response cache statistics", 6L, context.state.cacheStats.requests)
        val compacted = context.prepare()
        assertEquals(uncompacted.first(), compacted.first())
        assertNotEquals("Compaction must actually replace the historical prefix", uncompacted[1], compacted[1])
        context = manager(Json.decodeFromString(Json.encodeToString(context.state)))
        assertEquals(compacted, context.prepare())
        request("post-compaction", reset = true)
        input("Continue from the checkpoint. Reply only OK, without tools.")
        request("post-compaction-warm")

        val baseline = requireNotNull(samples.first().second.input)
        val historyReads = samples.filter { it.first in setOf("restored-warm", "tool-result-2") }
            .mapNotNull { it.second.read }
        println("Editor cache probe summary model=${config.modelName} tools=${tools.size} attempts=$attempts " +
            "mainResponses=${context.state.cacheStats.requests} reported=${context.state.cacheStats.reportedRequests} " +
            "weightedRate=${context.state.cacheStats.totalRate} initialInput=$baseline " +
            "maxHistoryReadBeyondInitialInput=${historyReads.maxOrNull()?.minus(baseline)?.coerceAtLeast(0)}")
        val allStats = allResponses.fold(AgentCacheStats()) { stats, response ->
            stats.observe(AgentCacheUsage.from(response, stats.inputExcludesCached))
        }
        val mainBounds = AgentCacheProbeBounds.from(samples.map { it.second })
        val allBounds = AgentCacheProbeBounds.from(allResponses.map {
            AgentCacheUsage.from(it, allStats.inputExcludesCached)
        })
        val writeReported = allResponses.count {
            AgentCacheUsage.from(it, allStats.inputExcludesCached).write != null
        }
        println("Editor cache workload bounds mainLower=${mainBounds.lower} mainUpper=${mainBounds.upper} " +
            "includingCompactionLower=${allBounds.lower} includingCompactionUpper=${allBounds.upper}")
        println("Editor cache all completed calls (including compaction): responses=${allStats.requests} " +
            "reported=${allStats.reportedRequests} reportedInput=${allStats.inputTokens} confirmedRead=${allStats.readTokens} " +
            "knownWriteTokens=${allStats.writeTokens} writeReported=$writeReported weightedRate=${allStats.totalRate}")
        // A high rate from static system/tools alone cannot satisfy this assertion.
        assertTrue("Warm requests must cache conversation/tool history beyond the whole initial input",
            historyReads.any { it > baseline })
        assertTrue("Checkpoint continuation must get a real cache read", requireNotNull(samples.last().second.read) > 0)
        assertEquals(8L, context.state.cacheStats.requests)
        assertEquals(samples.count { it.second.input != null && it.second.read != null }.toLong(),
            context.state.cacheStats.reportedRequests)
    }

    @Test fun longPrefixSurvivesToolStateChangesAndRestoreWithHighMeasuredCacheReuse() {
        assumeTrue("Run :app:aiCacheAcceptanceTest explicitly; this test consumes API quota.",
            System.getProperty("sts.runAiCacheAcceptance") == "true")
        // Unique synthetic content: no user conversations, local files or Mod data leave the machine.
        val marker = UUID.randomUUID().toString()
        val config = try { OpenCodeLiveTestConfig.load().copy(cacheAffinity = marker) } catch (_: Exception) {
            throw AssertionError("Cannot read local OpenCode configuration; select a JSON config/provider using STS_AI_CACHE_OPENCODE_CONFIG / STS_AI_CACHE_PROVIDER.")
        }
        val model = AgentChatModelFactory.create(config)
        val fixed = buildAiEditorSystemPrompt("Synthetic", "synthetic") +
            "\nYou are testing prompt cache reuse. Do not call tools. Reply only OK.\n" +
            "Synthetic acceptance run: $marker\n" + (1..400).joinToString("\n") {
                "Reference entry $it: inspect source before editing; never compile, enable or package a patch in this test."
            }
        val tool = ToolSpecification.builder().name("inspect_synthetic_reference")
            .description("Test-only schema. Must not be called.").build()
        var manager = AgentContextManager(AgentContextState(messages = listOf(
            AgentContextMessage(1, "user", "Read the reference and reply OK without calling any tool."),
        )), fixed, tool.toString(), AgentContextBudget(), 2, model, {})
        val registry = AgentToolRegistry(listOf(object : AgentTool {
            override val specification = tool
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String): String = error("No tools should execute in cache acceptance")
        }))
        fun request(label: String): List<dev.langchain4j.data.message.ChatMessage> {
            val prepared = manager.prepare()
            val response = try {
                model.chat(ChatRequest.builder().messages(prepared).toolSpecifications(registry.toolSpecifications())
                    .maxOutputTokens(1024).build())
            } catch (error: Exception) {
                // Do not let HTTP exceptions echo provider configuration or headers into test reports.
                throw safeFailure("Live cache request $label", error)
            }
            assertFalse("Test response must not call tools", response.aiMessage().hasToolExecutionRequests())
            assertTrue("Expected visible output", response.aiMessage().text().orEmpty().isNotBlank())
            manager.observe(response, manager.requestEstimate())
            // Diagnostics contain only whitelisted numeric usage counters, never raw HTTP bodies.
            val rawBody = (response.metadata() as? OpenAiChatResponseMetadata)?.rawHttpResponse()?.body()
            val rawUsage = rawBody?.let { org.json.JSONObject(it).optJSONObject("usage") }
            val safeUsage = listOf("prompt_tokens", "input_tokens", "completion_tokens", "total_tokens",
                "prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "cached_tokens", "cache_read_input_tokens")
                .mapNotNull { key -> (rawUsage?.opt(key) as? Number)?.let { "$key=$it" } }
            val detail = rawUsage?.optJSONObject("prompt_tokens_details")?.opt("cached_tokens") as? Number
            println("Cache counters $label: ${safeUsage.joinToString()}, details.cached_tokens=$detail")
            val stats = manager.state.cacheStats
            println("Cache acceptance $label: input=${stats.lastInputTokens}, cached=${stats.lastReadTokens}, rate=${stats.lastRate}")
            assertNotNull("Provider must report cached input tokens", stats.lastReadTokens)
            manager.append(response.aiMessage())
            return prepared
        }
        val cold = request("cold")
        manager.setActivePatch("synthetic-alpha")
        assertEquals(cold, manager.prepare().take(cold.size))
        request("workspace-created")
        // DeepSeek's disk cache can be populated asynchronously. Probe a bounded number of times.
        val warmRates = ArrayList<Double>()
        repeat(3) { index ->
            if (index > 0) Thread.sleep(2000)
            manager.setActivePatch("synthetic-${index + 1}")
            val beforeRestore = manager.prepare()
            val restored = Json.decodeFromString<AgentContextState>(Json.encodeToString(manager.state))
            manager = AgentContextManager(restored, fixed, tool.toString(), AgentContextBudget(), 3 + index.toLong(), model, {})
            assertEquals(beforeRestore, manager.prepare())
            request("restored-${index + 1}")
            warmRates += requireNotNull(manager.state.cacheStats.lastRate)
        }
        assertTrue("Warm continuation must reach >= 90% cached input; measured=$warmRates", warmRates.max() >= 0.90)
        assertEquals(fixed, (manager.prepare().first() as SystemMessage).text())

        // Exercise actual provider-generated tool IDs/thinking too, not fabricated assistant calls.
        val seed = try {
            model.chat(ChatRequest.builder().messages(manager.prepare())
                .toolSpecifications(registry.toolSpecifications()).toolChoice(ToolChoice.REQUIRED)
                .maxOutputTokens(1024).build())
        } catch (error: Exception) {
            throw safeFailure("Live synthetic tool-call request", error)
        }
        assertTrue("Provider must generate an actual tool call", seed.aiMessage().hasToolExecutionRequests())
        manager.observe(seed, manager.requestEstimate())
        manager.append(seed.aiMessage())
        seed.aiMessage().toolExecutionRequests().forEach { call ->
            assertEquals("inspect_synthetic_reference", call.name())
            manager.append(ToolExecutionResultMessage.from(call, "Synthetic inspection complete. No files changed."))
        }
        manager.setActivePatch("synthetic-after-tool")
        val toolContext = manager.prepare()
        manager = AgentContextManager(Json.decodeFromString(Json.encodeToString(manager.state)), fixed,
            tool.toString(), AgentContextBudget(), 7, model, {})
        assertEquals(toolContext, manager.prepare())
        request("after-real-tool-restore")
        assertTrue("Actual tool history must retain high prefix reuse", manager.state.cacheStats.lastRate!! >= 0.90)

        println("Cache acceptance non-streaming passed: weightedTotal=${manager.state.cacheStats.totalRate}, responses=${manager.state.cacheStats.requests}")
    }

    @Test fun streamingEndpointReportsWarmCacheUsage() {
        assumeTrue("Run :app:aiCacheAcceptanceTest explicitly; this test consumes API quota.",
            System.getProperty("sts.runAiCacheAcceptance") == "true")
        val marker = UUID.randomUUID().toString()
        val config = try { OpenCodeLiveTestConfig.load().copy(cacheAffinity = marker,
            reasoningEffort = LlmReasoningEffort.LOW) } catch (_: Exception) {
            throw AssertionError("Cannot read local OpenCode JSON configuration/API credential.")
        }
        val model = AgentChatModelFactory.create(config)
        val stream = AgentChatModelFactory.createStreaming(config)!!
        val fixture = AgentCacheEditorFixture.request(temporary.newFolder())
        val tools = fixture.toolSpecifications()
        val fixed = (fixture.messages().first() as SystemMessage).text()
        val manager = AgentContextManager(AgentContextState(messages = listOf(AgentContextMessage(1, "user",
            "Synthetic streaming cache run $marker. Reply only OK; do not call tools."))),
            fixed, tools.joinToString("\n"), AgentContextBudget(outputTarget = 1024), 2, model, {},
            toolSpecifications = tools)
        val warm = try {
            model.chat(ChatRequest.builder().messages(manager.prepare()).toolSpecifications(tools).maxOutputTokens(1024).build())
        } catch (error: Exception) { throw safeFailure("Streaming warm-up", error) }
        assertFalse(warm.aiMessage().hasToolExecutionRequests())
        assertTrue(warm.aiMessage().text().orEmpty().isNotBlank())
        manager.observe(warm, manager.requestEstimate())
        manager.append(warm.aiMessage())
        manager.setActivePatch("synthetic-stream")
        manager.appendInput(AgentContextMessage(3, "user", "Continue the synthetic test. Reply only OK, without tools."), 4)
        val result = AtomicReference<ChatResponse?>()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val visible = StringBuffer()
        val started = System.nanoTime()
        val firstTextMs = java.util.concurrent.atomic.AtomicLong(-1)
        // Exactly one streamed request. No non-streaming fallback can make this test pass falsely.
        try {
            stream.chat(ChatRequest.builder().messages(manager.prepare()).toolSpecifications(tools).maxOutputTokens(1024).build(),
                object : StreamingChatResponseHandler {
                    override fun onPartialResponse(partialResponse: String) {
                        if (partialResponse.isNotEmpty()) {
                            firstTextMs.compareAndSet(-1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                            visible.append(partialResponse)
                        }
                    }
                    override fun onCompleteResponse(complete: ChatResponse) { result.set(complete); done.countDown() }
                    override fun onError(error: Throwable) { failure.set(error); done.countDown() }
                })
        } catch (error: Exception) { throw safeFailure("Streaming endpoint", error) }
        assertTrue("Streaming response timed out", done.await(310, TimeUnit.SECONDS))
        failure.get()?.let { throw safeFailure("Streaming endpoint", it) }
        val response = requireNotNull(result.get())
        assertTrue("Streaming endpoint must return visible text", response.aiMessage().text().orEmpty().isNotBlank())
        assertTrue("Streaming must deliver a real text delta, not just a completed response", visible.isNotEmpty())
        manager.observe(response, manager.requestEstimate())
        println("Cache acceptance streaming: input=${manager.state.cacheStats.lastInputTokens}, cached=${manager.state.cacheStats.lastReadTokens}, " +
            "rate=${manager.state.cacheStats.lastRate}, firstTextMs=${firstTextMs.get()}, tools=${tools.size}, model=${config.modelName}")
        assertNotNull("Streaming must retain cache statistics", manager.state.cacheStats.lastReadTokens)
        assertTrue("Streaming must preserve the warm prefix", manager.state.cacheStats.lastRate!! >= 0.90)
    }

    private fun numericUsage(response: ChatResponse): String {
        val body = (response.metadata() as? OpenAiChatResponseMetadata)?.rawHttpResponse()?.body()
        val usage = body?.let { org.json.JSONObject(it).optJSONObject("usage") }
        val counters = listOf("prompt_tokens", "input_tokens", "completion_tokens", "total_tokens",
            "prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
            .mapNotNull { key -> (usage?.opt(key) as? Number)?.let { "$key=$it" } }
        val details = usage?.optJSONObject("prompt_tokens_details") ?: usage?.optJSONObject("input_tokens_details")
        return (counters + listOf("cached_tokens", "cache_write_tokens").mapNotNull { key ->
            (details?.opt(key) as? Number)?.let { "details.$key=$it" }
        }).joinToString()
    }

    private fun safeFailure(stage: String, error: Throwable): AssertionError {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val types = chain.joinToString(" -> ") { it.javaClass.simpleName }
        val status = chain.filterIsInstance<dev.langchain4j.exception.HttpException>().firstOrNull()?.statusCode()
        val reason = when {
            chain.any { it.message.orEmpty().contains("reasoning_content", ignoreCase = true) } -> "reasoning-field-protocol"
            chain.any { it.message.orEmpty().contains("tool", ignoreCase = true) } -> "tool-protocol"
            chain.any { it.message.orEmpty().contains("timeout", ignoreCase = true) } -> "timeout"
            else -> "unspecified"
        }
        return AssertionError("$stage failed ($types; status=$status; category=$reason); endpoint/credentials/body omitted.")
    }
}
