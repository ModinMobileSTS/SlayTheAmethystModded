package io.stamethyst.backend.launch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.config.RuntimePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MtsPatchCacheCoordinatorTest {
    @Test
    fun cacheMarkerChangesWhenModJarIsRebuiltInPlaceWithSameSizeAndMtime() {
        val root = Files.createTempDirectory("mts-patch-cache-rebuilt-mod-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJar(root, "gdx-patch.jar", "f.class" to "gdx")
            // Same entry name and same byte count, different content — this is what an
            // in-place mod rebuild looks like on disk.
            val modJar = writeJar(root, "ExampleMod.jar", "Mod.class" to "AAAA")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            val originalLength = modJar.length()
            val originalMtime = modJar.lastModified()
            writeJar(root, "ExampleMod.jar", "Mod.class" to "BBBB")
            modJar.setLastModified(originalMtime)

            // The scenario is only meaningful if size and mtime really did stay put.
            assertEquals(originalLength, modJar.length())
            assertEquals(originalMtime, modJar.lastModified())

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerIgnoresModJarMtimeWhenContentIsUnchanged() {
        val root = Files.createTempDirectory("mts-patch-cache-mod-mtime-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJar(root, "gdx-patch.jar", "f.class" to "gdx")
            val modJar = writeJar(root, "ExampleMod.jar", "Mod.class" to "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            // A copy or restore that only moves mtime must not throw away a valid cache.
            modJar.setLastModified(modJar.lastModified() + 60_000L)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerChangesWhenGdxPatchArchiveBytesChange() {
        val root = Files.createTempDirectory("mts-patch-cache-gdx-patch-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJarWithComment(
                root,
                "gdx-patch.jar",
                "same-length-comment-v1",
                "f.class" to "gdx"
            )
            val modJar = writeJar(root, "ExampleMod.jar", "Mod.class" to "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = buildMarkerForTest(
                desktopJar,
                mtsJar,
                baseModJar,
                stsLibJar,
                bootBridgeJar,
                gdxPatchJar,
                modFileList
            )
            val originalLength = gdxPatchJar.length()
            val originalMtime = gdxPatchJar.lastModified()

            writeJarWithComment(
                root,
                "gdx-patch.jar",
                "same-length-comment-v2",
                "f.class" to "gdx"
            )
            gdxPatchJar.setLastModified(originalMtime)

            assertEquals(originalLength, gdxPatchJar.length())
            assertEquals(originalMtime, gdxPatchJar.lastModified())
            assertNotEquals(
                first,
                buildMarkerForTest(
                    desktopJar,
                    mtsJar,
                    baseModJar,
                    stsLibJar,
                    bootBridgeJar,
                    gdxPatchJar,
                    modFileList
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerStillDistinguishesNonZipFiles() {
        val root = Files.createTempDirectory("mts-patch-cache-nonzip-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJar(root, "gdx-patch.jar", "f.class" to "gdx")
            // Not a zip at all: the fingerprint must degrade to size and mtime rather
            // than collapsing every unreadable file to one constant.
            val modJar = writeFile(root, "Broken.jar", "not-a-zip")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            modJar.writeText("not-a-zip-either-but-longer", StandardCharsets.UTF_8)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerChangesWhenModFileChanges() {
        val root = Files.createTempDirectory("mts-patch-cache-key-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod-v1")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            modJar.writeText("mod-v2", StandardCharsets.UTF_8)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerDoesNotChangeWhenModFileListMtimeChangesWithoutContentChange() {
        val root = Files.createTempDirectory("mts-patch-cache-list-mtime-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            modFileList.setLastModified(System.currentTimeMillis())

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerChangesWhenBootBridgeChanges() {
        val root = Files.createTempDirectory("mts-patch-cache-boot-bridge-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge-v1")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")

            val first = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            Thread.sleep(5)
            bootBridgeJar.writeText("bootbridge-v2", StandardCharsets.UTF_8)

            val second = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertNotEquals(first, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheCurrentRequiresMarkerAndPackagedJar() {
        val root = Files.createTempDirectory("mts-patch-cache-current-").toFile()
        try {
            val desktopJar = writeFile(root, "desktop-1.0.jar", "desktop")
            val mtsJar = writeFile(root, "ModTheSpire.jar", "mts")
            val baseModJar = writeFile(root, "BaseMod.jar", "basemod")
            val stsLibJar = writeFile(root, "StSLib.jar", "stslib")
            val bootBridgeJar = writeFile(root, "boot-bridge.jar", "bootbridge")
            val gdxPatchJar = writeFile(root, "gdx-patch.jar", "gdx")
            val modJar = writeFile(root, "ExampleMod.jar", "mod")
            val modFileList = writeFile(root, ".mts_mod_file_list", modJar.absolutePath + "\n")
            val cachedJar = File(root, "desktop-1.0-modded.jar")
            val markerFile = File(root, ".mts_patch_cache")
            val packageDir = File(root, "package")

            val marker = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )
            markerFile.writeText(marker, StandardCharsets.UTF_8)

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            cachedJar.writeBytes(ByteArray(1024 * 1024) { 1 })
            writeFile(packageDir, "ExampleMod-modded.jar", "modded")

            assertTrue(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            File(packageDir, "ExampleMod-modded.jar").delete()

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = marker
                )
            )

            writeFile(packageDir, "ExampleMod-modded.jar", "modded")

            assertFalse(
                MtsPatchCacheCoordinator.isCacheCurrent(
                    markerFile = markerFile,
                    cachedJar = cachedJar,
                    packageDir = packageDir,
                    expectedMarker = "$marker\nchanged"
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheRuntimePropertiesAreAppendedWhenCurrent() {
        val args = arrayListOf<String>()
        val cachedJar = File("desktop-1.0-modded.jar")
        val baseJar = File("desktop-1.0.jar")
        val markerFile = File(".mts_patch_cache")
        val packageDir = File("package")
        val gameDir = File("sts")

        MtsPatchCacheCoordinator.appendRuntimeProperties(
            args = args,
            enabled = true,
            cacheCurrent = true,
            cachedJar = cachedJar,
            baseJar = baseJar,
            markerFile = markerFile,
            packageDir = packageDir,
            expectedMarker = "marker",
            gameDir = gameDir
        )

        assertEquals(
            listOf(
                "-Damethyst.mts.patch_cache.enabled=true",
                "-Damethyst.mts.patch_cache.current=true",
                "-Damethyst.mts.patch_cache.jar=${cachedJar.absolutePath}",
                "-Damethyst.mts.patch_cache.base_jar=${baseJar.absolutePath}",
                "-Damethyst.mts.patch_cache.marker=${markerFile.absolutePath}",
                "-Damethyst.mts.patch_cache.package_dir=${packageDir.absolutePath}",
                "-Damethyst.mts.patch_cache.expected=marker",
                "-Damethyst.mts.patch_cache.game_dir=${gameDir.absolutePath}"
            ),
            args
        )
    }

    @Test
    fun cacheRuntimePropertiesStayDisabledWhenFeatureIsOff() {
        val args = arrayListOf<String>()
        val cachedJar = File("desktop-1.0-modded.jar")
        val baseJar = File("desktop-1.0.jar")
        val markerFile = File(".mts_patch_cache")
        val packageDir = File("package")
        val gameDir = File("sts")

        MtsPatchCacheCoordinator.appendRuntimeProperties(
            args = args,
            enabled = false,
            cacheCurrent = false,
            cachedJar = cachedJar,
            baseJar = baseJar,
            markerFile = markerFile,
            packageDir = packageDir,
            expectedMarker = "",
            gameDir = gameDir
        )

        assertEquals(
            listOf(
                "-Damethyst.mts.patch_cache.enabled=false",
                "-Damethyst.mts.patch_cache.current=false",
                "-Damethyst.mts.patch_cache.jar=${cachedJar.absolutePath}",
                "-Damethyst.mts.patch_cache.base_jar=${baseJar.absolutePath}",
                "-Damethyst.mts.patch_cache.marker=${markerFile.absolutePath}",
                "-Damethyst.mts.patch_cache.package_dir=${packageDir.absolutePath}",
                "-Damethyst.mts.patch_cache.expected=",
                "-Damethyst.mts.patch_cache.game_dir=${gameDir.absolutePath}"
            ),
            args
        )
    }

    @Test
    fun clearRemovesCurrentAndLegacyCacheArtifacts() {
        val roots = TestRoots.create("mts-patch-cache-clear-")
        try {
            writeFile(RuntimePaths.mtsPatchCacheJar(roots.context), "current-cache")
            writeFile(File(RuntimePaths.mtsPatchCachePackageDir(roots.context), "ExampleMod.jar"), "pkg")
            writeFile(File(RuntimePaths.legacyInternalStsRoot(roots.context), "desktop-1.0-modded.jar"), "legacy-internal")
            writeFile(
                File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache/loadout-scan-cache/cache.bin"),
                "legacy-internal-scan"
            )
            writeFile(
                File(requireNotNull(RuntimePaths.externalAppStsRoot(roots.context)), ".mts_patch_cache"),
                "legacy-external"
            )

            MtsPatchCacheCoordinator.clear(roots.context)

            assertFalse(RuntimePaths.mtsPatchCacheDir(roots.context).exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "desktop-1.0-modded.jar").exists())
            assertFalse(File(RuntimePaths.legacyInternalStsRoot(roots.context), "mts_patch_cache").exists())
            assertFalse(
                File(requireNotNull(RuntimePaths.externalAppStsRoot(roots.context)), ".mts_patch_cache").exists()
            )
        } finally {
            roots.rootDir.deleteRecursively()
        }
    }

    private fun writeFile(root: File, name: String, text: String): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    @Test
    fun gdxPatchDigestSidecarIsTrustedWhileIdentityMatchesAndRefreshedOnMismatch() {
        val root = Files.createTempDirectory("mts-patch-cache-gdx-digest-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJar(root, "gdx-patch.jar", "f.class" to "gdx")
            val modFileList = writeFile(File(root, ".mts_mod_file_list"), "")
            val digestCache = File(root, "gdx-patch-digest.txt")

            fun marker(): String = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList,
                gdxPatchDigestCache = digestCache
            )

            // First computation records the true digest.
            val trueMarker = marker()
            assertTrue(digestCache.isFile)
            val recorded = digestCache.readText(StandardCharsets.UTF_8).trim()
            assertEquals(3, recorded.split('|').size)

            // A well-formed sidecar entry for the current size+mtime is trusted without
            // reading the jar: a forged digest changes the marker.
            val identity = "${gdxPatchJar.length()}|${gdxPatchJar.lastModified()}"
            digestCache.writeText("$identity|" + "0".repeat(64), StandardCharsets.UTF_8)
            assertNotEquals(trueMarker, marker())

            // A mtime change invalidates the recorded entry, so the true digest is
            // recomputed and re-recorded.
            gdxPatchJar.setLastModified(gdxPatchJar.lastModified() + 60_000L)
            assertEquals(trueMarker, marker())
            assertEquals(
                "${gdxPatchJar.length()}|${gdxPatchJar.lastModified()}",
                digestCache.readText(StandardCharsets.UTF_8).trim().split('|').take(2).joinToString("|")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheMarkerIsDeterministicAcrossRepeatedComputationsWithManyMods() {
        val root = Files.createTempDirectory("mts-patch-cache-parallel-").toFile()
        try {
            val desktopJar = writeJar(root, "desktop-1.0.jar", "a.class" to "desktop")
            val mtsJar = writeJar(root, "ModTheSpire.jar", "b.class" to "mts")
            val baseModJar = writeJar(root, "BaseMod.jar", "c.class" to "basemod")
            val stsLibJar = writeJar(root, "StSLib.jar", "d.class" to "stslib")
            val bootBridgeJar = writeJar(root, "boot-bridge.jar", "e.class" to "bootbridge")
            val gdxPatchJar = writeJar(root, "gdx-patch.jar", "f.class" to "gdx")
            // Enough mods that the fingerprint fan-out runs on its pool rather than inline.
            val modJars = (1..12).map { index ->
                writeJar(root, "Mod$index.jar", "Mod$index.class" to "content-$index")
            }
            val modFileList = writeFile(
                File(root, ".mts_mod_file_list"),
                modJars.joinToString(separator = "\n", postfix = "\n") { it.absolutePath }
            )

            fun marker(): String = MtsPatchCacheCoordinator.buildCacheMarkerValue(
                desktopJar = desktopJar,
                mtsJar = mtsJar,
                baseModJar = baseModJar,
                stsLibJar = stsLibJar,
                bootBridgeJar = bootBridgeJar,
                gdxPatchJar = gdxPatchJar,
                modFileList = modFileList
            )

            assertEquals(marker(), marker())

            // Changing one mod in the middle of the list must move the marker even when
            // the fingerprints were computed concurrently.
            val before = marker()
            val originalMtime = modJars[6].lastModified()
            writeJar(root, "Mod7.jar", "Mod7.class" to "changed-content")
            modJars[6].setLastModified(originalMtime)

            assertNotEquals(before, marker())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeJar(root: File, name: String, vararg entries: Pair<String, String>): File {
        return writeJarWithComment(root, name, null, *entries)
    }

    private fun writeJarWithComment(
        root: File,
        name: String,
        comment: String?,
        vararg entries: Pair<String, String>
    ): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setComment(comment)
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun buildMarkerForTest(
        desktopJar: File,
        mtsJar: File,
        baseModJar: File,
        stsLibJar: File,
        bootBridgeJar: File,
        gdxPatchJar: File,
        modFileList: File
    ): String = MtsPatchCacheCoordinator.buildCacheMarkerValue(
        desktopJar = desktopJar,
        mtsJar = mtsJar,
        baseModJar = baseModJar,
        stsLibJar = stsLibJar,
        bootBridgeJar = bootBridgeJar,
        gdxPatchJar = gdxPatchJar,
        modFileList = modFileList
    )

    private fun writeFile(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
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
