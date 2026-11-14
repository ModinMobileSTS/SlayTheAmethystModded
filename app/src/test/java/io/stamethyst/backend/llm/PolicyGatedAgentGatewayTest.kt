package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchWorkspace
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
        val assistantMessage = secondRequest["messages"]!!.jsonArray
            .first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }
            .jsonObject
        assertTrue("assistant tool-call content must be concrete", assistantMessage["content"] != null)
        assertTrue("assistant tool_calls must be preserved", assistantMessage["tool_calls"] != null)
        val toolMessage = secondRequest["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertTrue(toolMessage["content"]!!.jsonPrimitive.content.contains("artifact validation passed"))
    }

    @Test
    fun respond_sendsAnImageReadByAToolAsAUserImagePart() {
        server.enqueue(toolCallResponse("read_agent_workspace_file"))
        server.enqueue(finalResponse("I can see a 1x1 image."))
        val workspace = createTempDirectory("agent-image").toFile().apply {
            resolve("note.txt").writeBytes(java.util.Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64))
        }

        val reply = newGateway(workspace, tools = listOf(AgentWorkspaceFileTool(workspace)))
            .respond(systemPrompt = "Inspect images.", userPrompt = "Read note.txt")

        assertEquals("I can see a 1x1 image.", reply.text)
        assertEquals(1, reply.toolRounds)
        server.takeRequest()
        val second = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val messages = second.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        assertEquals("user", last.getString("role"))
        val content = last.getJSONArray("content")
        assertEquals("Attached image(s) from tool result:", content.getJSONObject(0).getString("text"))
        val image = content.getJSONObject(1)
        assertEquals("image_url", image.getString("type"))
        assertTrue(image.getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))
    }

    @Test
    fun respond_waitsForNetworkBeforeRetryingAnAbortedConnection() {
        // A screen lock aborts the socket; the retry must not be spent while the radio is still down.
        server.enqueue(MockResponse.Builder().onRequestStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        server.enqueue(finalResponse("Recovered after the radio came back."))
        val workspace = createTempDirectory("agent-abort").toFile()
        val probed = java.util.concurrent.atomic.AtomicInteger()
        val retries = mutableListOf<Long>()
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(
                OpenAiCompatibleModelConfig(
                    baseUrl = server.url("/v1/").toString(),
                    apiKey = "test-key",
                    modelName = "test-model",
                ),
            ),
            toolRegistry = AgentToolRegistry(listOf(WorkspaceTextFileTool(workspace))),
            onRetry = { number, delay, _ -> if (number == 1) retries += delay },
            // Report the network as down on the first probe and up on the second, so the retry path
            // must actually wait rather than pass straight through.
            hasUsableNetwork = { probed.incrementAndGet() > 1 },
        )

        val reply = gateway.respond(systemPrompt = "Recover.", userPrompt = "Continue.")

        assertEquals("Recovered after the radio came back.", reply.text)
        // The abort path uses the longer abort backoff, not the 1 s transient one.
        assertEquals(listOf(5L), retries)
        assertTrue("the retry must probe the network, saw ${probed.get()} probes", probed.get() >= 2)
    }

    @Test
    fun respond_retriesImmediatelyWhenTheNetworkIsStillUsable() {
        server.enqueue(MockResponse.Builder().onRequestStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        server.enqueue(finalResponse("Retried without waiting."))
        val workspace = createTempDirectory("agent-abort-online").toFile()
        val probed = java.util.concurrent.atomic.AtomicInteger()
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(
                OpenAiCompatibleModelConfig(
                    baseUrl = server.url("/v1/").toString(),
                    apiKey = "test-key",
                    modelName = "test-model",
                ),
            ),
            toolRegistry = AgentToolRegistry(listOf(WorkspaceTextFileTool(workspace))),
            hasUsableNetwork = { probed.incrementAndGet(); true },
        )

        val reply = gateway.respond(systemPrompt = "Recover.", userPrompt = "Continue.")

        assertEquals("Retried without waiting.", reply.text)
        assertEquals("one probe is enough when the network is up", 1, probed.get())
    }

    @Test
    fun respond_preservesNativeParallelToolCallGroups() {
        server.enqueue(twoToolCallResponse())
        server.enqueue(finalResponse("Both reads completed."))
        val workspace = createTempDirectory("agent-multi-tool").toFile().apply {
            resolve("note.txt").writeText("artifact validation passed")
        }

        val reply = newGateway(workspace).respond(
            systemPrompt = "Inspect the note.",
            userPrompt = "Inspect the note twice.",
        )

        assertEquals("Both reads completed.", reply.text)
        server.takeRequest()
        val request = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        val messages = request["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf(2),
            messages.filter { it["role"]!!.jsonPrimitive.content == "assistant" }
                .map { it["tool_calls"]!!.jsonArray.size },
        )
    }

    @Test
    fun respond_preservesSingleReasoningBlockForParallelToolCallsInHttpRequest() {
        server.enqueue(twoToolCallResponse(reasoning = "Inspect both files before answering."))
        server.enqueue(finalResponse("Both reads completed."))
        val workspace = createTempDirectory("agent-thinking").toFile().apply {
            resolve("note.txt").writeText("verified")
        }
        newGateway(workspace).respond(systemPrompt = "Inspect the note.", userPrompt = "Read twice.")
        server.takeRequest()
        val request = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val messages = request.getJSONArray("messages")
        val assistants = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.getString("role") == "assistant" }
        assertEquals(1, assistants.size)
        assistants.forEach {
            assertEquals("Inspect both files before answering.", it.optString("reasoning_content"))
            assertEquals(2, it.getJSONArray("tool_calls").length())
        }
    }

    @Test
    fun respondStreaming_preservesSingleReasoningBlockForParallelToolCallsInHttpRequest() {
        val reasoning = "Inspect both files before answering."
        val calls = (0..1).joinToString(",") { index ->
            """{"index":$index,"id":"call-$index","type":"function","function":{"name":"read_workspace_file","arguments":"{\"path\":\"note.txt\"}"}}"""
        }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"$reasoning\"}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[$calls]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n",
        ).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Done.\",\"content\":\"Both reads completed.\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        ).build())
        val workspace = createTempDirectory("agent-thinking-stream").toFile().apply {
            resolve("note.txt").writeText("verified")
        }
        val config = OpenAiCompatibleModelConfig(
            baseUrl = server.url("/v1/").toString(), apiKey = "test-key",
            modelName = "deepseek-flash", reasoningEffort = LlmReasoningEffort.HIGH,
        )
        val gateway = PolicyGatedAgentGateway(
            chatModel = AgentChatModelFactory.create(config),
            toolRegistry = AgentToolRegistry(listOf(WorkspaceTextFileTool(workspace))),
        )
        val thinking = StringBuilder()
        val reply = gateway.respondStreaming("Inspect.", "Read twice.",
            AgentChatModelFactory.createStreaming(config)!!, onText = {}, onThinking = { thinking.append(it) })
        assertEquals("Both reads completed.", reply.text)
        assertTrue(thinking.startsWith(reasoning))
        server.takeRequest()
        val request = JSONObject(requireNotNull(server.takeRequest().body).utf8())
        val messages = request.getJSONArray("messages")
        val assistants = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.getString("role") == "assistant" }
        assertEquals(1, assistants.size)
        assistants.forEach {
            assertEquals(reasoning, it.optString("reasoning_content"))
            assertEquals(2, it.getJSONArray("tool_calls").length())
        }
    }

    @Test
    fun sanitizeToolMessageSequence_preservesParallelCallsAndReasoning() {
        val gateway = newGateway(createTempDirectory("agent-split").toFile())
        val first = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("call-first")
            .name("read_workspace_file")
            .arguments("{}")
            .build()
        val second = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("call-second")
            .name("read_workspace_file")
            .arguments("{}")
            .build()
        val assistant = AiMessage.builder()
            .text("I will read both notes.")
            .thinking("Inspect both files before answering.")
            .toolExecutionRequests(listOf(first, second))
            .build()

        val messages = gateway.sanitizeToolMessageSequence(listOf(
            assistant,
            ToolExecutionResultMessage.from(first, "first result"),
            ToolExecutionResultMessage.from(second, "second result"),
        ))

        assertEquals(3, messages.size)
        assertEquals(listOf("call-first", "call-second"), (messages[0] as AiMessage).toolExecutionRequests().map { it.id() })
        assertEquals("first result", (messages[1] as ToolExecutionResultMessage).text())
        assertEquals("second result", (messages[2] as ToolExecutionResultMessage).text())
        assertEquals("call-first", (messages[1] as ToolExecutionResultMessage).id())
        assertEquals("call-second", (messages[2] as ToolExecutionResultMessage).id())
        assertEquals("I will read both notes.", (messages[0] as AiMessage).text())
        assertEquals(assistant.thinking(), (messages[0] as AiMessage).thinking())
    }

    @Test
    fun respond_continuesBeyondFormerToolRoundLimit() {
        // A long task must keep going while it obtains new evidence, not reread one unchanged file.
        repeat(25) { server.enqueue(toolCallResponse(path = "note-$it.txt")) }
        server.enqueue(finalResponse("Inspection complete."))
        val workspace = createTempDirectory("agent-workspace").toFile().apply {
            repeat(25) { resolve("note-$it.txt").writeText("artifact $it validation passed") }
        }

        val reply = newGateway(workspace).respond(
            systemPrompt = "Inspect the workspace before answering.",
            userPrompt = "Inspect the note thoroughly.",
        )

        assertEquals("Inspection complete.", reply.text)
        assertEquals(25, reply.toolRounds)
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
    fun globTool_matchesRecursivePatternAndScopes() {
        val workspace = createTempDirectory("agent-glob").toFile().apply {
            resolve("source/ThMod/cards/Marisa/Spark.java").apply { parentFile!!.mkdirs(); writeText("class Spark {}") }
            resolve("source/ThMod/relics/Hakkero.java").apply { parentFile!!.mkdirs(); writeText("class Hakkero {}") }
            resolve("source/localization/cards.json").apply { parentFile!!.mkdirs(); writeText("{}") }
            resolve("patch_source/patch-1/src/Patch.java").apply { parentFile!!.mkdirs(); writeText("class Patch {}") }
        }

        val javaFiles = AgentWorkspaceGlobTool(workspace).execute("""{"pattern":"**/*.java"}""")
        assertTrue(javaFiles.contains("source/ThMod/cards/Marisa/Spark.java"))
        assertTrue(javaFiles.contains("source/ThMod/relics/Hakkero.java"))
        assertTrue(javaFiles.contains("patch_source/patch-1/src/Patch.java"))
        assertTrue(!javaFiles.contains("cards.json"))

        val scoped = AgentWorkspaceGlobTool(workspace).execute("""{"pattern":"**/*.java","path":"source/ThMod/cards"}""")
        assertTrue(scoped.contains("source/ThMod/cards/Marisa/Spark.java"))
        assertTrue(!scoped.contains("source/ThMod/relics/Hakkero.java"))

        val singleSegment = AgentWorkspaceGlobTool(workspace).execute("""{"pattern":"*.java"}""")
        assertTrue(!singleSegment.contains("source/ThMod/cards/Marisa/Spark.java"))
    }

    @Test
    fun globTool_rejectsEscapingPatternAndPath() {
        val workspace = createTempDirectory("agent-glob-guard").toFile().apply {
            resolve("source/ThMod/ThMod.java").apply { parentFile!!.mkdirs(); writeText("class ThMod {}") }
        }

        assertTrue(
            AgentWorkspaceGlobTool(workspace).execute("""{"pattern":"../secret/*"}""")
                .contains("invalid_pattern"),
        )
        assertTrue(
            AgentWorkspaceGlobTool(workspace).execute("""{"pattern":"**/*.java","path":"../../etc"}""")
                .contains("path_outside_workspace"),
        )
    }

    @Test
    fun grepTool_returnsLineMatchesWithPathAndLineNumber() {
        val workspace = createTempDirectory("agent-grep").toFile().apply {
            resolve("source/ThMod/relics/Hakkero.java").apply {
                parentFile!!.mkdirs()
                writeText("class Hakkero {\n    void atBattleStart() { addCard(); }\n}\n")
            }
            resolve("source/ThMod/cards/Spark.java").apply {
                parentFile!!.mkdirs()
                writeText("class Spark {\n    // no relic here\n}\n")
            }
            resolve("source/data.json").apply {
                parentFile!!.mkdirs()
                writeText("{\"relic\":\"Hakkero\"}\n")
            }
        }

        val result = AgentWorkspaceGrepTool(workspace).execute("""{"pattern":"Hakkero"}""")
        assertTrue(result.contains("source/ThMod/relics/Hakkero.java"))
        assertTrue(result.contains("source/data.json"))
        assertTrue(result.contains("\"line\":1"))
        assertTrue(!result.contains("Spark.java"))

        val scoped = AgentWorkspaceGrepTool(workspace).execute("""{"pattern":"Hakkero","include":"*.java"}""")
        assertTrue(scoped.contains("Hakkero.java"))
        assertTrue(!scoped.contains("data.json"))
    }

    @Test
    fun grepTool_reportsInvalidPatternAndSkipsBinary() {
        val workspace = createTempDirectory("agent-grep-binary").toFile().apply {
            resolve("source/Bin.class").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(0x00, 0x01, 0x48, 0x61, 0x6B, 0x00))
            }
            resolve("source/Note.java").apply {
                parentFile!!.mkdirs()
                writeText("class Note {}\n")
            }
        }

        assertTrue(
            AgentWorkspaceGrepTool(workspace).execute("""{"pattern":"["}""").contains("invalid_pattern"),
        )
        val binary = AgentWorkspaceGrepTool(workspace).execute("""{"pattern":"Hak"}""")
        assertTrue(binary.contains("\"searched_files\":1"))
        assertTrue(binary.contains("\"returned\":0"))
    }

    @Test
    fun globAndGrepTools_areReadOnlyAndScopedToWorkspace() {
        val workspace = createTempDirectory("agent-scope").toFile()
        val glob = AgentWorkspaceGlobTool(workspace)
        val grep = AgentWorkspaceGrepTool(workspace)

        assertEquals(AgentToolSafety.READ_ONLY, glob.safety)
        assertEquals(AgentToolSafety.READ_ONLY, grep.safety)
        assertEquals("glob_agent_workspace", glob.specification.name())
        assertEquals("grep_agent_workspace", grep.specification.name())
        assertTrue(grep.execute("""{"pattern":"x","path":"../outside"}""").contains("path_outside_workspace"))
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
    fun createPatchModTool_usesAgentChosenNameAndCreatesDistinctWorkspaces() {
        val root = createTempDirectory("agent-patch-create").toFile()
        val context = testContext(root)
        val source = sourceJar(root)
        var created: AgentPatchWorkspace? = null
        val tool = AgentPatchWorkspaceCreateTool(
            context = context,
            parentModId = "parent",
            sourceJar = source,
            onCreated = { created = it },
        )

        assertEquals("create_agent_patch_workspace", tool.specification.name())
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
        assertEquals("patch_source/${workspace.patchId}", first["patch_workspace_path"]!!.jsonPrimitive.content)

        val second = Json.parseToJsonElement(tool.execute("""{"name":"Another name"}""")).jsonObject
        assertEquals("created", second["status"]!!.jsonPrimitive.content)
        val secondWorkspace = requireNotNull(created)
        assertTrue(workspace.patchId != secondWorkspace.patchId)
        assertTrue(workspace.patchRoot.resolve("ModTheSpire.json").isFile)
        assertEquals(
            "Another name",
            JSONObject(secondWorkspace.patchRoot.resolve("ModTheSpire.json").readText()).getString("name"),
        )
    }

    @Test
    fun updatePatchModTool_isAdvertisedWithPackageSafetyAndUpdatesVersion() {
        val root = createTempDirectory("agent-patch-update").toFile()
        val context = testContext(root)
        val source = sourceJar(root)
        val workspace = AgentPatchModManager.createWorkspace(context, "parent", source, name = "Tweak")
        AgentPatchModManager.packagePatchMod(context, workspace, "Tweak", "1.0.0", "test")
        val tool = AgentPatchModUpdateTool(
            context = context,
            parentModId = "parent",
            parentJar = source,
        )

        assertEquals("update_agent_patch_mod", tool.specification.name())
        assertEquals(AgentToolSafety.PATCH_PACKAGE, tool.safety)

        val result = Json.parseToJsonElement(
            tool.execute("""{"patch_id":"${workspace.patchId}","version":"1.1.0"}"""),
        ).jsonObject
        assertEquals("updated", result["status"]!!.jsonPrimitive.content)
        assertEquals("1.1.0", result["version"]!!.jsonPrimitive.content)
        assertEquals("valid", result["preflight"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(result["preflight"]!!.jsonObject["warnings"]!!.jsonArray.isNotEmpty())

        ZipFile(File(result["jar_path"]!!.jsonPrimitive.content)).use { jar ->
            val manifest = JSONObject(
                jar.getInputStream(jar.getEntry("ModTheSpire.json"))
                    .use { it.readBytes() }
                    .toString(Charsets.UTF_8),
            )
            assertEquals("1.1.0", manifest.getString("version"))
        }
    }

    @Test
    fun updatePatchModTool_rejectsUnknownPatchId() {
        val root = createTempDirectory("agent-patch-update-missing").toFile()
        val context = testContext(root)
        val source = sourceJar(root)
        val tool = AgentPatchModUpdateTool(
            context = context,
            parentModId = "parent",
            parentJar = source,
        )

        val result = tool.execute("""{"patch_id":"patch-missing","version":"1.1.0"}""")

        assertTrue(result.contains("patch_not_found"))
    }

    @Test
    fun writeTool_canCreateFilesOnlyUnderPatchSource() {
        val root = createTempDirectory("agent-patch-write").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val tool = AgentWorkspaceWriteTool(workspaceRoot)

        val result = Json.parseToJsonElement(
            tool.execute("""{"path":"patch_source/patch-test/src/Notes.java","content":"class Notes {}"}"""),
        ).jsonObject

        assertEquals("patch_source/patch-test/src/Notes.java", result["path"]!!.jsonPrimitive.content)
        assertTrue(File(workspaceRoot, "patch_source/patch-test/src/Notes.java").isFile)
        assertTrue(tool.execute("""{"path":"patch_source/Notes.java","content":"class Notes {}"}""").contains("path_outside_patch_source"))
        assertTrue(tool.execute("""{"path":"source/Notes.java","content":"class Notes {}"}""").contains("path_outside_patch_source"))
        assertTrue(tool.execute("""{"path":"../secret.txt","content":"secret"}""").contains("path_outside_patch_source"))
    }

    @Test
    fun deleteTool_canDeleteOnlyFromPatchSource() {
        val root = createTempDirectory("agent-patch-delete").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val sourceFile = File(workspaceRoot, "source/original.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }
        val staleFile = File(workspaceRoot, "patch_source/patch-test/data/stale.txt").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val result = Json.parseToJsonElement(
            tool.execute("""{"path":"patch_source/patch-test/data/stale.txt"}"""),
        ).jsonObject
        assertEquals("file", result["type"]!!.jsonPrimitive.content)
        assertTrue(!staleFile.exists())

        val sourceResult = Json.parseToJsonElement(
            tool.execute("""{"path":"source/original.txt"}"""),
        ).jsonObject
        assertTrue(sourceResult["error"]!!.jsonPrimitive.content == "path_outside_patch_source")
        assertTrue(sourceFile.exists())
    }

    @Test
    fun deleteTool_removesNonEmptyDirectoryOnlyWhenRecursive() {
        val root = createTempDirectory("agent-patch-delete-dir").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val directory = File(workspaceRoot, "patch_source/patch-test/assets").apply { mkdirs() }
        File(directory, "a.txt").writeText("a")
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val notRecursive = tool.execute("""{"path":"patch_source/patch-test/assets"}""")
        assertTrue(notRecursive.contains("delete_failed") || directory.exists())

        val recursive = Json.parseToJsonElement(
            tool.execute("""{"path":"patch_source/patch-test/assets","recursive":true}"""),
        ).jsonObject
        assertEquals("directory", recursive["type"]!!.jsonPrimitive.content)
        assertEquals(1, recursive["files_deleted"]!!.jsonPrimitive.content.toInt())
        assertTrue(!directory.exists())
    }

    @Test
    fun deleteTool_refusesToDeletePatchSourceRoot() {
        val root = createTempDirectory("agent-patch-delete-root").toFile()
        val workspaceRoot = File(root, "workspace").apply { mkdirs() }
        val tool = AgentWorkspaceDeleteTool(workspaceRoot)

        val result = tool.execute("""{"path":"patch_source","recursive":true}""")

        assertTrue(result.contains("cannot_delete_patch_source_root"))
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

        newGateway(workspace, tools, agentWorkspaceRoot = workspace)
            .respond(systemPrompt = "Use the tool.", userPrompt = "Go.")

        server.takeRequest()
        val secondRequest = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject
        val toolMessage = secondRequest["messages"]!!.jsonArray.last().jsonObject["content"]!!
            .jsonPrimitive.content
        assertTrue(
            "tool result must be capped, was ${toolMessage.toByteArray().size} bytes",
            toolMessage.toByteArray().size < 53_000,
        )
        assertTrue("truncation must be signalled", toolMessage.contains("truncated"))

        val overflow = File(workspace, "tool_output").listFiles().orEmpty()
        assertEquals(1, overflow.size)
        assertEquals(payload, overflow.single().readText())
        assertTrue("the model must be told where the full output is", toolMessage.contains("tool_output/"))
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
    fun readTool_returnsTextByLineAndSignalsContinuation() {
        val root = createTempDirectory("agent-workspace-lines").toFile()
        val lines = (1..50).map { "line $it" }
        File(root, "big.txt").writeText(lines.joinToString("\n"))
        val tool = AgentWorkspaceFileTool(root, maxLines = 10)

        val first = Json.parseToJsonElement(tool.execute("""{"path":"big.txt"}""")).jsonObject
        assertTrue(first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(10, first["returned_lines"]!!.jsonPrimitive.content.toInt())
        assertEquals("1: line 1\n2: line 2\n", first["content"]!!.jsonPrimitive.content.take(20))
        val nextOffset = first["next_offset"]!!.jsonPrimitive.content.toInt()
        assertEquals(11, nextOffset)

        val second = Json.parseToJsonElement(
            tool.execute("""{"path":"big.txt","offset":"$nextOffset"}"""),
        ).jsonObject
        assertTrue(second["content"]!!.jsonPrimitive.content.startsWith("11: line 11\n"))
    }

    @Test
    fun readTool_truncatesVeryLongLines() {
        val root = createTempDirectory("agent-workspace-longline").toFile()
        File(root, "long.txt").writeText("a".repeat(5000))
        val tool = AgentWorkspaceFileTool(root, maxLineChars = 2000)

        val result = Json.parseToJsonElement(tool.execute("""{"path":"long.txt"}""")).jsonObject

        assertEquals("1: " + "a".repeat(2000) + "\n", result["content"]!!.jsonPrimitive.content)
        assertTrue(result["long_lines_truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun readTool_characterPagingRecoversEntireSingleLineJson() {
        val root = createTempDirectory("agent-workspace-char-pages").toFile()
        try {
            val text = """{"content":"${"\u6c49\u5b57".repeat(30_000)}"}"""
            File(root, "output.txt").writeText(text)
            val tool = AgentWorkspaceFileTool(root)
            val recovered = StringBuilder()
            var offset = 0
            do {
                val page = Json.parseToJsonElement(tool.execute(
                    """{"path":"output.txt","encoding":"utf8_chars","offset":"$offset","limit":"4000"}""",
                )).jsonObject
                recovered.append(page["content"]!!.jsonPrimitive.content)
                val next = page["next_offset"]?.jsonPrimitive?.content?.toInt()
                if (next == null) break
                assertTrue(next > offset)
                offset = next
            } while (true)
            assertEquals(text, recovered.toString())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun respond_capsMultibyteSingleLineByBytes() {
        val workspace = createTempDirectory("agent-workspace-multibyte").toFile()
        try {
            server.enqueue(toolCallResponse("huge_tool"))
            server.enqueue(finalResponse("done"))
            val payload = "\u6c49".repeat(100_000)
            newGateway(workspace, listOf(HugeResultTool(payload)), workspace).respond("system", "go")
            server.takeRequest()
            val request = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val text = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(text.toByteArray(Charsets.UTF_8).size < 53_000)
            assertTrue(text.contains("encoding=utf8_chars"))
            assertEquals(payload, File(workspace, "tool_output").listFiles()!!.single().readText())
        } finally { workspace.deleteRecursively() }
    }

    @Test
    fun readTool_readsBinaryAsBase64ByByteRange() {
        val root = createTempDirectory("agent-workspace-base64").toFile()
        File(root, "blob.bin").writeBytes(ByteArray(200) { it.toByte() })
        val tool = AgentWorkspaceFileTool(root, maxBytes = 64)

        val first = Json.parseToJsonElement(
            tool.execute("""{"path":"blob.bin","encoding":"base64"}"""),
        ).jsonObject

        assertTrue(first["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(64, first["returned_bytes"]!!.jsonPrimitive.content.toInt())
        assertEquals(64, first["next_offset"]!!.jsonPrimitive.content.toInt())
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

    @Test
    fun sanitizeToolMessageSequence_dropsOrphanedResultsAndKeepsMatchingResults() {
        val workspace = createTempDirectory("agent-sanitize").toFile()
        val gateway = newGateway(workspace)
        val call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("call-valid")
            .name("read_workspace_file")
            .arguments("{}")
            .build()
        val messages = gateway.sanitizeToolMessageSequence(listOf(
            UserMessage.from("inspect"),
            ToolExecutionResultMessage.from("call-orphan", "read_workspace_file", "stale"),
            AiMessage.from(call),
            ToolExecutionResultMessage.from("call-valid", "read_workspace_file", "fresh"),
            ToolExecutionResultMessage.from("call-duplicate", "read_workspace_file", "stale"),
        ))

        assertEquals(3, messages.size)
        assertTrue(messages[0] is UserMessage)
        assertTrue(messages[1] is AiMessage)
        assertEquals("call-valid", (messages[2] as ToolExecutionResultMessage).id())
    }

    @Test
    fun patchTargetInspectToolDefaultsToBytecodeAndCanOmitIt() {
        val root = createTempDirectory("agent-target-inspect").toFile()
        val context = testContext(root)
        val stsRoot = File(root, "external/sts").apply { mkdirs() }
        val gameJar = File(stsRoot, "desktop-1.0.jar")
        ZipOutputStream(gameJar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("com/example/VanillaThing.class"))
            zip.write(simpleClassBytes("com/example/VanillaThing"))
            zip.closeEntry()
        }
        val parentJar = File(root, "parent.jar")
        val tool = AgentPatchTargetInspectTool(context, parentJar)

        val withBytecode = Json.parseToJsonElement(
            tool.execute("""{"target_class":"com.example.VanillaThing","member":"run"}"""),
        ).jsonObject
        val defaultOverload = withBytecode["overloads"]!!.jsonArray.single().jsonObject
        val bytecode = defaultOverload["bytecode"]!!.jsonPrimitive.content
        assertTrue("bytecode listing should name run, was: $bytecode", bytecode.contains("run()V"))
        assertEquals(true, defaultOverload["has_code"]?.jsonPrimitive?.content?.toBoolean())

        val withoutBytecode = Json.parseToJsonElement(
            tool.execute(
                """{"target_class":"com.example.VanillaThing","member":"run","include_bytecode":false}""",
            ),
        ).jsonObject
        val omittedOverload = withoutBytecode["overloads"]!!.jsonArray.single().jsonObject
        assertTrue("bytecode must be omitted", "bytecode" !in omittedOverload)

        val selected = Json.parseToJsonElement(
            tool.execute(
                """{"target_class":"com.example.VanillaThing","member":"run","overload_index":"0"}""",
            ),
        ).jsonObject
        assertEquals(1, selected["overloads"]!!.jsonArray.size)
        val outOfRange = Json.parseToJsonElement(
            tool.execute(
                """{"target_class":"com.example.VanillaThing","member":"run","overload_index":"9"}""",
            ),
        ).jsonObject
        assertEquals("overload_index_out_of_range", outOfRange["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun apiClassDecompileToolReadsClassFromGameJar() {
        val root = createTempDirectory("agent-api-decompile").toFile()
        val context = testContext(root)
        val stsRoot = File(root, "external/sts").apply { mkdirs() }
        val gameJar = File(stsRoot, "desktop-1.0.jar")
        ZipOutputStream(gameJar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("com/example/VanillaThing.class"))
            zip.write(simpleClassBytes("com/example/VanillaThing"))
            zip.closeEntry()
        }

        val tool = AgentApiClassDecompileTool(context, File(root, "parent.jar"))
        val result = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.VanillaThing"}"""),
        ).jsonObject

        assertEquals("decompiled", result["status"]?.jsonPrimitive?.content)
        assertEquals("desktop-1.0.jar", result["origin"]?.jsonPrimitive?.content)
        assertEquals(false, result["truncated"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(result["source"]!!.jsonPrimitive.content.contains("class VanillaThing"))

        val missing = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.Missing"}"""),
        ).jsonObject
        assertEquals("class_not_found", missing["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun apiClassDecompileToolCachesRepeatedReads() {
        val root = createTempDirectory("agent-api-cache").toFile()
        val context = testContext(root)
        val gameJar = gameJarWith(root, "com/example/VanillaThing.class" to simpleClassBytes("com/example/VanillaThing"))
        val tool = AgentApiClassDecompileTool(context, File(root, "parent.jar"))

        val first = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.VanillaThing"}"""),
        ).jsonObject
        val second = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.VanillaThing"}"""),
        ).jsonObject

        assertEquals(false, first["cached"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, second["cached"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(first["source"], second["source"])
        assertTrue("cache must live under the app cache", File(context.cacheDir, "agent-api-source").isDirectory)
        assertTrue("the game jar is only read", gameJar.isFile)
    }

    @Test
    fun apiClassDecompileToolAcceptsBatchOfClasses() {
        val root = createTempDirectory("agent-api-batch").toFile()
        val context = testContext(root)
        gameJarWith(
            root,
            "com/example/VanillaThing.class" to simpleClassBytes("com/example/VanillaThing"),
            "com/example/OtherThing.class" to simpleClassBytes("com/example/OtherThing"),
        )
        val tool = AgentApiClassDecompileTool(context, File(root, "parent.jar"))

        val batch = Json.parseToJsonElement(
            tool.execute(
                """{"class_names":["com.example.VanillaThing","com.example.OtherThing"]}""",
            ),
        ).jsonObject

        assertEquals("decompiled", batch["status"]?.jsonPrimitive?.content)
        val results = batch["results"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, results.size)
        assertEquals(
            listOf("com.example.VanillaThing", "com.example.OtherThing"),
            results.map { it["class_name"]?.jsonPrimitive?.content },
        )
        assertTrue(results.all { it["source"]!!.jsonPrimitive.content.contains("class ") })

        val arrayForm = Json.parseToJsonElement(
            tool.execute("""{"class_name":["com.example.VanillaThing"]}"""),
        ).jsonObject
        assertEquals("decompiled", arrayForm["status"]?.jsonPrimitive?.content)
        assertTrue("a single class keeps the flat shape", "source" in arrayForm)

        val empty = Json.parseToJsonElement(tool.execute("""{"class_names":[]}""")).jsonObject
        assertEquals("invalid_arguments", empty["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun apiClassDecompileToolSlicesOversizedClassByMember() {
        val root = createTempDirectory("agent-api-slice").toFile()
        val context = testContext(root)
        gameJarWith(root, "com/example/Huge.class" to wideClassBytes("com/example/Huge", 1200))
        val tool = AgentApiClassDecompileTool(context, File(root, "parent.jar"))

        val skeleton = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.Huge"}"""),
        ).jsonObject

        assertTrue(
            "fixture must exceed the inline cap, was ${skeleton["source_chars"]}",
            skeleton["source_chars"]!!.jsonPrimitive.content.toInt() > 32_000,
        )
        assertEquals(true, skeleton["truncated"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, skeleton["sliced"]?.jsonPrimitive?.content?.toBoolean())
        val body = skeleton["skeleton"]!!.jsonPrimitive.content
        assertTrue("skeleton must keep the class header", body.contains("class Huge"))
        assertTrue("skeleton must keep signatures", body.contains("public int m0() { /* … */ }"))
        assertTrue("skeleton must drop bodies", !body.contains("return 5;"))
        val index = skeleton["member_index"]!!.jsonArray
        assertEquals(200, index.size)
        assertTrue(index.any { it.jsonObject["name"]?.jsonPrimitive?.content == "m5" })

        val sliced = Json.parseToJsonElement(
            tool.execute("""{"class_name":"com.example.Huge","member":"m5"}"""),
        ).jsonObject

        assertEquals(true, sliced["sliced"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("m5", sliced["member"]?.jsonPrimitive?.content)
        val text = sliced["source"]!!.jsonPrimitive.content
        assertTrue("requested member must be returned: $text", text.contains("public int m5()"))
        assertTrue(text.contains("return 5;"))
        assertFalse("neighbouring members must not leak", text.contains("public int m6()"))
        assertEquals(skeleton["source_chars"], sliced["source_chars"])
    }

    private fun gameJarWith(root: File, vararg entries: Pair<String, ByteArray>): File {
        val stsRoot = File(root, "external/sts").apply { mkdirs() }
        val gameJar = File(stsRoot, "desktop-1.0.jar")
        ZipOutputStream(gameJar.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return gameJar
    }

    /** A class with [methodCount] trivial methods, so CFR emits far more than the inline cap. */
    private fun wideClassBytes(binaryName: String, methodCount: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null)
        repeat(methodCount) { index ->
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "m$index", "()I", null, null)
            method.visitCode()
            when {
                index == 0 -> method.visitInsn(Opcodes.ICONST_0)
                index in 1..5 -> method.visitInsn(Opcodes.ICONST_0 + index)
                else -> {
                    method.visitIntInsn(Opcodes.SIPUSH, index)
                }
            }
            method.visitInsn(Opcodes.IRETURN)
            method.visitMaxs(1, 1)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun simpleClassBytes(binaryName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null)
        val constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        val run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        run.visitCode()
        run.visitVarInsn(Opcodes.ALOAD, 0)
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
        run.visitInsn(Opcodes.POP)
        run.visitInsn(Opcodes.RETURN)
        run.visitMaxs(1, 1)
        run.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun testContext(root: File): android.content.Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : android.content.ContextWrapper(android.app.Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
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
        agentWorkspaceRoot: File? = null,
    ): PolicyGatedAgentGateway = PolicyGatedAgentGateway(
        chatModel = AgentChatModelFactory.create(
            OpenAiCompatibleModelConfig(
                baseUrl = server.url("/v1/").toString(),
                apiKey = "test-key",
                modelName = "test-model",
            ),
        ),
        toolRegistry = AgentToolRegistry(tools),
        agentWorkspaceRoot = agentWorkspaceRoot,
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

    private fun toolCallResponse(toolName: String = "read_workspace_file", path: String = "note.txt"): MockResponse = MockResponse.Builder()
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
                      "arguments": "{\"path\":\"$path\"}"
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

    private fun twoToolCallResponse(reasoning: String = ""): MockResponse = MockResponse.Builder()
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
                  "reasoning_content": "$reasoning",
                  "tool_calls": [
                    {
                      "id": "call-note-1",
                      "type": "function",
                      "function": {
                        "name": "read_workspace_file",
                        "arguments": "{\"path\":\"note.txt\"}"
                      }
                    },
                    {
                      "id": "call-note-2",
                      "type": "function",
                      "function": {
                        "name": "read_workspace_file",
                        "arguments": "{\"path\":\"note.txt\"}"
                      }
                    }
                  ]
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
    ).text

    private companion object {
        /** Smallest valid PNG; enough to exercise MIME sniffing and the image content part. */
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
