package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentModInspectionManagerTest {
    @Test
    fun createInspection_extractsSourceWithoutCreatingPatchWorkspace() {
        val root = Files.createTempDirectory("agent-mod-inspection").toFile()
        val context = testContext(root)
        val source = File(root, "source.jar")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("{\"modid\":\"ShoujoKageki\"}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("com/example/Parent.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            zip.closeEntry()
        }

        val inspection = AgentModInspectionManager.createInspection(
            context = context,
            parentModId = "shoujokageki",
            sourceJar = source,
        )

        assertEquals("ShoujoKageki", inspection.parentModId)
        assertTrue(inspection.sourceRoot.resolve("ModTheSpire.json").isFile)
        assertTrue(inspection.sourceRoot.resolve("com/example/Parent.class").isFile)
        assertFalse(inspection.root.resolve("patch").exists())
        val metadata = JSONObject(inspection.root.resolve("inspection-metadata.json").readText())
        assertEquals("ShoujoKageki", metadata.getString("parent_mod_id"))
        assertFalse(metadata.getBoolean("source_decompiled"))
    }

    private fun testContext(root: File): Context {
        val filesDir = File(root, "files").apply { mkdirs() }
        val externalFilesDir = File(root, "external").apply { mkdirs() }
        return object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir
            override fun getExternalFilesDir(type: String?): File = externalFilesDir
        }
    }
}
