package io.stamethyst.backend.nativelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibraryMarketServiceTest {
    @Test
    fun parseCatalog_supportsCurrentFormat() {
        val parsed = NativeLibraryMarketService.parseCatalog(
            """
            [
              {
                "name": "Libgdx Video",
                "description": "Video playback bridge",
                "files": [
                  {
                    "file_name": "libgdx-video-desktoparm64.so"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("libgdx-video-desktoparm64", parsed.first().id)
        assertEquals("Libgdx Video", parsed.first().displayName)
        assertEquals("Video playback bridge", parsed.first().description)
        assertEquals(1, parsed.first().files.size)
        assertEquals(
            "libgdx-video-desktoparm64.so",
            parsed.first().files.first().fileName
        )
        assertTrue(
            parsed.first().files.first().downloadUrl.endsWith("libgdx-video-desktoparm64.so")
        )
    }

    @Test
    fun parseCatalog_supportsLegacyAliasesAndExplicitIds() {
        val parsed = NativeLibraryMarketService.parseCatalog(
            """
            [
              {
                "id": "tensorflow",
                "card_name": "Tensorflow",
                "description": "Legacy fields",
                "card_files": [
                  {
                    "file_name": "libjnitensorflow.so",
                    "download_url": "https://example.com/libjnitensorflow.so"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("tensorflow", parsed.first().id)
        assertEquals("Tensorflow", parsed.first().displayName)
        assertEquals(
            "https://example.com/libjnitensorflow.so",
            parsed.first().files.first().downloadUrl
        )
    }
}
