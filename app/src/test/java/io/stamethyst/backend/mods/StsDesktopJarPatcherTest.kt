package io.stamethyst.backend.mods

import org.junit.Assert.assertTrue
import org.junit.Test

class StsDesktopJarPatcherTest {
    @Test
    fun requiredPatchClasses_includeFrameBufferOwnerSummary() {
        assertTrue(REQUIRED_STS_PATCH_CLASSES.contains(STS_PATCH_FRAMEBUFFER_OWNER_SUMMARY_CLASS))
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
}
