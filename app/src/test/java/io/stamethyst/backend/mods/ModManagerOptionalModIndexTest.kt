package io.stamethyst.backend.mods

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SpecialKeyInputMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(0, optionalMods.single().explicitPriority)
        assertEquals(0, optionalMods.single().effectivePriority)
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
            "${optionalJar.absolutePath}\t0",
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
    fun listInstalledMods_reusesLargeOptionalModIndexForWorkshopSizedLibrary() {
        val roots = TestRoots.create("mod-manager-large-optional-index")
        val context = roots.context
        val libraryDir = RuntimePaths.optionalModsLibraryDir(context)
        val expectedIds = (1..260).map { index -> "large_mod_$index" }

        expectedIds.forEachIndexed { index, modId ->
            writeOptionalModJar(
                file = File(libraryDir, "LargeMod%03d.jar".format(index + 1)),
                modId = modId,
                name = "Large Mod ${index + 1}",
                lastModified = 20_000L + index
            )
        }

        val firstPass = ModManager.listInstalledMods(context).filterNot { it.required }
        assertEquals(expectedIds, firstPass.map { it.modId })

        val indexRoot = JSONObject(RuntimePaths.optionalModIndexFile(context).readText(StandardCharsets.UTF_8))
        assertEquals(260, indexRoot.getJSONArray("entries").length())

        val secondPass = ModManager.listInstalledMods(context).filterNot { it.required }
        assertEquals(expectedIds, secondPass.map { it.modId })
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
            listOf("basemod", "stslib", "amethystruntimecompat", "amethystfloatingtools", "ramsaver", "alpha"),
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
            listOf("basemod", "stslib", "amethystruntimecompat", "amethystfloatingtools", "ramsaver", "beta"),
            ModManager.resolveLaunchModIds(context)
        )
    }

    @Test
    fun resolveLaunchModIds_sortsByPriorityAndPullsDependenciesEarlier() {
        val roots = TestRoots.create("mod-manager-priority-order")
        val context = roots.context
        installRequiredLaunchMods(context)
        val alphaJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "Alpha.jar"),
            modId = "alpha",
            name = "Alpha",
            dependencies = listOf("beta"),
            lastModified = 10_000L
        )
        val betaJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "Beta.jar"),
            modId = "beta",
            name = "Beta",
            lastModified = 11_000L
        )
        val gammaJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "Gamma.jar"),
            modId = "gamma",
            name = "Gamma",
            lastModified = 12_000L
        )

        RuntimePaths.stsRoot(context).mkdirs()
        RuntimePaths.enabledModsConfig(context).writeText(
            listOf(alphaJar.absolutePath, betaJar.absolutePath, gammaJar.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )
        RuntimePaths.priorityModsConfig(context).writeText(
            listOf("${gammaJar.absolutePath}\t2", alphaJar.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "amethystfloatingtools", "ramsaver", "beta", "alpha", "gamma"),
            ModManager.resolveLaunchModIds(context)
        )

        val optionalModsById = ModManager.listInstalledMods(context)
            .filterNot { it.required }
            .associateBy { it.modId }
        assertEquals(0, optionalModsById.getValue("alpha").explicitPriority)
        assertEquals(0, optionalModsById.getValue("alpha").effectivePriority)
        assertNull(optionalModsById.getValue("beta").explicitPriority)
        assertEquals(0, optionalModsById.getValue("beta").effectivePriority)
        assertEquals(2, optionalModsById.getValue("gamma").explicitPriority)
        assertEquals(2, optionalModsById.getValue("gamma").effectivePriority)
    }

    @Test
    fun resolveLaunchModIds_ignoresOptionalRamSaverWhenBundledRamSaverIsRequired() {
        val roots = TestRoots.create("mod-manager-skip-optional-ramsaver")
        val context = roots.context
        installRequiredLaunchMods(context)
        val optionalRamSaverJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "OldRamSaver.jar"),
            modId = "ramsaver",
            name = "Ram Saver",
            lastModified = 13_000L
        )
        val alphaJar = writeOptionalModJar(
            file = File(RuntimePaths.optionalModsLibraryDir(context), "Alpha.jar"),
            modId = "alpha",
            name = "Alpha",
            lastModified = 14_000L
        )

        RuntimePaths.stsRoot(context).mkdirs()
        RuntimePaths.enabledModsConfig(context).writeText(
            listOf(optionalRamSaverJar.absolutePath, alphaJar.absolutePath).joinToString("\n"),
            StandardCharsets.UTF_8
        )

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "amethystfloatingtools", "ramsaver", "alpha"),
            ModManager.resolveLaunchModIds(context)
        )
    }

    @Test
    fun resolveLaunchModIds_excludesRamSaverWhenDisabled() {
        val roots = TestRoots.create("mod-manager-ramsaver-disabled")
        val context = roots.context
        installRequiredLaunchMods(context)
        LauncherConfig.setRamSaverEnabled(context, false)

        try {
            assertFalse(ModManager.isRamSaverEnabled(context))
            val ramSaver = ModManager.listInstalledMods(context)
                .single { it.modId == ModManager.MOD_ID_RAM_SAVER }
            assertTrue(ramSaver.installed)
            assertFalse(ramSaver.enabled)
            assertEquals(
                listOf("basemod", "stslib", "amethystruntimecompat", "amethystfloatingtools"),
                ModManager.resolveLaunchModIds(context)
            )
        } finally {
            LauncherConfig.setRamSaverEnabled(context, LauncherConfig.DEFAULT_RAM_SAVER_ENABLED)
        }
    }

    @Test
    fun resolveLaunchModIds_excludesFloatingToolsWhenSpecialKeyModeDoesNotUseBuiltInMod() {
        val roots = TestRoots.create("mod-manager-floating-tools-disabled")
        val context = roots.context
        installRequiredLaunchMods(context)
        LauncherConfig.saveSpecialKeyInputMode(context, SpecialKeyInputMode.LEGACY_FLOATING_WINDOW)

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "ramsaver"),
            ModManager.resolveLaunchModIds(context)
        )

        LauncherConfig.saveSpecialKeyInputMode(context, SpecialKeyInputMode.DISABLED)

        assertEquals(
            listOf("basemod", "stslib", "amethystruntimecompat", "ramsaver"),
            ModManager.resolveLaunchModIds(context)
        )
    }

    @Test
    fun frameProbe_matchesEffectiveDiagnosticsWithoutArthasPack() {
        val context = TestRoots.create("mod-manager-frame-probe-override").context
        installRequiredLaunchMods(context)
        writeOptionalModJar(
            file = RuntimePaths.importedAmethystFrameProbeJar(context),
            modId = ModManager.MOD_ID_AMETHYST_FRAME_PROBE,
            name = "Amethyst Frame Probe",
            lastModified = 9_000L
        )
        LauncherConfig.setGpuResourceDiagEnabled(context, false)

        val normal = ModManager.buildLaunchModSnapshot(context)
        assertFalse(normal.launchModIds.contains(ModManager.MOD_ID_AMETHYST_FRAME_PROBE))

        val diagnostic = ModManager.buildLaunchModSnapshot(context, true)
        assertTrue(diagnostic.launchModIds.contains(ModManager.MOD_ID_AMETHYST_FRAME_PROBE))
        assertTrue(diagnostic.launchModFiles.contains(RuntimePaths.importedAmethystFrameProbeJar(context)))
        assertTrue(ModManager.resolveLaunchModIds(context, true).contains(ModManager.MOD_ID_AMETHYST_FRAME_PROBE))

        LauncherConfig.setGpuResourceDiagEnabled(context, true)
        assertTrue(ModManager.buildLaunchModSnapshot(context).launchModIds.contains(ModManager.MOD_ID_AMETHYST_FRAME_PROBE))
        assertFalse(ModManager.buildLaunchModSnapshot(context, false).launchModIds.contains(ModManager.MOD_ID_AMETHYST_FRAME_PROBE))
    }

    private fun installRequiredLaunchMods(context: Context) {
        LauncherConfig.setRamSaverEnabled(context, LauncherConfig.DEFAULT_RAM_SAVER_ENABLED)
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
        writeOptionalModJar(
            file = RuntimePaths.importedAmethystFloatingToolsJar(context),
            modId = "amethystfloatingtools",
            name = "Amethyst Floating Tools",
            lastModified = 7_500L
        )
        writeOptionalModJar(
            file = RuntimePaths.importedRamSaverJar(context),
            modId = "ramsaver",
            name = "Ram Saver",
            lastModified = 8_000L
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
                val prefs = LinkedHashMap<String, InMemorySharedPreferences>()
                return TestRoots(
                    rootDir = rootDir,
                    context = object : ContextWrapper(Application()) {
                        override fun getFilesDir(): File = filesDir

                        override fun getExternalFilesDir(type: String?): File = externalFilesDir

                        override fun getPackageName(): String = "io.stamethyst.test"

                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            prefs.getOrPut(name) { InMemorySharedPreferences() }
                    }
                )
            }
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
