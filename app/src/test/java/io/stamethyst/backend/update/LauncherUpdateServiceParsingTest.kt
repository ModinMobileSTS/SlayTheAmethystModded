package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateServiceParsingTest {
    @Test
    fun parseLatestRelease_extractsVersionAndUppercaseApkAsset() {
        val parsed = LauncherUpdateService.parseLatestRelease(
            "{\"tag_name\":\"v1.0.6-hotfix1\"," +
                "\"published_at\":\"2026-03-09T04:20:00Z\"," +
                "\"html_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.0.6-hotfix1\"," +
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
        assertEquals(
            "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.0.6-hotfix1",
            parsed?.releasePageUrl
        )
        assertEquals("SlayTheAmethyst-dev-1.0.6-hotfix1.APK", parsed?.assetName)
        assertEquals(
            "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.0.6-hotfix1/SlayTheAmethyst-dev-1.0.6-hotfix1.APK",
            parsed?.assetDownloadUrl
        )
    }

    @Test
    fun parseReleaseHistory_extractsMultipleEntriesAndSkipsDrafts() {
        val parsed = LauncherUpdateService.parseReleaseHistory(
            "[" +
                "{" +
                "\"tag_name\":\"v1.2.2\"," +
                "\"published_at\":\"2026-04-08T12:00:00Z\"," +
                "\"html_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.2.2\"," +
                "\"body\":\"# Features\\n- first\\n\"" +
                "}," +
                "{" +
                "\"tag_name\":\"v1.2.1\"," +
                "\"published_at\":\"2026-04-01T10:00:00Z\"," +
                "\"html_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.2.1\"," +
                "\"body\":\"# Fixes\\n- second\\n\"" +
                "}," +
                "{" +
                "\"tag_name\":\"v1.2.0-preview\"," +
                "\"draft\":true," +
                "\"published_at\":\"2026-03-20T10:00:00Z\"," +
                "\"body\":\"draft\"" +
                "}" +
                "]"
        )

        assertEquals(2, parsed.size)
        assertEquals("v1.2.2", parsed[0].rawTagName)
        assertEquals("1.2.2", parsed[0].normalizedVersion)
        assertEquals("# Features\n- first", parsed[0].notesText)
        assertEquals(
            "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.2.2",
            parsed[0].releasePageUrl
        )
        assertEquals("v1.2.1", parsed[1].rawTagName)
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

    @Test
    fun buildUrl_prefixesGithubRawUrlsButLeavesExternalUrlsUnchanged() {
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/example/repo/main/file.txt",
            UpdateSource.GH_PROXY_COM.buildUrl(
                "https://raw.githubusercontent.com/example/repo/main/file.txt"
            )
        )
        assertEquals(
            "https://example.com/file.txt",
            UpdateSource.GH_PROXY_COM.buildUrl("https://example.com/file.txt")
        )
    }

    @Test
    fun userSelectableSources_includeOfficialSource() {
        assertTrue(UpdateSource.userSelectableSources().contains(UpdateSource.OFFICIAL))
    }
}
