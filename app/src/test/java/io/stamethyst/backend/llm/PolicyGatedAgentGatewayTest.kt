package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.stamethyst.backend.mods.AgentPatchWorkspace
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class PolicyGatedAgentGatewayTest {
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
    fun respond_executesOnlyWhitelistedReadOnlyToolAndReturnsFinalAnswer() {
        server.enqueue(toolCallResponse())
        server.enqueue(finalResponse("The workspace note was read."))
        val workspace = createTempDirectory("agent-workspace").toFile().apply {
            resolve("note.txt").writeText("artifact validation passed")
        }

        val reply = newGateway(workspace).respond(
            systemPrompt = "Use tools only for workspace inspection.",
            userPrompt = "Inspect the note.",
        )

        assertEquals("The workspace note was read.", reply.text)
        assertEquals(1, reply.toolRounds)

        val firstHttpRequest = server.takeRequest()
        assertEquals("POST", firstHttpRequest.method)
        val firstRequest = Json.parseToJsonElement(requireNotNull(firstHttpRequest.body).utf8()).jsonObject
        assertTrue(firstRequest["tools"]!!.jsonArray.isNotEmpty())
        assertEquals(
            "read_workspace_file",
            firstRequest["tools"]!!.jsonArray[0].jsonObject["function"]!!
                .jsonObject["name"]!!.jsonPrimitive.content,
        )

        val secondRequest = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        val toolMessage = secondRequest["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertTrue(toolMessage["content"]!!.jsonPrimitive.content.contains("artifact validation passed"))
    }

    @Test
    fun respond_allowsLongerWorkspaceInspectionBeforeApplyingRoundLimit() {
        repeat(9) { server.enqueue(toolCallResponse()) }
        server.enqueue(finalResponse("Inspection complete."))
        val workspace = createTempDirectory("agent-workspace").toFile().apply {
            resolve("note.txt").writeText("artifact validation passed")
        }

        val reply = newGateway(workspace).respond(
            systemPrompt = "Inspect the workspace before answering.",
            userPrompt = "Inspect the note thoroughly.",
        )

        assertEquals("Inspection complete.", reply.text)
        assertEquals(9, reply.toolRounds)
    }

    @Test
    fun respond_throwsOnEmptyFinalResponseInsteadOfReturningBlankSuccess() {
        // One retry is allowed, so it takes two empty responses to surface the failure.
        server.enqueue(finalResponse(""))
        server.enqueue(finalResponse(""))

        val failure = runCatching {
            newGateway(createTempDirectory("agent-workspace").toFile()).respond(
                systemPrompt = "Answer the user.",
                userPrompt = "Do the thing.",
            )
        }.exceptionOrNull()

        assertTrue("expected AgentEmptyResponseException, got $failure", failure is AgentEmptyResponseException)
    }

    @Test
    fun respond_returnsNonBlankFinalAnswerWhenToolsRanEarlier() {
        server.enqueue(toolCallResponse())
        server.enqueue(finalResponse("Done, the note was inspected."))

        val reply = newGateway(createTempDirectory("agent-workspace").toFile().apply {
            resolve("note.txt").writeText("ok")
        }).respond(systemPrompt = "Inspect.", userPrompt = "Inspect the note.")

        assertEquals("Done, the note was inspected.", reply.text)
    }

    @Test
    fun registry_notifiesToolExecutionForBothStartAndCompletion() {
        val workspace = createTempDirectory("agent-workspace-registry").toFile().apply {
            resolve("note.txt").writeText("hello")
        }
        val events = mutableListOf<AgentToolExecutionEvent>()
        val registry = AgentToolRegistry(
            tools = listOf(WorkspaceTextFileTool(workspace)),
            policyGate = AgentToolPolicyGate(setOf(AgentToolSafety.READ_ONLY)),
            onToolExecution = { events += it },
        )

        registry.execute(
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("call-1")
                .name("read_workspace_file")
                .arguments("""{"path":"note.txt"}""")
                .build(),
        )

        assertEquals(2, events.size)
        assertEquals("read_workspace_file", events[0].name)
        assertTrue("first event must be the start", events[0].result == null)
        assertNotNull("second event must carry the result", events[1].result)
        assertTrue(events[1].result!!.contains("hello"))
        assertTrue(!events[1].failed)
    }

    @Test
    fun registry_reportsFailureEventForUnknownTool() {
        val events = mutableListOf<AgentToolExecutionEvent>()
        val registry = AgentToolRegistry(tools = emptyList(), onToolExecution = { events += it })

        registry.execute(
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("call-x")
                .name("missing_tool")
                .arguments("{}")
                .build(),
        )

        assertEquals(2, events.size)
        assertTrue(events[1].failed)
    }

    @Test
    fun workspaceTool_rejectsPathOutsideWorkspace() {
        val workspace = createTempDirectory("agent-workspace").toFile()
        val result = WorkspaceTextFileTool(workspace).execute("""{"path":"../secret.txt"}""")

        assertTrue(result.contains("path_outside_workspace"))
    }

    @Test
    fun registry_doesNotAdvertiseOrExecuteApprovalTool() {
        val registry = AgentToolRegistry(listOf(ApprovalTool()))

        assertTrue(registry.toolSpecifications().isEmpty())
        assertTrue(registry.execute(requestName = "write_workspace_file").contains("approval_required"))
    }

    @Test
    fun deleteTool_isAdvertisedOnlyWhenDeletePermissionIsGranted() {
        val tool = AgentPatchModDeleteTool(
            context = object : android.content.ContextWrapper(android.app.Application()) {},
            parentModId = "parent",
        )
        val denied = AgentToolRegistry(
            tools = listOf(tool),
            policyGate = AgentToolPolicyGate(setOf(AgentToolSafety.READ_ONLY)),
        )
        val granted = AgentToolRegistry(
            tools = listOf(tool),
            policyGate = AgentToolPolicyGate(setOf(AgentToolSafety.PATCH_DELETE)),
        )

        assertEquals("delete_agent_patch_mod", tool.specification.name())
        assertEquals(AgentToolSafety.PATCH_DELETE, tool.safety)
        assertTrue(denied.toolSpecifications().isEmpty())
        assertEquals(listOf("delete_agent_patch_mod"), granted.toolSpecifications().map { it.name() })
    }

    @Test
    fun createPatchModTool_usesAgentChosenNameAndReusesExistingWorkspace() {
        val root = createTempDirectory("agent-patch-create").toFile()
        val context = testContext(root)
        val source = sourceJar(root)
        var created: AgentPatchWorkspace? = null
        val tool = AgentPatchModCreateTool(
            context = context,
            parentModId = "parent",
            sourceJar = source,
            currentWorkspace = { created },
            onCreated = { created = it },
        )

        assertEquals(AgentToolSafety.PATCH_CREATE, tool.safety)
        val first = Json.parseToJsonElement(
            tool.execute("""{"name":"Rare card tweak","version":"1.2.0","description":"Buff a card."}"""),
        ).jsonObject
        assertEquals("created", first["status"]!!.jsonPrimitive.content)
        val workspace = created
        assertNotNull(workspace)
        val manifest = JSONObject(workspace!!.patchRoot.resolve("ModTheSpire.json").readText())
        assertEquals("Rare card tweak", manifest.getString("name"))
        assertEquals("1.2.0", manifest.getString("version"))
        assertEquals("Buff a card.", manifest.getString("description"))
        assertEquals("parent", manifest.getJSONArray("dependencies").getString(0))

        val second = Json.parseToJsonElement(tool.execute("""{"name":"Another name"}""")).jsonObject
        assertEquals("exists", second["status"]!!.jsonPrimitive.content)
        assertEquals(workspace.patchId, created!!.patchId)
    }

    @Test
    fun writeTool_canCreateFilesAnywhereInsideWorkspaceWithoutPatchRevision() {
        val root = createTempDirectory("agent-patch-write").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val tool = AgentWorkspaceWriteTool(workspaceRoot)

        val result = Json.parseToJsonElement(
            tool.execute("""{"path":"inspection/old/source/Notes.java","content":"class Notes {}"}"""),
        ).jsonObject

        assertEquals("inspection/old/source/Notes.java", result["path"]!!.jsonPrimitive.content)
        assertTrue(File(workspaceRoot, "inspection/old/source/Notes.java").isFile)
        assertTrue(tool.execute("""{"path":"../secret.txt","content":"secret"}""").contains("path_outside_workspace"))
    }

    @Test
    fun deleteTool_removesFilesFromPreviousRevisionsAndInspectionTrees() {
        val root = createTempDirectory("agent-patch-delete").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val sourceFile = File(workspaceRoot, "inspection/old/source/original.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }
        val staleFile = File(workspaceRoot, "patch-old/patch/data/stale.txt").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val result = Json.parseToJsonElement(
            tool.execute("""{"path":"patch-old/patch/data/stale.txt"}"""),
        ).jsonObject
        assertEquals("file", result["type"]!!.jsonPrimitive.content)
        assertTrue(!staleFile.exists())

        val sourceResult = Json.parseToJsonElement(
            tool.execute("""{"path":"inspection/old/source/original.txt"}"""),
        ).jsonObject
        assertEquals("file", sourceResult["type"]!!.jsonPrimitive.content)
        assertTrue(!sourceFile.exists())
    }

    @Test
    fun deleteTool_removesNonEmptyDirectoryOnlyWhenRecursive() {
        val root = createTempDirectory("agent-patch-delete-dir").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val directory = File(workspaceRoot, "inspection/old/assets").apply { mkdirs() }
        File(directory, "a.txt").writeText("a")
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val notRecursive = tool.execute("""{"path":"inspection/old/assets"}""")
        assertTrue(notRecursive.contains("delete_failed") || directory.exists())

        val recursive = Json.parseToJsonElement(
            tool.execute("""{"path":"inspection/old/assets","recursive":true}"""),
        ).jsonObject
        assertEquals("directory", recursive["type"]!!.jsonPrimitive.content)
        assertEquals(1, recursive["files_deleted"]!!.jsonPrimitive.content.toInt())
        assertTrue(!directory.exists())
    }

    @Test
    fun deleteTool_refusesToDeleteWorkspaceRoot() {
        val root = createTempDirectory("agent-patch-delete-root").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val result = tool.execute("""{"path":".","recursive":true}""")

        assertTrue(result.contains("cannot_delete_workspace_root"))
        assertTrue(workspaceRoot.exists())
    }

    @Test
    fun respond_retriesOnceWhenModelReturnsEmptyThenSucceeds() {
        server.enqueue(finalResponse(""))
        server.enqueue(finalResponse("Recovered answer."))

        val reply = newGateway(createTempDirectory("agent-workspace-retry").toFile()).respond(
            systemPrompt = "Answer the user.",
            userPrompt = "Do the thing.",
        )

        assertEquals("Recovered answer.", reply.text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun respond_capsOversizedToolResultBeforeSendingItBackToTheModel() {
        val payload = "x".repeat(100_000)
        server.enqueue(toolCallResponse(toolName = "huge_tool"))
        server.enqueue(finalResponse("done"))
        val workspace = createTempDirectory("agent-workspace-cap").toFile()
        val tools = listOf(HugeResultTool(payload))

        newGateway(workspace, tools).respond(systemPrompt = "Use the tool.", userPrompt = "Go.")

        server.takeRequest()
        val secondRequest = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        val toolMessage = secondRequest["messages"]!!.jsonArray.last().jsonObject["content"]!!
            .jsonPrimitive.content
        assertTrue("tool result must be capped, was ${toolMessage.length}", toolMessage.length < 25_000)
        assertTrue("truncation must be signalled", toolMessage.contains("truncated"))
    }

    @Test
    fun listTool_boundsLargeListingsAndSupportsScoping() {
        val root = createTempDirectory("agent-workspace-list").toFile()
        val scoped = File(root, "patch/one").apply { mkdirs() }
        repeat(20) { File(scoped, "file$it.java").writeText("x") }
        val tool = AgentWorkspaceListTool(root, maxEntries = 5)

        val scopedResult = Json.parseToJsonElement(
            tool.execute("""{"path":"patch/one"}"""),
        ).jsonObject
        assertEquals(20, scopedResult["file_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, scopedResult["returned"]!!.jsonPrimitive.content.toInt())
        assertTrue(scopedResult["truncated"]!!.jsonPrimitive.content.toBoolean())

        val badPath = tool.execute("""{"path":"../outside"}""")
        assertTrue(badPath.contains("path_outside_workspace"))
    }

    @Test
    fun readTool_returnsLargeFilesInChunks() {
        val root = createTempDirectory("agent-workspace-chunk").toFile()
        val content = "abcdefghij".repeat(100)
        File(root, "big.txt").writeText(content)
        val tool = AgentWorkspaceFileTool(root, maxBytes = 64)

        val first = Json.parseToJsonElement(tool.execute("""{"path":"big.txt"}""")).jsonObject
        assertTrue(first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(64, first["returned_bytes"]!!.jsonPrimitive.content.toInt())
        val nextOffset = first["next_offset"]!!.jsonPrimitive.content.toInt()

        val second = Json.parseToJsonElement(
            tool.execute("""{"path":"big.txt","offset":"$nextOffset"}"""),
        ).jsonObject
        val rebuilt = first["content"]!!.jsonPrimitive.content + second["content"]!!.jsonPrimitive.content
        assertTrue(rebuilt.startsWith(content.take(64)))
    }

    @Test
    fun respondStreaming_fallsBackToNonStreamingWhenStreamProducesNothing() {
        // A proxy that streams an empty completion but answers correctly when asked without streaming.
        // This is what produces "the model returned an empty response with no text and no tool call".
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                return if (body.contains("\"stream\":true")) {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type: text/event-stream")
                        .body(
                            "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":0," +
                                "\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{}," +
                                "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                        )
                        .build()
                } else {
                    finalResponse("Recovered via fallback.")
                }
            }
        }
        val config = OpenAiCompatibleModelConfig(
            baseUrl = server.url("/v1/").toString(),
            apiKey = "test-key",
            modelName = "test-model",
        )
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(config),
            toolRegistry = AgentToolRegistry(listOf(WorkspaceTextFileTool(createTempDirectory("agent-fallback").toFile()))),
        )

        val reply = gateway.respondStreaming(
            systemPrompt = "Answer the user.",
            userPrompt = "Do the thing.",
            streamingModel = AgentChatModelFactory.createStreaming(config)!!,
            onText = {},
        )

        assertEquals("Recovered via fallback.", reply.text)
        assertTrue("both a streamed and a non-streamed request should have been sent", server.requestCount >= 2)
    }

    private fun testContext(root: File): android.content.Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : android.content.ContextWrapper(android.app.Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
    }

    private fun sourceJar(root: File): File {
        val source = File(root, "source.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"parent\",\"name\":\"Parent\"}".toByteArray())
            zip.closeEntry()
        }
        return source
    }

    private fun newGateway(
        workspace: File,
        tools: List<AgentTool> = listOf(WorkspaceTextFileTool(workspace)),
    ): PolicyGatedAgentGateway = PolicyGatedAgentGateway(
        chatModel = AgentChatModelFactory.create(
            OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(),
                apiKey = "test-key",
                modelName = "test-model",
            ),
        ),
        toolRegistry = AgentToolRegistry(tools),
    )

    /** Returns a caller-supplied payload so tool-result bounding can be exercised. */
    private class HugeResultTool(private val payload: String) : AgentTool {
        override val specification = ToolSpecification.builder()
            .name("huge_tool")
            .description("Returns a large payload.")
            .parameters(JsonObjectSchema.builder().additionalProperties(false).build())
            .build()
        override val safety = AgentToolSafety.READ_ONLY
        override fun execute(arguments: String): String = payload
    }

    private fun toolCallResponse(toolName: String = "read_workspace_file"): MockResponse = MockResponse.Builder()
        .code(200)
        .body(
            """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 0,
              "model": "test-model",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call-note",
                    "type": "function",
                    "function": {
                      "name": "$toolName",
                      "arguments": "{\"path\":\"note.txt\"}"
                    }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """.trimIndent(),
        )
        .build()

    private fun finalResponse(text: String): MockResponse = MockResponse.Builder()
        .code(200)
        .body(
            """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 0,
              "model": "test-model",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "$text"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
            """.trimIndent(),
        )
        .build()

    private class ApprovalTool : AgentTool {
        override val specification = WorkspaceTextFileTool(File(".")).specification.toBuilder()
            .name("write_workspace_file")
            .build()
        override val safety = AgentToolSafety.REQUIRES_APPROVAL

        override fun execute(arguments: String): String = error("Must not execute.")
    }

    private fun AgentToolRegistry.execute(requestName: String): String = execute(
        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("test-call")
            .name(requestName)
            .arguments("{}")
            .build(),
    )
}
