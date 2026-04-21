package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StsDesktopJarPatcherTest {
    @Test
    fun requiredPatchClasses_includeFrameBufferOwnerSummary() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS))
    }

    @Test
    fun requiredPatchClasses_includeTextureOwnerSummary() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_TEXTURE_OWNER_SUMMARY_CLASS))
    }

    @Test
    fun shouldPatchStsEntry_acceptsFrameBufferOwnerSummary() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS
        ) as Boolean

        assertTrue(included)
    }

    @Test
    fun shouldPatchStsEntry_acceptsTextureOwnerSummary() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_TEXTURE_OWNER_SUMMARY_CLASS
        ) as Boolean

        assertTrue(included)
    }

    @Test
    fun shouldPatchStsEntry_acceptsGlTextureInnerClasses() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val namedInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            "com/badlogic/gdx/graphics/GLTexture\$TextureAttribution.class"
        ) as Boolean
        val anonymousInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            "com/badlogic/gdx/graphics/GLTexture\$1.class"
        ) as Boolean

        assertTrue(namedInnerIncluded)
        assertTrue(anonymousInnerIncluded)
    }

    @Test
    fun shouldPatchStsEntry_acceptsGlFrameBufferInnerClasses() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val namedInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            "com/badlogic/gdx/graphics/glutils/GLFrameBuffer\$FrameBufferPressureSweepResult.class"
        ) as Boolean
        val anonymousInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            "com/badlogic/gdx/graphics/glutils/GLFrameBuffer\$1.class"
        ) as Boolean

        assertTrue(namedInnerIncluded)
        assertTrue(anonymousInnerIncluded)
    }

    @Test
    fun recoverInterruptedPatchArtifacts_restoresPatchedTempWhenTargetMissing() {
        val directory = Files.createTempDirectory("sts-desktop-patcher").toFile()
        val target = File(directory, "desktop-1.0.jar")
        val temp = File(directory, "desktop-1.0.jar.patching.tmp").apply {
            writeText("patched")
        }
        val backup = File(directory, "desktop-1.0.jar.patching.backup").apply {
            writeText("original")
        }

        StsDesktopJarPatcher.recoverInterruptedPatchArtifacts(
            targetJar = target,
            tempJar = temp,
            backupJar = backup,
            isValidPatchedJar = { file -> file.readText() == "patched" }
        )

        assertTrue(target.isFile)
        assertEquals("patched", target.readText())
        assertTrue(!temp.exists())
        assertTrue(!backup.exists())
    }

    @Test
    fun recoverInterruptedPatchArtifacts_promotesPatchedTempOverOriginalTarget() {
        val directory = Files.createTempDirectory("sts-desktop-patcher").toFile()
        val target = File(directory, "desktop-1.0.jar").apply {
            writeText("original")
        }
        val temp = File(directory, "desktop-1.0.jar.patching.tmp").apply {
            writeText("patched")
        }
        val backup = File(directory, "desktop-1.0.jar.patching.backup")

        StsDesktopJarPatcher.recoverInterruptedPatchArtifacts(
            targetJar = target,
            tempJar = temp,
            backupJar = backup,
            isValidPatchedJar = { file -> file.readText() == "patched" }
        )

        assertTrue(target.isFile)
        assertEquals("patched", target.readText())
        assertTrue(!temp.exists())
        assertTrue(!backup.exists())
    }

    @Test
    fun recoverInterruptedPatchArtifacts_restoresBackupWhenOnlyBackupRemains() {
        val directory = Files.createTempDirectory("sts-desktop-patcher").toFile()
        val target = File(directory, "desktop-1.0.jar")
        val temp = File(directory, "desktop-1.0.jar.patching.tmp")
        val backup = File(directory, "desktop-1.0.jar.patching.backup").apply {
            writeText("original")
        }

        StsDesktopJarPatcher.recoverInterruptedPatchArtifacts(
            targetJar = target,
            tempJar = temp,
            backupJar = backup,
            isValidPatchedJar = { file -> file.readText() == "patched" }
        )

        assertTrue(target.isFile)
        assertEquals("original", target.readText())
        assertTrue(!backup.exists())
    }

    @Test
    fun replaceTargetJarWithBackup_promotesTempAndCleansBackup() {
        val directory = Files.createTempDirectory("sts-desktop-patcher").toFile()
        val target = File(directory, "desktop-1.0.jar").apply {
            writeText("original")
        }
        val temp = File(directory, "desktop-1.0.jar.patching.tmp").apply {
            writeText("patched")
        }
        val backup = File(directory, "desktop-1.0.jar.patching.backup")

        StsDesktopJarPatcher.replaceTargetJarWithBackup(
            targetJar = target,
            tempJar = temp,
            backupJar = backup
        )

        assertTrue(target.isFile)
        assertEquals("patched", target.readText())
        assertTrue(!temp.exists())
        assertTrue(!backup.exists())
    }
}
