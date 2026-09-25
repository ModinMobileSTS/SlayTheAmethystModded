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
    fun parseLatestRelease_prefersSlimApkAndFullParserSelectsFullApk() {
        val payload = "{\"tag_name\":\"v1.4.7\"," +
            "\"published_at\":\"2026-06-07T04:20:00Z\"," +
            "\"html_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/tag/v1.4.7\"," +
            "\"body\":\"Release\"," +
            "\"assets\":[{" +
            "\"name\":\"SlayTheAmethyst-release-1.4.7-full.apk\"," +
            "\"browser_download_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.4.7/SlayTheAmethyst-release-1.4.7-full.apk\"" +
            "},{" +
            "\"name\":\"SlayTheAmethyst-release-1.4.7.apk\"," +
            "\"browser_download_url\":\"https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.4.7/SlayTheAmethyst-release-1.4.7.apk\"" +
            "}]}"

        val slim = LauncherUpdateService.parseLatestRelease(payload)
        val full = LauncherUpdateService.parseLatestFullRelease(payload)

        assertNotNull(slim)
        assertNotNull(full)
        assertEquals("SlayTheAmethyst-release-1.4.7.apk", slim?.assetName)
        assertEquals("SlayTheAmethyst-release-1.4.7-full.apk", full?.assetName)
        assertEquals(
            "https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases/download/v1.4.7/SlayTheAmethyst-release-1.4.7-full.apk",
            full?.assetDownloadUrl
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
    fun buildPendingReleaseNotesText_excludesCurrentAndKeepsLatestHotfixPerPatch() {
        val latestRelease = UpdateReleaseInfo(
            rawTagName = "v1.3.4",
            normalizedVersion = "1.3.4",
            publishedAtRaw = "2026-05-03T12:00:00Z",
            publishedAtDisplayText = "2026-05-03 20:00",
            notesText = "latest fallback notes",
            releasePageUrl = "https://github.com/example/releases/tag/v1.3.4",
            assetName = "SlayTheAmethyst-release-1.3.4.apk",
            assetDownloadUrl = "https://github.com/example/releases/download/v1.3.4/app.apk"
        )
        val history = listOf(
            historyEntry("v1.3.4", "1.3.4", "fix four"),
            historyEntry("v1.3.3", "1.3.3", "fix three"),
            historyEntry("v1.3.2-hotfix2", "1.3.2-hotfix2", "fix two hotfix two"),
            historyEntry("v1.3.2-hotfix1", "1.3.2-hotfix1", "fix two hotfix one"),
            historyEntry("v1.3.2", "1.3.2", "fix two"),
            historyEntry("v1.3.1", "1.3.1", "fix one"),
            historyEntry("v1.3.0", "1.3.0", "old fix")
        )

        val notesText = LauncherUpdateService.buildPendingReleaseNotesText(
            currentVersion = "1.3.1",
            latestRelease = latestRelease,
            historyEntries = history
        )

        assertEquals(
            "# v1.3.2-hotfix2\n\nfix two hotfix two\n\n" +
                "# v1.3.3\n\nfix three\n\n" +
                "# v1.3.4\n\nfix four",
            notesText
        )
    }

    @Test
    fun buildPendingReleaseNotesText_includesRcAfterDevAndKeepsLatestRcPerPatch() {
        val latestRelease = UpdateReleaseInfo(
            rawTagName = "v1.6.1-RC2",
            normalizedVersion = "1.6.1-RC2",
            publishedAtRaw = null,
            publishedAtDisplayText = "",
            notesText = "latest fallback notes",
            assetName = "SlayTheAmethyst-release-1.6.1-RC2.apk",
            assetDownloadUrl = "https://github.com/example/releases/download/v1.6.1-RC2/app.apk"
        )

        val notesText = LauncherUpdateService.buildPendingReleaseNotesText(
            currentVersion = "1.6.1-dev2",
            latestRelease = latestRelease,
            historyEntries = listOf(
                historyEntry("v1.6.1-RC2", "1.6.1-RC2", "rc two notes"),
                historyEntry("v1.6.1-RC1", "1.6.1-RC1", "rc one notes"),
                historyEntry("v1.6.1-dev2", "1.6.1-dev2", "dev notes")
            )
        )

        assertEquals("# v1.6.1-RC2\n\nrc two notes", notesText)
    }

    @Test
    fun buildPendingReleaseNotesText_fallsBackToLatestNotesWhenHistoryHasNoComparableEntries() {
        val latestRelease = UpdateReleaseInfo(
            rawTagName = "nightly-2",
            normalizedVersion = "nightly-2",
            publishedAtRaw = null,
            publishedAtDisplayText = "",
            notesText = "latest notes",
            assetName = "SlayTheAmethyst-nightly.apk",
            assetDownloadUrl = "https://github.com/example/releases/download/nightly-2/app.apk"
        )

        val notesText = LauncherUpdateService.buildPendingReleaseNotesText(
            currentVersion = "nightly",
            latestRelease = latestRelease,
            historyEntries = listOf(
                historyEntry("nightly-2", "nightly-2", "history notes")
            )
        )

        assertEquals("latest notes", notesText)
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
        assertEquals(
            "https://github.com/example/release.apk",
            UpdateSource.ACCELERATED_DIRECT.buildUrl("https://github.com/example/release.apk")
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
    fun buildUrl_prefixesCloudControlReleaseAssetUrl() {
        val cloudControlUrl =
            "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/cloud-control.json"

        assertEquals(
            "https://gh-proxy.com/$cloudControlUrl",
            UpdateSource.GH_PROXY_COM.buildUrl(cloudControlUrl)
        )
    }

    @Test
    fun userSelectableSources_includeOfficialSource() {
        assertTrue(UpdateSource.userSelectableSources().contains(UpdateSource.OFFICIAL))
    }

    private fun historyEntry(
        rawTagName: String,
        normalizedVersion: String,
        notesText: String,
    ): UpdateReleaseHistoryEntry {
        return UpdateReleaseHistoryEntry(
            rawTagName = rawTagName,
            normalizedVersion = normalizedVersion,
            publishedAtRaw = null,
            publishedAtDisplayText = "",
            notesText = notesText
        )
    }
}
