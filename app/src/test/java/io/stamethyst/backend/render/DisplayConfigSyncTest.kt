package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayConfigSyncTest {
    @Test
    fun buildConfigLines_forcesVsyncOffWhilePreservingWindowFlags() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = listOf("2400", "1080", "60", "true", "true", "true"),
            width = 1920,
            height = 1080,
            targetFpsLimit = 240
        )

        assertEquals(
            listOf("1920", "1080", "240", "true", "true", "false"),
            lines
        )
    }

    @Test
    fun buildConfigLines_usesDefaultsWhenExistingConfigMissing() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = null,
            width = 320,
            height = 200,
            targetFpsLimit = 60
        )

        assertEquals(
            listOf("800", "450", "60", "false", "false", "false"),
            lines
        )
    }
}
