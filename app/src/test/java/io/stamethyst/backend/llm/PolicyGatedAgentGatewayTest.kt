package io.stamethyst.backend.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.stamethyst.backend.mods.AgentPatchModManager
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
        val toolMessage = secondRequest["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertTrue(toolMessage["content"]!!.jsonPrimitive.content.contains("artifact validation passed"))
    }

    @Test
    fun respond_continuesBeyondFormerToolRoundLimit() {
        repeat(25) { server.enqueue(toolCallResponse()) }
        server.enqueue(finalResponse("Inspection complete."))
        val workspace = createTempDirectory("agent-workspace").toFile().apply {
            resolve("note.txt").writeText("artifact validation passed")
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
