package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentPatchSmokeTestModListTest {
    @Test
    fun resolve_ordersBuiltInsThenDependenciesThenParentThenPatch() {
        val root = Files.createTempDirectory("smoke-mod-list").toFile()
        val baseMod = jar(root, "BaseMod.jar")
        val stsLib = jar(root, "StSLib.jar")
        val dependency = jar(root, "Dependency.jar")
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = linkedMapOf("basemod" to baseMod, "stslib" to stsLib),
            requiredModIds = setOf("basemod", "stslib"),
            rootModIds = listOf("parent"),
            explicitJars = linkedMapOf("parent" to parent, "amethyst.ai.patch.parent.patch-1" to patch),
            dependenciesByModId = mapOf(
                "parent" to listOf("stslib", "dependency"),
                "dependency" to listOf("stslib"),
            ),
            jarByModId = mapOf("dependency" to dependency, "parent" to parent),
        )

        assertEquals(listOf("basemod", "stslib", "dependency", "parent", "amethyst.ai.patch.parent.patch-1"), selection.filesByModId.keys.toList())
        assertEquals(listOf(baseMod, stsLib, dependency, parent, patch), selection.files)
        assertTrue(selection.unresolvedDependencies.isEmpty())
    }

    @Test
    fun resolve_reportsMissingDependencies() {
        val root = Files.createTempDirectory("smoke-mod-list-missing").toFile()
        val baseMod = jar(root, "BaseMod.jar")
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = linkedMapOf("basemod" to baseMod),
            requiredModIds = setOf("basemod"),
            rootModIds = listOf("parent"),
            explicitJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = mapOf("parent" to listOf("missinglib", "basemod")),
            jarByModId = emptyMap(),
        )

        assertEquals(listOf("missinglib"), selection.unresolvedDependencies)
        assertEquals(listOf("basemod", "parent", "patch"), selection.filesByModId.keys.toList())
    }

    @Test
    fun resolve_skipsMissingJarFiles() {
        val root = Files.createTempDirectory("smoke-mod-list-missing-jar").toFile()
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")
        val absent = File(root, "Absent.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = emptyMap(),
            requiredModIds = emptySet(),
            rootModIds = listOf("parent"),
            explicitJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = mapOf("parent" to listOf("dependency")),
            jarByModId = mapOf("dependency" to absent),
        )

        assertEquals(listOf("parent", "patch"), selection.filesByModId.keys.toList())
        assertTrue(selection.unresolvedDependencies.isEmpty())
    }

    private fun jar(root: File, name: String): File = File(root, name).apply { writeText("jar") }
}
