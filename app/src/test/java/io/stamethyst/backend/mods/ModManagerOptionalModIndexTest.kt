package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModManagerOptionalModIndexTest {
    @Test
    fun listInstalledMods_rewritesLegacySelectionConfigsToCurrentStorageRoot() {
        val roots = TestRoots.create("mod-manager-legacy-enabled")
        val context = roots.context
        val optionalJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "Alpha.jar"),
            modId = "alpha",
            name = "Alpha",
            lastModified = 1_000L
        )

        RuntimePaths.stsRoot(context).mkdirs()
        RuntimePaths.enabledModsConfig(context).writeText(
            File(File(context.filesDir, "sts"), "mods_library/Alpha.jar").absolutePath,
            StandardCharsets.UTF_8
        )
        RuntimePaths.priorityModsConfig(context).writeText(
            File(File(context.filesDir, "sts"), "mods_library/Alpha.jar").absolutePath,
            StandardCharsets.UTF_8
        )

        val optionalMods = ModManager.listInstalledMods(context).filterNot { it.required }

        assertEquals(1, optionalMods.size)
        assertEquals(optionalJar.absolutePath, optionalMods.single().jarFile.absolutePath)
        assertTrue(optionalMods.single().enabled)
        assertTrue(optionalMods.single().priorityRoot)
        assertEquals(
            optionalJar.absolutePath,
            JSONObject(RuntimePaths.optionalModIndexFile(context).readText(StandardCharsets.UTF_8))
                .getJSONArray("entries")
                .getJSONObject(0)
                .getString("storagePath")
        )
        assertEquals(
            optionalJar.absolutePath,
            RuntimePaths.enabledModsConfig(context).readText(StandardCharsets.UTF_8).trim()
        )
        assertEquals(
            optionalJar.absolutePath,
            RuntimePaths.priorityModsConfig(context).readText(StandardCharsets.UTF_8).trim()
        )
    }

    @Test
    fun listInstalledMods_followsManualAddAndDeleteInLibrary() {
        val roots = TestRoots.create("mod-manager-manual-storage")
        val context = roots.context
        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        val alphaJar = writeOptionalModJar(
            file = File(libraryDir, "Alpha.jar"),
            modId = "alpha",
            name = "Alpha",
            lastModified = 2_000L
        )

        val firstPass = ModManager.listInstalledMods(context).filterNot { it.required }
        assertEquals(listOf(alphaJar.absolutePath), firstPass.map { it.jarFile.absolutePath })

        assertTrue(alphaJar.delete())
        val betaJar = writeOptionalModJar(
            file = File(libraryDir, "Beta.jar"),
            modId = "beta",
            name = "Beta",
            lastModified = 3_000L
        )

        val secondPass = ModManager.listInstalledMods(context).filterNot { it.required }
        assertEquals(listOf(betaJar.absolutePath), secondPass.map { it.jarFile.absolutePath })
        assertEquals(listOf("beta"), secondPass.map { it.modId })
    }

    @Test
    fun resolveLaunchModIds_refreshesWhenUserReplacesJarInPlace() {
        val roots = TestRoots.create("mod-manager-launch-refresh")
        val context = roots.context
        installRequiredLaunchMods(context)
        val optionalJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "SameName.jar"),
            modId = "alpha",
            name = "Alpha",
            description = "first",
            lastModified = 4_000L
        )
        RuntimePaths.stsRoot(context).mkdirs()
        RuntimePaths.enabledModsConfig(context).writeText(
            optionalJar.absolutePath,
            StandardCharsets.UTF_8
        )

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "alpha"),
            ModManager.resolveLaunchModIds(context)
        )

        writeOptionalModJar(
            file = optionalJar,
            modId = "beta",
            name = "Beta",
            description = "second replacement",
            lastModified = 9_000L
        )

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "beta"),
            ModManager.resolveLaunchModIds(context)
        )
    }

    private fun installRequiredLaunchMods(context: Context) {
        writeOptionalModJar(
            file = RuntimePaths.importedBaseModJar(context),
            modId = "basemod",
            name = "BaseMod",
            lastModified = 5_000L
        )
        writeOptionalModJar(
            file = RuntimePaths.importedStsLibJar(context),
            modId = "stslib",
            name = "StSLib",
            lastModified = 6_000L
        )
        writeOptionalModJar(
            file = RuntimePaths.importedAmethystRuntimeCompatJar(context),
            modId = "amethystruntimecompat",
            name = "Amethyst Runtime Compat",
            lastModified = 7_000L
        )
    }

    private fun writeOptionalModJar(
        file: File,
        modId: String,
        name: String,
        version: String = "1.0.0",
        description: String = "desc",
        dependencies: List<String> = emptyList(),
        lastModified: Long
    ): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zipOutput ->
            zipOutput.putNextEntry(ZipEntry("ModTheSpire.json"))
            val manifest = JSONObject().apply {
                put("modid", modId)
                put("name", name)
                put("version", version)
                put("description", description)
                put("dependencies", JSONArray().apply {
                    dependencies.forEach(::put)
                })
            }
            zipOutput.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
            zipOutput.closeEntry()
        }
        file.setLastModified(lastModified)
        return file
    }

    private class TestRoots private constructor(
        val rootDir: File,
        val context: Context
    ) {
        companion object {
            fun create(prefix: String): TestRoots {
                val rootDir = Files.createTempDirectory(prefix).toFile()
                val filesDir = File(rootDir, "internal-files").apply { mkdirs() }
                val externalFilesDir = File(rootDir, "external-files").apply { mkdirs() }
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"
                    }
                )
            }
        }
    }
}
