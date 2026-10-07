package io.stamethyst.backend.resources

import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.BuildConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalResourcePackServiceTest {
    @Test
    fun webRuntimeCandidates_reuseGithubMirrorsAndPreserveSeparateGiteeRelease() {
        val urls = BuildConfig.WEB_RUNTIME_DOWNLOAD_URLS.toList()
        val github = "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/geckoview-148.0.20260309125808-v1-arm64-v8a.zip"
        val gitee = "https://gitee.com/apricityx/SlayTheAmethystResource/releases/download/v1.1/geckoview-148.0.20260309125808-v1-arm64-v8a.zip"
        assertTrue(urls.contains(gitee))
        val candidates = ExternalResourcePackService.buildResourcePackDownloadCandidates(
            listOf(github, gitee), UpdateSource.GH_PROXY_VIP, bypassAcceleratedLinks = false,
        )
        assertEquals("ghproxy.vip", candidates.first().displayName)
        assertTrue(candidates.first().requestUrl.endsWith(github))
        assertEquals(gitee, candidates.last().requestUrl)
        assertEquals("Gitee", candidates.last().displayName)
        assertTrue(candidates.any { it.usesGithubAcceleration && it.requestUrl == github })
    }

    @Test
    fun webRuntimeCandidates_respectAccelerationBypass() {
        val github = "https://github.com/example/releases/download/Resource/geckoview.zip"
        val gitee = "https://gitee.com/example/releases/download/v1.1/geckoview.zip"
        val candidates = ExternalResourcePackService.buildResourcePackDownloadCandidates(
            listOf(github, gitee), UpdateSource.GH_PROXY_VIP, bypassAcceleratedLinks = true,
        )
        assertEquals(listOf(github, gitee), candidates.map { it.requestUrl })
        assertTrue(candidates.none { it.usesGithubAcceleration })
    }

    @Test
    fun downloadSize_usesWebDependencyLimitInsteadOfResourcePackLimit() {
        val maxBytes = 200L * 1024L * 1024L
        ExternalResourcePackService.requireDownloadSize(94_765_265L, maxBytes)
        ExternalResourcePackService.requireDownloadSize(maxBytes, maxBytes)
    }

    @Test(expected = IOException::class)
    fun downloadSize_rejectsEmptyArchive() {
        ExternalResourcePackService.requireDownloadSize(0L, 200L * 1024L * 1024L)
    }

    @Test(expected = IOException::class)
    fun downloadSize_rejectsOversizedWebDependency() {
        val maxBytes = 200L * 1024L * 1024L
        ExternalResourcePackService.requireDownloadSize(maxBytes + 1L, maxBytes)
    }

    @Test
    fun mirrorSwitchController_notifiesPromptAndRecordsSwitchRequest() {
        val controller = ResourcePackDownloadMirrorSwitchController()
        val prompts = ArrayList<ResourcePackSlowDownloadMirrorSwitch?>()
        val listener = { prompt: ResourcePackSlowDownloadMirrorSwitch? ->
            prompts += prompt
        }
        controller.addSlowDownloadListener(listener)

        val initialVersion = controller.switchRequestVersion()
        val prompt = ResourcePackSlowDownloadMirrorSwitch(
            currentSourceLabel = UpdateSource.GH_PROXY_VIP.displayName,
            nextSourceLabel = UpdateSource.GH_LLKK.displayName,
            nextPreferredMirrorSource = UpdateSource.GH_LLKK
        )
        controller.publishSlowDownloadPrompt(prompt)

        assertEquals(prompt, prompts.last())
        assertTrue(controller.requestSwitchToNextMirror())
        assertTrue(controller.hasSwitchRequestSince(initialVersion))
        assertEquals(null, prompts.last())

        controller.removeSlowDownloadListener(listener)
    }

    @Test
    fun buildResourcePackDownloadCandidates_includesGiteeAlongsideGithubMirrors() {
        val githubUrl =
            "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/v1.1/resources.zip"
        val giteeUrl =
            "https://gitee.com/apricityx/SlayTheAmethystResource/releases/download/v1.1/resources.zip"

        val candidates = ExternalResourcePackService.buildResourcePackDownloadCandidates(
            resourcePackUrls = listOf(githubUrl, giteeUrl),
            preferredSource = UpdateSource.GH_PROXY_VIP,
            bypassAcceleratedLinks = false
        )

        assertEquals(
            listOf(
                "ghproxy.vip",
                "gh-proxy.com",
                "gh.llkk.cc",
                "ghproxy.net",
                UpdateSource.ACCELERATED_DIRECT.displayName,
                "GitHub",
                "Gitee",
            ),
            candidates.map { it.displayName }
        )
        assertEquals(giteeUrl, candidates.last().requestUrl)
        assertNull(candidates.last().preferredMirrorSource)
    }

    @Test
    fun orderResourcePackDownloadCandidates_prefersReachableFastestLinks() {
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            listOf(
                probe(UpdateSource.GH_PROXY_COM, reachable = false, elapsedNanos = 100, index = 0),
                probe(UpdateSource.GH_PROXY_VIP, reachable = true, elapsedNanos = 500, index = 1),
                probe(UpdateSource.GH_LLKK, reachable = true, elapsedNanos = 200, index = 2),
                probe(UpdateSource.OFFICIAL, reachable = true, elapsedNanos = 200, index = 3),
            )
        )

        assertEquals(
            listOf(
                UpdateSource.GH_LLKK.displayName,
                UpdateSource.OFFICIAL.displayName,
                UpdateSource.GH_PROXY_VIP.displayName
            ),
            ordered.map { it.displayName }
        )
        assertEquals(listOf(2, 3, 1), ordered.map { it.candidateIndex })
    }

    @Test
    fun orderResourcePackDownloadCandidates_returnsEmptyWhenAllLinksFail() {
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            listOf(
                probe(UpdateSource.GH_PROXY_COM, reachable = false, elapsedNanos = 100, index = 0),
                probe(UpdateSource.GH_PROXY_VIP, reachable = false, elapsedNanos = 200, index = 1),
            )
        )

        assertTrue(ordered.isEmpty())
    }

    @Test
    fun orderResourcePackDownloadCandidates_keepsUnfinishedTransportsBeforeKnownFailures() {
        val url = "https://github.com/example/resources.zip"
        val candidates = listOf(
            configured(UpdateSource.GH_PROXY_COM),
            configured(UpdateSource.ACCELERATED_DIRECT).copy(requestUrl = url),
            configured(UpdateSource.OFFICIAL).copy(requestUrl = url),
            configured(UpdateSource.GH_LLKK),
        )
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            probeResults = listOf(
                probe(UpdateSource.GH_PROXY_COM, reachable = false, elapsedNanos = 100, index = 0),
                probe(UpdateSource.GH_LLKK, reachable = true, elapsedNanos = 200, index = 3),
            ),
            fallbackCandidates = candidates,
        )

        assertEquals(listOf(3, 1, 2, 0), ordered.map { it.candidateIndex })
        assertEquals(listOf(true, false), ordered.slice(1..2).map { it.usesGithubAcceleration })
    }

    @Test
    fun orderResourcePackDownloadCandidates_keepsFallbacksWhenNoProbeSucceeded() {
        val candidates = listOf(configured(UpdateSource.GH_PROXY_COM), configured(UpdateSource.OFFICIAL))
        val ordered = ExternalResourcePackService.orderResourcePackDownloadCandidates(
            probeResults = emptyList(),
            fallbackCandidates = candidates,
        )

        assertEquals(listOf(0, 1), ordered.map { it.candidateIndex })
        assertTrue(ordered.none { it.rangeSupportProbed })
    }

    private fun configured(source: UpdateSource) =
        ExternalResourcePackService.ConfiguredResourcePackDownloadCandidate(
            displayName = source.displayName,
            requestUrl = "https://example.com/${source.id}",
            usesGithubAcceleration = source.usesGithubAcceleration,
            preferredMirrorSource = source.takeIf { it.userSelectable },
        )

    private fun probe(
        source: UpdateSource,
        reachable: Boolean,
        elapsedNanos: Long,
        index: Int,
    ): ExternalResourcePackService.ResourcePackLinkProbeResult {
        return ExternalResourcePackService.ResourcePackLinkProbeResult(
            displayName = source.displayName,
            requestUrl = "https://example.com/${source.id}",
            usesGithubAcceleration = source.usesGithubAcceleration,
            preferredMirrorSource = source.takeIf { it.userSelectable },
            reachable = reachable,
            elapsedNanos = elapsedNanos,
            candidateIndex = index,
            error = if (reachable) null else IOException("failed")
        )
    }
}
