package io.stamethyst.backend.llm

import io.stamethyst.backend.mods.ModManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AgentModReadTargetsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun mod(id: String, manifestId: String, jar: File, installed: Boolean = true) =
        ModManager.InstalledMod(id, manifestId, id, "1", "", emptyList(), jar, false, installed, false, null, null)

    @Test fun defaultsToParentWithoutConsultingCatalog() {
        val parent = temp.newFile("parent.jar")
        val targets = AgentModReadTargets("parent", parent) { error("unexpected catalog access") }
        for (id in listOf(null, "", "PARENT")) {
            assertEquals(parent, targets.resolve(id).jar)
        }
    }

    @Test fun inspectionToolsAdvertiseOptionalModId() {
        val jar = temp.newFile("parent.jar")
        val tools = listOf(
            AgentModEntriesListTool(jar), AgentModResourceReadTool(jar),
            AgentModClassInspectTool("parent", jar),
            AgentModClassDecompileTool(android.app.Application(), "parent", jar),
        )
        tools.forEach { tool ->
            val schema = tool.specification.parameters()
            for (id in listOf("mod_id", "patch_mod_id")) {
                assertTrue(tool.specification.name(), schema.properties().containsKey(id))
                assertFalse(tool.specification.name(), schema.required().orEmpty().contains(id))
            }
        }
    }

    @Test fun resolvesDisabledModsAndManifestAliasesButRejectsUnknownOrMissingMods() {
        val parent = temp.newFile("parent.jar")
        val other = temp.newFile("other.jar")
        val targets = AgentModReadTargets("parent", parent) {
            listOf(mod("other", "OtherAlias", other), mod("missing", "MissingAlias", File(temp.root, "absent.jar")),
                mod("uninstalled", "UninstalledAlias", other, installed = false))
        }
        assertEquals(other, targets.resolve(" OTHERALIAS ").jar)
        assertEquals("other", targets.resolve("other").modId)
        for (id in listOf("missing", "uninstalled", "unknown", other.absolutePath, "../other.jar")) {
            assertEquals("mod_not_installed", runCatching { targets.resolve(id) }.exceptionOrNull()?.message)
        }
    }

    @Test fun rejectsAmbiguousManifestAliasesAndPrefersCatalogId() {
        val jar = temp.newFile("other.jar")
        val targets = AgentModReadTargets("parent", jar) {
            listOf(mod("one", "shared", jar), mod("two", "shared", jar), mod("shared", "Third", jar))
        }
        assertEquals("shared", targets.resolve("shared").modId)
        val ambiguous = AgentModReadTargets("parent", jar) {
            listOf(mod("one", "shared", jar), mod("two", "shared", jar))
        }
        assertEquals("ambiguous_mod_id_use_catalog_mod_id", runCatching { ambiguous.resolve("shared") }.exceptionOrNull()?.message)
    }

    @Test fun listAndResourceToolsReadOnlyTheRequestedJarAndKeepParentDefault() {
        fun jar(name: String, entry: String, content: String): File {
            val file = File(temp.root, name)
            JarOutputStream(file.outputStream()).use {
                it.putNextEntry(JarEntry(entry))
                it.write(content.toByteArray())
                it.closeEntry()
            }
            return file
        }
        val parent = jar("parent.jar", "parent.txt", "parent")
        val other = jar("other.jar", "other.txt", "other")
        val targets = AgentModReadTargets("parent", parent) { listOf(mod("other", "Other", other)) }
        val list = AgentModEntriesListTool(parent, targets)
        assertTrue(list.execute("{}").contains("parent.txt"))
        val result = list.execute("""{"mod_id":"other"}""")
        assertTrue(result.contains("other.txt"))
        assertFalse(result.contains("parent.txt"))
        val read = AgentModResourceReadTool(parent, targets)
        val resource = Json.parseToJsonElement(read.execute("""{"mod_id":"other","entry":"other.txt"}""")).jsonObject
        assertEquals("other", resource["content"]?.jsonPrimitive?.content)
        assertTrue(read.execute("""{"mod_id":"other","entry":"parent.txt"}""").contains("resource_not_found"))
        assertTrue(list.execute("""{"mod_id":"unknown"}""").contains("mod_not_installed"))
        assertTrue(AgentModClassInspectTool("parent", parent, targets)
            .execute("""{"mod_id":"unknown","class_name":"Example"}""").contains("mod_not_installed"))
    }

    @Test fun inspectReadsClassesFromAnotherModRatherThanParent() {
        val parent = temp.newFile("empty-parent.jar")
        val baseMod = listOf(File("src/main/assets/components/mods/BaseMod.jar"),
            File("app/src/main/assets/components/mods/BaseMod.jar")).first(File::isFile)
        val targets = AgentModReadTargets("parent", parent) { listOf(mod("basemod", "BaseMod", baseMod)) }
        val inspect = AgentModClassInspectTool("parent", parent, targets)
        val result = Json.parseToJsonElement(inspect.execute(
            """{"mod_id":"basemod","class_name":"basemod.abstracts.CustomCard"}"""
        )).jsonObject
        assertEquals("basemod", result["mod_id"]?.jsonPrimitive?.content)
        assertEquals("parent", result["parent_mod_id"]?.jsonPrimitive?.content)
        assertEquals("basemod.abstracts.CustomCard", result["name"]?.jsonPrimitive?.content)
        val target = targets.resolve("basemod")
        val source = io.stamethyst.backend.mods.AgentPatchClassDecompiler.decompileClass(
            target.jar, "basemod.interfaces.EditCardsSubscriber", target.jar.absolutePath, temp.root,
        ).source
        assertTrue(source.contains("interface EditCardsSubscriber"))
        assertTrue(source.contains("receiveEditCards"))
        val decompile = AgentModClassDecompileTool(android.app.Application(), "parent", parent, targets)
        assertTrue(decompile.execute("""{"mod_id":"unknown","class_name":"Example"}""").contains("mod_not_installed"))
    }
}
