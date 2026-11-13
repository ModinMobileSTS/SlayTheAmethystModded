package io.stamethyst.backend.llm

import android.app.Application
import android.content.ContextWrapper
import io.stamethyst.backend.mods.AgentPatchModManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real tool calls on synthetic local JARs; no standalone validate call or model/network service. */
class AgentPatchPackagingWorkflowTest {
    @get:Rule val temporary = TemporaryFolder(
        File(if (File("src/main").isDirectory) "../agent-tmp" else "agent-tmp").apply { mkdirs() },
    )
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
    private fun result(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
        }
        val jar = File(root, "parent.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("""{"modid":"parent","name":"Parent"}""".toByteArray())
            zip.closeEntry()
        }
        val workspace = AgentPatchModManager.createWorkspace(context, "parent", jar, "资源补丁")
        File(workspace.patchRoot, "asset.txt").writeText("resource")
        return Fixture(context, jar, workspace)
    }

    @Test fun packageAndUpdateExposeTheirOwnPreflightWarningsWithoutSeparateValidation() {
        val (context, jar, workspace) = fixture()
        val packaged = result(AgentPatchModPackageTool(context, jar) { workspace }.execute("""{"version":"1.0.0"}"""))
        assertEquals(packaged.toString(), "packaged", packaged.text("status"))
        val preflight = packaged["preflight"]!!.jsonObject
        assertEquals("valid", preflight.text("status"))
        assertTrue(preflight["issues"]!!.jsonArray.isEmpty())
        assertTrue(preflight["warnings"]!!.jsonArray.any { it.jsonPrimitive.content.contains("resource or helper-only") })
        assertTrue(File(packaged.text("jar_path")!!).isFile)

        File(workspace.patchRoot, "asset.txt").writeText("updated resource")
        val updated = result(AgentPatchModUpdateTool(context, "parent", jar)
            .execute("""{"patch_id":"${workspace.patchId}","version":"1.1.0"}"""))
        assertEquals("updated", updated.text("status"))
        assertEquals(preflight, updated["preflight"])
        assertEquals(packaged.text("patch_id"), updated.text("patch_id"))
    }

    @Test fun finalPreflightStillBlocksPackagingInvalidBytecode() {
        val (context, jar, workspace) = fixture()
        File(workspace.patchRoot, "Broken.class").writeBytes(byteArrayOf(1, 2, 3))
        val response = result(AgentPatchModPackageTool(context, jar) { workspace }.execute("{}"))
        assertTrue(response.toString(), response.text("error")!!.startsWith("patch_preflight_failed"))
        assertTrue(response.text("error")!!.contains("invalid class file"))
        assertTrue(AgentPatchModManager.listPackaged(context, "parent").isEmpty())
    }

    @Test fun failingUpdateLeavesThePackagedJarAndVersionIntact() {
        val (context, jar, workspace) = fixture()
        val packaged = result(AgentPatchModPackageTool(context, jar) { workspace }.execute("""{"version":"1.0.0"}"""))
        val packagedJar = File(packaged.text("jar_path")!!)
        val original = packagedJar.readBytes()
        File(workspace.patchRoot, "Broken.class").writeBytes(byteArrayOf(1, 2, 3))

        val response = result(AgentPatchModUpdateTool(context, "parent", jar)
            .execute("""{"patch_id":"${workspace.patchId}","version":"1.1.0"}"""))
        assertTrue(response.toString(), response.text("error")!!.startsWith("patch_preflight_failed"))
        assertArrayEquals(original, packagedJar.readBytes())
        assertEquals("1.0.0", AgentPatchModManager.listPackaged(context, "parent").single().version)
    }

    private data class Fixture(
        val context: android.content.Context,
        val jar: File,
        val workspace: io.stamethyst.backend.mods.AgentPatchWorkspace,
    )
}
