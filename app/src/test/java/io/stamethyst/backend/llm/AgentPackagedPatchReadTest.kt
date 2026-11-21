package io.stamethyst.backend.llm

import android.app.Application
import android.content.ContextWrapper
import io.stamethyst.backend.mods.AgentPatchModManager
import io.stamethyst.config.RuntimePaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentPackagedPatchReadTest {
    @get:Rule val temp = TemporaryFolder(File("agent-tmp").absoluteFile.apply { mkdirs() })

    @Test fun discoversAndReadsACommunityPatchFromAnotherModWithoutAWorkspace() {
        val context = context()
        val activePatch = patch(context, "parent", "local", "ai.local", "local content")
        val otherPatch = patch(context, "other", "catalogue-example", "ai.shared", "shared content")
        patch(context, "other", "misplaced", "ai.misplaced", "misplaced content", descriptorParent = "parent")
        val enabled = RuntimePaths.enabledAgentPatchModsConfig(context).apply {
            parentFile?.mkdirs()
            writeText("ai.local\n")
        }
        val originalBytes = otherPatch.readBytes()
        val parent = archive(temp.newFile("parent.jar"), mapOf("only-parent.txt" to "parent".toByteArray()))
        val targets = AgentModReadTargets("parent", parent, packagedPatches = {
            AgentPatchModManager.listAllPackaged(context)
        }) { emptyList() }

        val listed = parse(AgentListInstalledModsTool(context, "parent").execute("{}"))
        val patches = listed.getValue("patches").jsonArray.associate { it.jsonObject.let { patch ->
            patch.getValue("patch_mod_id").jsonPrimitive.content to patch
        } }
        assertEquals(setOf("ai.local", "ai.shared"), patches.keys)
        assertEquals("true", patches.getValue("ai.local").getValue("enabled").jsonPrimitive.content)
        val shared = patches.getValue("ai.shared")
        assertEquals("false", shared.getValue("enabled").jsonPrimitive.content)
        assertEquals("other", shared.getValue("parent_mod_id").jsonPrimitive.content)
        assertFalse(listed.getValue("selectable_mod_ids").toString().contains("ai."))
        assertEquals(activePatch, targets.resolve(null, "ai.local").jar)

        val entries = parse(AgentModEntriesListTool(parent, targets)
            .execute("""{"patch_mod_id":" AI.SHARED ","entry_type":"classes"}"""))
        assertEquals("ai.shared", entries.getValue("patch_mod_id").jsonPrimitive.content)
        assertEquals(listOf("example/Fix.class"), entries.getValue("entries").jsonArray.map { it.jsonPrimitive.content })
        val resource = parse(AgentModResourceReadTool(parent, targets)
            .execute("""{"patch_mod_id":"ai.shared","entry":"fix.txt"}"""))
        assertEquals("shared content", resource.getValue("content").jsonPrimitive.content)
        assertEquals("ai.shared", resource.getValue("patch_mod_id").jsonPrimitive.content)
        val inspected = parse(AgentModClassInspectTool("parent", parent, targets)
            .execute("""{"patch_mod_id":"ai.shared","class_name":"example.Fix"}"""))
        assertEquals("example.Fix", inspected.getValue("name").jsonPrimitive.content)
        assertEquals("ai.shared", inspected.getValue("patch_mod_id").jsonPrimitive.content)
        val decompiled = parse(AgentModClassDecompileTool(context, "parent", parent, targets)
            .execute("""{"patch_mod_id":"ai.shared","class_name":"example.Fix","materialize":true}"""))
        assertEquals("decompiled", decompiled.getValue("status").jsonPrimitive.content)
        assertEquals("ai.shared", decompiled.getValue("patch_mod_id").jsonPrimitive.content)
        assertTrue(decompiled.getValue("source").jsonPrimitive.content.contains("class Fix"))
        assertEquals("false", decompiled.getValue("materialized").jsonPrimitive.content)
        assertFalse(decompiled.containsKey("source_path"))
        assertFalse(RuntimePaths.agentModWorkspaceRoot(context, "parent").exists())
        assertFalse(RuntimePaths.agentModWorkspaceRoot(context, "other").exists())
        assertArrayEquals(originalBytes, otherPatch.readBytes())
        assertEquals("ai.local\n", enabled.readText())
    }

    @Test fun rejectsConflictingSelectorsAndUnknownDeletedOrAmbiguousPatchesWithoutFallingBack() {
        val context = context()
        val jar = patch(context, "parent", "first", "ai.same", "first content")
        val other = patch(context, "other", "second", "ai.same", "second content")
        val parent = archive(temp.newFile("parent.jar"), mapOf("fix.txt" to "parent".toByteArray()))
        val targets = AgentModReadTargets("parent", parent, packagedPatches = {
            AgentPatchModManager.listAllPackaged(context)
        }) { emptyList() }
        val read = AgentModResourceReadTool(parent, targets, gameJar = parent)
        fun result(args: String, key: String = "error") = parse(read.execute(args)).getValue(key).jsonPrimitive.content

        assertEquals("parent", result("""{"entry":"fix.txt"}""", "content"))
        assertEquals("mod_id_and_patch_mod_id_are_mutually_exclusive",
            result("""{"entry":"fix.txt","mod_id":"parent","patch_mod_id":"ai.same"}"""))
        assertEquals("patch_mod_id_not_allowed_for_game_source",
            result("""{"entry":"fix.txt","source":"game","patch_mod_id":"ai.same"}"""))
        assertEquals("ambiguous_patch_mod_id", result("""{"entry":"fix.txt","patch_mod_id":"ai.same"}"""))
        assertEquals("patch_not_installed", result("""{"entry":"fix.txt","patch_mod_id":"first"}"""))
        assertEquals("patch_not_installed", result("""{"entry":"fix.txt","patch_mod_id":"${jar.absolutePath}"}"""))
        assertTrue(other.delete())
        assertEquals("first content", result("""{"entry":"fix.txt","patch_mod_id":"ai.same"}""", "content"))
        assertTrue(jar.delete())
        assertEquals("patch_not_installed", result("""{"entry":"fix.txt","patch_mod_id":"ai.same"}"""))
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun context() = object : ContextWrapper(Application()) {
        private val files = temp.newFolder("files")
        private val cache = temp.newFolder("cache")
        override fun getFilesDir() = files
        override fun getCacheDir() = cache
        override fun getExternalFilesDir(type: String?): File? = null
    }

    private fun patch(
        context: ContextWrapper, parent: String, id: String, modId: String, resource: String,
        descriptorParent: String = parent,
    ): File = archive(
        RuntimePaths.agentModsForModRoot(context, parent).resolve("$id.jar"),
        mapOf(
            "agent-patch.json" to
                """{"parent_mod_id":"$descriptorParent","patch_id":"$id","patch_mod_id":"$modId"}""".toByteArray(),
            "ModTheSpire.json" to """{"modid":"$modId","dependencies":["$descriptorParent"]}""".toByteArray(),
            "example/Fix.class" to ClassWriter(0).apply {
                visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "example/Fix", null, "java/lang/Object", null)
                visitEnd()
            }.toByteArray(),
            "fix.txt" to resource.toByteArray(),
        ),
    )

    private fun archive(file: File, entries: Map<String, ByteArray>): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
