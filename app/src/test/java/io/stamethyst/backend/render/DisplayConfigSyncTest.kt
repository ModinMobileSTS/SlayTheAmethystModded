package io.stamethyst.backend.render

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayConfigSyncTest {
    @Test
    fun buildConfigLines_preservesExistingFpsAndWindowFlags() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = listOf("2400", "1080", "60", "true", "true", "true"),
            width = 1920,
            height = 1080
        )

        assertEquals(
            listOf("1920", "1080", "60", "true", "true", "true"),
            lines
        )
    }

    @Test
    fun buildConfigLines_usesGameDefaultsWhenExistingConfigMissing() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = null,
            width = 320,
            height = 200
        )

        assertEquals(
            listOf("320", "200", "60", "false", "false", "true"),
            lines
        )
    }

    @Test
    fun buildConfigLines_keepsSubHdScaledResolutionWithoutClamping() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = listOf("1920", "1080", "120", "false", "false", "true"),
            width = 384,
            height = 216
        )

        assertEquals(
            listOf("384", "216", "120", "false", "false", "true"),
            lines
        )
    }

    @Test
    fun buildConfigLines_appliesFpsOverrideWithoutChangingWindowFlags() {
        val lines = DisplayConfigSync.buildConfigLines(
            existingLines = listOf("1920", "1080", "120", "true", "false", "false"),
            width = 1600,
            height = 900,
            targetFpsLimitOverride = 30
        )

        assertEquals(
            listOf("1600", "900", "30", "true", "false", "false"),
            lines
        )
    }

    @Test
    fun buildTargetFpsConfigLines_updatesOnlyFpsLimit() {
        val lines = DisplayConfigSync.buildTargetFpsConfigLines(
            existingLines = listOf("1920", "1080", "60", "true", "false", "true"),
            targetFpsLimit = 120
        )

        assertEquals(
            listOf("1920", "1080", "120", "true", "false", "true"),
            lines
        )
    }

    @Test
    fun buildTargetFpsConfigLines_usesLauncherCompatibleGameDefaultsWhenConfigMissing() {
        val lines = DisplayConfigSync.buildTargetFpsConfigLines(
            existingLines = null,
            targetFpsLimit = 240
        )

        assertEquals(
            listOf("1280", "720", "240", "false", "false", "true"),
            lines
        )
    }
}
