package io.stamethyst

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlingBreakHostLifecycleTest {
    @Test
    fun blankPageBypassesAssetServerAndDoesNotReceiveBridgeToken() {
        assertEquals("about:blank", resolveSlingBreakGeckoUrl("about:blank", "channel") {
            throw IOException("Unsupported Sling asset URL")
        })
    }

    @Test
    fun standaloneAssetsAreMappedWithoutBridgeToken() {
        val input = "file:///android_asset/slingbreak/index.html"
        val mapped = "http://127.0.0.1:1234/origin/slingbreak/index.html"
        assertEquals(mapped, resolveSlingBreakGeckoUrl(input, null) {
            assertEquals(input, it)
            mapped
        })
    }

    @Test
    fun launcherAssetsKeepQueryAndReceiveBridgeToken() {
        assertEquals(
            "http://127.0.0.1:1234/origin/slingbreak/index.html?launcher=1&geckoLauncher=channel",
            resolveSlingBreakGeckoUrl("file:///android_asset/slingbreak/index.html?launcher=1", "channel") {
                "http://127.0.0.1:1234/origin/slingbreak/index.html?launcher=1"
            },
        )
    }

    @Test
    fun assetsWithoutQueryUseQuestionMarkForBridgeToken() {
        assertEquals("http://127.0.0.1:1234/origin/slingbreak/index.html?geckoLauncher=channel",
            resolveSlingBreakGeckoUrl("file:///android_asset/slingbreak/index.html", "channel") {
                "http://127.0.0.1:1234/origin/slingbreak/index.html"
            })
    }

    @Test(expected = IOException::class)
    fun nonBlankUrlsStillPassThroughAssetWhitelist() {
        resolveSlingBreakGeckoUrl("https://example.com/", "channel") {
            throw IOException("Unsupported Sling asset URL")
        }
    }

    @Test
    fun cleanupCompletesInOrder() {
        val steps = mutableListOf<String>()
        cleanupSlingBreakHost(
            stopLoading = { steps += "stop" },
            pause = { steps += "pause" },
            clearPage = { steps += "blank" },
            closeAudio = { steps += "audio" },
            destroy = { steps += "destroy" },
            onFailure = { _, error -> throw AssertionError(error) },
        )
        assertEquals(listOf("stop", "pause", "blank", "audio", "destroy"), steps)
    }

    @Test
    fun anyCleanupFailureStillAllowsRemainingStepsAndDismissal() {
        for (failedStep in 0..4) {
            val steps = mutableListOf<Int>()
            val failures = mutableListOf<String>()
            fun action(index: Int): () -> Unit = {
                steps += index
                if (index == failedStep) throw IOException("engine failure")
            }
            cleanupSlingBreakHost(action(0), action(1), action(2), action(3), action(4)) { step, error ->
                failures += step
                assertTrue(error is IOException)
            }
            steps += 5 // Native overlay hiding and onDismissed may proceed after cleanup returns.
            assertEquals(listOf(0, 1, 2, 3, 4, 5), steps)
            assertEquals(listOf(listOf("stop_loading", "pause", "clear_page", "close_audio", "destroy")[failedStep]), failures)
        }
    }
}
