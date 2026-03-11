package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LauncherUpdateServiceParsingTest {
    @Test
    fun parseLatestRelease_extractsVersionAndUppercaseApkAsset() {
        val parsed = LauncherUpdateService.parseLatestRelease(
            "{\"tag_name\":\"v1.0.6-hotfix1\"," +
                "\"published_at\":\"2026-03-09T04:20:00Z\"," +
                "\"body\":\"# Fixes\\n- one\\n- two\\n- three\\n\"," +
                "\"assets\":[{" +
                "\"name\":\"SlayTheAmethyst-dev-1.0.6-hotfix1.APK\"," +
                "\"browser_download_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.6-hotfix1/SlayTheAmethyst-dev-1.0.6-hotfix1.APK\"" +
                "}]}"
        )

        assertNotNull(parsed)
        assertEquals("v1.0.6-hotfix1", parsed?.rawTagName)
        assertEquals("1.0.6-hotfix1", parsed?.normalizedVersion)
        assertEquals("# Fixes\n- one\n- two\n- three", parsed?.notesText)
        assertEquals("SlayTheAmethyst-dev-1.0.6-hotfix1.APK", parsed?.assetName)
        assertEquals(
            "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.6-hotfix1/SlayTheAmethyst-dev-1.0.6-hotfix1.APK",
            parsed?.assetDownloadUrl
        )
    }

    @Test
    fun buildUrl_prefixesMirrorDownloadUrl() {
        assertEquals(
            "https://gh-proxy.com/https://github.com/example/release.apk",
            UpdateSource.GH_PROXY_COM.buildUrl("https://github.com/example/release.apk")
        )
        assertEquals(
            "https://ghproxy.vip/https://github.com/example/release.apk",
            UpdateSource.GH_PROXY_VIP.buildUrl("https://github.com/example/release.apk")
        )
        assertEquals(
            "https://github.com/example/release.apk",
            UpdateSource.OFFICIAL.buildUrl("https://github.com/example/release.apk")
        )
    }
}
