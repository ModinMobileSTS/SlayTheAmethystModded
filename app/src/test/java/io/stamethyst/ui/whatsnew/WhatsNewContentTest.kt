package io.stamethyst.ui.whatsnew

import io.stamethyst.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WhatsNewContentTest {
    @Test
    fun releaseFor_matchesRcVersionsAndAddsPrereleaseNotice() {
        val release = WhatsNewContent.releaseFor("1.6.1-RC1")

        assertNotNull(release)
        assertEquals("1.6.1", release?.id)
        assertEquals(R.string.whats_new_prerelease_notice, release?.noticeRes)
    }

    @Test
    fun releaseFor_keepsPrereleaseNoticeOffStableAndHotfixVersions() {
        assertNull(WhatsNewContent.releaseFor("1.6.1")?.noticeRes)
        assertNull(WhatsNewContent.releaseFor("1.6.1-hotfix1")?.noticeRes)
    }
}
