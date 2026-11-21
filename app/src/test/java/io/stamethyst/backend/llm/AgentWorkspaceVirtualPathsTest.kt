package io.stamethyst.backend.llm

import android.app.Application
import android.content.ContextWrapper
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.backend.mods.AgentPatchWorkspace
import io.stamethyst.config.RuntimePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentWorkspaceVirtualPathsTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )
    private fun result(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
    private fun args(path: String, vararg fields: Pair<String, String>) = buildJsonObject {
        put("path", JsonPrimitive(path))
        fields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }.toString()
    private fun file(root: File, path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun pathsRoundTripAcrossAllToolsAndMultipleRevisionsWithoutChangingRoot() {
        val root = temporary.newFolder()
        val writer = AgentWorkspaceWriteTool(root)
        val a = result(writer.execute(args("patch_source/patch-a/src/./A.java", "content" to "class A {}"))).text("path")!!
        val b = result(writer.execute(args("/patch_source/patch-b/src/B.java", "content" to "class B {}"))).text("path")!!
        assertEquals("/patch_source/patch-a/src/A.java", a)
        assertEquals("/patch_source/patch-b/src/B.java", b)
        val listed = result(AgentWorkspaceListTool(root).execute("""{"path":"/"}"""))
        assertEquals("/", listed.text("root"))
        assertEquals(setOf(a, b), listed["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val globbed = result(AgentWorkspaceGlobTool(root).execute("""{"pattern":"/patch_source/**/*.java","path":"/patch_source"}"""))
        assertEquals(setOf(a, b), globbed["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val grep = result(AgentWorkspaceGrepTool(root).execute("""{"pattern":"class","include":"/patch_source/**/*.java"}"""))
        assertEquals(setOf(a, b), grep["matches"]!!.jsonArray.map { it.jsonObject.text("path") }.toSet())
        for (path in listOf(a, b)) {
            val read = result(AgentWorkspaceFileTool(root).execute(args(path, "encoding" to "utf8_chars")))
            assertEquals(path, read.text("path"))
            val edit = result(AgentWorkspaceEditTool(root).execute(args(path, "old_text" to "{}", "new_text" to "{ int n; }", "expected_sha256" to read.text("sha256")!!)))
            assertEquals("edited", edit.text("status"))
            assertEquals(path, edit.text("path"))
        }
        assertEquals("path_not_exposed", result(writer.execute(args("src/A.java", "content" to "wrong"))).text("error"))
        assertFalse(File(root, "src/A.java").exists())
        assertEquals(a, result(AgentWorkspaceDeleteTool(root).execute(args(a))).text("path"))
        assertEquals("file_not_found", result(AgentWorkspaceFileTool(root).execute(args(a))).text("error"))
        assertTrue(File(root, b.removePrefix("/")).readText().contains("int n"))
    }

    @Test fun internalFilesAreNotExposedAndReadOnlyMountsCannotBeModified() {
        val root = temporary.newFolder()
        file(root, "conversations/session.json", "private marker")
        file(root, "mod-metadata.json", "private marker")
        file(root, "source/Original.java", "public marker")
        file(root, "tool_output/result.txt", "public marker")
        val list = result(AgentWorkspaceListTool(root).execute("{}"))
        assertEquals(setOf("/source/Original.java", "/tool_output/result.txt"), list["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(AgentWorkspaceGlobTool(root).execute("""{"pattern":"**/*"}""").contains("conversations"))
        assertFalse(AgentWorkspaceGrepTool(root).execute("""{"pattern":"marker"}""").contains("private"))
        assertEquals("path_not_exposed", result(AgentWorkspaceFileTool(root).execute(args("/conversations/session.json"))).text("error"))
        val tools = listOf(AgentWorkspaceWriteTool(root), AgentWorkspaceEditTool(root), AgentWorkspaceDeleteTool(root))
        for (path in listOf("/source/Original.java", "/tool_output/result.txt")) {
            for (tool in tools) {
                val response = result(tool.execute(args(path, "content" to "changed", "old_text" to "marker", "new_text" to "changed")))
                assertEquals(response.toString(), "read_only_path", response.text("error"))
                assertFalse(response.text("hint").isNullOrBlank())
            }
        }
    }

    @Test fun rejectsEscapesInvalidPathsAndProtectedDirectoriesWithoutGuessingPatchIds() {
        val root = temporary.newFolder()
        val cases = mapOf("/../source/x" to "path_outside_workspace", "../../secret" to "path_outside_workspace",
            "bad\u0000path" to "invalid_path", "C:\\secret" to "invalid_path", "patch_source\\patch-a\\x" to "invalid_path",
            "/patch_source/src/A.java" to "incomplete_patch_path", "/patch_source/patch-a" to "incomplete_patch_path",
            "/patch_source/patch-a/../../../secret" to "path_outside_workspace",
            File(root, "patch_source/patch-a/src/A.java").absolutePath to "path_not_exposed")
        for ((path, code) in cases) {
            assertEquals(path, code, result(AgentWorkspaceWriteTool(root).execute(args(path, "content" to "x"))).text("error"))
        }
        for (path in listOf("/", "/patch_source", "/patch_source/patch-a")) {
            assertEquals("protected_patch_directory", result(AgentWorkspaceDeleteTool(root).execute(args(path))).text("error"))
        }
        assertEquals("file_not_found", result(AgentWorkspaceEditTool(root).execute(args("/patch_source/patch-a/src/Missing.java", "old_text" to "x", "new_text" to "y"))).text("error"))
        assertEquals("/patch_source/patch-a/src/A.java", result(AgentWorkspaceWriteTool(root).execute(args("/patch_source/patch-a/src/../src/A.java", "content" to "x"))).text("path"))
    }

    @Test fun searchesCannotFollowSymlinksIntoPrivateStateOtherMountsOrOutsideWorkspace() {
        val root = temporary.newFolder()
        val public = file(root, "source/Visible.java", "public")
        val hidden = file(root, "conversations/secret.txt", "private")
        val outside = File(temporary.newFolder(), "outside.txt").apply { writeText("outside") }
        val patch = File(root, "patch_source/patch-a/src").apply { mkdirs() }
        val links = listOf("hidden.txt" to hidden, "outside.txt" to outside, "source.java" to public)
        links.forEach { (name, target) -> Files.createSymbolicLink(File(patch, name).toPath(), target.toPath().toAbsolutePath()) }
        Files.createSymbolicLink(File(patch, "cycle").toPath(), patch.toPath().toAbsolutePath())
        for (tool in listOf(AgentWorkspaceListTool(root), AgentWorkspaceGlobTool(root), AgentWorkspaceGrepTool(root))) {
            val text = tool.execute(if (tool is AgentWorkspaceGrepTool) """{"pattern":"public|private|outside"}""" else """{"pattern":"**/*"}""")
            assertNull(result(text).text("error"))
            assertFalse(text, text.contains("hidden.txt") || text.contains("outside.txt") || text.contains("source.java") || text.contains("cycle/"))
        }
        for ((name, _) in links) {
            assertNotNull(result(AgentWorkspaceFileTool(root).execute(args("/patch_source/patch-a/src/$name"))).text("error"))
        }
        val deleted = result(AgentWorkspaceDeleteTool(root).execute("""{"path":"/patch_source/patch-a/src","recursive":true}"""))
        assertNotNull(deleted.text("error"))
        assertEquals("private", hidden.readText())
        assertEquals("outside", outside.readText())
        assertTrue(patch.exists())
    }

    @Test fun redirectedMountRootsCannotExposeInternalState() {
        val root = temporary.newFolder()
        val hidden = file(root, "private/secret.txt", "private")
        Files.createSymbolicLink(File(root, "source").toPath(), hidden.parentFile!!.toPath().toAbsolutePath())
        assertEquals("unsafe_symlink", result(AgentWorkspaceFileTool(root).execute(args("/source/secret.txt"))).text("error"))
        assertFalse(AgentWorkspaceListTool(root).execute("{}").contains("secret.txt"))
    }

    @Test fun physicalWorkspaceAliasesStillProduceTheSameVirtualPaths() {
        val root = temporary.newFolder()
        val source = file(root, "source/Visible.java", "public")
        val alias = File(temporary.newFolder(), "workspace")
        Files.createSymbolicLink(alias.toPath(), root.toPath().toAbsolutePath())
        val paths = AgentWorkspacePaths(alias)
        assertEquals("/source/Visible.java", paths.virtual(File(alias, "source/Visible.java")))
        assertEquals("/source/Visible.java", paths.virtual(source))
        assertEquals("/source/Visible.java", paths.diagnostics(File(alias, "source/Visible.java").absolutePath))
        assertEquals("/source/Visible.java", result(AgentWorkspaceFileTool(alias).execute(args("/source/Visible.java"))).text("path"))
    }

    @Test fun sourceSnapshotAndDecompilerReturnVirtualReadOnlyPaths() {
        val (context, jar, workspace) = fixture()
        val snapshot = result(AgentModSourceDecompileTool(context, "parent", jar).execute("{}"))
        assertEquals("/", snapshot.text("workspace_root"))
        assertEquals("/source", snapshot.text("source_root"))
        val decompiled = result(AgentModClassDecompileTool(context, "parent", jar).execute("""{"class_name":"demo.Target"}"""))
        val path = decompiled.text("source_path")!!
        assertEquals("/source/demo/Target.java", path)
        assertFalse(snapshot.toString().contains(workspace.root.absolutePath))
        assertFalse(decompiled.toString().contains(workspace.root.absolutePath))
        assertEquals(path, result(AgentWorkspaceFileTool(workspace.root).execute(args(path))).text("path"))
        assertEquals("read_only_path", result(AgentWorkspaceWriteTool(workspace.root).execute(args(path, "content" to "changed"))).text("error"))
    }

    @Test fun preflightReportsFullVirtualClassPathsAndPatchListingProvidesReusableWorkspacePath() {
        val (context, jar, workspace) = fixture()
        file(workspace.patchRoot, "assets/test.txt", "resource")
        val packaged = result(AgentPatchModPackageTool(context, jar) { workspace }.execute("{}"))
        assertEquals("packaged", packaged.text("status"))
        val files = result(AgentWorkspaceListTool(workspace.root).execute(args(packaged.text("patch_workspace_path")!!)))["files"]!!.jsonArray
        assertTrue(files.any { it.jsonPrimitive.content == "/patch_source/${workspace.patchId}/assets/test.txt" })
        val listed = result(AgentPatchModListTool(context, "parent").execute("{}"))
        assertEquals(packaged.text("patch_workspace_path"), listed["patches"]!!.jsonArray.single().jsonObject.text("patch_workspace_path"))
        File(workspace.patchRoot, "Broken.class").writeBytes(byteArrayOf(1, 2, 3))
        val preflight = result(AgentPatchPreflightTool(context, jar, { workspace }).execute("{}"))
        assertEquals("failed", preflight.text("status"))
        val issue = preflight["issues"]!!.jsonArray.first().jsonPrimitive.content
        val path = issue.substringBefore(':')
        assertEquals("/patch_source/${workspace.patchId}/Broken.class", path)
        assertEquals(path, result(AgentWorkspaceFileTool(workspace.root).execute(args(path, "encoding" to "base64"))).text("path"))
    }

    @Test fun generatedSkeletonPathCanBeReadEditedAndDeletedUnchanged() {
        val fixture = fixture()
        val (context, jar, workspace) = fixture
        val generated = result(AgentPatchSkeletonTool(context, jar) { workspace }.execute(
            """{"target_class":"demo.Target","member":"update","package_name":"demo.patch","class_name":"UpdatePatch"}""",
        ))
        val path = generated.text("path")!!
        assertEquals("/patch_source/${workspace.patchId}/src/demo/patch/UpdatePatch.java", path)
        assertFalse(generated.toString().contains(workspace.root.absolutePath))
        val read = result(AgentWorkspaceFileTool(workspace.root).execute(args(path, "encoding" to "utf8_chars")))
        assertEquals(generated.text("source"), read.text("content"))
        val edited = result(AgentWorkspaceEditTool(workspace.root).execute(args(path, "old_text" to "class UpdatePatch", "new_text" to "class ChangedPatch")))
        assertEquals("edited", edited.text("status"))
        assertEquals(path, edited.text("path"))
        val exists = result(AgentPatchSkeletonTool(context, jar) { workspace }.execute(
            """{"target_class":"demo.Target","member":"update","package_name":"demo.patch","class_name":"UpdatePatch"}""",
        ))
        assertEquals("source_already_exists", exists.text("error"))
        assertEquals(path, exists.text("path"))
        assertEquals(path, result(AgentWorkspaceDeleteTool(workspace.root).execute(args(path))).text("path"))
    }

    @Test fun generationRejectsSymlinkEscapesBeforeWriting() {
        val (context, jar, workspace) = fixture()
        val outside = temporary.newFolder()
        Files.createSymbolicLink(File(workspace.patchRoot, "src").toPath(), outside.toPath().toAbsolutePath())
        val generated = result(AgentPatchSkeletonTool(context, jar) { workspace }.execute(
            """{"target_class":"demo.Target","member":"update","package_name":"demo.patch","class_name":"UpdatePatch"}""",
        ))
        assertEquals("path_outside_workspace", generated.text("error"))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun compilerDiagnosticsUseReusableVirtualSourcePaths() {
        val (context, jar, workspace) = fixture()
        val source = file(workspace.root, "patch_source/${workspace.patchId}/src/demo/Broken.java", "package demo; public class Broken { void update() { missing(); } }")
        // Minimal Java 8 boot classpath for a real offline ECJ failure; no device/runtime download.
        val rt = File(RuntimePaths.runtimeRoot(context), "lib/rt.jar").apply { parentFile!!.mkdirs() }
        zip(rt, mapOf("java/lang/Object.class" to classBytes("java/lang/Object", null)))
        val compiled = result(AgentPatchModCompileTool(context, jar, { workspace }).execute("{}"))
        assertEquals(compiled.toString(), "failed", compiled.text("status"))
        val diagnostics = compiled.text("diagnostics")!!
        val virtual = "/patch_source/${workspace.patchId}/src/demo/Broken.java"
        assertTrue(diagnostics, diagnostics.contains(virtual))
        assertTrue(diagnostics, diagnostics.contains("missing"))
        assertFalse(diagnostics.contains(source.absolutePath))
        assertFalse(diagnostics.contains(workspace.root.canonicalPath))
        assertEquals(virtual, result(AgentWorkspaceFileTool(workspace.root).execute(args(virtual))).text("path"))
    }

    @Test fun boundExistingDraftCompilesAndFirstPackagesInPlace() {
        val (context, jar, original) = fixture()
        val reopened = AgentPatchModManager.resolvePatchWorkspace(context, "parent", original.patchId, jar)!!
        val writer = AgentWorkspaceWriteTool(original.root, binding = { reopened.patchId })
        val path = result(writer.execute("""{"path":"src/demo/Addition.java","content":"package demo; public interface Addition {}"}""")).text("path")
        assertEquals("/patch_source/${original.patchId}/src/demo/Addition.java", path)
        val rt = File(RuntimePaths.runtimeRoot(context), "lib/rt.jar").apply { parentFile!!.mkdirs() }
        zip(rt, mapOf("java/lang/Object.class" to classBytes("java/lang/Object", null)))
        val compile = BoundRevisionTool(AgentPatchModCompileTool(context, jar, { reopened })) { reopened.patchId }
        val compiled = result(compile.execute("{}"))
        assertEquals(compiled.toString(), "compiled", compiled.text("status"))
        val pack = BoundRevisionTool(AgentPatchModPackageTool(context, jar) { reopened }) { reopened.patchId }
        assertEquals(original.patchId, result(pack.execute("{}")).text("patch_id"))
        assertEquals(listOf(original.patchId), original.patchRoot.parentFile!!.list()!!.toList())
    }

    private fun fixture(): Triple<ContextWrapper, File, AgentPatchWorkspace> {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        val jar = File(root, "parent.jar")
        zip(jar, mapOf("ModTheSpire.json" to """{"modid":"parent","name":"Parent"}""".toByteArray(), "demo/Target.class" to classBytes("demo/Target", "java/lang/Object")))
        return Triple(context, jar, AgentPatchModManager.createWorkspace(context, "parent", jar, "补丁"))
    }

    private fun classBytes(name: String, parent: String?): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null)
        visitMethod(Opcodes.ACC_PUBLIC, "update", "()V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
}
