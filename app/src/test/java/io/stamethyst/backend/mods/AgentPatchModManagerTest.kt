package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AgentPatchModManagerTest {
    @Test
    fun packagePatchMod_keepsParentDependencyAndDescriptor() {
        val root = Files.createTempDirectory("agent-patch-manager").toFile()
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
        val source = File(root, "source.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"parent\",\"name\":\"Parent\"}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data/config.txt"))
            zip.write("original".toByteArray())
            zip.closeEntry()
        }

        val workspace = AgentPatchModManager.createWorkspace(context, "Parent", source)
        assertTrue(workspace.sourceRoot.resolve("data/config.txt").isFile)
        workspace.patchRoot.resolve("data").mkdirs()
        workspace.patchRoot.resolve("data/config.txt").writeText("patched", StandardCharsets.UTF_8)

        val packaged = AgentPatchModManager.packagePatchMod(
            context = context,
            workspace = workspace,
            name = "Parent tweak",
            version = "1.0.0",
            description = "test patch",
        )

        ZipFile(packaged.jarFile).use { jar ->
            val manifest = JSONObject(
                jar.getInputStream(jar.getEntry("ModTheSpire.json"))
                    .use { it.readBytes() }
                    .toString(StandardCharsets.UTF_8),
            )
            assertEquals(packaged.patchModId, manifest.getString("modid"))
            assertEquals("parent", manifest.getJSONArray("dependencies").getString(0))
            assertTrue(jar.getEntry("agent-patch.json") != null)
            assertEquals(
                "patched",
                jar.getInputStream(jar.getEntry("data/config.txt"))
                    .use { it.readBytes() }
                    .toString(StandardCharsets.UTF_8),
            )
        }
        val listed = AgentPatchModManager.listPackaged(context, "parent")
        assertEquals(1, listed.size)
        assertEquals(workspace.patchId, listed.single().patchId)
    }

    @Test
    fun createWorkspace_usesSourceManifestModIdCase() {
        val root = Files.createTempDirectory("agent-patch-case").toFile()
        val context = testContext(root)
        val source = sourceJar(root, modId = "ShoujoKageki")

        val workspace = AgentPatchModManager.createWorkspace(context, "shoujokageki", source)

        assertEquals("ShoujoKageki", workspace.parentModId)
        assertEquals("shoujokageki", workspace.parentModSegment)
        val manifest = JSONObject(workspace.patchRoot.resolve("ModTheSpire.json").readText())
        assertEquals("ShoujoKageki", manifest.getJSONArray("dependencies").getString(0))
    }

    @Test
    fun packagePatchMod_dedupesCaseVariantParentDependency() {
        val root = Files.createTempDirectory("agent-patch-dedupe").toFile()
        val context = testContext(root)
        val source = sourceJar(root, modId = "ShoujoKageki")
        val workspace = AgentPatchModManager.createWorkspace(context, "ShoujoKageki", source)

        // Simulate an agent rewriting the manifest with a wrongly-cased parent dependency.
        workspace.patchRoot.resolve("ModTheSpire.json").writeText(
            JSONObject()
                .put("modid", "amethyst.ai.patch.shoujokageki.patch-case")
                .put("dependencies", org.json.JSONArray().put("shoujokageki").put("basemod"))
                .toString(2),
            StandardCharsets.UTF_8,
        )

        val packaged = AgentPatchModManager.packagePatchMod(
            context = context,
            workspace = workspace,
            name = "Case patch",
            version = "1.0.0",
            description = "test patch",
        )

        ZipFile(packaged.jarFile).use { jar ->
            val manifest = JSONObject(
                jar.getInputStream(jar.getEntry("ModTheSpire.json"))
                    .use { it.readBytes() }
                    .toString(StandardCharsets.UTF_8),
            )
            val dependencies = manifest.getJSONArray("dependencies")
            assertEquals(2, dependencies.length())
            assertEquals("basemod", dependencies.getString(0))
            assertEquals("ShoujoKageki", dependencies.getString(1))
        }
    }

    @Test
    fun createWorkspace_seedsAgentChosenNameWithoutPackaging() {
        val root = Files.createTempDirectory("agent-patch-name").toFile()
        val context = testContext(root)
        val source = sourceJar(root, modId = "parent")

        val workspace = AgentPatchModManager.createWorkspace(
            context = context,
            parentModId = "parent",
            sourceJar = source,
            name = "Quality-of-life tweak",
            version = "2.0.0",
            description = "Small quality-of-life changes.",
        )

        val manifest = JSONObject(workspace.patchRoot.resolve("ModTheSpire.json").readText())
        assertEquals("Quality-of-life tweak", manifest.getString("name"))
        assertEquals("2.0.0", manifest.getString("version"))
        assertEquals("Small quality-of-life changes.", manifest.getString("description"))
        assertEquals("parent", manifest.getJSONArray("dependencies").getString(0))
        assertTrue(AgentPatchModManager.listPackaged(context, "parent").isEmpty())
    }

    @Test
    fun packagePatchMod_excludesJavaSourcesUnderSrc() {
        withWorkspace { context, workspace ->
            File(workspace.patchRoot, "src/com/example/Tweak.java").apply {
                parentFile?.mkdirs()
                writeText("package com.example; class Tweak {}")
            }
            File(workspace.patchRoot, "com/example/Tweak.class").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            }

            val packaged = AgentPatchModManager.packagePatchMod(context, workspace, "Tweak", "1.0.0", "test")

            ZipFile(packaged.jarFile).use { jar ->
                assertTrue(jar.getEntry("com/example/Tweak.class") != null)
                assertTrue(jar.getEntry("src/com/example/Tweak.java") == null)
                assertTrue(jar.entries().asSequence().none { it.name.startsWith("src/") })
            }
        }
    }

    private fun testContext(root: File): Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
    }

    private fun sourceJar(root: File, modId: String): File {
        val source = File(root, "source-$modId.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"$modId\",\"name\":\"$modId\"}".toByteArray())
            zip.closeEntry()
        }
        return source
    }

    @Test
    fun delete_removesTheRevision() {
        withWorkspace { context, workspace ->
            val packaged = AgentPatchModManager.packagePatchMod(context, workspace, "Parent tweak", "1.0.0", "test")

            AgentPatchModManager.delete(context, "parent", workspace.patchId)

            assertTrue(!packaged.jarFile.exists())
            assertTrue(AgentPatchModManager.listPackaged(context, "parent").isEmpty())
        }
    }

    @Test
    fun packagePatchMod_startsDisabledAndLivesUnderAgentMods() {
        withWorkspace { context, workspace ->
            val packaged = AgentPatchModManager.packagePatchMod(context, workspace, "Parent tweak", "1.0.0", "test")

            assertTrue(packaged.jarFile.isFile)
            assertEquals(RuntimePaths.agentModsForModRoot(context, "parent"), packaged.jarFile.parentFile)
            assertTrue(!packaged.enabled)
            assertTrue(!AgentPatchModManager.listPackaged(context, "parent").single().enabled)
        }
    }

    @Test
    fun delete_rejectsAnUnknownPatchId() {
        withWorkspace { context, workspace ->
            AgentPatchModManager.packagePatchMod(context, workspace, "Parent tweak", "1.0.0", "test")

            val error = runCatching {
                AgentPatchModManager.delete(context, "parent", "patch-missing")
            }.exceptionOrNull()

            assertTrue(error is IOException)
        }
    }

    private fun withWorkspace(block: (Context, AgentPatchWorkspace) -> Unit) {
        val root = Files.createTempDirectory("agent-patch-manager").toFile()
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
        val source = File(root, "source.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"parent\",\"name\":\"Parent\"}".toByteArray())
            zip.closeEntry()
        }
        block(context, AgentPatchModManager.createWorkspace(context, "Parent", source))
    }
}
