package io.stamethyst.backend.steamcloud

import android.content.Context
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessRuntime
import io.stamethyst.backend.github.ExperimentalGithubDirectAccessInterceptor
import io.stamethyst.backend.github.FileBackedWattToolkitGithubRouteStore
import io.stamethyst.backend.github.GithubDirectHostnameVerifier
import io.stamethyst.backend.github.WattToolkitGithubRouteResolver
import io.stamethyst.backend.github.WattToolkitRouteProfile
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

internal val SteamCommunityWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-community",
    cacheFileName = "watt-steam-community-route-cache.json",
    supportedHosts = setOf("steamcommunity.com", "www.steamcommunity.com"),
    bootstrapForwardTargets = listOf("steamcommunity.rmbgame.net"),
)

internal val SteamStoreWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-store",
    cacheFileName = "watt-steam-store-route-cache.json",
    supportedHosts = setOf(
        "api.steampowered.com",
        "store.steampowered.com",
        "help.steampowered.com",
        "login.steampowered.com",
        "checkout.steampowered.com",
    ),
    bootstrapForwardTargets = listOf("steamstore.rmbgame.net"),
)

internal val SteamImageCdnWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-image-cdn",
    cacheFileName = "watt-steam-image-cdn-route-cache.json",
    supportedHosts = setOf(
        "steamcdn-a.akamaihd.net",
        "steamuserimages-a.akamaihd.net",
        "cdn.akamai.steamstatic.com",
        "community.akamai.steamstatic.com",
        "avatars.akamai.steamstatic.com",
        "store.akamai.steamstatic.com",
        "avatars.fastly.steamstatic.com",
    ),
    bootstrapForwardTargets = listOf("steamimage.rmbgame.net"),
)

internal val SteamMediaWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "steam-media",
    cacheFileName = "watt-steam-media-route-cache.json",
    supportedHosts = setOf("media.steampowered.com"),
    bootstrapForwardTargets = listOf("steammedia.rmbgame.net"),
)

private val defaultSteamCloudWattToolkitRouteProfiles = listOf(
    SteamCommunityWattToolkitRouteProfile,
    SteamStoreWattToolkitRouteProfile,
    SteamImageCdnWattToolkitRouteProfile,
    SteamMediaWattToolkitRouteProfile,
)

object SteamCloudAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        LauncherConfig.isSteamCloudWattAccelerationEnabled(context)

    @JvmStatic
    fun createClient(
        context: Context,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        callTimeoutMs: Long,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)

        if (!isEnabled(context)) {
            return builder.build()
        }

        val filesDir = context.filesDir
        val runtime = runtimeCache.getOrPut(filesDir.absolutePath) {
            createSteamCloudWattToolkitRuntime(filesDir)
        }
        return builder
            .hostnameVerifier(runtime.hostnameVerifier)
            .addInterceptor(
                ExperimentalGithubDirectAccessInterceptor(
                    routeResolvers = runtime.resolvers,
                    directCallFactory = runtime.directHttpClient,
                ),
            )
            .build()
    }

    @JvmStatic
    fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

internal fun createSteamCloudWattToolkitRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultSteamCloudWattToolkitRouteProfiles,
): ExperimentalGithubDirectAccessRuntime {
    val resolvers = routeProfiles.map { routeProfile ->
        WattToolkitGithubRouteResolver(
            routeProfile = routeProfile,
            routeStore = FileBackedWattToolkitGithubRouteStore(
                file = File(filesDir, "steam-cloud/network/${routeProfile.cacheFileName}"),
                fallbackLogicalHosts = routeProfile.supportedHosts,
            ),
        )
    }
    val hostnameVerifier = GithubDirectHostnameVerifier { host ->
        resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
    }
    val directHttpClient = OkHttpClient.Builder()
        .connectTimeout(STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .hostnameVerifier(hostnameVerifier)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    return ExperimentalGithubDirectAccessRuntime(
        resolvers = resolvers,
        hostnameVerifier = hostnameVerifier,
        directHttpClient = directHttpClient,
    )
}

private const val STEAM_CLOUD_DIRECT_ACCESS_CONNECT_TIMEOUT_MS = 8_000L
private const val STEAM_CLOUD_DIRECT_ACCESS_READ_TIMEOUT_MS = 60_000L
