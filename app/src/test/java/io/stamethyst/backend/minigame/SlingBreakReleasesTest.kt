package io.stamethyst.backend.minigame

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SlingBreakReleasesTest {
    @Test fun resourceAndPrereleaseDoNotHideLatestGame() {
        val resource = release("Resource", 99).put("assets", JSONArray().put(JSONObject().put("name", "engine.apk")))
        val preview = release("v9.0.0", 90).put("prerelease", true)
        val draft = release("v8.0.0", 80).put("draft", true)
        val latest = SlingBreakReleases.parseLatest(JSONArray().put(resource).put(preview).put(draft)
            .put(release("v0.0.1", 1)).put(release("v0.0.2", 2)).toString())!!
        assertEquals("v0.0.2", latest.rawTagName)
        assertEquals("0.0.2", latest.normalizedVersion)
        assertEquals("a".repeat(64), latest.assetSha256)
        assertEquals(2L, latest.assetId)
    }

    @Test fun chronologyRatherThanLauncherVersionChoosesGame() {
        val latest = SlingBreakReleases.parseLatest(JSONArray().put(release("v10.0.0", 1))
            .put(release("release-october", 2)).toString())!!
        assertEquals("release-october", latest.rawTagName)
    }

    @Test fun missingOrMalformedDigestIsRejected() {
        for (digest in listOf("", "null", "sha256:abc", "md5:${"a".repeat(64)}")) {
            val json = release("v0.0.1", 1)
            json.getJSONArray("assets").getJSONObject(0).put("digest", digest)
            assertNull(SlingBreakReleases.parseLatest("[$json]"))
        }
    }

    @Test fun invalidLatestPackCannotSilentlySelectOlderRelease() {
        val latest = release("v0.0.2", 2)
        latest.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(SlingBreakReleases.parseLatest("[${release("v0.0.1", 1)},$latest]"))
    }

    @Test fun htmlAndExternalAssetsAreRejected() {
        assertNull(SlingBreakReleases.parseLatest("<html>mirror error</html>"))
        val json = release("v0.0.1", 1)
        json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.invalid/game.pck")
        assertNull(SlingBreakReleases.parseLatest("[$json]"))
    }

    @Test fun sizeAndTimestampMustBeValid() {
        val json = release("v0.0.1", 1)
        json.getJSONArray("assets").getJSONObject(0).put("size", SlingBreakReleases.MAX_COMPONENT_BYTES + 1)
        assertNull(SlingBreakReleases.parseLatest("[$json]"))
        assertNull(SlingBreakReleases.parseLatest("[${release("v0.0.1", 1).put("published_at", "invalid")}]"))
    }

    @Test fun sameReleaseIsNotUpdateButReplacedAssetIs() {
        val installed = parse("v0.0.1", 1)
        assertFalse(SlingBreakReleases.isUpdate(installed, installed))
        assertTrue(SlingBreakReleases.isUpdate(installed, installed.copy(assetId = 11)))
        assertTrue(SlingBreakReleases.isUpdate(installed, installed.copy(assetSha256 = "b".repeat(64))))
        assertTrue(SlingBreakReleases.isUpdate(null, installed))
    }

    @Test fun staleMirrorCannotDowngradeCurrentInstallation() {
        assertFalse(SlingBreakReleases.isUpdate(parse("v0.0.2", 2), parse("v0.0.1", 1)))
        assertTrue(SlingBreakReleases.isUpdate(parse("v0.0.1", 1), parse("v0.0.2", 2)))
    }

    @Test fun ambiguousPlatformPacksAreNotGuessed() {
        val json = release("v1", 1)
        val original = json.getJSONArray("assets").getJSONObject(0)
        original.put("name", "linux.pck")
        json.getJSONArray("assets").put(JSONObject(original.toString()).put("name", "android.pck"))
        assertNull(SlingBreakReleases.parseLatest("[$json]"))
    }

    companion object {
        fun parse(tag: String, id: Int) = SlingBreakReleases.parseLatest("[${release(tag, id)}]")!!
        fun release(tag: String, id: Int) = JSONObject().put("id", id).put("tag_name", tag)
            .put("published_at", "2026-10-${id.toString().padStart(2, '0')}T17:22:06Z")
            .put("body", "Game notes").put("assets", JSONArray().put(JSONObject()
                .put("id", id).put("name", "game.pck").put("size", 128)
                .put("digest", "sha256:${"a".repeat(64)}")
                .put("browser_download_url", "https://github.com/Apricityx/slingbreak/releases/download/$tag/game.pck")))
    }
}
