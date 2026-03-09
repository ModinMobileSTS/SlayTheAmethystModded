package io.stamethyst.backend.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateUiReducerTest {
    @Test
    fun reduce_autoCheckWithoutUpdate_staysSilent() {
        val decision = LauncherUpdateUiReducer.reduce(
            result = success(hasUpdate = false),
            userInitiated = false
        )

        assertFalse(decision.showPrompt)
        assertNull(decision.message)
    }

    @Test
    fun reduce_autoCheckWithUpdate_showsPrompt() {
        val decision = LauncherUpdateUiReducer.reduce(
            result = success(hasUpdate = true),
            userInitiated = false
        )

        assertTrue(decision.showPrompt)
        assertNull(decision.message)
    }

    @Test
    fun reduce_manualCheckWithoutUpdate_returnsLatestMessage() {
        val decision = LauncherUpdateUiReducer.reduce(
            result = success(hasUpdate = false),
            userInitiated = true
        )

        assertFalse(decision.showPrompt)
        assertEquals(UpdateUiMessage.LATEST, decision.message)
    }

    @Test
    fun reduce_sameAvailableUpdateStillPromptsAgainAfterDismiss() {
        val first = LauncherUpdateUiReducer.reduce(
            result = success(hasUpdate = true),
            userInitiated = false
        )
        val second = LauncherUpdateUiReducer.reduce(
            result = success(hasUpdate = true),
            userInitiated = false
        )

        assertTrue(first.showPrompt)
        assertTrue(second.showPrompt)
        assertNull(first.message)
        assertNull(second.message)
    }

    private fun success(hasUpdate: Boolean): UpdateCheckExecutionResult.Success {
        val release = UpdateReleaseInfo(
            rawTagName = "v1.0.6-hotfix1",
            normalizedVersion = "1.0.6-hotfix1",
            publishedAtRaw = "2026-03-09T04:20:00Z",
            publishedAtDisplayText = "2026-03-09 12:20",
            notesPreview = "Fixes",
            assetName = "SlayTheAmethyst-dev-1.0.6-hotfix1.APK",
            assetDownloadUrl = "https://github.com/example/release.apk"
        )
        return UpdateCheckExecutionResult.Success(
            currentVersion = "1.0.6",
            release = release,
            metadataSource = UpdateSource.GH_PROXY_VIP,
            downloadResolution = UpdateDownloadResolution(
                source = UpdateSource.GH_PROXY_VIP,
                resolvedUrl = "https://ghproxy.vip/https://github.com/example/release.apk"
            ),
            hasUpdate = hasUpdate
        )
    }
}
