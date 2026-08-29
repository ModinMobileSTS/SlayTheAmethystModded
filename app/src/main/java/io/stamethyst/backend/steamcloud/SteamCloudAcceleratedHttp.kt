package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.WATT_PROXY_TYPE_DIRECT
import io.stamethyst.backend.github.WATT_PROXY_TYPE_REVERSE_PROXY
import io.stamethyst.backend.github.WattToolkitForwardDns
import io.stamethyst.backend.github.WattToolkitGithubRoute
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.backend.github.CredentialSafeRedirectInterceptor
import io.stamethyst.backend.github.addHttpsOnlyTransport
import io.stamethyst.backend.github.createWattToolkitRuntime
import io.stamethyst.backend.github.trustWattToolkitForwardCertificates
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.net.ProtocolException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import top.apricityx.workshop.steam.protocol.SteamDeclaredCdnHosts
import top.apricityx.workshop.steam.protocol.SteamWebSocketFactory

internal val SteamCommunityWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-community",
    // v5 invalidates caches created before bootstrap host scoping and HTTPS enforcement.
    cacheFileName = "watt-steam-community-route-cache-v5.json",
    supportedHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
    bootstrapForwardTargets = listOf("https://steamcommunity.rmbgame.net"),
    bootstrapSupportedHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
)

internal val SteamStoreWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-store",
    cacheFileName = "watt-steam-store-route-cache-v4.json",
    supportedHosts = setOf(
        "api.steampowered.com",
        "store.steampowered.com",
        "help.steampowered.com",
        "login.steampowered.com",
        "checkout.steampowered.com",
    ),
    bootstrapForwardTargets = listOf("https://steamstore.rmbgame.net"),
    bootstrapSupportedHosts = setOf(
        "api.steampowered.com",
        "store.steampowered.com",
        "help.steampowered.com",
        "checkout.steampowered.com",
    ),
)

internal val SteamImageCdnWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-image-cdn",
    // v3 invalidates caches created before the suffix families were declared.
    cacheFileName = "watt-steam-image-cdn-route-cache-v4.json",
    supportedHosts = setOf(
        "steamcdn-a.akamaihd.net",
        "steamuserimages-a.akamaihd.net",
        "images.steamusercontent.com",
        "steamusercontent.com",
        "cdn.akamai.steamstatic.com",
        "community.akamai.steamstatic.com",
        "avatars.akamai.steamstatic.com",
        "store.akamai.steamstatic.com",
        "avatars.fastly.steamstatic.com",
    ),
    bootstrapForwardTargets = listOf("https://steamimage.rmbgame.net"),
    bootstrapSupportedHosts = emptySet(),
    // Upstream publishes one rule per image CDN family. Enumerating hosts exactly left
    // siblings such as avatars.steamstatic.com and avatars.cloudflare.steamstatic.com
    // unaccelerated, which is precisely where logged-in profile avatars resolve to.
    supportedHostSuffixes = setOf(
        ".steamstatic.com",
        ".akamaihd.net",
        ".steamusercontent.com",
    ),
)

internal val SteamMediaWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-media",
    cacheFileName = "watt-steam-media-route-cache-v3.json",
    supportedHosts = setOf("media.steampowered.com"),
    bootstrapForwardTargets = listOf("https://steammedia.rmbgame.net"),
    bootstrapSupportedHosts = setOf("media.steampowered.com"),
)

internal val SteamContentCdnWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-content-cdn",
    cacheFileName = "watt-steam-content-cdn-route-cache-v4.json",
    supportedHosts = setOf(
        "st.dl.eccdnx.com",
        "shared.st.dl.eccdnx.com",
        "clan.st.dl.eccdnx.com",
        "store.st.dl.eccdnx.com",
        "avatars.st.dl.eccdnx.com",
        "media.st.dl.eccdnx.com",
        "video.st.dl.eccdnx.com",
        "xz.pphimalayanrt.com",
        "xz.sycontroller.com",
        "dl.steam.clngaa.com",
        "files.steam.nsclouds.cn",
    ),
    bootstrapForwardTargets = emptyList(),
    supportedProxyTypes = setOf(WATT_PROXY_TYPE_DIRECT, WATT_PROXY_TYPE_REVERSE_PROXY),
    // SteamPipe CDN rules are published as unchecked while their health is being updated.
    // Workshop downloads still need the available reverse-proxy route instead of falling
    // back to the origin CDN whenever that flag is false.
    allowUncheckedRoutes = true,
)

/**
 * SteamPipe returns cleartext endpoints for some workshop CDN edges. These hosts only serve
 * public depot manifests and chunks; Steam web sessions and account endpoints remain HTTPS-only.
 * The allowlist is a bootstrap for the well-known China CDN families; hosts Steam itself declares
 * in its content-server directory are admitted dynamically via [SteamDeclaredCdnHosts].
 */
internal fun allowsSteamContentCdnHttp(url: HttpUrl): Boolean =
    !url.isHttps &&
        url.encodedPath.startsWith("/depot/") &&
        url.host.lowercase(Locale.ROOT).let { host ->
            host in SteamContentCdnWattToolkitRouteProfile.supportedHosts ||
                SteamDeclaredCdnHosts.isDeclared(host) ||
                host == "steamcontent.com" ||
                host.endsWith(".steamcontent.com")
        }

internal val SteamCmWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-cm",
    cacheFileName = "watt-steam-cm-route-cache-v2.json",
    supportedHosts = setOf("steamserver.net"),
    bootstrapForwardTargets = emptyList(),
    supportedHostSuffixes = setOf(".steamserver.net"),
)

private val defaultSteamCloudWattToolkitRouteProfiles = listOf(
    SteamCommunityWattToolkitRouteProfile,
    SteamStoreWattToolkitRouteProfile,
    SteamImageCdnWattToolkitRouteProfile,
    SteamMediaWattToolkitRouteProfile,
    SteamContentCdnWattToolkitRouteProfile,
    // Steam CM accepts the WebSocket handshake through a forward route, but the
    // subsequent Cloud RPC can be rejected with ClientServerUnavailable/serverType=41.
    // Keep binary CM traffic on the official endpoint; HTTP/CDN acceleration remains enabled.
)

object SteamCloudAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
            context = context,
            configuredEnabled = LauncherConfig.isSteamCloudWattAccelerationEnabled(context),
        )

    @JvmStatic
    @JvmOverloads
    fun createClient(
        context: Context,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
        enabled: Boolean = isEnabled(context),
        enabledProvider: (() -> Boolean)? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)

        if (!enabled) {
            return builder.build()
        }

        val accelerationEnabledProvider = {
            NetworkAccelerationPolicy.shouldUseAcceleratedLinks(
                context = context,
                configuredEnabled = enabledProvider?.invoke() ?: enabled,
            )
        }
        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createSteamCloudWattToolkitRuntime(filesDir)
        }
        return builder
            .hostnameVerifier(runtime.hostnameVerifier)
            .followRedirects(false)
            .followSslRedirects(false)
            .addHttpsOnlyTransport(::allowsSteamContentCdnHttp)
            .addInterceptor(
                CredentialSafeRedirectInterceptor(
                    requireHttps = true,
                    allowInsecureUrl = ::allowsSteamContentCdnHttp,
                ),
            )
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                    forwardDns = runtime.forwardDns,
                    enabledProvider = accelerationEnabledProvider,
                    requireHttps = runtime.requireHttps,
                    allowInsecureUrl = ::allowsSteamContentCdnHttp,
                ),
            )
            .build()
    }

    /**
     * CM traffic uses a WebSocket and therefore does not pass through OkHttp's
     * application interceptor forwarding path. Keep the CM route selection here so
     * cloud batch completion and manifest refresh RPCs use the same Watt route as
     * their HTTP setup calls.
     */
    @JvmStatic
    fun createWebSocketFactory(
        context: Context,
        client: OkHttpClient,
    ): SteamWebSocketFactory {
        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createSteamCloudWattToolkitRuntime(filesDir)
        }
        val officialClient = client.newBuilder().apply {
            interceptors().removeAll { interceptor ->
                interceptor is ExperimentalGithubDirectAccessInterceptor
            }
        }
            .hostnameVerifier(runtime.hostnameVerifier)
            .build()
        val forwardClient = officialClient.newBuilder()
            .hostnameVerifier(runtime.forwardHostnameVerifier)
            .dns(runtime.forwardDns ?: WattToolkitForwardDns())
            .trustWattToolkitForwardCertificates { host ->
                runtime.resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
            }
            .addHttpsOnlyTransport()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        return SteamCmAcceleratedWebSocketFactory(
            officialClient = officialClient,
            forwardClient = forwardClient,
            routeResolvers = runtime.resolvers,
            forwardDns = runtime.forwardDns,
            enabledProvider = { isEnabled(context) },
        )
    }

    @JvmStatic
    fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

/** Applies a Watt route to the WebSocket opening handshake and falls back once before open. */
internal class SteamCmAcceleratedWebSocketFactory(
    private val officialClient: OkHttpClient,
    private val forwardClient: OkHttpClient,
    private val routeResolvers: List<WattToolkitGithubRouteResolver>,
    private val forwardDns: WattToolkitForwardDns? = null,
    private val enabledProvider: () -> Boolean = { true },
) : SteamWebSocketFactory {
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        requireSecureWebSocketUrl(request.url)
        val resolver = routeResolvers.firstOrNull { candidate -> candidate.supports(request.url.host) }
        if (!enabledProvider() || resolver == null) {
            return officialClient.newWebSocket(request, listener)
        }
        val route = resolver.resolveRouteForHost(request.url.host)
        if (route == null || route.isOfficial || route.forwardTargets.isEmpty()) {
            return officialClient.newWebSocket(request, listener)
        }

        val attempts = route.forwardTargetCandidates()
            .filter { candidate -> candidate.forwardTargets.isNotEmpty() }
        if (attempts.isEmpty()) {
            return officialClient.newWebSocket(request, listener)
        }
        return openAttempt(
            logicalRequest = request,
            listener = listener,
            resolver = resolver,
            candidateRoutes = attempts,
            attemptIndex = 0,
        )
    }

    private fun openAttempt(
        logicalRequest: Request,
        listener: WebSocketListener,
        resolver: WattToolkitGithubRouteResolver,
        candidateRoutes: List<WattToolkitGithubRoute>,
        attemptIndex: Int,
    ): WebSocket {
        val route = candidateRoutes[attemptIndex]
        val target = route.forwardTargets.first()
        requireSecureWebSocketUrl(logicalRequest.url)
        val forwardedRequest = buildSteamCmForwardedWebSocketRequest(logicalRequest, route, forwardDns)
        requireSecureWebSocketUrl(forwardedRequest.url)
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val terminal = java.util.concurrent.atomic.AtomicBoolean(false)
        val forwardedListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                resolver.confirmSuccessfulForwardTarget(logicalRequest.url.host, target)
                listener.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener.onMessage(webSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (opened.get() || !terminal.compareAndSet(false, true)) {
                    listener.onClosed(webSocket, code, reason)
                } else {
                    resolver.markForwardTargetFailed(logicalRequest.url.host, target)
                    openFallback(
                        logicalRequest = logicalRequest,
                        listener = listener,
                        resolver = resolver,
                        candidateRoutes = candidateRoutes,
                        nextAttemptIndex = attemptIndex + 1,
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (opened.get() || !terminal.compareAndSet(false, true)) {
                    listener.onFailure(webSocket, t, response)
                } else {
                    resolver.markForwardTargetFailed(logicalRequest.url.host, target)
                    openFallback(
                        logicalRequest = logicalRequest,
                        listener = listener,
                        resolver = resolver,
                        candidateRoutes = candidateRoutes,
                        nextAttemptIndex = attemptIndex + 1,
                    )
                }
            }
        }
        return forwardClient.newWebSocket(forwardedRequest, forwardedListener)
    }

    private fun openFallback(
        logicalRequest: Request,
        listener: WebSocketListener,
        resolver: WattToolkitGithubRouteResolver,
        candidateRoutes: List<WattToolkitGithubRoute>,
        nextAttemptIndex: Int,
    ) {
        if (nextAttemptIndex < candidateRoutes.size) {
            openAttempt(
                logicalRequest = logicalRequest,
                listener = listener,
                resolver = resolver,
                candidateRoutes = candidateRoutes,
                attemptIndex = nextAttemptIndex,
            )
        } else {
            val officialListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    resolver.confirmSuccessfulOfficialPath(logicalRequest.url.host)
                    listener.onOpen(webSocket, response)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    listener.onMessage(webSocket, bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(webSocket, code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(webSocket, code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    resolver.markOfficialPathFailed(logicalRequest.url.host)
                    listener.onFailure(webSocket, t, response)
                }
            }
            officialClient.newWebSocket(logicalRequest, officialListener)
        }
    }
}

internal fun buildSteamCmForwardedWebSocketRequest(
    logicalRequest: Request,
    route: WattToolkitGithubRoute,
    forwardDns: WattToolkitForwardDns? = null,
): Request {
    val logicalUrl = route.normalizeLogicalUrl(logicalRequest.url, logicalRequest.url.host)
    val networkUrl = route.buildForwardedUrl(logicalUrl)
    forwardDns?.register(route)
    return logicalRequest.newBuilder()
        .url(networkUrl)
        .header("Host", logicalUrl.host)
        .build()
}

internal fun requireSecureWebSocketUrl(url: HttpUrl) {
    if (!url.isHttps) {
        throw ProtocolException("WSS is required for Steam CM WebSocket: $url")
    }
}

internal fun createSteamCloudWattToolkitRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultSteamCloudWattToolkitRouteProfiles,
): ExperimentalGithubDirectAccessRuntime = createWattToolkitRuntime(
    filesDir = filesDir,
    cacheSubDirectory = "steam-cloud/network",
    routeProfiles = routeProfiles,
    connectTimeoutMs = STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS,
    readTimeoutMs = STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS,
    requireHttps = true,
    allowInsecureUrl = ::allowsSteamContentCdnHttp,
)

private const val STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS = 8_000L
private const val STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS = 60_000L
