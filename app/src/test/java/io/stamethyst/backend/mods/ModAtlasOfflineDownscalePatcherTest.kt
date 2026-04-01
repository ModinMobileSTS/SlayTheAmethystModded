package io.stamethyst.backend.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModAtlasOfflineDownscalePatcherTest {
    @Test
    fun collectAtlasPageEntryNames_returnsEverySequentialPage() {
        val atlasText = """
            nyoxide.png
            size: 2048, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            back
              rotate: false
              xy: 864, 555
              size: 860, 551

            nyoxide_2.png
            size: 2048, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            feetR
              rotate: false
              xy: 2, 2
              size: 148, 142

            nyoxide_3.png
            size: 2048, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            head
              rotate: false
              xy: 864, 2
              size: 150, 182
        """.trimIndent()

        val pageEntries = ModAtlasOfflineDownscalePatcher.collectAtlasPageEntryNames(
            atlasEntryName = "duelist/nyoxide.atlas",
            atlasText = atlasText
        )

        assertEquals(
            listOf(
                "duelist/nyoxide.png",
                "duelist/nyoxide_2.png",
                "duelist/nyoxide_3.png"
            ),
            pageEntries
        )
    }

    @Test
    fun rewriteAtlasTextForPageScales_scalesEachPageHeaderAndRegionBlock() {
        val atlasText = """
            nyoxide.png
            size: 2048, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            back
              rotate: false
              xy: 864, 555
              size: 860, 551
              orig: 860, 551

            nyoxide_2.png
            size: 2048, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            feetR
              rotate: false
              xy: 2, 2
              size: 148, 142
              orig: 149, 144

            nyoxide_3.png
            size: 1024, 2048
            format: RGBA8888
            filter: Linear, Linear
            repeat: none
            head
              rotate: false
              xy: 700, 2
              size: 150, 182
              orig: 152, 184
        """.trimIndent()

        val rewritten = ModAtlasOfflineDownscalePatcher.rewriteAtlasTextForPageScales(
            atlasEntryName = "duelist/nyoxide.atlas",
            atlasText = atlasText,
            pageScales = mapOf(
                "duelist/nyoxide.png" to 0.25f,
                "duelist/nyoxide_2.png" to 0.25f,
                "duelist/nyoxide_3.png" to 0.5f
            )
        )

        assertTrue(rewritten.contains("nyoxide.png\nsize: 512, 512"))
        assertTrue(rewritten.contains("back\n  rotate: false\n  xy: 216, 139\n  size: 215, 138\n  orig: 215, 138"))
        assertTrue(rewritten.contains("nyoxide_2.png\nsize: 512, 512"))
        assertTrue(rewritten.contains("feetR\n  rotate: false\n  xy: 1, 1\n  size: 37, 36\n  orig: 37, 36"))
        assertTrue(rewritten.contains("nyoxide_3.png\nsize: 512, 1024"))
        assertTrue(rewritten.contains("head\n  rotate: false\n  xy: 350, 1\n  size: 75, 91\n  orig: 76, 92"))
    }
}
