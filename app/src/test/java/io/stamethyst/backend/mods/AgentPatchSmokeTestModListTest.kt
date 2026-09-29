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
            rootJars = linkedMapOf("parent" to parent, "amethyst.ai.patch.parent.patch-1" to patch),
            dependenciesByModId = mapOf(
                "parent" to listOf("stslib", "dependency"),
                "dependency" to listOf("stslib"),
            ),
            jarByModId = mapOf("dependency" to dependency, "parent" to parent),
        )

        assertEquals(
            listOf("basemod", "stslib", "dependency", "parent", "amethyst.ai.patch.parent.patch-1"),
            selection.filesByModId.keys.toList(),
        )
        assertEquals(listOf(baseMod, stsLib, dependency, parent, patch), selection.files)
        assertTrue(selection.unresolvedDependencies.isEmpty())
        assertTrue(selection.unknownSelectedModIds.isEmpty())
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
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
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
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = mapOf("parent" to listOf("dependency")),
            jarByModId = mapOf("dependency" to absent),
        )

        assertEquals(listOf("parent", "patch"), selection.filesByModId.keys.toList())
        assertTrue(selection.unresolvedDependencies.isEmpty())
    }

    @Test
    fun resolve_addsSelectedModsAndTheirDependenciesAfterTheBaseline() {
        val root = Files.createTempDirectory("smoke-mod-list-selected").toFile()
        val baseMod = jar(root, "BaseMod.jar")
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")
        val selected = jar(root, "Selected.jar")
        val selectedDependency = jar(root, "SelectedDependency.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = linkedMapOf("basemod" to baseMod),
            requiredModIds = setOf("basemod"),
            rootModIds = listOf("parent"),
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = mapOf(
                "parent" to emptyList(),
                "selected" to listOf("selecteddependency"),
            ),
            jarByModId = mapOf(
                "selected" to selected,
                "selecteddependency" to selectedDependency,
            ),
            selectedModIds = listOf("Selected"),
        )

        assertEquals(
            listOf("basemod", "parent", "patch", "selected", "selecteddependency"),
            selection.filesByModId.keys.toList(),
        )
        assertTrue(selection.unknownSelectedModIds.isEmpty())
        assertTrue(selection.unresolvedDependencies.isEmpty())
    }

    @Test
    fun resolve_reportsUnknownSelectedModsWithoutDroppingThemSilently() {
        val root = Files.createTempDirectory("smoke-mod-list-unknown").toFile()
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = emptyMap(),
            requiredModIds = emptySet(),
            rootModIds = listOf("parent"),
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = emptyMap(),
            jarByModId = emptyMap(),
            selectedModIds = listOf("notinstalled"),
        )

        assertEquals(listOf("notinstalled"), selection.unknownSelectedModIds)
        assertEquals(listOf("parent", "patch"), selection.filesByModId.keys.toList())
    }

    @Test
    fun resolve_ignoresSelectedBuiltInMods() {
        val root = Files.createTempDirectory("smoke-mod-list-builtin").toFile()
        val baseMod = jar(root, "BaseMod.jar")
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = linkedMapOf("basemod" to baseMod),
            requiredModIds = setOf("basemod"),
            rootModIds = listOf("parent"),
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = emptyMap(),
            jarByModId = emptyMap(),
            selectedModIds = listOf("BaseMod"),
        )

        assertTrue(selection.unknownSelectedModIds.isEmpty())
        assertEquals(listOf("basemod", "parent", "patch"), selection.filesByModId.keys.toList())
    }

    @Test
    fun resolve_doesNotDuplicateModsSharedWithTheBaseline() {
        val root = Files.createTempDirectory("smoke-mod-list-shared").toFile()
        val baseMod = jar(root, "BaseMod.jar")
        val parent = jar(root, "Parent.jar")
        val patch = jar(root, "Patch.jar")
        val shared = jar(root, "Shared.jar")

        val selection = AgentPatchSmokeTestModList.resolve(
            builtInJars = linkedMapOf("basemod" to baseMod),
            requiredModIds = setOf("basemod"),
            rootModIds = listOf("parent"),
            rootJars = linkedMapOf("parent" to parent, "patch" to patch),
            dependenciesByModId = mapOf(
                "parent" to listOf("shared"),
                "selected" to listOf("shared"),
            ),
            jarByModId = mapOf("shared" to shared, "selected" to jar(root, "Selected.jar")),
            selectedModIds = listOf("selected"),
        )

        assertEquals(
            listOf("basemod", "shared", "parent", "patch", "selected"),
            selection.filesByModId.keys.toList(),
        )
    }

    private fun jar(root: File, name: String): File = File(root, name).apply { writeText("jar") }
}
