package io.stamethyst.backend.mods

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StsDesktopJarPatcherTest {
    @Test
    fun requiredPatchClasses_includeFrameBufferOwnerSummary() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS))
    }

    @Test
    fun requiredPatchClasses_includeLwjglHotLoopConfig() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_LWJGL_HOT_LOOP_CONFIG_CLASS))
    }

    @Test
    fun requiredPatchClasses_includeLwjglFramePacerSchedule() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_LWJGL_FRAME_PACER_SCHEDULE_CLASS))
    }

    @Test
    fun shouldPatchStsEntry_acceptsLwjglFramePacerSchedule() {
        // LwjglApplication calls into this class from the per-frame pacing path. It is a separate
        // top-level class, so it does not match the LwjglApplication prefix rule and has to be listed
        // explicitly; otherwise the patched desktop jar ships a LwjglApplication that immediately
        // throws NoClassDefFoundError on the first frame.
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_LWJGL_FRAME_PACER_SCHEDULE_CLASS
        ) as Boolean

        assertTrue(included)
    }

    @Test
    fun shouldPatchStsEntry_acceptsFrameRingBufferInnerClasses() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            "com/badlogic/gdx/backends/lwjgl/FrameRingBuffer\$FrameConsumer.class"
        ) as Boolean

        assertTrue(included)
    }

    @Test
    fun requiredPatchClasses_includeTextureOwnerSummary() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_TEXTURE_OWNER_SUMMARY_CLASS))
    }

    @Test
    fun requiredPatchClasses_includeGpuGuardianSupportClasses() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_GPU_RESOURCE_GUARDIAN_CLASS))
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_GPU_RESOURCE_GUARDIAN_MODE_CLASS))
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_GPU_RESOURCE_GUARDIAN_STATE_CLASS))
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_GPU_LEAK_INJECTOR_CLASS))
    }

    @Test
    fun requiredPatchClasses_includeFragmentShaderCompatSupportClasses() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_FRAGMENT_SHADER_COMPAT_CLASS))
        assertTrue(
            REQUIRED_STS_PATCH_CLASSES.contains(
                STS_PATCH_FRAGMENT_SHADER_COMPAT_INTEGER_LITERAL_CLASS
            )
        )
    }

    @Test
    fun requiredPatchClasses_includeFirstPersonGyroBridge() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_FIRST_PERSON_GYRO_BRIDGE_CLASS))
    }

    @Test
    fun requiredPatchClasses_doNotRequireOptionsPanelFromPatchJar() {
        assertFalse(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_OPTIONS_PANEL_CLASS))
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
    fun shouldPatchStsEntry_acceptsLwjglHotLoopConfig() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_LWJGL_HOT_LOOP_CONFIG_CLASS
        ) as Boolean

        assertTrue(included)
    }

    @Test
    fun shouldPatchStsEntry_acceptsGpuGuardianSupportClasses() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val guardianIncluded = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_GPU_RESOURCE_GUARDIAN_CLASS
        ) as Boolean
        val injectorIncluded = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_GPU_LEAK_INJECTOR_CLASS
        ) as Boolean
        val guardianInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_GPU_RESOURCE_GUARDIAN_MODE_CLASS
        ) as Boolean

        assertTrue(guardianIncluded)
        assertTrue(injectorIncluded)
        assertTrue(guardianInnerIncluded)
    }

    @Test
    fun shouldPatchStsEntry_acceptsFragmentShaderCompatInnerClasses() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val namedInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_FRAGMENT_SHADER_COMPAT_INTEGER_LITERAL_CLASS
        ) as Boolean
        val anonymousInnerIncluded = method.invoke(
            StsDesktopJarPatcher,
            "io/stamethyst/gdx/FragmentShaderCompat\$1.class"
        ) as Boolean

        assertTrue(namedInnerIncluded)
        assertTrue(anonymousInnerIncluded)
    }

    @Test
    fun shouldPatchStsEntry_acceptsFirstPersonGyroBridge() {
        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        val included = method.invoke(
            StsDesktopJarPatcher,
            STS_PATCH_FIRST_PERSON_GYRO_BRIDGE_CLASS
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

    /**
     * Every class gdx-patch *introduces* into a package it patches must be copied into the desktop jar.
     *
     * The per-class tests above only cover classes someone remembered to add. This one scans the real
     * build output, so adding a new helper class to an already-patched package fails here rather than at
     * runtime with NoClassDefFoundError on the first frame. That is exactly how `LwjglFramePacerSchedule`
     * shipped broken: `shouldPatchStsEntry` matches `LwjglApplication` by prefix, and a new sibling
     * class matched no rule at all.
     *
     * Scoped deliberately to classes that do **not** already exist in the vanilla jar. A patched
     * override of an existing class (`SpriteBatch`, `FrameBuffer`, `HdpiUtils`) still resolves at
     * runtime from the vanilla bytes when it is not copied — that costs the patch its effect but does
     * not crash, and deciding which of those must be copied is a separate judgement. A class that
     * exists only in gdx-patch has nothing to fall back to, so omitting it is always a crash.
     *
     * Skips when either jar is unavailable, so unit tests do not depend on build ordering or on the
     * Steam depot being present.
     */
    @Test
    fun shouldPatchStsEntry_coversEveryNewClassIntroducedIntoPatchedPackages() {
        val patchJar = File("../patches/gdx-patch/build/libs/gdx-patch.jar")
            .takeIf { it.isFile } ?: return
        val vanillaJar = File("../build-deps/steamapps/common/SlayTheSpire/desktop-1.0.jar")
            .takeIf { it.isFile } ?: return

        val method = StsDesktopJarPatcher::class.java.getDeclaredMethod(
            "shouldPatchStsEntry",
            String::class.java
        )
        method.isAccessible = true

        // Only packages the patcher already rewrites. A class in a package the patcher never touches is
        // loaded from the patch jar itself, so it is out of scope here.
        val patchedPackages = setOf(
            "com/badlogic/gdx/backends/lwjgl/",
            "com/badlogic/gdx/graphics/",
            "com/badlogic/gdx/graphics/glutils/",
            "com/badlogic/gdx/graphics/g2d/"
        )

        val vanillaEntries = java.util.zip.ZipFile(vanillaJar).use { zip ->
            zip.entries().asSequence().map { it.name }.toHashSet()
        }

        val missing = mutableListOf<String>()
        java.util.zip.ZipFile(patchJar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val packageName = name.substringBeforeLast('/', "") + "/"
                if (packageName !in patchedPackages) continue
                // Inner classes travel with their outer class, which the prefix rules already cover.
                if (name.contains('$')) continue
                // Overrides of vanilla classes still resolve at runtime; only new classes can crash.
                if (name in vanillaEntries) continue
                if (!(method.invoke(StsDesktopJarPatcher, name) as Boolean)) {
                    missing.add(name)
                }
            }
        }

        assertTrue(
            "gdx-patch introduces these classes into patched packages but never copies them into " +
                "desktop-1.0.jar, so they will fail at runtime with NoClassDefFoundError: $missing",
            missing.isEmpty()
        )
    }
}
