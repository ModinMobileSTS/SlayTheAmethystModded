package io.stamethyst.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootOverlayImageConfigTest {
    @Test
    fun builtInArtwork_defaultsToNewPairAndRejectsUnknownValues() {
        assertEquals(BootOverlayArtwork.POSTER, BootOverlayImageConfig().artwork)
        assertEquals(BootOverlayArtwork.POSTER, BootOverlayArtwork.fromPersistedValue(null))
        assertEquals(BootOverlayArtwork.POSTER, BootOverlayArtwork.fromPersistedValue("unknown"))
        BootOverlayArtwork.entries.forEach {
            assertEquals(it, BootOverlayArtwork.fromPersistedValue(it.persistedValue))
        }
    }

    @Test
    fun builtInArtwork_switchesBothImagesAndHonorsSingleMode() {
        val original = BootOverlayImageConfig(artwork = BootOverlayArtwork.ORIGINAL)
        assertEquals("ui/boot_dark.png", original.startResourcePath())
        assertEquals("ui/boot_bright.png", original.endResourcePath())
        val poster = original.copy(artwork = BootOverlayArtwork.POSTER)
        assertEquals("boot-art/poster_dark.jpg", poster.startResourcePath())
        assertEquals("boot-art/poster_bright.jpg", poster.endResourcePath())
        BootOverlayArtwork.entries.forEach { artwork ->
            val single = BootOverlayImageConfig(mode = BootOverlayImageMode.SINGLE, artwork = artwork)
            assertEquals(single.startResourcePath(), single.endResourcePath())
        }
    }

    @Test
    fun switchingBuiltInArtwork_preservesCustomImagePathsAndVersions() {
        val custom = BootOverlayImageConfig(
            startImagePath = "/custom/start.png",
            endImagePath = "/custom/end.png",
            startImageVersion = 11L,
            endImageVersion = 22L,
        )
        val original = custom.copy(artwork = BootOverlayArtwork.ORIGINAL)
        assertTrue(original.hasCustomImages)
        assertEquals(custom.resolvedStartImagePath(), original.resolvedStartImagePath())
        assertEquals(custom.resolvedEndImagePath(), original.resolvedEndImagePath())
        assertEquals(11L, original.resolvedStartImageVersion())
        assertEquals(22L, original.resolvedEndImageVersion())
    }

    @Test
    fun fromPersistedValue_returnsMatchingMode() {
        assertEquals(BootOverlayImageMode.SINGLE, BootOverlayImageMode.fromPersistedValue("single"))
        assertEquals(BootOverlayImageMode.DUAL, BootOverlayImageMode.fromPersistedValue("dual"))
        assertNull(BootOverlayImageMode.fromPersistedValue("unknown"))
    }

    @Test
    fun singleMode_resolvesEndImageToStartImage() {
        val config = BootOverlayImageConfig(
            mode = BootOverlayImageMode.SINGLE,
            startImagePath = "/tmp/start.png",
            endImagePath = "/tmp/end.png",
            startImageVersion = 11L,
            endImageVersion = 22L
        )

        assertEquals("/tmp/start.png", config.resolvedStartImagePath())
        assertEquals("/tmp/start.png", config.resolvedEndImagePath())
        assertEquals(11L, config.resolvedStartImageVersion())
        assertEquals(11L, config.resolvedEndImageVersion())
    }

    @Test
    fun dualMode_resolvesEachImageIndependently() {
        val config = BootOverlayImageConfig(
            mode = BootOverlayImageMode.DUAL,
            startImagePath = "/tmp/start.png",
            endImagePath = "/tmp/end.png",
            startImageVersion = 11L,
            endImageVersion = 22L
        )

        assertEquals("/tmp/start.png", config.resolvedStartImagePath())
        assertEquals("/tmp/end.png", config.resolvedEndImagePath())
        assertEquals(11L, config.resolvedStartImageVersion())
        assertEquals(22L, config.resolvedEndImageVersion())
    }

    @Test
    fun hasCustomImages_acceptsEitherSlot() {
        assertFalse(BootOverlayImageConfig().hasCustomImages)
        assertTrue(
            BootOverlayImageConfig(
                mode = BootOverlayImageMode.DUAL,
                startImagePath = "/tmp/start.png"
            ).hasCustomImages
        )
        assertTrue(
            BootOverlayImageConfig(
                mode = BootOverlayImageMode.DUAL,
                endImagePath = "/tmp/end.png"
            ).hasCustomImages
        )
    }
}
