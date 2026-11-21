package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.RouteDiscoveryContext
import io.stamethyst.backend.github.WattToolkitGithubRoute
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** The ordinary, non-Beta Steam Community hop observed serving complete Workshop topics. */
internal class WorkshopTopicIpv4Route(
    private val dnsClient: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS)
        .build(),
    private val dnsUrl: HttpUrl = "https://doh.pub/resolve".toHttpUrl(),
) {
    fun resolve(request: Request): WattToolkitGithubRoute? {
        val url = request.url
        if (request.method != "GET" || !url.isHttps || url.port != 443 || url.host != "steamcommunity.com" ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || !topicPath.matches(url.encodedPath)
        ) return null

        val query = Request.Builder().url(dnsUrl.newBuilder()
            .addQueryParameter("name", "steamcommunity.rmbgame.net")
            .addQueryParameter("type", "A")
            .build())
            .header("Accept", "application/dns-json")
            .build()
        val addresses = RouteDiscoveryContext.request(dnsClient, query) { response ->
            if (!response.isSuccessful) emptyList() else {
                val body = response.peekBody(MAX_DNS_BYTES + 1)
                if (body.contentLength() > MAX_DNS_BYTES) emptyList() else parseIpv4Answers(body.string())
            }
        }
        if (addresses.isEmpty()) return null
        return WattToolkitGithubRoute(
            logicalHosts = setOf(url.host),
            forwardTargets = SteamCommunityWattToolkitRouteProfile.bootstrapForwardTargets,
            fakeServerName = SteamCommunityWattToolkitRouteProfile.bootstrapFakeServerName,
            resolvedForwardAddresses = addresses,
        )
    }

    private fun parseIpv4Answers(payload: String): List<InetAddress> {
        val json = try { JSONObject(payload) } catch (_: JSONException) { return emptyList() }
        if (json.optInt("Status", -1) != 0 || json.optBoolean("TC")) return emptyList()
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        return (0 until answers.length()).asSequence()
            .mapNotNull(answers::optJSONObject)
            .filter { it.optInt("type") == 1 }
            .mapNotNull { publicIpv4(it.optString("data")) }
            .distinct().take(4).toList()
    }

    private fun publicIpv4(value: String): InetAddress? {
        if (!ipv4.matches(value)) return null
        val octets = value.split('.').map { it.toInt() }
        if (octets.any { it !in 0..255 } || octets[0] == 0 || octets[0] >= 224 ||
            (octets[0] == 100 && octets[1] in 64..127)
        ) return null
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()).takeUnless {
            it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress
        }
    }

    private companion object {
        val topicPath = Regex("^/(?:workshop|sharedfiles)/filedetails/discussion/[1-9][0-9]{0,19}/[1-9][0-9]{0,19}/?$")
        val ipv4 = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
        const val MAX_DNS_BYTES = 16_384L
    }
}
