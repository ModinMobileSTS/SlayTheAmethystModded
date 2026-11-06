package io.stamethyst.backend.minigame

import android.content.Context
import android.content.ContextWrapper
import io.stamethyst.godot.GodotFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SlingBreakInstallationTest {
    @Test fun installedVersionSurvivesFreshReadAndIsNotLauncherVersion() = withContext { context, _ ->
        val release = SlingBreakReleasesTest.parse("v0.0.1", 1)
        payloads(context, release.assetSha256)
        SlingBreakInstallation.activateWhenReady(context, release) {}
        assertEquals(release, SlingBreakInstallation.read(context))
    }

    @Test fun failedPrepareKeepsOldRecordAndAllSaves() = withContext { context, root ->
        val old = SlingBreakReleasesTest.parse("v0.0.1", 1)
        payloads(context, old.assetSha256)
        SlingBreakInstallation.activate(context, old)
        val save = File(root, "godot-prototype-user/save.json").apply { parentFile!!.mkdirs(); writeText("saved-game") }
        val web = File(root, "app_webview/Default/Local Storage/old-save").apply { parentFile!!.mkdirs(); writeText("web-save") }
        val before = File(SlingBreakInstallation.root(context), "installed-release.json").readText()
        val previousEngine = File(SlingBreakInstallation.root(context), "engine-${"c".repeat(64)}/engine.apk")
            .apply { parentFile!!.mkdirs(); writeText("previous-engine") }
        val next = SlingBreakReleasesTest.parse("v0.0.2", 2).copy(assetSha256 = "b".repeat(64))
        assertTrue(runCatching {
            SlingBreakInstallation.activateWhenReady(context, next) {
                payloads(context, next.assetSha256)
                throw IOException("Checksum mismatch / disk full")
            }
        }.isFailure)
        assertEquals(before, File(SlingBreakInstallation.root(context), "installed-release.json").readText())
        assertEquals(old, SlingBreakInstallation.read(context))
        assertTrue(File(SlingBreakInstallation.root(context), "game-${old.assetSha256}.pck").isFile)
        assertTrue(File(SlingBreakInstallation.root(context), "assets-${old.assetSha256}.zip").isFile)
        assertEquals("previous-engine", previousEngine.readText())
        assertEquals("saved-game", save.readText())
        assertEquals("web-save", web.readText())
    }

    @Test fun successfulUpgradeKeepsOnlyCurrentGameAndOneEngine() = withContext { context, root ->
        val old = SlingBreakReleasesTest.parse("v0.0.1", 1)
        val next = SlingBreakReleasesTest.parse("v0.0.2", 2).copy(assetSha256 = "b".repeat(64))
        payloads(context, old.assetSha256)
        SlingBreakInstallation.activate(context, old)
        val components = SlingBreakInstallation.root(context)
        val oldEngine = File(components, "engine-${"c".repeat(64)}/lib/libgodot_android.so")
            .apply { parentFile!!.mkdirs(); writeText("old-engine"); setReadOnly() }
        val retiredEngine = File(components, "engine-retired-12345678-1234-1234-1234-123456789abc/engine.apk")
            .apply { parentFile!!.mkdirs(); writeText("retired") }
        val save = File(root, "godot-prototype-user/save.json").apply { parentFile!!.mkdirs(); writeText("save") }
        val webSave = File(root, "app_webview/Default/save").apply { parentFile!!.mkdirs(); writeText("web") }
        val lock = File(components, "install.lock").apply { writeText("lock") }

        val failures = SlingBreakInstallation.activateWhenReady(context, next) {
            assertEquals(old, SlingBreakInstallation.read(context))
            assertTrue(oldEngine.isFile)
            assertTrue(File(components, "game-${old.assetSha256}.pck").isFile)
            payloads(context, next.assetSha256)
        }

        assertTrue(failures.isEmpty())
        assertEquals(next, SlingBreakInstallation.read(context))
        assertFalse(File(components, "game-${old.assetSha256}.pck").exists())
        assertFalse(File(components, "assets-${old.assetSha256}.zip").exists())
        assertFalse(oldEngine.parentFile.parentFile.exists())
        assertFalse(retiredEngine.parentFile.exists())
        assertEquals(listOf("engine-${SlingBreakReleases.ENGINE_SHA256}"),
            components.listFiles()!!.filter { it.name.startsWith("engine-") }.map { it.name })
        assertEquals("game", File(components, "game-${next.assetSha256}.pck").readText())
        assertEquals("mount", File(components, "assets-${next.assetSha256}.zip").readText())
        assertEquals("save", save.readText())
        assertEquals("web", webSave.readText())
        assertEquals("lock", lock.readText())
    }

    @Test fun reinstallingCurrentVersionAlsoRemovesAccumulatedOldComponents() = withContext { context, _ ->
        val current = SlingBreakReleasesTest.parse("v0.0.2", 2)
        payloads(context, current.assetSha256)
        SlingBreakInstallation.activate(context, current)
        val oldHash = "b".repeat(64)
        payloads(context, oldHash)
        assertTrue(SlingBreakInstallation.activateWhenReady(context, current) {}.isEmpty())
        assertEquals(current, SlingBreakInstallation.read(context))
        assertFalse(File(SlingBreakInstallation.root(context), "game-$oldHash.pck").exists())
        assertFalse(File(SlingBreakInstallation.root(context), "assets-$oldHash.zip").exists())
    }

    @Test fun failedAtomicActivationDoesNotPrunePreviousComponents() = withContext { context, _ ->
        val old = SlingBreakReleasesTest.parse("v0.0.1", 1)
        val next = SlingBreakReleasesTest.parse("v0.0.2", 2).copy(assetSha256 = "b".repeat(64))
        payloads(context, old.assetSha256)
        val components = SlingBreakInstallation.root(context)
        // A nonempty directory at the destination deterministically makes atomic replacement fail.
        File(components, "installed-release.json/blocker").apply { parentFile!!.mkdirs(); writeText("blocked") }
        assertTrue(runCatching {
            SlingBreakInstallation.activateWhenReady(context, next) { payloads(context, next.assetSha256) }
        }.isFailure)
        assertTrue(File(components, "game-${old.assetSha256}.pck").isFile)
        assertTrue(File(components, "assets-${old.assetSha256}.zip").isFile)
        assertFalse(components.listFiles()!!.any { it.name.startsWith("release-") && it.name.endsWith(".part") })
    }

    @Test fun sameTagReplacementPrunesByAssetHashRatherThanTag() = withContext { context, _ ->
        val old = SlingBreakReleasesTest.parse("v0.0.1", 1)
        val replacement = old.copy(assetSha256 = "b".repeat(64), assetId = 11)
        payloads(context, old.assetSha256)
        SlingBreakInstallation.activate(context, old)
        val components = SlingBreakInstallation.root(context)
        SlingBreakInstallation.activateWhenReady(context, replacement) { payloads(context, replacement.assetSha256) }
        assertEquals(replacement, SlingBreakInstallation.read(context))
        assertFalse(File(components, "game-${old.assetSha256}.pck").exists())
        assertFalse(File(components, "assets-${old.assetSha256}.zip").exists())
        assertTrue(File(components, "game-${replacement.assetSha256}.pck").isFile)
    }

    @Test fun missingPayloadOrCorruptRecordIsNotInstalled() = withContext { context, _ ->
        val release = SlingBreakReleasesTest.parse("v0.0.1", 1)
        payloads(context, release.assetSha256)
        SlingBreakInstallation.activate(context, release)
        File(SlingBreakInstallation.root(context), "game-${release.assetSha256}.pck").delete()
        assertNull(SlingBreakInstallation.read(context))
        File(SlingBreakInstallation.root(context), "installed-release.json").writeText("partial")
        assertNull(SlingBreakInstallation.read(context))
    }

    @Test fun derivedMountIsStoredAndRejectsWrongHash() = withContext { _, root ->
        val pck = File(root, "sample.pck").apply { writeText("GDPC-test-content") }
        val mount = File(root, "assets.zip")
        GodotFiles.writeAssets(pck, mount)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(pck.readBytes()).joinToString("") { "%02x".format(it) }
        GodotFiles.verifyAssets(mount, sha)
        assertTrue(runCatching { GodotFiles.verifyAssets(mount, "0".repeat(64)) }.isFailure)
    }

    @Test fun incompatiblePackIsRejected() = withContext { _, root ->
        val file = File(root, "game.pck")
        val header = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (value in listOf(0x43504447, 4, 4, 7, 2, 2)) header.putInt(value)
        file.writeBytes(header.array())
        SlingBreakService.validatePck(file)
        header.putInt(16, 1); file.writeBytes(header.array())
        assertTrue(runCatching { SlingBreakService.validatePck(file) }.isFailure)
    }

    private fun payloads(context: Context, sha: String) {
        val root = SlingBreakInstallation.root(context).apply { mkdirs() }
        File(root, "game-$sha.pck").writeText("game")
        File(root, "assets-$sha.zip").writeText("mount")
        File(root, "engine-${SlingBreakReleases.ENGINE_SHA256}/engine.apk").apply { parentFile!!.mkdirs(); writeText("engine") }
    }

    private fun withContext(test: (Context, File) -> Unit) {
        val root = Files.createTempDirectory("slingbreak-test").toFile()
        try {
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = root
            }
            test(context, root)
        } finally { root.deleteRecursively() }
    }
}
