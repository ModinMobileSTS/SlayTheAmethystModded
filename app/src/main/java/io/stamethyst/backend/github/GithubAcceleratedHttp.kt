package io.stamethyst.backend.github

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.Proxy
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import okhttp3.ConnectionPool
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
import org.json.JSONArray
import org.json.JSONObject

internal data class WattToolkitRouteProfile(
    val name: String,
    val cacheFileName: String,
    val supportedHosts: Set<String>,
    val bootstrapForwardTargets: List<String>,
)

internal val GithubApiWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-api",
    cacheFileName = "watt-github-api-route-cache.json",
    supportedHosts = setOf("api.github.com"),
    bootstrapForwardTargets = listOf("githubapi.rmbgame.net"),
)

internal val GithubWebWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "github-web",
    cacheFileName = "watt-github-web-route-cache.json",
    supportedHosts = setOf("github.com"),
    bootstrapForwardTargets = emptyList(),
)

internal val GithubUserContentWattToolkitRouteProfile = WattToolkitRouteProfile(
    name = "githubusercontent",
    cacheFileName = "watt-githubusercontent-route-cache.json",
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
)

internal object GithubAcceleratedHttp {
    private val runtimeCache = ConcurrentHashMap<String, ExperimentalGithubDirectAccessRuntime>()

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
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .hostnameVerifier(runtime.hostnameVerifier)
            .addExperimentalGithubDirectAccess(runtime)
            .build()
    }

    internal fun clearRuntimeCacheForTests() {
        runtimeCache.clear()
    }
}

internal fun createExperimentalGithubDirectAccessRuntime(
    filesDir: File,
    routeProfiles: List<WattToolkitRouteProfile> = defaultExperimentalGithubDirectAccessProfiles,
): ExperimentalGithubDirectAccessRuntime {
    val resolvers = routeProfiles.map { routeProfile ->
        WattToolkitGithubRouteResolver(
            routeProfile = routeProfile,
            routeStore = FileBackedWattToolkitGithubRouteStore(
                file = File(filesDir, "github/network/${routeProfile.cacheFileName}"),
                fallbackLogicalHosts = routeProfile.supportedHosts,
            ),
        )
    }
    val hostnameVerifier = GithubDirectHostnameVerifier { host ->
        resolvers.any { resolver -> resolver.allowsUnsafeHostnameBypass(host) }
    }
    val directHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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

internal fun OkHttpClient.Builder.addExperimentalGithubDirectAccess(
    runtime: ExperimentalGithubDirectAccessRuntime,
): OkHttpClient.Builder = apply {
    addInterceptor(
        ExperimentalGithubDirectAccessInterceptor(
            routeResolvers = runtime.resolvers,
            directCallFactory = runtime.directHttpClient,
        ),
    )
}

internal class ExperimentalGithubDirectAccessInterceptor(
    private val routeResolvers: List<WattToolkitGithubRouteResolver>,
    private val directCallFactory: okhttp3.Call.Factory,
    private val maxRedirects: Int = MAX_FOLLOW_UPS,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (routeResolvers.none { resolver -> resolver.supports(request.url.host) }) {
            return chain.proceed(request)
        }
        return executeDirectAccessRequest(request)
    }

    private fun executeDirectAccessRequest(initialLogicalRequest: Request): Response {
        var logicalRequest = initialLogicalRequest
        var followUpCount = 0
        while (true) {
            val resolver = routeResolvers.firstOrNull { candidate -> candidate.supports(logicalRequest.url.host) }
            val route = resolver?.resolveRouteForHost(logicalRequest.url.host)
            val networkRequest = buildNetworkRequest(logicalRequest, route)
            val response = directCallFactory.newCall(networkRequest).execute()
            val redirectTarget = response.redirectTarget(logicalRequest.url, route)
            if (redirectTarget == null) {
                return response.newBuilder()
                    .request(logicalRequest)
                    .build()
            }
            if (followUpCount >= maxRedirects) {
                response.close()
                throw ProtocolException("Too many GitHub direct-access redirects: $maxRedirects")
            }
            val nextLogicalRequest = buildRedirectRequest(
                previousLogicalRequest = logicalRequest,
                redirectUrl = redirectTarget,
                responseCode = response.code,
            )
            response.close()
            logicalRequest = nextLogicalRequest
            followUpCount++
        }
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

    private fun buildRedirectRequest(
        previousLogicalRequest: Request,
        redirectUrl: HttpUrl,
        responseCode: Int,
    ): Request {
        val preserveBody = responseCode == HTTP_TEMP_REDIRECT || responseCode == HTTP_PERM_REDIRECT
        val originalMethod = previousLogicalRequest.method
        val redirectMethod = when {
            preserveBody -> originalMethod
            originalMethod == HTTP_METHOD_GET || originalMethod == HTTP_METHOD_HEAD -> originalMethod
            else -> HTTP_METHOD_GET
        }
        val redirectBody: RequestBody? = if (redirectMethod == originalMethod) previousLogicalRequest.body else null
        return previousLogicalRequest.newBuilder()
            .url(redirectUrl)
            .method(redirectMethod, redirectBody)
            .apply {
                if (redirectBody == null) {
                    removeHeader("Transfer-Encoding")
                    removeHeader("Content-Length")
                    removeHeader("Content-Type")
                }
            }
            .build()
    }
}

internal class GithubDirectHostnameVerifier(
    private val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
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
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleepProvider: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
) {
    private val lock = Any()
    private val normalizedSupportedHosts = routeProfile.supportedHosts.map { it.lowercase(Locale.ROOT) }.toSet()

    @Volatile
    private var cachedRoute: WattToolkitGithubRoute? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var persistedRouteLoaded: Boolean = false

    fun supports(host: String): Boolean = host.lowercase(Locale.ROOT) in normalizedSupportedHosts

    fun allowsUnsafeHostnameBypass(host: String): Boolean =
        cachedRoute?.shouldBypassHostnameVerification(host) == true

    fun resolveRouteForHost(host: String): WattToolkitGithubRoute? {
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (normalizedHost !in normalizedSupportedHosts) {
            return null
        }
        val now = nowProvider()
        synchronized(lock) {
            restorePersistedRouteLocked()
            val cached = cachedRoute
            if (cached != null && cached.matchesLogicalHost(normalizedHost) && now - cachedAtMs < ROUTE_CACHE_TTL_MS) {
                return cached
            }
        }

        val fetched = runCatching(::fetchSupportedRouteWithRetries).getOrNull()
        synchronized(lock) {
            if (fetched != null) {
                cachedRoute = fetched
                cachedAtMs = now
                routeStore.save(
                    PersistedWattToolkitGithubRoute(
                        route = fetched,
                        cachedAtMs = now,
                    ),
                )
                return fetched
            }
            val cachedMatch = cachedRoute?.takeIf { it.matchesLogicalHost(normalizedHost) }
            if (cachedMatch != null) {
                return cachedMatch
            }
            val bootstrapRoute = bootstrapRouteProvider(routeProfile)
                ?.takeIf { it.matchesLogicalHost(normalizedHost) }
            if (bootstrapRoute != null) {
                cachedRoute = bootstrapRoute
                cachedAtMs = now
                routeStore.save(
                    PersistedWattToolkitGithubRoute(
                        route = bootstrapRoute,
                        cachedAtMs = now,
                    ),
                )
                return bootstrapRoute
            }
            return null
        }
    }

    private fun restorePersistedRouteLocked() {
        if (persistedRouteLoaded) {
            return
        }
        persistedRouteLoaded = true
        val persisted = routeStore.load() ?: return
        cachedRoute = persisted.route
        cachedAtMs = persisted.cachedAtMs
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
            if (matchedProject.proxyType != 0) {
                throw IOException(
                    "Unsupported Watt Toolkit route type for hosts=${matchedProject.logicalHosts.joinToString(";")}: ${matchedProject.proxyType}",
                )
            }
            return WattToolkitGithubRoute(
                logicalHosts = matchedProject.logicalHosts,
                forwardTargets = matchedProject.forwardTargets,
                ignoreSslCertVerification = matchedProject.ignoreSslCertVerification,
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
            val logicalHosts = parseHosts(
                item.optString(WATT_MATCH_DOMAIN_NAMES_KEY),
                item.optString(WATT_LISTEN_DOMAIN_NAMES_KEY),
            ).intersect(normalizedSupportedHosts)
            if (logicalHosts.isNotEmpty()) {
                return MatchedWattProject(
                    logicalHosts = logicalHosts,
                    forwardTargets = parseForwardTargets(item.optString(WATT_FORWARD_DOMAIN_NAMES_KEY)),
                    proxyType = item.optInt(WATT_PROXY_TYPE_KEY, -1),
                    ignoreSslCertVerification = item.optBoolean(WATT_IGNORE_SSL_CERT_KEY),
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
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ROUTE_CACHE_TTL_MS = 30L * 60L * 1_000L
        const val ROUTE_FETCH_ATTEMPTS = 5
        val ROUTE_FETCH_RETRY_DELAYS_MS = longArrayOf(250L, 500L, 1_000L, 1_500L)
    }
}

internal data class WattToolkitGithubRoute(
    val logicalHosts: Set<String>,
    val forwardTargets: List<String>,
    val ignoreSslCertVerification: Boolean = false,
) {
    val forwardHosts: Set<String> = forwardTargets.mapNotNull(::extractForwardHost).toSet()

    fun buildForwardedUrl(originalUrl: HttpUrl): HttpUrl {
        val firstTarget = forwardTargets.firstOrNull()?.trim().orEmpty()
        if (firstTarget.isBlank()) {
            return originalUrl
        }
        return if (firstTarget.contains("://")) {
            val forwardedBase = firstTarget.toHttpUrlOrNull() ?: return originalUrl
            forwardedBase.newBuilder()
                .encodedPath(originalUrl.encodedPath)
                .encodedQuery(originalUrl.encodedQuery)
                .build()
        } else {
            originalUrl.newBuilder()
                .host(firstTarget)
                .build()
        }
    }

    fun normalizeLogicalUrl(
        url: HttpUrl,
        fallbackLogicalHost: String,
    ): HttpUrl {
        if (url.host.lowercase(Locale.ROOT) !in forwardHosts) {
            return url
        }
        return url.newBuilder()
            .host(fallbackLogicalHost)
            .build()
    }

    fun matchesLogicalHost(host: String): Boolean = host.lowercase(Locale.ROOT) in logicalHosts

    fun shouldBypassHostnameVerification(host: String): Boolean =
        ignoreSslCertVerification && host.lowercase(Locale.ROOT) in forwardHosts
}

internal interface WattToolkitGithubRouteStore {
    fun load(): PersistedWattToolkitGithubRoute?

    fun save(route: PersistedWattToolkitGithubRoute)
}

internal object NoOpWattToolkitGithubRouteStore : WattToolkitGithubRouteStore {
    override fun load(): PersistedWattToolkitGithubRoute? = null

    override fun save(route: PersistedWattToolkitGithubRoute) = Unit
}

internal class FileBackedWattToolkitGithubRouteStore(
    private val file: File,
    private val fallbackLogicalHosts: Set<String> = emptySet(),
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
            val forwardTargets = buildStringList(snapshot.optJSONArray("forwardTargets"))
            if (forwardTargets.isEmpty() || logicalHosts.isEmpty()) {
                return null
            }
            PersistedWattToolkitGithubRoute(
                route = WattToolkitGithubRoute(
                    logicalHosts = logicalHosts,
                    forwardTargets = forwardTargets,
                    ignoreSslCertVerification = snapshot.optBoolean("ignoreSslCertVerification"),
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
                put("ignoreSslCertVerification", route.route.ignoreSslCertVerification)
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
}

internal data class PersistedWattToolkitGithubRoute(
    val route: WattToolkitGithubRoute,
    val cachedAtMs: Long,
)

internal fun defaultWattToolkitRouteClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .build()

private fun defaultBootstrapRouteForProfile(routeProfile: WattToolkitRouteProfile): WattToolkitGithubRoute? =
    routeProfile.bootstrapForwardTargets
        .takeIf(List<String>::isNotEmpty)
        ?.let { forwardTargets ->
            WattToolkitGithubRoute(
                logicalHosts = routeProfile.supportedHosts.map { it.lowercase(Locale.ROOT) }.toSet(),
                forwardTargets = forwardTargets,
                ignoreSslCertVerification = true,
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

private fun parseHosts(vararg hostGroups: String): Set<String> {
    val hosts = LinkedHashSet<String>()
    hostGroups.forEach { group ->
        group.split(';').forEach { rawHost ->
            val normalized = rawHost.trim()
            if (normalized.isEmpty() || '*' in normalized) {
                return@forEach
            }
            val parsedHost = if ("://" in normalized) {
                normalized.toHttpUrlOrNull()?.host
            } else {
                normalized
            } ?: return@forEach
            hosts += parsedHost.lowercase(Locale.ROOT)
        }
    }
    return hosts
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
private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
private const val DEFAULT_READ_TIMEOUT_MS = 18_000L
private const val MAX_FOLLOW_UPS = 10
private const val HTTP_METHOD_GET = "GET"
private const val HTTP_METHOD_HEAD = "HEAD"
private const val HTTP_TEMP_REDIRECT = 307
private const val HTTP_PERM_REDIRECT = 308
private val REDIRECT_RESPONSE_CODES = setOf(300, 301, 302, 303, HTTP_TEMP_REDIRECT, HTTP_PERM_REDIRECT)
