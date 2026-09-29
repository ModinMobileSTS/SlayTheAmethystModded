package io.stamethyst.backend.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewDataDirectoryTest {
    private val packageName = "io.stamethyst"

    @Test
    fun defaultProcessKeepsLegacyDirectory() {
        assertNull(WebViewDataDirectory.dataDirectorySuffixFor(null, packageName))
        assertNull(WebViewDataDirectory.dataDirectorySuffixFor("", packageName))
        assertNull(WebViewDataDirectory.dataDirectorySuffixFor(packageName, packageName))
    }

    @Test
    fun privateProcessGetsItsOwnSuffix() {
        assertEquals(
            "game",
            WebViewDataDirectory.dataDirectorySuffixFor("$packageName:game", packageName)
        )
        assertEquals(
            "logcat",
            WebViewDataDirectory.dataDirectorySuffixFor("$packageName:logcat", packageName)
        )
    }

    @Test
    fun processNamesOutsidePackageFallBackToLegacyDirectory() {
        assertNull(
            WebViewDataDirectory.dataDirectorySuffixFor("io.other.app:worker", packageName)
        )
    }

    @Test
    fun suffixIsSanitizedForFileSystemUse() {
        assertEquals(
            "game_worker",
            WebViewDataDirectory.dataDirectorySuffixFor("$packageName:game worker", packageName)
        )
    }

    @Test
    fun emptyPrivateProcessSuffixFallsBackToLegacyDirectory() {
        assertNull(WebViewDataDirectory.dataDirectorySuffixFor("$packageName:", packageName))
    }
}
