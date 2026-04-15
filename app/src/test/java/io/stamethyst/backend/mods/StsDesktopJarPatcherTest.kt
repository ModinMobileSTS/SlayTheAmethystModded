package io.stamethyst.backend.mods

import org.junit.Assert.assertTrue
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
}
