package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.GithubDirectHostnameVerifier
import io.stamethyst.backend.github.NoOpWattToolkitGithubRouteStore
import io.stamethyst.backend.github.WattToolkitForwardDns
import io.stamethyst.backend.github.WattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitGithubRouteStore
import io.stamethyst.backend.github.PersistedWattToolkitGithubRoute
import io.stamethyst.backend.github.trustWattToolkitForwardCertificates
import io.stamethyst.backend.workshop.WorkshopBrowseParser
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live acceptance test for the current Watt Steam Community rule.
 *
 * This intentionally contacts Watt and Steam. It is disabled by default because its result
 * describes the current machine/network and third-party endpoints, rather than deterministic
 * launcher behavior. Enable it only with STS_RUN_MARKET_NETWORK_ACCEPTANCE=true.
 */
class SteamCommunityMarketNetworkAcceptanceTest {
    @Test
    fun everyCurrentWattCandidate_canServeOrFailOverToAValidWorkshopBrowsePage() {
        assumeTrue(
            "Set STS_RUN_MARKET_NETWORK_ACCEPTANCE=true to run the live market acceptance test.",
            System.getenv(ENABLE_ENVIRONMENT_VARIABLE)?.equals("true", ignoreCase = true) == true,
        )

        val discoveredRoute = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            routeStore = NoOpWattToolkitGithubRouteStore,
            // Each candidate is exercised below. Discovery itself must not make network I/O
            // against an arbitrary candidate before the acceptance matrix is assembled.
            forwardTargetProbe = { io.stamethyst.backend.github.WattToolkitForwardTargetProbe.failed() },
            backgroundExecutor = Executor { },
        ).resolveRouteForHost(STEAM_COMMUNITY_HOST)
            ?: error("Watt did not publish a Steam Community route")
        val candidates = discoveredRoute.forwardTargets.distinct()
        check(candidates.isNotEmpty()) { "Watt published no Steam Community forward targets" }

        println(
            "Steam market acceptance candidates=${candidates.size} " +
                "targets=${candidates.joinToString(separator = ";")}",
        )

        val executor = Executors.newFixedThreadPool(candidates.size.coerceAtMost(MAX_PARALLEL_PROBES))
        try {
            val futures = candidates.associateWith { preferredTarget ->
                executor.submit<MarketCandidateResult> {
                    probeCandidateAsPreferredRoute(
                        discoveredRoute = discoveredRoute,
                        preferredTarget = preferredTarget,
                    )
                }
            }
            val results = futures.mapValues { (target, future) ->
                try {
                    future.get(ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (error: TimeoutException) {
                    MarketCandidateResult(
                        preferredTarget = target,
                        statusCode = null,
                        validWorkshopPage = false,
                        elapsedMs = ACCEPTANCE_TIMEOUT_MS,
                        failure = "TimeoutException after ${ACCEPTANCE_TIMEOUT_MS}ms",
                    )
                }
            }
            results.values.forEach(::println)

            val failures = results.values.filterNot(MarketCandidateResult::isSuccessful)
            assertTrue(
                "Every Watt candidate must serve or fail over to a valid Steam Workshop browse page. " +
                    "Failures: ${failures.joinToString()}",
                failures.isEmpty(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun everyCurrentWattCandidate_canDownloadWorkshopBrowseContentWithoutFallback() {
        assumeTrue(
            "Set STS_RUN_MARKET_NETWORK_ACCEPTANCE=true to run the live market acceptance test.",
            System.getenv(ENABLE_ENVIRONMENT_VARIABLE)?.equals("true", ignoreCase = true) == true,
        )

        val discoveredRoute = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            routeStore = NoOpWattToolkitGithubRouteStore,
            // The real content request below is authoritative. Do not let discovery probes
            // reorder the published candidates before each one is tested as the only route.
            forwardTargetProbe = { io.stamethyst.backend.github.WattToolkitForwardTargetProbe.failed() },
            backgroundExecutor = Executor { },
        ).resolveRouteForHost(STEAM_COMMUNITY_HOST)
            ?: error("Watt did not publish a Steam Community route")
        val candidates = discoveredRoute.forwardTargets.distinct()
        check(candidates.isNotEmpty()) { "Watt published no Steam Community forward targets" }

        val results = candidates.map { preferredTarget ->
            downloadWorkshopBrowseContentThroughOnlyTarget(
                discoveredRoute = discoveredRoute,
                preferredTarget = preferredTarget,
            )
        }
        results.forEach(::println)

        val failures = results.filterNot(MarketCandidateResult::isSuccessful)
        assertTrue(
            "Every Watt candidate must download valid Steam Workshop browse content without " +
                "using another Watt candidate or the official route. Failures: " +
                failures.joinToString(),
            failures.isEmpty(),
        )
    }

    private fun probeCandidateAsPreferredRoute(
        discoveredRoute: WattToolkitGithubRoute,
        preferredTarget: String,
    ): MarketCandidateResult {
        val startedAtNs = System.nanoTime()
        println("Steam market acceptance probeStart preferredTarget=$preferredTarget")
        val route = discoveredRoute.copy(
            forwardTargets = listOf(preferredTarget) + discoveredRoute.forwardTargets.filterNot { it == preferredTarget },
            isOfficial = false,
        )
        val store = object : WattToolkitGithubRouteStore {
            override fun load(): PersistedWattToolkitGithubRoute =
                PersistedWattToolkitGithubRoute(route = route, cachedAtMs = System.currentTimeMillis())

            override fun save(route: PersistedWattToolkitGithubRoute) = Unit

            override fun clear() = Unit
        }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            routeStore = store,
            // The real request is authoritative. Avoid HEAD health checks changing the selected
            // order before this candidate has been exercised as the initial forward target.
            forwardTargetProbe = { io.stamethyst.backend.github.WattToolkitForwardTargetProbe.failed() },
            backgroundExecutor = Executor { },
        )
        val forwardDns = WattToolkitForwardDns()
        val unsafeHostProvider: (String) -> Boolean = resolver::allowsUnsafeHostnameBypass
        val directClient = OkHttpClient.Builder()
            .connectTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .hostnameVerifier(
                GithubDirectHostnameVerifier(
                    unsafeHostBypassProvider = unsafeHostProvider,
                ),
            )
            .dns(forwardDns)
            .trustWattToolkitForwardCertificates(unsafeHostProvider)
            .followRedirects(false)
            .followSslRedirects(false)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Leave a full request budget for the official-origin fallback after a forward hop
            // consumes its own timeout.
            .callTimeout(ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = listOf(resolver),
                    directCallFactory = directClient,
                    forwardDns = forwardDns,
                ),
            )
            .build()

        return try {
            client.newCall(
                Request.Builder()
                    .url(WORKSHOP_BROWSE_URL)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                val body = response.body.string()
                MarketCandidateResult(
                    preferredTarget = preferredTarget,
                    statusCode = response.code,
                    validWorkshopPage = response.isSuccessful && body.isValidWorkshopBrowsePage(),
                    elapsedMs = elapsedMs(startedAtNs),
                    failure = null,
                )
            }
        } catch (error: Exception) {
            return MarketCandidateResult(
                preferredTarget = preferredTarget,
                statusCode = null,
                validWorkshopPage = false,
                elapsedMs = elapsedMs(startedAtNs),
                failure = "${error::class.simpleName}: ${error.message}",
            )
        }
    }

    private fun downloadWorkshopBrowseContentThroughOnlyTarget(
        discoveredRoute: WattToolkitGithubRoute,
        preferredTarget: String,
    ): MarketCandidateResult {
        val startedAtNs = System.nanoTime()
        println("Steam market acceptance browseDownloadStart preferredTarget=$preferredTarget")
        val route = discoveredRoute.copy(
            // A single target makes this a node capability test. If it fails, the test must not
            // turn that failure into a false pass by falling through to another Watt node.
            forwardTargets = listOf(preferredTarget),
            isOfficial = false,
        )
        val store = object : WattToolkitGithubRouteStore {
            override fun load(): PersistedWattToolkitGithubRoute =
                PersistedWattToolkitGithubRoute(route = route, cachedAtMs = System.currentTimeMillis())

            override fun save(route: PersistedWattToolkitGithubRoute) = Unit

            override fun clear() = Unit
        }
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = SteamCommunityWattToolkitRouteProfile,
            routeStore = store,
            // The real GET is the health check for this acceptance case.
            forwardTargetProbe = { io.stamethyst.backend.github.WattToolkitForwardTargetProbe.failed() },
            backgroundExecutor = Executor { },
        )
        try {
            val forwardDns = WattToolkitForwardDns()
            val unsafeHostProvider: (String) -> Boolean = resolver::allowsUnsafeHostnameBypass
            val directClient = OkHttpClient.Builder()
                .connectTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .hostnameVerifier(
                    GithubDirectHostnameVerifier(
                        unsafeHostBypassProvider = unsafeHostProvider,
                    ),
                )
                .dns(forwardDns)
                .trustWattToolkitForwardCertificates(unsafeHostProvider)
                .followRedirects(false)
                .followSslRedirects(false)
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
            val client = OkHttpClient.Builder()
                .connectTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(
                    ExperimentalGithubDirectAccessInterceptor(
                        routeResolvers = listOf(resolver),
                        directCallFactory = directClient,
                        forwardDns = forwardDns,
                    ),
                )
                // The official path is deliberately disabled. A successful result must come from
                // the candidate being tested, not from the origin after the candidate fails.
                .addInterceptor { throw IOException("official route disabled for candidate test") }
                .build()

            val outputFile = Files.createTempFile("steam-market-candidate-", ".html").toFile()
            return try {
                client.newCall(
                    Request.Builder()
                        .url(WORKSHOP_BROWSE_URL)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build(),
                ).execute().use { response ->
                    response.body.byteStream().use { input ->
                        outputFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val body = outputFile.readText()
                    MarketCandidateResult(
                        preferredTarget = preferredTarget,
                        statusCode = response.code,
                        validWorkshopPage = response.isSuccessful &&
                            outputFile.length() > 0L &&
                            body.isValidWorkshopBrowsePage(),
                        elapsedMs = elapsedMs(startedAtNs),
                        failure = null,
                    )
                }
            } finally {
                outputFile.delete()
            }
        } catch (error: Exception) {
            return MarketCandidateResult(
                preferredTarget = preferredTarget,
                statusCode = null,
                validWorkshopPage = false,
                elapsedMs = elapsedMs(startedAtNs),
                failure = "${error::class.simpleName}: ${error.message}",
            )
        }
    }

    private fun String.isValidWorkshopBrowsePage(): Boolean =
        WorkshopBrowseParser.parsePage(this, page = 1).items.isNotEmpty()

    private fun elapsedMs(startedAtNs: Long): Long =
        ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(1L)

    private data class MarketCandidateResult(
        val preferredTarget: String,
        val statusCode: Int?,
        val validWorkshopPage: Boolean,
        val elapsedMs: Long,
        val failure: String?,
    ) {
        fun isSuccessful(): Boolean = statusCode in 200..299 && validWorkshopPage

        override fun toString(): String =
            "Steam market acceptance preferredTarget=$preferredTarget status=${statusCode ?: "none"} " +
                "validWorkshopPage=$validWorkshopPage elapsedMs=$elapsedMs failure=${failure ?: "none"}"
    }

    private companion object {
        const val ENABLE_ENVIRONMENT_VARIABLE = "STS_RUN_MARKET_NETWORK_ACCEPTANCE"
        const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
        const val WORKSHOP_BROWSE_URL =
            "https://steamcommunity.com/workshop/browse/?appid=646570&browsesort=trend&section=readytouseitems&actualsort=trend&p=1&numperpage=10"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        const val MAX_PARALLEL_PROBES = 4
        const val PER_REQUEST_TIMEOUT_MS = 15_000L
        const val ACCEPTANCE_TIMEOUT_MS = 30_000L
    }
}
