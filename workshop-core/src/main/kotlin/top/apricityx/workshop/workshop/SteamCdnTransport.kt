package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnRequestEndpoint
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.SteamDeclaredCdnHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class SteamCdnServerPool(
    val proxyServer: CdnServer?,
    val downloadServers: List<CdnServer>,
)

internal class SteamCdnTransport(
    private val client: OkHttpClient,
) {
    /**
     * Builds the ordered download pool for a depot request.
     *
     * @param preferSteamChinaServers keeps Steam-China-only edges at the head of
     * the pool. Steam returns a globally weighted list, so without this a
     * mainland client is pushed onto overseas edges that it can barely reach.
     */
    fun buildServerPool(
        appId: UInt,
        contentServers: List<CdnServer>,
        preferSteamChinaServers: Boolean = false,
    ): SteamCdnServerPool {
        // Remember every host Steam handed us so the app's HTTPS-only guard can accept
        // cleartext depot traffic on these edges, including China CDN hosts that were not
        // in the launcher's static allowlist.
        contentServers.forEach { server ->
            SteamDeclaredCdnHosts.register(server.host)
            SteamDeclaredCdnHosts.register(server.vHost)
        }
        val proxyServer = contentServers.firstOrNull(CdnServer::useAsProxy)
        val eligibleServers = contentServers
            .asSequence()
            .filter { it.allowedAppIds.isEmpty() || appId in it.allowedAppIds }
            .filter { it.type == "SteamCache" || it.type == "CDN" }
            .sortedWith(
                if (preferSteamChinaServers) {
                    compareByDescending<CdnServer> { it.steamChinaOnly }
                        .thenBy(CdnServer::weightedLoad)
                } else {
                    compareBy(CdnServer::weightedLoad)
                },
            )
            .toList()
        return SteamCdnServerPool(
            proxyServer = proxyServer,
            downloadServers = spreadByClientListWeight(eligibleServers),
        )
    }

    /**
     * Expands client-list weights round-robin instead of in consecutive runs.
     *
     * Concurrent chunk workers all start from the head of this pool, so
     * consecutive duplicates made every worker hammer the same edge and made a
     * single dead host absorb as many retries as its weight.
     */
    private fun spreadByClientListWeight(servers: List<CdnServer>): List<CdnServer> {
        if (servers.isEmpty()) {
            return emptyList()
        }
        val remaining = servers.map { it.numEntriesInClientList.coerceAtLeast(0) }.toIntArray()
        val total = remaining.sum()
        if (total <= 0) {
            return servers
        }
        val ordered = ArrayList<CdnServer>(total)
        while (ordered.size < total) {
            servers.forEachIndexed { index, server ->
                if (remaining[index] > 0) {
                    ordered += server
                    remaining[index]--
                }
            }
        }
        return ordered
    }

    suspend fun requestBytes(
        server: CdnServer,
        path: String,
        query: String?,
        proxyServer: CdnServer?,
        resolveAuthToken: (suspend (String) -> String)? = null,
    ): ByteArray {
        var lastError: Throwable? = null
        for (endpoint in server.requestEndpoints()) {
            try {
                currentCoroutineContext().ensureActive()
                return requestBytesFromEndpoint(
                    server = server,
                    endpoint = endpoint,
                    path = path,
                    query = query,
                    proxyServer = proxyServer,
                    resolveAuthToken = resolveAuthToken,
                )
            } catch (error: Throwable) {
                if (error is CancellationException || error is InterruptedException) throw error
                lastError = error
            }
        }
        val detail = lastError?.message?.takeIf(String::isNotBlank)
        throw WorkshopDownloadException(
            detail?.let { "Steam CDN request exhausted retries: $it" } ?: "Steam CDN request exhausted retries",
            lastError,
        )
    }

    internal fun buildRequestUrl(
        server: CdnServer,
        endpoint: CdnRequestEndpoint,
        path: String,
        query: String?,
        proxyServer: CdnServer?,
    ): HttpUrl {
        val normalizedQuery = query
            ?.trim()
            ?.removePrefix("?")
            ?.takeIf(String::isNotBlank)
        val originPath = "/${path.trimStart('/')}"
        val originHost = server.vHost
        val targetEndpoint = if (proxyServer != null && proxyServer.useAsProxy && !proxyServer.proxyRequestPathTemplate.isNullOrBlank()) {
            proxyServer.requestEndpoints().first()
        } else {
            endpoint
        }
        val targetHost = if (targetEndpoint == endpoint) {
            server.vHost
        } else {
            proxyServer!!.vHost
        }
        val targetPath = if (targetEndpoint == endpoint) {
            originPath
        } else {
            proxyServer!!.proxyRequestPathTemplate!!
                .replace("%host%", originHost)
                .replace("%path%", originPath)
                .let { rewritten ->
                    if (rewritten.startsWith("/")) {
                        rewritten
                    } else {
                        "/$rewritten"
                    }
                }
        }

        return HttpUrl.Builder()
            .scheme(targetEndpoint.scheme)
            .host(targetHost)
            .port(targetEndpoint.port)
            .encodedPath(targetPath)
            .apply {
                if (!normalizedQuery.isNullOrBlank()) {
                    encodedQuery(normalizedQuery)
                }
            }
            .build()
    }

    private suspend fun requestBytesFromEndpoint(
        server: CdnServer,
        endpoint: CdnRequestEndpoint,
        path: String,
        query: String?,
        proxyServer: CdnServer?,
        resolveAuthToken: (suspend (String) -> String)?,
    ): ByteArray {
        var currentQuery = query
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url(buildRequestUrl(server, endpoint, path, currentQuery, proxyServer))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> return response.body?.bytes() ?: ByteArray(0)
                    response.code == 403 && attempt == 0 && resolveAuthToken != null -> {
                        currentQuery = resolveAuthToken(server.host)
                    }

                    else -> throw WorkshopDownloadException("Steam CDN request failed: ${response.code}")
                }
            }
        }
        throw WorkshopDownloadException("Steam CDN request exhausted retries")
    }
}
