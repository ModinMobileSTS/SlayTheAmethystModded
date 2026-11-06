package io.stamethyst.backend.steamcloud

import io.stamethyst.backend.github.HttpRetrySafety
import okhttp3.Request

/** Covers queries built in workshop-core, which does not depend on app transport policies. */
internal fun prepareSteamHttpRequest(request: Request): Request {
    if (request.tag(HttpRetrySafety::class.java) != null) return request
    val isPublishedFileQuery = request.method == "POST" && request.url.isHttps &&
        request.url.host == "api.steampowered.com" &&
        request.url.encodedPath.trimEnd('/') == "/ISteamRemoteStorage/GetPublishedFileDetails/v1"
    return if (isPublishedFileQuery) request.newBuilder()
        .tag(HttpRetrySafety::class.java, HttpRetrySafety.READ_ONLY).build() else request
}
