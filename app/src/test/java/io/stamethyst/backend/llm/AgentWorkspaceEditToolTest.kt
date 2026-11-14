package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AgentWorkspaceEditToolTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )
    private val path = "patch_source/patch-test/src/Example.java"
    private fun workspace() = temporary.newFolder("workspace")
    private fun file(root: File, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun result(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun arguments(old: String, new: String, path: String = this.path, expected: String? = null) = buildJsonObject {
        put("path", JsonPrimitive(path))
        put("old_text", JsonPrimitive(old))
        put("new_text", JsonPrimitive(new))
        expected?.let { put("expected_sha256", JsonPrimitive(it)) }
    }.toString()
    private fun assertError(code: String, output: String) {
        val response = result(output)
        assertEquals(output, code, response.text("error"))
        assertFalse("error needs an actionable hint: $output", response.text("hint").isNullOrBlank())
    }

    @Test fun replacesOnlyTheLiteralSpanPreservingUtf8BomAndMixedNewlines() {
        val root = workspace()
        val prefix = "\uFEFF// 中文😀\r\nclass Example {\n"
        val suffix = "\r    // keep this \t\r\n}"
        val target = file(root, prefix + "    int x = 1;" + suffix)
        val response = result(AgentWorkspaceEditTool(root).execute(arguments("int x = 1;", "int x = 2;")))

        assertArrayEquals((prefix + "    int x = 2;" + suffix).toByteArray(), target.readBytes())
        assertEquals("edited", response.text("status"))
        assertEquals("1", response.text("replacements"))
        assertEquals(hash(target.readBytes()), response.text("sha256"))
        assertFalse("success must not echo file contents", "content" in response || response.toString().contains("class Example"))
        assertTrue(response.toString().length < 300)
        assertEquals(listOf("Example.java"), target.parentFile.list()!!.toList())
    }

    @Test fun pagedTextReadsReturnWholeFileHashAndExactCharactersCanBeUsedForEditing() {
        val root = workspace()
        val text = "first\r\n中文😀\r\nlast"
        val target = file(root, text)
        val reader = AgentWorkspaceFileTool(root)
        val lines = result(reader.execute("""{"path":"$path","limit":"1"}"""))
        val chars = result(reader.execute("""{"path":"$path","encoding":"utf8_chars","offset":"7","limit":"6"}"""))
        assertEquals("1: first\n", lines.text("content"))
        assertEquals("中文😀\r\n", chars.text("content"))
        assertEquals(hash(text.toByteArray()), lines.text("sha256"))
        assertEquals(lines.text("sha256"), chars.text("sha256"))
        val first = result(AgentWorkspaceEditTool(root).execute(arguments(chars.text("content")!!, "changed\r\n", expected = chars.text("sha256"))))
        val second = result(AgentWorkspaceEditTool(root).execute(arguments("last", "end", expected = first.text("sha256")!!.uppercase())))
        assertEquals("first\r\nchanged\r\nend", target.readText())
        assertEquals(hash(target.readBytes()), second.text("sha256"))
    }

    @Test fun staleHashRejectsEvenWhenOldTextStillMatchesAndSizeAndTimestampAreUnchanged() {
        val root = workspace()
        val target = file(root, "first target last")
        val oldHash = result(AgentWorkspaceFileTool(root).execute("""{"path":"$path"}""")).text("sha256")!!
        val timestamp = target.lastModified()
        target.writeText("other target last")
        target.setLastModified(timestamp)
        val current = target.readBytes()
        assertError("file_conflict", AgentWorkspaceEditTool(root).execute(arguments("target", "edited", expected = oldHash)))
        assertArrayEquals(current, target.readBytes())
    }

    @Test fun largeMultilineFileSmallWindowReturnsOnlyRequestedLinesAndFullFileHash() {
        val root = workspace()
        val target = file(root, "")
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        val lineCount = 40_000
        val prefix = "数据😀".repeat(40)
        target.outputStream().buffered().use { output ->
            for (line in 1..lineCount) {
                val ending = if (line % 3 == 0) "\r" else if (line % 3 == 1) "\r\n" else "\n"
                val bytes = "$line $prefix$ending".toByteArray()
                expectedDigest.update(bytes)
                output.write(bytes)
            }
        }
        assertTrue("fixture must be near the 16 MiB cap", target.length() > 15L * 1024 * 1024)
        val expectedHash = expectedDigest.digest().joinToString("") { "%02x".format(it) }
        val tool = AgentWorkspaceFileTool(root)
        val page = result(tool.execute("""{"path":"$path","offset":"20000","limit":"2"}"""))
        assertEquals("20000: 20000 $prefix\n20001: 20001 $prefix\n", page.text("content"))
        assertEquals("2", page.text("returned_lines"))
        assertEquals("20002", page.text("next_offset"))
        assertEquals(expectedHash, page.text("sha256"))
        val last = result(tool.execute("""{"path":"$path","offset":"40000","limit":"2"}"""))
        assertEquals("40000: 40000 $prefix\n", last.text("content"))
        assertEquals("false", last.text("truncated"))
        assertEquals(expectedHash, last.text("sha256"))
    }

    @Test fun linePagingValidatesUtf8AfterTheRequestedWindowAndHandlesEmptyLines() {
        val root = workspace()
        val target = file(root, "\r\n\nend\r")
        val reader = AgentWorkspaceFileTool(root)
        val page = result(reader.execute("""{"path":"$path","offset":"2","limit":"2"}"""))
        assertEquals("2: \n3: end\n", page.text("content"))
        assertEquals("false", page.text("truncated"))
        val eof = result(reader.execute("""{"path":"$path","offset":"4","limit":"1"}"""))
        assertEquals("0", eof.text("returned_lines"))
        assertEquals(hash(target.readBytes()), eof.text("sha256"))
        assertEquals("offset_past_end_of_file", result(reader.execute("""{"path":"$path","offset":"5"}""")).text("error"))
        target.writeBytes("first\n".toByteArray() + "valid\n".repeat(4000).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        assertTrue(result(reader.execute("""{"path":"$path","limit":"1"}""")).text("error")!!.startsWith("invalid_utf8_text"))
        target.writeText("first\n" + "valid\n".repeat(4000) + "\u0000")
        assertTrue(result(reader.execute("""{"path":"$path","limit":"1"}""")).text("error")!!.startsWith("invalid_utf8_text"))
    }

    @Test fun rejectsMultipleIncludingOverlappingMatchesWithoutModifyingTheFile() {
        val root = workspace()
        val target = file(root, "word and word")
        val tool = AgentWorkspaceEditTool(root)
        assertError("multiple_matches", tool.execute(arguments("word", "new")))
        assertEquals("word and word", target.readText())
        target.writeText("aaa")
        assertError("multiple_matches", tool.execute(arguments("aa", "b")))
        assertEquals("aaa", target.readText())
    }

    @Test fun rejectsMissingEmptyAndNoOpMatchesWithoutWriting() {
        val root = workspace()
        val target = file(root, "exact text\r\n")
        val tool = AgentWorkspaceEditTool(root)
        assertError("empty_old_text", tool.execute(arguments("", "new")))
        assertError("text_not_found", tool.execute(arguments("exact text\n", "new")))
        assertError("no_change", tool.execute(arguments("exact", "exact")))
        assertEquals("exact text\r\n", target.readText())
    }

    @Test fun supportsDeletionAndDoesNotInterpretRegexOrReplacementMetacharacters() {
        val root = workspace()
        val target = file(root, "before .* after")
        val tool = AgentWorkspaceEditTool(root)
        assertEquals("edited", result(tool.execute(arguments(".*", "$1\\literal"))).text("status"))
        assertEquals("before $1\\literal after", target.readText())
        assertEquals("edited", result(tool.execute(arguments("$1\\literal", ""))).text("status"))
        assertEquals("before  after", target.readText())
    }

    @Test fun missingFilesAndDirectoriesAreNotCreatedOrReplaced() {
        val root = workspace()
        val tool = AgentWorkspaceEditTool(root)
        assertError("file_not_found", tool.execute(arguments("old", "new")))
        assertFalse(File(root, path).exists())
        File(root, path).mkdirs()
        assertError("file_not_found", tool.execute(arguments("old", "new")))
        assertTrue(File(root, path).isDirectory)
    }

    @Test fun editWriteAndDeleteShareCanonicalBoundaries() {
        val root = workspace()
        val source = File(root, "source/Original.java").apply { parentFile.mkdirs(); writeText("old") }
        val outside = File(root.parentFile, "outside.txt").apply { writeText("old") }
        val paths = listOf("source/Original.java", "../outside.txt", "patch_source/patch-test/../../source/Original.java",
            "patch_source/invalid/file.txt", "patch_source/loose.txt", source.absolutePath, "bad\u0000path")
        for (candidate in paths) {
            assertError("path_outside_patch_source", AgentWorkspaceEditTool(root).execute(arguments("old", "new", candidate)))
            val write = buildJsonObject { put("path", JsonPrimitive(candidate)); put("content", JsonPrimitive("new")) }
            val delete = buildJsonObject { put("path", JsonPrimitive(candidate)) }
            assertEquals(candidate, "path_outside_patch_source", result(AgentWorkspaceWriteTool(root).execute(write.toString())).text("error"))
            assertEquals(candidate, "path_outside_patch_source", result(AgentWorkspaceDeleteTool(root).execute(delete.toString())).text("error"))
        }
        assertEquals("old", source.readText())
        assertEquals("old", outside.readText())
    }

    @Test fun rejectsFileAndDirectorySymlinksEscapingPatchSource() {
        val root = workspace()
        val target = file(root, "old")
        val source = File(root, "source/Original.java").apply { parentFile.mkdirs(); writeText("old") }
        val outside = File(root.parentFile, "outside").apply { mkdirs() }
        val outsideFile = File(outside, "Original.java").apply { writeText("old") }
        val links = listOf(
            File(target.parentFile, "source.java") to source,
            File(target.parentFile, "outside.java") to outsideFile,
            File(target.parentFile, "dir") to outside,
        )
        links.forEach { (link, destination) ->
            // TemporaryFolder's parent is relative; symlink targets resolve from the link's parent.
            Files.createSymbolicLink(link.toPath(), destination.toPath().toAbsolutePath())
            assertTrue("Fixture must link to the existing escape target", Files.isSameFile(link.toPath(), destination.toPath()))
        }
        val paths = listOf("source.java", "outside.java", "dir/Original.java")
        paths.forEach { name ->
            val linkPath = "patch_source/patch-test/src/$name"
            assertError("path_outside_patch_source", AgentWorkspaceEditTool(root).execute(arguments("old", "new", linkPath)))
        }
        assertEquals("old", source.readText())
        assertEquals("old", outsideFile.readText())
    }

    @Test fun symlinkedPatchSourceRootCannotMakeSourceWritable() {
        val root = workspace()
        val source = File(root, "source/patch-test/src/Example.java").apply { parentFile.mkdirs(); writeText("old") }
        val patchSource = File(root, "patch_source").toPath()
        val originalSource = File(root, "source").toPath()
        Files.createSymbolicLink(patchSource, originalSource.toAbsolutePath())
        assertTrue("Fixture must redirect patch_source to source", Files.isSameFile(patchSource, originalSource))
        assertError("path_outside_patch_source", AgentWorkspaceEditTool(root).execute(arguments("old", "new")))
        assertEquals("path_outside_patch_source", result(AgentWorkspaceWriteTool(root).execute("""{"path":"$path","content":"new"}""")).text("error"))
        assertEquals("path_outside_patch_source", result(AgentWorkspaceDeleteTool(root).execute("""{"path":"$path"}""")).text("error"))
        assertEquals("old", source.readText())
    }

    @Test fun patchSourceRootItselfCannotBeEditedEvenIfItIsARegularFile() {
        val root = workspace()
        val malformedRoot = File(root, "patch_source").apply { writeText("old") }
        assertError("path_outside_patch_source", AgentWorkspaceEditTool(root).execute(arguments("old", "new", "patch_source")))
        assertEquals("path_outside_patch_source", result(AgentWorkspaceWriteTool(root)
            .execute("""{"path":"patch_source","content":"new"}""")).text("error"))
        assertEquals("cannot_delete_patch_source_root", result(AgentWorkspaceDeleteTool(root)
            .execute("""{"path":"patch_source"}""")).text("error"))
        assertEquals("old", malformedRoot.readText())
    }

    @Test fun malformedUtf8AndBinaryTextCannotBeSilentlyReencoded() {
        val root = workspace()
        val target = file(root, "old")
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), "old\u0000binary".toByteArray())) {
            target.writeBytes(bytes)
            assertError("invalid_text", AgentWorkspaceEditTool(root).execute(arguments("old", "new")))
            assertArrayEquals(bytes, target.readBytes())
            val read = result(AgentWorkspaceFileTool(root).execute("""{"path":"$path"}"""))
            assertTrue(read.text("error")!!.startsWith("invalid_utf8_text"))
        }
    }

    @Test fun invalidUnicodeReplacementOrMatchIsRejectedWithoutWriting() {
        val root = workspace()
        val target = file(root, "old")
        val tool = AgentWorkspaceEditTool(root)
        listOf(arguments("old", "\u0000"), arguments("\uD800", "new"), arguments("old", "\uDFFF")).forEach {
            assertError("invalid_text", tool.execute(it))
            assertEquals("old", target.readText())
        }
    }

    @Test fun invalidJsonTypesAndHashAreActionableAndNeverWrite() {
        val root = workspace()
        val target = file(root, "old")
        val tool = AgentWorkspaceEditTool(root)
        listOf("[]", "not json", """{"path":"$path","old_text":"old"}""",
            """{"path":"$path","old_text":null,"new_text":"new"}""",
            """{"path":"$path","old_text":"old","new_text":42}""").forEach {
            assertError("invalid_arguments", tool.execute(it))
        }
        assertError("invalid_sha256", tool.execute(arguments("old", "new", expected = "wrong")))
        assertError("invalid_sha256", tool.execute("""{"path":"$path","old_text":"old","new_text":"new","expected_sha256":null}"""))
        assertEquals("old", target.readText())
    }

    @Test fun limitsBothInputAndResultByUtf8ByteCount() {
        val root = workspace()
        val target = file(root, "long input")
        val tool = AgentWorkspaceEditTool(root, maxBytes = 6)
        assertError("file_too_large", tool.execute(arguments("long", "new")))
        assertEquals("long input", target.readText())
        target.writeText("ab")
        assertError("file_too_large", tool.execute(arguments("a", "中文")))
        assertEquals("ab", target.readText())
        assertEquals("edited", result(tool.execute(arguments("ab", "中文"))).text("status"))
        assertEquals("中文", target.readText())
    }

    @Test fun registryRequiresWritePermissionAndSchedulesEditsExclusively() {
        val root = workspace()
        val target = file(root, "old")
        val tool = AgentWorkspaceEditTool(root)
        val request = ToolExecutionRequest.builder().id("edit").name("edit_agent_workspace_file")
            .arguments(arguments("old", "new")).build()
        val denied = AgentToolRegistry(listOf(tool))
        assertTrue(denied.toolSpecifications().isEmpty())
        assertEquals("approval_required", result(denied.execute(request).text).text("error"))
        assertEquals("old", target.readText())
        val granted = AgentToolRegistry(listOf(tool), AgentToolPolicyGate(setOf(AgentToolSafety.WORKSPACE_WRITE)))
        assertEquals(listOf("edit_agent_workspace_file"), granted.toolSpecifications().map { it.name() })
        assertEquals(AgentToolConcurrency.EXCLUSIVE, granted.concurrency(request))
        assertEquals("edited", result(granted.execute(request).text).text("status"))
        assertEquals("new", target.readText())
    }
}
