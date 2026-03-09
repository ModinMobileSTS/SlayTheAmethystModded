package io.stamethyst.backend.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherUpdateVersioningTest {
    @Test
    fun isRemoteNewer_treatsHotfixAsNewerThanBasePatch() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6",
                remoteVersionTag = "1.0.6-hotfix1"
            )
        )
    }

    @Test
    fun isRemoteNewer_ordersHotfixesNumerically() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6-hotfix1",
                remoteVersionTag = "1.0.6-hotfix2"
            )
        )
    }

    @Test
    fun isRemoteNewer_prefersHigherPatchOverHotfix() {
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "1.0.6-hotfix1",
                remoteVersionTag = "1.0.7"
            )
        )
    }

    @Test
    fun isRemoteNewer_fallsBackToNormalizedStringComparisonForInvalidTags() {
        assertFalse(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "nightly",
                remoteVersionTag = "vnightly"
            )
        )
        assertTrue(
            LauncherUpdateVersioning.isRemoteNewer(
                currentVersion = "nightly",
                remoteVersionTag = "nightly-2"
            )
        )
    }
}
