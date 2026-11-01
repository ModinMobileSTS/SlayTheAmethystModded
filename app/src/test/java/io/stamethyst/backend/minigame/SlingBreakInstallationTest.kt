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
        assertTrue(runCatching {
            SlingBreakInstallation.activateWhenReady(context, SlingBreakReleasesTest.parse("v0.0.2", 2)) {
                throw IOException("Checksum mismatch / disk full")
            }
        }.isFailure)
        assertEquals(before, File(SlingBreakInstallation.root(context), "installed-release.json").readText())
        assertEquals(old, SlingBreakInstallation.read(context))
        assertEquals("saved-game", save.readText())
        assertEquals("web-save", web.readText())
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
