package top.apricityx.workshop.workshop

import top.apricityx.workshop.steam.protocol.CdnRequestEndpoint
import top.apricityx.workshop.steam.protocol.CdnServer
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
    fun buildServerPool(
        appId: UInt,
        contentServers: List<CdnServer>,
    ): SteamCdnServerPool {
        val proxyServer = contentServers.firstOrNull(CdnServer::useAsProxy)
        val downloadServers = buildList {
            contentServers
                .asSequence()
                .filter { it.allowedAppIds.isEmpty() || appId in it.allowedAppIds }
                .filter { it.type == "SteamCache" || it.type == "CDN" }
                .sortedBy(CdnServer::weightedLoad)
                .forEach { server ->
                    repeat(server.numEntriesInClientList.coerceAtLeast(0)) {
                        add(server)
                    }
                }
        }
        return SteamCdnServerPool(
            proxyServer = proxyServer,
            downloadServers = downloadServers,
        )
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
