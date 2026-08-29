package io.stamethyst.backend.github

import android.content.Context
import io.stamethyst.backend.network.AcceleratedRouteEvent
import io.stamethyst.backend.network.AcceleratedRouteEvents
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.Proxy
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import org.json.JSONArray
import org.json.JSONObject

internal data class WattToolkitRouteProfile(
    val name: String,
    val cacheFileName: String,
    val supportedHosts: Set<String>,
    val bootstrapForwardTargets: List<String>,
    val bootstrapSupportedHosts: Set<String> = emptySet(),
    val supportedProxyTypes: Set<Int> = setOf(WATT_PROXY_TYPE_DIRECT),
    val allowUncheckedRoutes: Boolean = false,
    val officialProbePath: String = "/",
    /**
     * Subdomain suffixes (".example.com") accelerated by the same upstream rule.
     *
     * Watt publishes one forwarding rule per logical domain family, so every
     * subdomain of that family can reuse the resolved hop. Enumerating hosts
     * exactly used to leave siblings such as avatars.githubusercontent.com
     * unaccelerated even though a working route already existed.
     */
    val supportedHostSuffixes: Set<String> = emptySet(),
)

internal val GithubApiWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-api",
    cacheFileName = "watt-github-api-route-cache-v3.json",
    supportedHosts = setOf("api.github.com"),
    bootstrapForwardTargets = listOf("githubapi.rmbgame.net"),
    officialProbePath = "/rate_limit",
)

internal val GithubWebWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-web",
    cacheFileName = "watt-github-web-route-cache-v2.json",
    supportedHosts = setOf("github.com"),
    bootstrapForwardTargets = emptyList(),
)

internal val GithubUserContentWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "githubusercontent",
    cacheFileName = "watt-githubusercontent-route-cache-v2.json",
    supportedHosts = setOf(
        "codeload.github.com",
        "githubusercontent.com",
        "media.githubusercontent.com",
        "objects.githubusercontent.com",
        "raw.github.com",
        "raw.githubusercontent.com",
        "release-assets.githubusercontent.com",
    ),
    bootstrapForwardTargets = emptyList(),
    // Upstream publishes one githubusercontent.com rule; avatars/user-images and
    // other siblings previously fell through to an unaccelerated direct request.
    supportedHostSuffixes = setOf(".githubusercontent.com"),
)

private val defaultExperimentalGithubDirectAccessProfiles = listOf(
    GithubApiWattToolkitRouteProfile,
    GithubWebWattToolkitRouteProfile,
    GithubUserContentWattToolkitRouteProfile,
)

internal data class ExperimentalGithubDirectAccessRuntime(
    val resolvers: List<WattToolkitGithubRouteResolver>,
    val hostnameVerifier: HostnameVerifier,
    val directHttpClient: OkHttpClient,
    val forwardDns: WattToolkitForwardDns? = null,
    val requireHttps: Boolean = false,
    val forwardHostnameVerifier: HostnameVerifier = hostnameVerifier,
)

internal object WattToolkitAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

    fun createClientPair(
        context: Context,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean = true,
    ): GithubRequestClients {
        val accelerationAllowedProvider = {
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                context = context,
                configuredEnabled = true,
            )
        }
        return GithubRequestClients(
            plainClient = createPlainClient(
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                followRedirects = followRedirects,
            ),
            acceleratedClient = createClient(
                context = context,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                followRedirects = followRedirects,
            ),
            accelerationAllowedProvider = accelerationAllowedProvider,
        )
    }

    fun createClient(
        context: Context,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean = true,
    ): OkHttpClient {
        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createExperimentalGithubDirectAccessRuntime(filesDir)
        }
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .hostnameVerifier(runtime.hostnameVerifier)
            .addHttpsOnlyTransport()
            .apply {
                if (followRedirects) {
                    addInterceptor(CredentialSafeRedirectInterceptor(requireHttps = true))
                }
            }
            .addExperimentalGithubDirectAccess(
                runtime = runtime,
                enabledProvider = {
                    NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                        context = context,
                        configuredEnabled = true,
                    )
                },
            )
            .build()
    }

    internal fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

internal data class GithubRequestClients(
    val plainClient: OkHttpClient,
    val acceleratedClient: OkHttpClient,
    val accelerationAllowedProvider: () -> Boolean = { true },
) {
    fun pick(useAcceleration: Boolean): OkHttpClient {
        return if (useAcceleration && accelerationAllowedProvider()) acceleratedClient else plainClient
    }
}

internal fun createPlainClient(
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    followRedirects: Boolean = true,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addHttpsOnlyTransport()
    if (followRedirects) {
        builder.addInterceptor(CredentialSafeRedirectInterceptor(requireHttps = true))
    }
    return builder.build()
}

internal fun createExperimentalGithubDirectAccessRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultExperimentalGithubDirectAccessProfiles,
): ExperimentalGithubDirectAccessRuntime = createWattToolkitRuntime(
    filesDir = filesDir,
    cacheSubDirectory = "github/network",
    routeProfiles = routeProfiles,
    connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
    requireHttps = true,
)

/**
 * Single builder for every Watt forwarding runtime.
 *
 * GitHub and Steam previously kept near-identical copies of this wiring, which
 * is how the certificate-validation fix could be applied to one and missed by
 * the other. Both now share this code path.
 */
internal fun createWattToolkitRuntime(
    filesDir: File,
    cacheSubDirectory: String,
    routeProfiles: List<WattToolkitRouteProfile>,
    connectTimeoutMs: Long,
    readTimeoutMs: Long,
    requireHttps: Boolean = false,
    allowInsecureUrl: (HttpUrl) -> Boolean = { false },
): ExperimentalGithubDirectAccessRuntime {
    val forwardDns = WattToolkitForwardDns()
    val routeClient = defaultWattToolkitRouteClient(requireHttps = requireHttps)
    val resolvers = routeProfiles.map { routeProfile ->
        WattToolkitGithubRouteResolver(
            routeProfile = routeProfile,
            client = routeClient,
            routeStore = FileBackedWattToolkitGithubRouteStore(
                file = File(filesDir, "$cacheSubDirectory/${routeProfile.cacheFileName}"),
                fallbackLogicalHosts = routeProfile.supportedHosts,
                fallbackLogicalHostSuffixes = routeProfile.supportedHostSuffixes,
            ),
            forwardTargetProbe = { target ->
                probeWattToolkitForwardTarget(routeClient, target, requireHttps = requireHttps)
            },
            officialTargetProbe = { host, path ->
                probeWattToolkitOfficialTarget(routeClient, host, path, requireHttps = requireHttps)
            },
            requireHttps = requireHttps,
        )
    }
    val unsafeHostProvider: (String) -> Boolean = { host ->
        resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
    }
    val platformHostnameVerifier: HostnameVerifier = OkHostnameVerifier
    val forwardHostnameVerifier = GithubDirectHostnameVerifier(
        defaultVerifier = platformHostnameVerifier,
        unsafeHostBypassProvider = unsafeHostProvider,
    )
    val directHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .hostnameVerifier(forwardHostnameVerifier)
        .dns(forwardDns)
        .trustWattToolkitForwardCertificates(unsafeHostProvider)
        .apply {
            if (requireHttps) {
                addHttpsOnlyTransport(allowInsecureUrl)
            }
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    return ExperimentalGithubDirectAccessRuntime(
        resolvers = resolvers,
        hostnameVerifier = platformHostnameVerifier,
        directHttpClient = directHttpClient,
        forwardDns = forwardDns,
        requireHttps = requireHttps,
        forwardHostnameVerifier = forwardHostnameVerifier,
    )
}

internal fun OkHttpClient.Builder.addExperimentalGithubDirectAccess(
    runtime: ExperimentalGithubDirectAccessRuntime,
    enabledProvider: () -> Boolean = { true },
    allowInsecureUrl: (HttpUrl) -> Boolean = { false },
): OkHttpClient.Builder = apply {
    if (runtime.requireHttps) {
        addHttpsOnlyTransport(allowInsecureUrl)
    }
    addInterceptor(
        ExperimentalGithubDirectAccessInterceptor(
            routeResolvers = runtime.resolvers,
            directCallFactory = runtime.directHttpClient,
            forwardDns = runtime.forwardDns,
            enabledProvider = enabledProvider,
            requireHttps = runtime.requireHttps,
            allowInsecureUrl = allowInsecureUrl,
        ),
    )
}

/**
 * Binds [cookieJar] to both OkHttp and the acceleration interceptor.
 *
 * `cookieJar(...)` alone only covers the unaccelerated path, because
 * [ExperimentalGithubDirectAccessInterceptor] answers routed requests on its own call factory and
 * never reaches OkHttp's cookie bridge. Callers that need cookies on accelerated hosts (Steam
 * workshop browsing, for example) must go through this helper.
 */
internal fun OkHttpClient.Builder.withAcceleratedCookieJar(
    cookieJar: CookieJar,
): OkHttpClient.Builder = apply {
    cookieJar(cookieJar)
    val existing = interceptors().toList()
    interceptors().clear()
    existing.forEach { interceptor ->
        if (interceptor is ExperimentalGithubDirectAccessInterceptor) {
            addInterceptor(interceptor.withCookieJar(cookieJar))
        } else {
            addInterceptor(interceptor)
        }
    }
}

/**
 * Installs a trust manager that only relaxes chain validation for hops whose
 * upstream rule explicitly sets IgnoreSSLCertVerification.
 *
 * Previously every accelerated request accepted any certificate, so a mirror
 * operator or on-path attacker could read traffic including Steam session
 * cookies. Most published routes (Steam store/community, for example) declare
 * IgnoreSSLCertVerification=false and rely on SNI cloaking instead, so they
 * still validate normally through the platform trust manager.
 */
internal fun OkHttpClient.Builder.trustWattToolkitForwardCertificates(
    unsafeHostProvider: (String) -> Boolean = { false },
): OkHttpClient.Builder = apply {
    val platformTrustManager = platformX509TrustManager() ?: return@apply
    val trustManager = WattToolkitForwardTrustManager(
        delegate = platformTrustManager,
        unsafeHostProvider = unsafeHostProvider,
    )
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)
}

private fun platformX509TrustManager(): X509TrustManager? = runCatching {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
}.getOrNull()

/**
 * Routes matching requests through a Watt forward target.
 *
 * This is an *application* interceptor that deliberately does not call
 * `chain.proceed()` on the accelerated path: it re-issues the request on
 * [directCallFactory] instead. OkHttp's own `BridgeInterceptor` — the component
 * that reads and writes cookies — sits after all application interceptors, so
 * it never runs for accelerated calls. Any cookie jar configured on the calling
 * client is therefore invisible here and must be bridged explicitly through
 * [cookieJar]; see [applyCookieHeader] and [persistResponseCookies].
 */
internal class ExperimentalGithubDirectAccessInterceptor(
    private val routeResolvers: List<WattToolkitGithubRouteResolver>,
    private val directCallFactory: okhttp3.Call.Factory,
    private val maxRedirects: Int = MAX_FOLLOW_UPS,
    private val enabledProvider: () -> Boolean = { true },
    private val forwardDns: WattToolkitForwardDns? = null,
    private val cookieJar: CookieJar = CookieJar.NO_COOKIES,
    private val requireHttps: Boolean = false,
    private val allowInsecureUrl: (HttpUrl) -> Boolean = { false },
) : Interceptor {
    /**
     * Returns a copy bound to [cookieJar].
     *
     * Derived clients built with `newBuilder()` reuse the very same interceptor instance, so a
     * cookie jar cannot simply be set on the builder: it would either be ignored (the accelerated
     * path never reaches OkHttp's bridge) or leak one client's Steam session into every sibling
     * client. Rebinding produces a per-client interceptor instead.
     */
    fun withCookieJar(cookieJar: CookieJar): ExperimentalGithubDirectAccessInterceptor =
        ExperimentalGithubDirectAccessInterceptor(
            routeResolvers = routeResolvers,
            directCallFactory = directCallFactory,
            maxRedirects = maxRedirects,
            enabledProvider = enabledProvider,
            forwardDns = forwardDns,
            cookieJar = cookieJar,
            requireHttps = requireHttps,
            allowInsecureUrl = allowInsecureUrl,
        )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        ensureUrlAllowed(request.url)
        // A caller may explicitly allow a cleartext origin such as a public SteamPipe CDN.
        // Keep that request on the official path: forwarding it could replace the approved
        // origin with an unrelated cleartext proxy target.
        if (!request.url.isHttps && allowInsecureUrl(request.url)) {
            return chain.proceed(request)
        }
        if (!enabledProvider()) {
            return chain.proceed(request)
        }
        if (routeResolvers.none { resolver -> resolver.supports(request.url.host) }) {
            return chain.proceed(request)
        }
        var officialRequestAttempted = false
        return try {
            executeDirectAccessRequest(
                initialLogicalRequest = request,
                officialRequestExecutor = { officialRequest ->
                    officialRequestAttempted = true
                    chain.proceed(officialRequest)
                },
                officialRequestAttemptedProvider = { officialRequestAttempted },
            )
        } catch (error: ProtocolException) {
            throw error
        } catch (error: IOException) {
            if (officialRequestAttempted) {
                routeResolvers
                    .firstOrNull { resolver -> resolver.supports(request.url.host) }
                    ?.markOfficialPathFailed(request.url.host)
                throw error
            }
            chain.proceed(request)
        }
    }

    private fun executeDirectAccessRequest(
        initialLogicalRequest: Request,
        officialRequestExecutor: (Request) -> Response,
        officialRequestAttemptedProvider: () -> Boolean,
    ): Response {
        var logicalRequest = initialLogicalRequest
        var followUpCount = 0
        var credentialsAllowed = true
        val failedForwardTargets = LinkedHashSet<String>()
        while (true) {
            ensureUrlAllowed(logicalRequest.url)
            val resolver = routeResolvers.firstOrNull { candidate -> candidate.supports(logicalRequest.url.host) }
            val route = resolver?.resolveRouteForHost(logicalRequest.url.host)
            var effectiveRoute = route
            var usedForwardTarget: String? = null
            var usedOfficial = false
            // Cookies are keyed on the logical URL so they are never handed to the
            // forward target's own hostname.
            val logicalUrl = logicalRequest.url
            val cookieRequest = applyCookieHeader(logicalRequest, logicalUrl, credentialsAllowed)
            val response = try {
                val executed = executeWithForwardTargetFallback(
                    logicalRequest = logicalRequest,
                    forwardedRequest = cookieRequest,
                    route = route,
                    failedForwardTargets = failedForwardTargets,
                    officialRequestExecutor = officialRequestExecutor,
                    includeOfficialCandidate = !officialRequestAttemptedProvider(),
                )
                usedForwardTarget = executed.usedForwardTarget
                usedOfficial = executed.usedOfficial
                executed.response
            } catch (error: IOException) {
                val refreshedRoute = resolver?.refreshRouteForHost(
                    host = logicalRequest.url.host,
                    excludedForwardTargets = failedForwardTargets,
                ) ?: throw error
                effectiveRoute = refreshedRoute
                try {
                    val executed = executeWithForwardTargetFallback(
                        logicalRequest = logicalRequest,
                        forwardedRequest = cookieRequest,
                        route = refreshedRoute,
                        failedForwardTargets = failedForwardTargets,
                        officialRequestExecutor = officialRequestExecutor,
                        includeOfficialCandidate = !officialRequestAttemptedProvider(),
                    )
                    usedForwardTarget = executed.usedForwardTarget
                    usedOfficial = executed.usedOfficial
                    executed.response
                } catch (refreshedError: IOException) {
                    refreshedError.addSuppressed(error)
                    throw refreshedError
                }
            }
            if (usedOfficial) {
                resolver?.confirmSuccessfulOfficialPath(logicalRequest.url.host)
                return response
            }
            var responseTransferred = false
            try {
                persistResponseCookies(logicalUrl, response)
                if (
                    resolver != null &&
                effectiveRoute != null &&
                usedForwardTarget != null &&
                response.isStaleForwardRouteResponse(logicalRequest.url) &&
                failedForwardTargets.add(usedForwardTarget)
                ) {
                    responseTransferred = true
                    AcceleratedRouteEvents.emit(
                        AcceleratedRouteEvent.ForwardTargetFailed(
                            host = logicalRequest.url.host,
                            target = usedForwardTarget,
                            reason = "HTTP ${response.code}",
                        ),
                    )
                    response.close()
                    resolver.refreshRouteForHost(
                        host = logicalRequest.url.host,
                        excludedForwardTargets = failedForwardTargets,
                    )
                continue
            }
            if (
                resolver != null &&
                effectiveRoute != null &&
                usedForwardTarget != null &&
                response.isSuccessful
            ) {
                // Real successful hop becomes the preferred best path for next cold start.
                resolver.confirmSuccessfulForwardTarget(
                    host = logicalRequest.url.host,
                    successfulTarget = usedForwardTarget,
                )
                }
                val redirectTarget = response.redirectTarget(logicalRequest.url, effectiveRoute)
                if (redirectTarget == null) {
                    responseTransferred = true
                    return response.newBuilder()
                        .request(logicalRequest)
                        .build()
                }
                if (followUpCount >= maxRedirects) {
                    responseTransferred = true
                    response.close()
                    throw ProtocolException("Too many GitHub direct-access redirects: $maxRedirects")
                }
                val nextCredentialsAllowed = credentialsAllowed &&
                    logicalRequest.url.hasSameSecureOrigin(redirectTarget)
                val nextLogicalRequest = try {
                    ensureUrlAllowed(redirectTarget)
                    buildCredentialSafeRedirectRequest(
                        previousLogicalRequest = logicalRequest,
                        redirectUrl = redirectTarget,
                        responseCode = response.code,
                        preserveSensitiveHeaders = nextCredentialsAllowed,
                    )
                } finally {
                    responseTransferred = true
                    response.close()
                }
                logicalRequest = nextLogicalRequest
                credentialsAllowed = nextCredentialsAllowed
                followUpCount++
            } finally {
                if (!responseTransferred) {
                    responseTransferred = true
                    response.close()
                }
            }
        }
    }

    private fun ensureUrlAllowed(url: HttpUrl) {
        if (requireHttps && !url.isHttps && !allowInsecureUrl(url)) {
            throw ProtocolException("HTTPS is required for accelerated request: $url")
        }
    }

    /**
     * Attaches the jar's cookies for [logicalUrl] to the outgoing request.
     *
     * OkHttp's own cookie handling lives in BridgeInterceptor, which runs after every
     * application interceptor. Because this interceptor answers routed requests through a
     * separate call factory instead of [Interceptor.Chain.proceed], that bridge never runs and
     * any cookie jar configured on the calling client is silently ignored. Steam workshop
     * browsing then loses its `steamLoginSecure` cookie and Steam serves the logged-out view.
     */
    private fun applyCookieHeader(
        request: Request,
        logicalUrl: HttpUrl,
        credentialsAllowed: Boolean,
    ): Request {
        if (!credentialsAllowed) {
            return request.newBuilder()
                .removeSensitiveCredentialHeaders()
                .build()
        }
        if (cookieJar == CookieJar.NO_COOKIES) return request
        val cookies = runCatching { cookieJar.loadForRequest(logicalUrl) }.getOrDefault(emptyList())
        if (cookies.isEmpty()) return request
        val header = cookies.joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
        return request.newBuilder()
            .header("Cookie", header)
            .build()
    }

    /** Writes back `Set-Cookie` values against the logical URL, never the forward target. */
    private fun persistResponseCookies(logicalUrl: HttpUrl, response: Response) {
        if (cookieJar == CookieJar.NO_COOKIES) return
        val setCookieHeaders = response.headers.values("Set-Cookie")
        if (setCookieHeaders.isEmpty()) return
        val cookies = setCookieHeaders.mapNotNull { value -> Cookie.parse(logicalUrl, value) }
        if (cookies.isEmpty()) return
        runCatching { cookieJar.saveFromResponse(logicalUrl, cookies) }
    }

    private data class ForwardedExecution(
        val response: Response,
        val usedForwardTarget: String?,
        val usedOfficial: Boolean,
    )

    private fun executeWithForwardTargetFallback(
        logicalRequest: Request,
        forwardedRequest: Request,
        route: WattToolkitGithubRoute?,
        failedForwardTargets: MutableSet<String>,
        officialRequestExecutor: (Request) -> Response,
        includeOfficialCandidate: Boolean,
    ): ForwardedExecution {
        val candidateRoutes = when {
            route?.isOfficial == true -> {
                val wattCandidates = route.copy(isOfficial = false)
                    .forwardTargetCandidates()
                    .filter { it.forwardTargets.isNotEmpty() }
                if (includeOfficialCandidate) listOf(route) + wattCandidates else wattCandidates
            }
            route != null -> {
                val wattCandidates = route.forwardTargetCandidates()
                    .filter { it.forwardTargets.isNotEmpty() }
                if (includeOfficialCandidate) wattCandidates + listOf(null) else wattCandidates
            }
            includeOfficialCandidate -> listOf(null)
            else -> emptyList()
        }
        var lastError: IOException? = null
        candidateRoutes.forEach { candidateRoute ->
            val candidateTarget = candidateRoute
                ?.takeUnless(WattToolkitGithubRoute::isOfficial)
                ?.forwardTargets
                ?.firstOrNull()
            if (
                candidateTarget != null &&
                failedForwardTargets.any { failed -> forwardTargetsEquivalent(failed, candidateTarget) }
            ) {
                return@forEach
            }
            val logicalHost = logicalRequest.url.host
            try {
                if (candidateRoute == null || candidateRoute.isOfficial) {
                    AcceleratedRouteEvents.emit(AcceleratedRouteEvent.OfficialAttempt(logicalHost))
                    val officialResponse = officialRequestExecutor(logicalRequest)
                    AcceleratedRouteEvents.emit(AcceleratedRouteEvent.OfficialSucceeded(logicalHost))
                    return ForwardedExecution(
                        response = officialResponse,
                        usedForwardTarget = null,
                        usedOfficial = true,
                    )
                }
                AcceleratedRouteEvents.emit(
                    AcceleratedRouteEvent.ForwardTargetAttempt(logicalHost, candidateTarget.orEmpty()),
                )
                val response = directCallFactory.newCall(
                    buildNetworkRequest(forwardedRequest, candidateRoute),
                ).execute()
                AcceleratedRouteEvents.emit(
                    AcceleratedRouteEvent.ForwardTargetSucceeded(logicalHost, candidateTarget.orEmpty()),
                )
                return ForwardedExecution(
                    response = response,
                    usedForwardTarget = candidateTarget,
                    usedOfficial = false,
                )
            } catch (error: IOException) {
                if (candidateTarget != null) {
                    failedForwardTargets += candidateTarget
                    AcceleratedRouteEvents.emit(
                        AcceleratedRouteEvent.ForwardTargetFailed(
                            host = logicalHost,
                            target = candidateTarget,
                            reason = error.routeFailureReason(),
                        ),
                    )
                }
                lastError = error
                if (candidateRoute == null || candidateRoute.isOfficial) {
                    AcceleratedRouteEvents.emit(
                        AcceleratedRouteEvent.OfficialFailed(logicalHost, error.routeFailureReason()),
                    )
                    routeResolvers
                        .firstOrNull { resolver -> resolver.supports(logicalRequest.url.host) }
                        ?.markOfficialPathFailed(logicalRequest.url.host)
                }
            }
        }
        throw lastError ?: IOException("No acceleration route candidate was available")
    }

    private fun forwardTargetsEquivalent(left: String, right: String): Boolean {
        val leftNormalized = left.trim()
        val rightNormalized = right.trim()
        if (leftNormalized.equals(rightNormalized, ignoreCase = true)) {
            return true
        }
        val leftHost = extractForwardHost(leftNormalized)
        val rightHost = extractForwardHost(rightNormalized)
        return leftHost != null && leftHost == rightHost
    }

    private fun buildNetworkRequest(
        logicalRequest: Request,
        route: WattToolkitGithubRoute?,
    ): Request {
        if (route == null) {
            return logicalRequest
        }
        val logicalUrl = route.normalizeLogicalUrl(
            url = logicalRequest.url,
            fallbackLogicalHost = logicalRequest.url.host,
        )
        val shouldForward = route.matchesLogicalHost(logicalUrl.host)
        val networkUrl = if (shouldForward) route.buildForwardedUrl(logicalUrl) else logicalUrl
        if (shouldForward) {
            ensureUrlAllowed(networkUrl)
            forwardDns?.register(route)
        }
        return logicalRequest.newBuilder()
            .url(networkUrl)
            .apply {
                if (shouldForward) {
                    header("Host", logicalUrl.host)
                } else {
                    removeHeader("Host")
                }
            }
            .build()
    }

}

internal fun Request.Builder.removeSensitiveCredentialHeaders(): Request.Builder {
    build().headers.names()
        .filter(::isSensitiveCredentialHeader)
        .forEach(::removeHeader)
    return this
}

internal fun buildCredentialSafeRedirectRequest(
    previousLogicalRequest: Request,
    redirectUrl: HttpUrl,
    responseCode: Int,
    preserveSensitiveHeaders: Boolean,
): Request {
    val preserveBody = responseCode == HTTP_TEMP_REDIRECT || responseCode == HTTP_PERM_REDIRECT
    val preserveRequestBody = preserveBody && preserveSensitiveHeaders
    val originalMethod = previousLogicalRequest.method
    val redirectMethod = when {
        preserveRequestBody -> originalMethod
        originalMethod == HTTP_METHOD_GET || originalMethod == HTTP_METHOD_HEAD -> originalMethod
        else -> HTTP_METHOD_GET
    }
    val redirectBody: RequestBody? = if (
        redirectMethod == originalMethod && (!preserveBody || preserveSensitiveHeaders)
    ) {
        previousLogicalRequest.body
    } else {
        null
    }
    return previousLogicalRequest.newBuilder()
        .url(redirectUrl)
        .method(redirectMethod, redirectBody)
        .apply {
            if (!preserveSensitiveHeaders) {
                removeSensitiveCredentialHeaders()
            }
            if (redirectBody == null) {
                removeHeader("Transfer-Encoding")
                removeHeader("Content-Length")
                removeHeader("Content-Type")
            }
        }
        .build()
}

internal fun HttpUrl.hasSameSecureOrigin(other: HttpUrl): Boolean =
    isHttps && other.isHttps &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port

internal fun HttpUrl.hasSameLogicalHost(other: HttpUrl): Boolean = hasSameSecureOrigin(other)

private fun isSensitiveCredentialHeader(name: String): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    return normalized == "authorization" ||
        normalized == "proxy-authorization" ||
        normalized == "cookie" ||
        normalized.contains("steam") ||
        normalized.contains("session")
}

internal class HttpsOnlyInterceptor(
    private val allowInsecureUrl: (HttpUrl) -> Boolean = { false },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !allowInsecureUrl(url)) {
            throw ProtocolException("HTTPS is required for Steam request: $url")
        }
        return chain.proceed(chain.request())
    }
}

internal fun OkHttpClient.Builder.addHttpsOnlyTransport(
    allowInsecureUrl: (HttpUrl) -> Boolean = { false },
): OkHttpClient.Builder = apply {
    val interceptor = HttpsOnlyInterceptor(allowInsecureUrl)
    addInterceptor(interceptor)
    addNetworkInterceptor(interceptor)
}

internal class CredentialSafeRedirectInterceptor(
    private val maxRedirects: Int = MAX_FOLLOW_UPS,
    private val requireHttps: Boolean = false,
    private val allowInsecureUrl: (HttpUrl) -> Boolean = { false },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var logicalRequest = chain.request()
        var followUpCount = 0
        var credentialsAllowed = true
        while (true) {
            ensureRedirectUrlAllowed(logicalRequest.url)
            val response = chain.proceed(logicalRequest)
            val redirectTarget = response.redirectTarget(logicalRequest.url, route = null)
            if (redirectTarget == null) {
                return response
            }
            if (followUpCount >= maxRedirects) {
                response.close()
                throw ProtocolException("Too many secure redirects: $maxRedirects")
            }
            val nextCredentialsAllowed = credentialsAllowed &&
                logicalRequest.url.hasSameSecureOrigin(redirectTarget)
            val nextRequest = try {
                ensureRedirectUrlAllowed(redirectTarget)
                buildCredentialSafeRedirectRequest(
                    previousLogicalRequest = logicalRequest,
                    redirectUrl = redirectTarget,
                    responseCode = response.code,
                    preserveSensitiveHeaders = nextCredentialsAllowed,
                )
            } finally {
                response.close()
            }
            logicalRequest = nextRequest
            credentialsAllowed = nextCredentialsAllowed
            followUpCount++
        }
    }

    private fun ensureRedirectUrlAllowed(url: HttpUrl) {
        if (requireHttps && !url.isHttps && !allowInsecureUrl(url)) {
            throw ProtocolException("HTTPS is required for redirected request: $url")
        }
    }
}

internal class GithubDirectHostnameVerifier(
    private val defaultVerifier: HostnameVerifier = OkHostnameVerifier,
    private val unsafeHostBypassProvider: (String) -> Boolean,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        return defaultVerifier.verify(hostname, session) || unsafeHostBypassProvider(hostname)
    }
}

internal class WattToolkitGithubRouteResolver(
    private val routeProfile: WattToolkitRouteProfile = GithubApiWattToolkitRouteProfile,
    private val client: OkHttpClient = defaultWattToolkitRouteClient(),
    private val projectGroupsUrl: HttpUrl = WATT_ACCELERATOR_PROJECTGROUPS_URL.toHttpUrl(),
    private val routeStore: WattToolkitGithubRouteStore = NoOpWattToolkitGithubRouteStore,
    private val bootstrapRouteProvider: (WattToolkitRouteProfile) -> WattToolkitGithubRoute? = ::defaultBootstrapRouteForProfile,
    private val forwardTargetProbe: ((String) -> WattToolkitForwardTargetProbe)? = null,
    private val officialTargetProbe: ((String, String) -> WattToolkitForwardTargetProbe)? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleepProvider: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val backgroundExecutor: Executor = sharedBestPathBackgroundExecutor,
    private val requireHttps: Boolean = false,
) {
    private val lock = Any()
    private val normalizedSupportedHosts = routeProfile.supportedHosts.map { it.lowercase(Locale.ROOT) }.toSet()
    private val normalizedSupportedHostSuffixes = routeProfile.supportedHostSuffixes
        .map { it.lowercase(Locale.ROOT) }
        .map { if (it.startsWith(".")) it else ".$it" }
        .toSet()
    private val backgroundRefreshInFlight = AtomicBoolean(false)
    private val recentlyFailedForwardTargets = LinkedHashMap<String, Long>()
    private val effectiveForwardTargetProbe: (String) -> WattToolkitForwardTargetProbe =
        forwardTargetProbe ?: { WattToolkitForwardTargetProbe.failed() }
    private val effectiveOfficialTargetProbe: (String, String) -> WattToolkitForwardTargetProbe =
        officialTargetProbe ?: { _, _ -> WattToolkitForwardTargetProbe.failed() }

    @Volatile
    private var cachedRoute: WattToolkitGithubRoute? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var lastBackgroundSearchAtMs: Long = 0L

    @Volatile
    private var lastHttpConfirmedAtMs: Long = 0L

    @Volatile
    private var lastHttpConfirmedTarget: String = ""

    @Volatile
    private var persistedRouteLoaded: Boolean = false

    fun supports(host: String): Boolean = isProfileHost(host)

    /**
     * Exact host match, or a subdomain of a declared suffix family.
     *
     * Watt forwards a whole domain family through one rule, so restricting the
     * resolver to a hand-written host list silently dropped siblings that the
     * upstream rule already covers.
     */
    internal fun isProfileHost(host: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (normalizedHost in normalizedSupportedHosts) {
            return true
        }
        return normalizedSupportedHostSuffixes.any { suffix -> normalizedHost.endsWith(suffix) }
    }

    fun allowsUnsafeHostnameBypass(host: String): Boolean =
        cachedRoute?.shouldBypassHostnameVerification(host) == true

    fun resolveRouteForHost(host: String): WattToolkitGithubRoute? {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return null
        }
        val now = nowProvider()
        val cachedMatch = synchronized(lock) {
            restorePersistedRouteLocked()
            pruneFailedTargetsLocked(now)
            val cached = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) } ?: return@synchronized null
            val preferred = cached.forwardTargets.firstOrNull().orEmpty()
            val preferredPathFailed = if (cached.isOfficial) {
                recentlyFailedForwardTargets.keys.any { failed ->
                    failed == OFFICIAL_ROUTE_TARGET
                }
            } else {
                preferred.isNotEmpty() && recentlyFailedForwardTargets.keys.any { failed ->
                    forwardTargetsEquivalent(failed, preferred)
                }
            }
            if (preferredPathFailed) {
                // Last preferred hop already failed real traffic; rediscover instead of
                // replaying a known-bad best-path cache.
                null
            } else {
                cached
            }
        }
        if (cachedMatch != null) {
            // Always prefer last confirmed best path on the hot path.
            // Stale caches still serve immediately; discovery continues in background.
            scheduleBackgroundBestPathSearch(normalizedHost, force = now - cachedAtMs >= ROUTE_CACHE_TTL_MS)
            return cachedMatch
        }

        val excluded = synchronized(lock) {
            pruneFailedTargetsLocked(now)
            recentlyFailedForwardTargets.keys.toSet()
        }
        val discovered = discoverBestRoute(normalizedHost, excluded) ?: return null
        synchronized(lock) {
            installRouteLocked(discovered, now, httpConfirmed = false)
            return discovered
        }
    }

    fun refreshRouteForHost(
        host: String,
        excludedForwardTargets: Collection<String> = emptyList(),
    ): WattToolkitGithubRoute? {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return null
        }
        val excluded = synchronized(lock) {
            markFailedTargetsLocked(excludedForwardTargets)
            recentlyFailedForwardTargets.keys.toSet()
        }
        synchronized(lock) {
            cachedRoute = null
            cachedAtMs = 0L
            persistedRouteLoaded = true
            routeStore.clear()
        }
        val discovered = discoverBestRoute(normalizedHost, excluded) ?: return null
        synchronized(lock) {
            installRouteLocked(discovered, nowProvider(), httpConfirmed = false)
            return discovered
        }
    }

    /**
     * Pins a forward target that just completed a real request successfully as the
     * preferred best path, then keeps searching for a better path in the background.
     */
    fun confirmSuccessfulForwardTarget(host: String, successfulTarget: String) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        val normalizedTarget = successfulTarget.trim()
        if (!isProfileHost(normalizedHost) || normalizedTarget.isEmpty()) {
            return
        }
        synchronized(lock) {
            restorePersistedRouteLocked()
            // A hop that just served real traffic is no longer considered failed.
            recentlyFailedForwardTargets.keys
                .filter { failed -> forwardTargetsEquivalent(failed, normalizedTarget) }
                .forEach(recentlyFailedForwardTargets::remove)
            val current = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) } ?: return
            val preferred = preferForwardTarget(current, normalizedTarget)
            installRouteLocked(preferred, nowProvider(), httpConfirmed = true)
        }
        scheduleBackgroundBestPathSearch(normalizedHost, force = false)
    }

    fun confirmSuccessfulOfficialPath(host: String) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return
        }
        synchronized(lock) {
            restorePersistedRouteLocked()
            recentlyFailedForwardTargets.remove(OFFICIAL_ROUTE_TARGET)
            val current = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) }
                ?: officialRouteForHost(normalizedHost)
            installRouteLocked(current.copy(isOfficial = true), nowProvider(), httpConfirmed = true)
        }
        scheduleBackgroundBestPathSearch(normalizedHost, force = false)
    }

    fun markForwardTargetFailed(host: String, forwardTarget: String) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost) || forwardTarget.isBlank()) {
            return
        }
        synchronized(lock) {
            restorePersistedRouteLocked()
            markFailedTargetsLocked(listOf(forwardTarget))
        }
    }

    fun markOfficialPathFailed(host: String) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return
        }
        synchronized(lock) {
            recentlyFailedForwardTargets[OFFICIAL_ROUTE_TARGET] = nowProvider()
            cachedRoute = cachedRoute?.let { route ->
                if (route.forwardTargets.isEmpty()) null else route.copy(isOfficial = false)
            }
            routeStore.clear()
        }
    }

    fun scheduleBackgroundBestPathSearch(host: String, force: Boolean = false) {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (!isProfileHost(normalizedHost)) {
            return
        }
        val now = nowProvider()
        if (!force) {
            val lastSearch = lastBackgroundSearchAtMs
            if (lastSearch > 0L && now - lastSearch < BACKGROUND_BEST_PATH_MIN_INTERVAL_MS) {
                return
            }
        }
        if (!backgroundRefreshInFlight.compareAndSet(false, true)) {
            return
        }
        backgroundExecutor.execute {
            try {
                val excluded = synchronized(lock) {
                    pruneFailedTargetsLocked(nowProvider())
                    recentlyFailedForwardTargets.keys.toSet()
                }
                val discovered = discoverBestRoute(normalizedHost, excluded) ?: return@execute
                synchronized(lock) {
                    val current = cachedRoute
                    if (current == null || shouldSilentSwitchTo(current, discovered, normalizedHost)) {
                        installRouteLocked(discovered, nowProvider(), httpConfirmed = false)
                    } else {
                        lastBackgroundSearchAtMs = nowProvider()
                    }
                }
            } finally {
                backgroundRefreshInFlight.set(false)
            }
        }
    }

    private fun discoverBestRoute(
        normalizedHost: String,
        excludedForwardTargets: Set<String> = emptySet(),
    ): WattToolkitGithubRoute? {
        AcceleratedRouteEvents.emit(AcceleratedRouteEvent.RouteDiscoveryStarted(normalizedHost))
        val fetched = runCatching(::fetchSupportedRouteWithRetries)
            .getOrNull()
            ?.takeIf { it.matchesLogicalHost(normalizedHost) }
        val bootstrap = bootstrapRouteProvider(routeProfile)
            ?.takeIf { it.matchesLogicalHost(normalizedHost) }
        val merged = mergeDiscoveredRoutes(fetched, bootstrap)
            ?.withoutExcludedForwardTargets(excludedForwardTargets)
            ?.restrictForwardTargets()
        val rankedForwardRoute = merged?.copy(isOfficial = false)
        val officialProbe = if (excludedForwardTargets.contains(OFFICIAL_ROUTE_TARGET)) {
            WattToolkitForwardTargetProbe.failed()
        } else {
            runCatching {
                effectiveOfficialTargetProbe(normalizedHost, routeProfile.officialProbePath)
            }.getOrDefault(WattToolkitForwardTargetProbe.failed())
        }
        val forwardProbe = rankedForwardRoute?.forwardTargets
            ?.firstOrNull()
            ?.let { target ->
                runCatching { effectiveForwardTargetProbe(target) }
                    .getOrDefault(WattToolkitForwardTargetProbe.failed())
            }
        val resolved = when {
            officialProbe.isBetterThan(forwardProbe) ->
                (rankedForwardRoute ?: officialRouteForHost(normalizedHost)).copy(isOfficial = true)
            rankedForwardRoute != null -> rankedForwardRoute
            officialProbe.successRate > 0.0 -> officialRouteForHost(normalizedHost)
            else -> null
        }
        if (resolved == null) {
            AcceleratedRouteEvents.emit(AcceleratedRouteEvent.RouteDiscoveryFailed(normalizedHost))
        } else {
            AcceleratedRouteEvents.emit(
                AcceleratedRouteEvent.RouteDiscovered(
                    host = normalizedHost,
                    forwardTargetCount = resolved.forwardTargets.size,
                    preferOfficial = resolved.isOfficial,
                ),
            )
        }
        return resolved
    }

    private fun officialRouteForHost(host: String): WattToolkitGithubRoute =
        WattToolkitGithubRoute(
            logicalHosts = (routeProfile.supportedHosts + host)
                .map { it.lowercase(Locale.ROOT) }
                .toSet(),
            forwardTargets = emptyList(),
            isOfficial = true,
            logicalHostSuffixes = normalizedSupportedHostSuffixes,
        )

    private fun mergeDiscoveredRoutes(
        fetched: WattToolkitGithubRoute?,
        bootstrap: WattToolkitGithubRoute?,
    ): WattToolkitGithubRoute? {
        if (fetched == null) {
            return bootstrap
        }
        if (bootstrap == null) {
            return fetched.copy(forwardTargets = rankForwardTargets(fetched.forwardTargets))
        }
        val mergedTargets = LinkedHashSet<String>()
        // Keep probe-ranked Watt order, then append bootstrap hops as fallback.
        rankForwardTargets(fetched.forwardTargets).forEach { mergedTargets += it }
        val bootstrapCoversFetchedHosts = fetched.logicalHosts.all { host ->
            bootstrap.matchesLogicalHost(host)
        }
        if (bootstrapCoversFetchedHosts) {
            bootstrap.forwardTargets.forEach { target ->
                if (isAllowedForwardTarget(target) &&
                    mergedTargets.none { existing -> forwardTargetsEquivalent(existing, target) }
                ) {
                    mergedTargets += target
                }
            }
        }
        return fetched.copy(
            forwardTargets = mergedTargets.toList(),
            ignoreSslCertVerification = fetched.ignoreSslCertVerification ||
                (bootstrapCoversFetchedHosts && bootstrap.ignoreSslCertVerification),
            fakeServerName = fetched.fakeServerName.ifBlank {
                if (bootstrapCoversFetchedHosts) bootstrap.fakeServerName else ""
            },
        )
    }

    private fun installRouteLocked(
        route: WattToolkitGithubRoute,
        now: Long,
        httpConfirmed: Boolean,
    ) {
        cachedRoute = route
        cachedAtMs = now
        lastBackgroundSearchAtMs = now
        if (!httpConfirmed) {
            // Discovered/bootstrap routes are only in-memory until a real request
            // proves the hop. Persisting probe-ranked Watt hits poisoned cold starts
            // with hosts like www.valvesoftware.com that never serve workshop HTML.
            return
        }
        lastHttpConfirmedAtMs = now
        lastHttpConfirmedTarget = if (route.isOfficial) {
            OFFICIAL_ROUTE_TARGET
        } else {
            route.forwardTargets.firstOrNull().orEmpty()
        }
        routeStore.save(
            PersistedWattToolkitGithubRoute(
                route = route,
                cachedAtMs = now,
            ),
        )
    }

    private fun restorePersistedRouteLocked() {
        if (persistedRouteLoaded) {
            return
        }
        persistedRouteLoaded = true
        val persisted = routeStore.load() ?: return
        cachedRoute = persisted.route.restrictForwardTargets()
            ?: return
        cachedAtMs = persisted.cachedAtMs
        // Treat restored cache as recently searched so cold start does not immediately
        // re-hit projectgroups; TTL/force still triggers background revalidation.
        lastBackgroundSearchAtMs = persisted.cachedAtMs
        // Restored disk cache is unproven until a real HTTP success confirms it.
        lastHttpConfirmedAtMs = 0L
        lastHttpConfirmedTarget = ""
    }

    private fun markFailedTargetsLocked(targets: Collection<String>) {
        if (targets.isEmpty()) {
            return
        }
        val now = nowProvider()
        targets.forEach { target ->
            val normalized = target.trim()
            if (normalized.isNotEmpty()) {
                recentlyFailedForwardTargets[normalized] = now
            }
        }
        pruneFailedTargetsLocked(now)
    }

    private fun pruneFailedTargetsLocked(now: Long) {
        val iterator = recentlyFailedForwardTargets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= FAILED_FORWARD_TARGET_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun preferForwardTarget(
        route: WattToolkitGithubRoute,
        successfulTarget: String,
    ): WattToolkitGithubRoute {
        val matchIndex = route.forwardTargets.indexOfFirst { target ->
            forwardTargetsEquivalent(target, successfulTarget)
        }
        if (matchIndex <= 0) {
            return route
        }
        val reordered = ArrayList<String>(route.forwardTargets.size)
        reordered += route.forwardTargets[matchIndex]
        route.forwardTargets.forEachIndexed { index, target ->
            if (index != matchIndex) {
                reordered += target
            }
        }
        return route.copy(forwardTargets = reordered)
    }

    private fun probeForRoute(
        route: WattToolkitGithubRoute,
        host: String,
    ): WattToolkitForwardTargetProbe {
        return if (route.isOfficial) {
            runCatching {
                effectiveOfficialTargetProbe(
                    host,
                    routeProfile.officialProbePath,
                )
            }.getOrDefault(WattToolkitForwardTargetProbe.failed())
        } else {
            route.forwardTargets.firstOrNull()
                ?.let { target ->
                    runCatching { effectiveForwardTargetProbe(target) }
                        .getOrDefault(WattToolkitForwardTargetProbe.failed())
                }
                ?: WattToolkitForwardTargetProbe.failed()
        }
    }

    private fun shouldSilentSwitchTo(
        current: WattToolkitGithubRoute,
        candidate: WattToolkitGithubRoute,
        host: String,
    ): Boolean {
        if (current.forwardTargets == candidate.forwardTargets &&
            current.isOfficial == candidate.isOfficial &&
            current.ignoreSslCertVerification == candidate.ignoreSslCertVerification &&
            current.fakeServerName == candidate.fakeServerName &&
            current.logicalHosts == candidate.logicalHosts
        ) {
            return false
        }
        val currentPreferred = current.forwardTargets.firstOrNull().orEmpty()
        val candidatePreferred = candidate.forwardTargets.firstOrNull().orEmpty()
        if (current.isOfficial != candidate.isOfficial) {
            if (candidate.isOfficial && recentlyFailedForwardTargets.containsKey(OFFICIAL_ROUTE_TARGET)) {
                return false
            }
            val currentProbe = probeForRoute(current, host)
            val candidateProbe = probeForRoute(candidate, host)
            return candidateProbe.isBetterThan(currentProbe)
        }
        if (currentPreferred.isEmpty()) {
            return candidatePreferred.isNotEmpty()
        }
        if (candidatePreferred.isEmpty()) {
            return false
        }
        // Never silent-switch back onto a hop that just failed real traffic.
        if (recentlyFailedForwardTargets.keys.any { failed ->
                forwardTargetsEquivalent(failed, candidatePreferred)
            }
        ) {
            return false
        }
        if (forwardTargetsEquivalent(currentPreferred, candidatePreferred)) {
            // Same best hop; still adopt refreshed host/metadata set.
            return current.forwardTargets != candidate.forwardTargets ||
                current.logicalHosts != candidate.logicalHosts ||
                current.ignoreSslCertVerification != candidate.ignoreSslCertVerification ||
                current.fakeServerName != candidate.fakeServerName
        }
        // Lightweight probes can still rank an endpoint that rejects the real request.
        // Protect a recently HTTP-confirmed best path from silent demotion.
        val now = nowProvider()
        if (
            lastHttpConfirmedAtMs > 0L &&
            now - lastHttpConfirmedAtMs < ROUTE_CACHE_TTL_MS &&
            lastHttpConfirmedTarget.isNotEmpty() &&
            forwardTargetsEquivalent(currentPreferred, lastHttpConfirmedTarget)
        ) {
            return false
        }
        val currentProbe = runCatching { effectiveForwardTargetProbe(currentPreferred) }
            .getOrDefault(WattToolkitForwardTargetProbe.failed())
        val candidateProbe = runCatching { effectiveForwardTargetProbe(candidatePreferred) }
            .getOrDefault(WattToolkitForwardTargetProbe.failed())
        return when {
            candidateProbe.successRate > currentProbe.successRate -> true
            candidateProbe.successRate < currentProbe.successRate -> false
            else -> {
                val currentLatency = currentProbe.latencyMs ?: Long.MAX_VALUE
                val candidateLatency = candidateProbe.latencyMs ?: Long.MAX_VALUE
                candidateLatency + SILENT_SWITCH_LATENCY_MARGIN_MS < currentLatency
            }
        }
    }

    private fun forwardTargetsEquivalent(left: String, right: String): Boolean {
        val leftNormalized = left.trim()
        val rightNormalized = right.trim()
        if (leftNormalized.equals(rightNormalized, ignoreCase = true)) {
            return true
        }
        val leftHost = extractForwardHost(leftNormalized)
        val rightHost = extractForwardHost(rightNormalized)
        return leftHost != null && leftHost == rightHost
    }

    private fun WattToolkitGithubRoute.withoutExcludedForwardTargets(
        excludedForwardTargets: Set<String>,
    ): WattToolkitGithubRoute? {
        if (excludedForwardTargets.isEmpty()) {
            return this
        }
        if (isOfficial && excludedForwardTargets.any { it == OFFICIAL_ROUTE_TARGET }) {
            return null
        }
        val filteredTargets = forwardTargets.filterNot { target ->
            excludedForwardTargets.any { excluded -> forwardTargetsEquivalent(excluded, target) }
        }
        if (filteredTargets.isEmpty()) {
            return if (isOfficial) this else null
        }
        if (filteredTargets == forwardTargets) {
            return this
        }
        return copy(forwardTargets = filteredTargets)
    }

    private fun fetchSupportedRouteWithRetries(): WattToolkitGithubRoute {
        var lastError: Throwable? = null
        repeat(ROUTE_FETCH_ATTEMPTS) { attempt ->
            try {
                return fetchSupportedRoute()
            } catch (error: Throwable) {
                lastError = error
                val isLastAttempt = attempt == ROUTE_FETCH_ATTEMPTS - 1
                if (!error.isRetryableWattRouteFetchFailure() || isLastAttempt) {
                    throw error
                }
                val retryDelayMs = ROUTE_FETCH_RETRY_DELAYS_MS.getOrElse(attempt) { 0L }
                sleepForRetry(retryDelayMs)
            }
        }
        throw lastError ?: IllegalStateException("Watt Toolkit route fetch failed without exception")
    }

    private fun sleepForRetry(delayMs: Long) {
        if (delayMs <= 0L) {
            return
        }
        try {
            sleepProvider(delayMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to retry Watt route fetch", error)
        }
    }

    private fun fetchSupportedRoute(): WattToolkitGithubRoute {
        if (requireHttps && !projectGroupsUrl.isHttps) {
            throw ProtocolException("HTTPS is required for Watt Toolkit route discovery: $projectGroupsUrl")
        }
        val request = Request.Builder()
            .url(projectGroupsUrl)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Watt Toolkit route request failed: ${response.code}")
            }
            val payload = response.body.string()
            val matchedProject = findMatchingProject(payload)
                ?: throw IOException(
                    "Watt Toolkit route was not found for hosts=${normalizedSupportedHosts.joinToString(";")}",
                )
            if (matchedProject.proxyType !in routeProfile.supportedProxyTypes) {
                throw IOException(
                    "Unsupported Watt Toolkit route type for hosts=${matchedProject.logicalHosts.joinToString(";")}: ${matchedProject.proxyType}",
                )
            }
            return WattToolkitGithubRoute(
                logicalHosts = matchedProject.logicalHosts,
                forwardTargets = matchedProject.forwardTargets,
                ignoreSslCertVerification = matchedProject.ignoreSslCertVerification,
                fakeServerName = matchedProject.fakeServerName,
                logicalHostSuffixes = normalizedSupportedHostSuffixes,
            )
        }
    }

    private fun findMatchingProject(payload: String): MatchedWattProject? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val groups = root.optJSONArray(WATT_GROUPS_KEY) ?: return null
        return findMatchingProject(groups)
    }

    private fun findMatchingProject(groups: JSONArray): MatchedWattProject? {
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val items = group.optJSONArray(WATT_ITEMS_KEY) ?: continue
            val matched = findMatchingProjectInItems(items)
            if (matched != null) {
                return matched
            }
        }
        return null
    }

    private fun findMatchingProjectInItems(items: JSONArray): MatchedWattProject? {
        for (itemIndex in 0 until items.length()) {
            val item = items.optJSONObject(itemIndex) ?: continue
            val configuredHosts = parseHosts(
                item.optString(WATT_MATCH_DOMAIN_NAMES_KEY),
                item.optString(WATT_LISTEN_DOMAIN_NAMES_KEY),
            )
            val logicalHosts = normalizedSupportedHosts.filterTo(LinkedHashSet()) { supportedHost ->
                configuredHosts.any { configuredHost ->
                    wattHostPatternMatches(configuredHost, supportedHost)
                }
            }
            if (
                logicalHosts.isNotEmpty() &&
                (routeProfile.allowUncheckedRoutes || item.optBoolean(WATT_CHECKED_KEY, true))
            ) {
                return MatchedWattProject(
                    logicalHosts = logicalHosts,
                    forwardTargets = parseForwardTargets(item.optString(WATT_FORWARD_DOMAIN_NAMES_KEY)),
                    proxyType = item.optInt(WATT_PROXY_TYPE_KEY, -1),
                    ignoreSslCertVerification = item.optBoolean(WATT_IGNORE_SSL_CERT_KEY),
                    fakeServerName = item.optString(WATT_FAKE_SERVER_NAME_KEY).trim(),
                )
            }
            val nestedItems = item.optJSONArray(WATT_ITEMS_KEY) ?: continue
            val nestedMatched = findMatchingProjectInItems(nestedItems)
            if (nestedMatched != null) {
                return nestedMatched
            }
        }
        return null
    }

    private data class MatchedWattProject(
        val logicalHosts: Set<String>,
        val forwardTargets: List<String>,
        val proxyType: Int,
        val ignoreSslCertVerification: Boolean,
        val fakeServerName: String,
    )

    private fun rankForwardTargets(targets: List<String>): List<String> {
        val distinctTargets = targets
            .filter(::isAllowedForwardTarget)
            .distinct()
        if (distinctTargets.size < 2) {
            return distinctTargets
        }
        return distinctTargets
            .mapIndexed { index, target ->
                RankedWattForwardTarget(
                    target = target,
                    originalIndex = index,
                    probe = runCatching { effectiveForwardTargetProbe(target) }
                        .getOrDefault(WattToolkitForwardTargetProbe.failed()),
                )
            }
            .sortedWith(
                compareByDescending<RankedWattForwardTarget> { it.probe.successRate }
                    .thenBy { it.probe.latencyMs ?: Long.MAX_VALUE }
                    .thenBy { it.originalIndex },
            )
            .map(RankedWattForwardTarget::target)
    }

    private fun isAllowedForwardTarget(target: String): Boolean {
        if (!requireHttps) {
            return true
        }
        val normalized = target.trim()
        if (normalized.isEmpty()) {
            return false
        }
        val url = if (normalized.contains("://")) {
            normalized.toHttpUrlOrNull()
        } else {
            "https://$normalized".toHttpUrlOrNull()
        }
        return url?.isHttps == true
    }

    private fun WattToolkitGithubRoute.restrictForwardTargets(): WattToolkitGithubRoute? {
        if (!requireHttps) {
            return this
        }
        val allowedTargets = forwardTargets.filter(::isAllowedForwardTarget)
        return if (isOfficial) {
            copy(forwardTargets = allowedTargets)
        } else if (allowedTargets.isEmpty()) {
            null
        } else {
            copy(forwardTargets = allowedTargets)
        }
    }

    private data class RankedWattForwardTarget(
        val target: String,
        val originalIndex: Int,
        val probe: WattToolkitForwardTargetProbe,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ROUTE_CACHE_TTL_MS = 30L * 60L * 1_000L
        const val BACKGROUND_BEST_PATH_MIN_INTERVAL_MS = 5L * 60L * 1_000L
        const val FAILED_FORWARD_TARGET_TTL_MS = 30L * 60L * 1_000L
        const val SILENT_SWITCH_LATENCY_MARGIN_MS = 40L
        const val ROUTE_FETCH_ATTEMPTS = 5
        val ROUTE_FETCH_RETRY_DELAYS_MS = longArrayOf(250L, 500L, 1_000L, 1_500L)
        private val sharedBestPathBackgroundExecutor: Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "watt-best-path-search").apply {
                    isDaemon = true
                }
            }
    }
}

internal data class WattToolkitGithubRoute(
    val logicalHosts: Set<String>,
    val forwardTargets: List<String>,
    val isOfficial: Boolean = false,
    val ignoreSslCertVerification: Boolean = false,
    val fakeServerName: String = "",
    /**
     * Subdomain suffixes covered by the same upstream rule as [logicalHosts].
     *
     * Without this a resolved route would reject sibling hosts (for example
     * avatars.githubusercontent.com) even though the rule that produced the
     * route already forwards the entire domain family.
     */
    val logicalHostSuffixes: Set<String> = emptySet(),
) {
    val forwardHosts: Set<String> = forwardTargets.mapNotNull(::extractForwardHost).toSet()
    val networkHosts: Set<String> = buildSet {
        addAll(forwardHosts)
        normalizedFakeServerName()?.let(::add)
        if (usesOriginFakeServerName()) {
            addAll(logicalHosts)
        }
    }

    fun buildForwardedUrl(originalUrl: HttpUrl): HttpUrl {
        val firstTarget = forwardTargets.firstOrNull()?.trim().orEmpty()
        if (firstTarget.isBlank()) {
            return originalUrl
        }
        return if (firstTarget.contains("://")) {
            val forwardedBase = firstTarget.toHttpUrlOrNull() ?: return originalUrl
            val networkHost = networkHostFor(originalUrl.host) ?: forwardedBase.host
            forwardedBase.newBuilder()
                .host(networkHost)
                .encodedPath(originalUrl.encodedPath)
                .encodedQuery(originalUrl.encodedQuery)
                .build()
        } else {
            originalUrl.newBuilder()
                .host(networkHostFor(originalUrl.host) ?: firstTarget)
                .build()
        }
    }

    fun normalizeLogicalUrl(
        url: HttpUrl,
        fallbackLogicalHost: String,
    ): HttpUrl {
        if (url.host.lowercase(Locale.ROOT) !in networkHosts) {
            return url
        }
        return url.newBuilder()
            .host(fallbackLogicalHost)
            .build()
    }

    fun matchesLogicalHost(host: String): Boolean {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (normalizedHost in logicalHosts) {
            return true
        }
        return logicalHostSuffixes.any { suffix -> normalizedHost.endsWith(suffix) }
    }

    fun shouldBypassHostnameVerification(host: String): Boolean =
        ignoreSslCertVerification && host.lowercase(Locale.ROOT) in networkHosts

    fun networkHostFor(logicalHost: String): String? {
        val fakeHost = normalizedFakeServerName()
        return when {
            fakeHost != null -> fakeHost
            usesOriginFakeServerName() -> logicalHost.lowercase(Locale.ROOT)
            else -> null
        }
    }

    fun usesOriginFakeServerName(): Boolean = fakeServerName.trim() in setOf("{origin}", "@domain")

    fun forwardTargetCandidates(): List<WattToolkitGithubRoute> {
        if (forwardTargets.size < 2) {
            return listOf(this)
        }
        return forwardTargets.indices.map { index ->
            copy(forwardTargets = forwardTargets.drop(index))
        }
    }

    private fun normalizedFakeServerName(): String? = fakeServerName
        .trim()
        .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }
        ?.lowercase(Locale.ROOT)
}

internal class WattToolkitForwardDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    private val forwardHostsByNetworkHost = ConcurrentHashMap<String, String>()

    fun register(route: WattToolkitGithubRoute) {
        val targetHost = route.forwardHosts.firstOrNull() ?: return
        val fakeHost = route.fakeServerName
            .trim()
            .takeIf { it.isNotEmpty() && it != "{origin}" && it != "@domain" }
            ?.lowercase(Locale.ROOT)
        if (fakeHost != null) {
            forwardHostsByNetworkHost[fakeHost] = targetHost
        } else if (route.usesOriginFakeServerName()) {
            route.logicalHosts.forEach { logicalHost ->
                forwardHostsByNetworkHost[logicalHost.lowercase(Locale.ROOT)] = targetHost
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val targetHost = forwardHostsByNetworkHost[hostname.lowercase(Locale.ROOT)] ?: hostname
        return delegate.lookup(targetHost)
    }
}

internal interface WattToolkitGithubRouteStore {
    fun load(): PersistedWattToolkitGithubRoute?

    fun save(route: PersistedWattToolkitGithubRoute)

    fun clear() = Unit
}

internal object NoOpWattToolkitGithubRouteStore : WattToolkitGithubRouteStore {
    override fun load(): PersistedWattToolkitGithubRoute? = null

    override fun save(route: PersistedWattToolkitGithubRoute) = Unit

    override fun clear() = Unit
}

internal class FileBackedWattToolkitGithubRouteStore(
    private val file: File,
    private val fallbackLogicalHosts: Set<String> = emptySet(),
    private val fallbackLogicalHostSuffixes: Set<String> = emptySet(),
) : WattToolkitGithubRouteStore {
    override fun load(): PersistedWattToolkitGithubRoute? {
        return runCatching {
            if (!file.isFile) {
                return null
            }
            val snapshot = JSONObject(file.readText())
            val logicalHosts = buildStringList(snapshot.optJSONArray("logicalHosts"))
                .ifEmpty { fallbackLogicalHosts.toList() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val logicalHostSuffixes = buildStringList(snapshot.optJSONArray("logicalHostSuffixes"))
                .ifEmpty { fallbackLogicalHostSuffixes.toList() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val forwardTargets = buildStringList(snapshot.optJSONArray("forwardTargets"))
            val isOfficial = snapshot.optBoolean("isOfficial")
            if ((!isOfficial && forwardTargets.isEmpty()) || logicalHosts.isEmpty()) {
                return null
            }
            PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = logicalHosts,
                    forwardTargets = forwardTargets,
                    isOfficial = isOfficial,
                    ignoreSslCertVerification = snapshot.optBoolean("ignoreSslCertVerification"),
                    fakeServerName = snapshot.optString("fakeServerName").trim(),
                    logicalHostSuffixes = logicalHostSuffixes,
                ),
                cachedAtMs = snapshot.optLong("cachedAtMs"),
            )
        }.getOrNull()
    }

    override fun save(route: PersistedWattToolkitGithubRoute) {
        runCatching {
            file.parentFile?.mkdirs()
            val snapshot = JSONObject().apply {
                put("cachedAtMs", route.cachedAtMs)
                put("logicalHosts", JSONArray(route.route.logicalHosts.sorted()))
                put("forwardTargets", JSONArray(route.route.forwardTargets))
                put("isOfficial", route.route.isOfficial)
                put("ignoreSslCertVerification", route.route.ignoreSslCertVerification)
                put("fakeServerName", route.route.fakeServerName)
                put("logicalHostSuffixes", JSONArray(route.route.logicalHostSuffixes.sorted()))
            }
            val parentDir = file.parentFile ?: file.absoluteFile.parentFile
            val tempFile = File.createTempFile(file.name, ".tmp", parentDir)
            tempFile.writeText(snapshot.toString())
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}

internal data class PersistedWattToolkitGithubRoute(
    val route: WattToolkitGithubRoute,
    val cachedAtMs: Long,
)

internal fun defaultWattToolkitRouteClient(requireHttps: Boolean = false): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .apply {
            if (requireHttps) {
                addHttpsOnlyTransport()
            }
        }
        .build()

private fun defaultBootstrapRouteForProfile(routeProfile: WattToolkitRouteProfile): WattToolkitGithubRoute? =
    routeProfile.bootstrapForwardTargets
        .takeIf(List<String>::isNotEmpty)
        ?.let { forwardTargets ->
            val bootstrapHosts = routeProfile.bootstrapSupportedHosts
                .ifEmpty { routeProfile.supportedHosts }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            WattToolkitGithubRoute(
                logicalHosts = bootstrapHosts,
                forwardTargets = forwardTargets,
                ignoreSslCertVerification = false,
                fakeServerName = "",
            )
        }

private fun extractForwardHost(target: String): String? {
    val normalized = target.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return if (normalized.contains("://")) {
        normalized.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)
    } else {
        normalized.lowercase(Locale.ROOT)
    }
}

private fun parseForwardTargets(raw: String): List<String> =
    raw.split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)

internal data class WattToolkitForwardTargetProbe(
    val successes: Int,
    val attempts: Int,
    val latencyMs: Long?,
) {
    val successRate: Double
        get() = if (attempts <= 0) 0.0 else successes.toDouble() / attempts.toDouble()

    fun isBetterThan(other: WattToolkitForwardTargetProbe?): Boolean {
        if (other == null) {
            return successes > 0
        }
        if (successRate != other.successRate) {
            return successRate > other.successRate
        }
        val ownLatency = latencyMs ?: Long.MAX_VALUE
        val otherLatency = other.latencyMs ?: Long.MAX_VALUE
        return successes > 0 && ownLatency < otherLatency
    }

    companion object {
        fun failed(attempts: Int = FORWARD_TARGET_PROBE_ATTEMPTS): WattToolkitForwardTargetProbe =
            WattToolkitForwardTargetProbe(successes = 0, attempts = attempts, latencyMs = null)
    }
}

private fun probeWattToolkitForwardTarget(
    client: OkHttpClient,
    target: String,
    requireHttps: Boolean = false,
): WattToolkitForwardTargetProbe {
    val url = target.toHttpUrlOrNull()
        ?: "https://$target".toHttpUrlOrNull()
        ?: return WattToolkitForwardTargetProbe.failed()
    if (requireHttps && !url.isHttps) {
        return WattToolkitForwardTargetProbe.failed()
    }
    return probeWattToolkitHttpTarget(client, url, requireHttps)
}

private fun probeWattToolkitOfficialTarget(
    client: OkHttpClient,
    host: String,
    path: String,
    requireHttps: Boolean = false,
): WattToolkitForwardTargetProbe {
    val normalizedPath = if (path.startsWith('/')) path else "/$path"
    val url = "https://${host.trim()}$normalizedPath".toHttpUrlOrNull()
        ?: return WattToolkitForwardTargetProbe.failed()
    return probeWattToolkitHttpTarget(client, url, requireHttps)
}

private fun probeWattToolkitHttpTarget(
    client: OkHttpClient,
    url: HttpUrl,
    requireHttps: Boolean = false,
): WattToolkitForwardTargetProbe {
    if (requireHttps && !url.isHttps) {
        return WattToolkitForwardTargetProbe.failed()
    }
    val probeClient = client.newBuilder()
        .connectTimeout(FORWARD_TARGET_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FORWARD_TARGET_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(FORWARD_TARGET_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FORWARD_TARGET_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    var successes = 0
    val latencies = ArrayList<Long>(FORWARD_TARGET_PROBE_ATTEMPTS)
    repeat(FORWARD_TARGET_PROBE_ATTEMPTS) {
        val startedAt = System.nanoTime()
        try {
            probeClient.newCall(
                Request.Builder()
                    .url(url)
                    .head()
                    .build(),
            ).execute().use {
                // Any HTTP response proves the target completed transport and protocol setup.
                successes++
                latencies += ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            }
        } catch (_: IOException) {
            // Keep probing remaining samples so transient loss affects success rate.
        }
    }
    return WattToolkitForwardTargetProbe(
        successes = successes,
        attempts = FORWARD_TARGET_PROBE_ATTEMPTS,
        latencyMs = latencies.takeIf(List<Long>::isNotEmpty)?.average()?.toLong(),
    )
}

/**
 * Short, non-localized cause for route diagnostics.
 *
 * Exception messages here can be long TLS dumps, so keep only the leading clause: the progress UI
 * shows this inline and must not turn into a wall of certificate text.
 */
private fun IOException.routeFailureReason(): String {
    val rawMessage = message?.trim().orEmpty()
    val firstLine = rawMessage.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.ifEmpty { this::class.simpleName.orEmpty().ifEmpty { "I/O error" } }
}

private fun parseHosts(vararg hostGroups: String): Set<String> {    val hosts = LinkedHashSet<String>()
    hostGroups.forEach { group ->
        group.split(';').forEach { rawHost ->
            val normalized = rawHost.trim()
            if (normalized.isEmpty()) {
                return@forEach
            }
            val parsedHost = if ("://" in normalized) {
                normalized.toHttpUrlOrNull()?.host
            } else {
                normalized
            } ?: return@forEach
            val lowerHost = parsedHost.lowercase(Locale.ROOT)
            if (lowerHost == "*" || lowerHost.count { it == '*' } > 1 ||
                ("*" in lowerHost && !lowerHost.startsWith("*."))
            ) {
                return@forEach
            }
            hosts += lowerHost
        }
    }
    return hosts
}

private fun wattHostPatternMatches(pattern: String, host: String): Boolean {
    val normalizedPattern = pattern.lowercase(Locale.ROOT)
    val normalizedHost = host.lowercase(Locale.ROOT)
    if (normalizedPattern == normalizedHost) {
        return true
    }
    val wildcardSuffix = normalizedPattern.removePrefix("*.")
    return normalizedPattern.startsWith("*.") &&
        (normalizedHost == wildcardSuffix || normalizedHost.endsWith(".$wildcardSuffix"))
}

private fun buildStringList(array: JSONArray?): List<String> {
    if (array == null) {
        return emptyList()
    }
    val values = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotEmpty()) {
            values += value
        }
    }
    return values
}

private fun Throwable.isRetryableWattRouteFetchFailure(): Boolean =
    this is IOException || cause?.isRetryableWattRouteFetchFailure() == true

private fun Response.redirectTarget(
    logicalUrl: HttpUrl,
    route: WattToolkitGithubRoute?,
): HttpUrl? {
    if (code !in REDIRECT_RESPONSE_CODES) {
        return null
    }
    val location = header("Location")?.trim().orEmpty()
    if (location.isBlank()) {
        return null
    }
    return logicalUrl.resolve(location)?.let { resolvedUrl ->
        route?.normalizeLogicalUrl(
            url = resolvedUrl,
            fallbackLogicalHost = logicalUrl.host,
        ) ?: resolvedUrl
    }
}

private const val WATT_ACCELERATOR_PROJECTGROUPS_URL = "https://api.steampp.net/accelerator/projectgroups"
private const val WATT_GROUPS_KEY = "\uD83E\uDD93"
private const val WATT_ITEMS_KEY = "Items"
private const val WATT_MATCH_DOMAIN_NAMES_KEY = "MatchDomainNames"
private const val WATT_LISTEN_DOMAIN_NAMES_KEY = "ListenDomainNames"
private const val WATT_FORWARD_DOMAIN_NAMES_KEY = "ForwardDomainNames"
private const val WATT_PROXY_TYPE_KEY = "ProxyType"
private const val WATT_IGNORE_SSL_CERT_KEY = "IgnoreSSLCertVerification"
private const val WATT_FAKE_SERVER_NAME_KEY = "FakeServerName"
private const val WATT_CHECKED_KEY = "Checked"
internal const val WATT_PROXY_TYPE_DIRECT = 0
internal const val WATT_PROXY_TYPE_REVERSE_PROXY = 1
private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
private const val DEFAULT_READ_TIMEOUT_MS = 18_000L
private const val FORWARD_TARGET_PROBE_ATTEMPTS = 3
private const val FORWARD_TARGET_PROBE_TIMEOUT_MS = 1_200L
private const val OFFICIAL_ROUTE_TARGET = "__official__"
private const val MAX_FOLLOW_UPS = 10
private const val HTTP_METHOD_GET = "GET"
private const val HTTP_METHOD_HEAD = "HEAD"
private const val HTTP_TEMP_REDIRECT = 307
private const val HTTP_PERM_REDIRECT = 308
private val REDIRECT_RESPONSE_CODES = setOf(300, 301, 302, 303, HTTP_TEMP_REDIRECT, HTTP_PERM_REDIRECT)
private val STALE_ROUTE_RESPONSE_CODES = setOf(400, 404)
private val WORKSHOP_BROWSE_FORWARD_FAILURE_RESPONSE_CODES = setOf(
    400,
    403,
    404,
    408,
    421,
    500,
    501,
    502,
    503,
    504,
    521,
    522,
    523,
    524,
    525,
)

// A forward endpoint can reject the logical Host even though Steam itself is available. Treat a
// browse-only 403 as a failed Watt hop, refreshing until a new target succeeds or every forward
// target has been excluded. Other 403s retain their normal application-level meaning.
private fun Response.isStaleForwardRouteResponse(logicalUrl: HttpUrl): Boolean =
    code in STALE_ROUTE_RESPONSE_CODES ||
        (
            code in WORKSHOP_BROWSE_FORWARD_FAILURE_RESPONSE_CODES &&
            logicalUrl.host == "steamcommunity.com" &&
            logicalUrl.encodedPath.startsWith("/workshop/browse/")
        )

/**
 * Validates certificates through the platform trust manager, relaxing the check
 * only for forward hosts whose upstream rule opted out of chain verification.
 *
 * Extends [X509ExtendedTrustManager] because the peer host is required to make
 * that decision, and the two-argument [X509TrustManager] callbacks do not carry
 * it. The two-argument overloads therefore always validate strictly.
 */
private class WattToolkitForwardTrustManager(
    private val delegate: X509TrustManager,
    private val unsafeHostProvider: (String) -> Boolean,
) : X509ExtendedTrustManager() {
    private val extendedDelegate = delegate as? X509ExtendedTrustManager

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val peerHost = (socket as? SSLSocket)?.let { sslSocket ->
            runCatching { sslSocket.handshakeSession?.peerHost }.getOrNull()
        } ?: socket?.inetAddress?.hostName
        if (isUnsafeAllowed(peerHost)) {
            return
        }
        extendedDelegate?.checkServerTrusted(chain, authType, socket)
            ?: delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (isUnsafeAllowed(engine?.peerHost)) {
            return
        }
        extendedDelegate?.checkServerTrusted(chain, authType, engine)
            ?: delegate.checkServerTrusted(chain, authType)
    }

    private fun isUnsafeAllowed(peerHost: String?): Boolean {
        val host = peerHost?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return unsafeHostProvider(host)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
