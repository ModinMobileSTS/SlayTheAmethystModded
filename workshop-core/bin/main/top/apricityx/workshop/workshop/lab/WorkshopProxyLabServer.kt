package top.apricityx.workshop.workshop.lab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    val config = ProxyLabConfig.fromArgs(args)
    val startedAt = Instant.now().toString()
    val server = HttpServer.create(InetSocketAddress(config.bindHost, config.port), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/") { exchange -> handleIndex(exchange, config, startedAt) }
    server.createContext("/health") { exchange -> handleHealth(exchange, config, startedAt) }
    server.createContext("/lab/workshop/browse") { exchange -> handleBrowse(exchange, config) }
    server.createContext("/lab/workshop/search-summary") { exchange -> handleSearchSummary(exchange, config) }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(0)
        },
    )

    server.start()
    println("Workshop proxy lab listening on http://${config.bindHost}:${config.port}")
    println("Health: http://${config.bindHost}:${config.port}/health")
    println("Upstream proxy: ${config.redactedUpstreamProxy ?: "<none>"}")
    println(
        "Search summary: http://${config.bindHost}:${config.port}/lab/workshop/search-summary" +
            "?strategy=${config.defaultStrategy.id}&appid=646570&searchtext=basemod",
    )
    CountDownLatch(1).await()
}

private fun handleIndex(
    exchange: HttpExchange,
    config: ProxyLabConfig,
    startedAt: String,
) {
    if (exchange.requestMethod != "GET") {
        sendPlainText(exchange, 405, "Method Not Allowed")
        return
    }

    val browseUrl =
        "http://${config.bindHost}:${config.port}/lab/workshop/browse" +
            "?strategy=${config.defaultStrategy.id}&appid=646570&searchtext=basemod"
    val summaryUrl =
        "http://${config.bindHost}:${config.port}/lab/workshop/search-summary" +
            "?strategy=${config.defaultStrategy.id}&appid=646570&searchtext=basemod"
    val body =
        """
        <html>
        <head><meta charset="utf-8"><title>Workshop Proxy Lab</title></head>
        <body>
            <h1>Workshop Proxy Lab</h1>
            <p>Started: $startedAt</p>
            <p>Default strategy: ${config.defaultStrategy.id}</p>
            <p>Upstream proxy: ${config.redactedUpstreamProxy ?: "&lt;none&gt;"}</p>
            <ul>
                <li><a href="/health">/health</a></li>
                <li><a href="$summaryUrl">$summaryUrl</a></li>
                <li><a href="$browseUrl">$browseUrl</a></li>
            </ul>
        </body>
        </html>
        """.trimIndent()
    sendBytes(exchange, 200, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))
}

private fun handleHealth(
    exchange: HttpExchange,
    config: ProxyLabConfig,
    startedAt: String,
) {
    if (exchange.requestMethod != "GET") {
        sendPlainText(exchange, 405, "Method Not Allowed")
        return
    }

    val body = json.encodeToString(
        HealthResponse(
            status = "ok",
            startedAt = startedAt,
            bindHost = config.bindHost,
            port = config.port,
            defaultStrategy = config.defaultStrategy.id,
            upstreamProxy = config.redactedUpstreamProxy,
        ),
    )
    sendJson(exchange, 200, body)
}

private fun handleBrowse(
    exchange: HttpExchange,
    config: ProxyLabConfig,
) {
    if (exchange.requestMethod != "GET") {
        sendPlainText(exchange, 405, "Method Not Allowed")
        return
    }

    val request = buildWorkshopRequest(exchange, config)
    val result = runCatching {
        fetchWorkshopBrowsePage(request)
    }.fold(
        onSuccess = { it },
        onFailure = { throwable ->
            val body = json.encodeToString(
                ErrorResponse(
                    strategy = request.strategy.id,
                    upstreamUrl = request.upstreamUrl,
                    upstreamProxy = request.redactedUpstreamProxy,
                    error = throwable.message ?: throwable::class.java.simpleName,
                    attemptLog = if (throwable is FetchFailedException) throwable.attemptLog else emptyList(),
                ),
            )
            sendJson(exchange, 502, body)
            return
        },
    )

    exchange.responseHeaders.add("X-Workshop-Proxy-Strategy", request.strategy.id)
    exchange.responseHeaders.add("X-Workshop-Proxy-Upstream", request.upstreamUrl)
    if (result.attemptLog.isNotEmpty()) {
        exchange.responseHeaders.add("X-Workshop-Proxy-Attempts", result.attemptLog.size.toString())
    }
    sendBytes(exchange, 200, "text/html; charset=utf-8", result.body.toByteArray(Charsets.UTF_8))
}

private fun handleSearchSummary(
    exchange: HttpExchange,
    config: ProxyLabConfig,
) {
    if (exchange.requestMethod != "GET") {
        sendPlainText(exchange, 405, "Method Not Allowed")
        return
    }

    val request = buildWorkshopRequest(exchange, config)
    val result = runCatching {
        fetchWorkshopBrowsePage(request)
    }.fold(
        onSuccess = { it },
        onFailure = { throwable ->
            val body = json.encodeToString(
                ErrorResponse(
                    strategy = request.strategy.id,
                    upstreamUrl = request.upstreamUrl,
                    upstreamProxy = request.redactedUpstreamProxy,
                    error = throwable.message ?: throwable::class.java.simpleName,
                    attemptLog = if (throwable is FetchFailedException) throwable.attemptLog else emptyList(),
                ),
            )
            sendJson(exchange, 502, body)
            return
        },
    )

    val items = parseHoverItems(result.body)
    val query = request.parameters["searchtext"].orEmpty()
    val matches = items.filter { item ->
        item.title.contains(query, ignoreCase = true) ||
            item.description.contains(query, ignoreCase = true)
    }
    val body = json.encodeToString(
        SearchSummaryResponse(
            strategy = request.strategy.id,
            upstreamUrl = request.upstreamUrl,
            upstreamProxy = request.redactedUpstreamProxy,
            totalItems = items.size,
            matchedItems = matches.size,
            attemptLog = result.attemptLog,
            items = items.take(10).map { item ->
                SearchSummaryItem(
                    publishedFileId = item.publishedFileId.toString(),
                    appId = item.appId.toString(),
                    title = item.title,
                    description = item.description.take(180),
                )
            },
        ),
    )
    sendJson(exchange, 200, body)
}

private fun buildWorkshopRequest(
    exchange: HttpExchange,
    config: ProxyLabConfig,
): WorkshopBrowseRequest {
    val parameters = parseQuery(exchange.requestURI.rawQuery)
    val strategy = FetchStrategyId.from(parameters["strategy"]) ?: config.defaultStrategy
    val upstreamProxy = parameters["upstream_proxy"] ?: parameters["upstreamProxy"] ?: config.upstreamProxy
    val urlBuilder = STEAM_COMMUNITY_BASE_URL.newBuilder()
        .addPathSegments("workshop/browse/")

    val merged = linkedMapOf(
        "appid" to "646570",
        "searchtext" to "basemod",
        "childpublishedfileid" to "0",
        "l" to "english",
        "browsesort" to "trend",
        "section" to "readytouseitems",
        "actualsort" to "trend",
        "days" to "3650",
        "p" to "1",
        "numperpage" to "30",
    )
    parameters.forEach { (key, value) ->
        if (key != "strategy" && key != "upstream_proxy" && key != "upstreamProxy") {
            merged[key] = value
        }
    }
    merged.forEach { (key, value) ->
        urlBuilder.addQueryParameter(key, value)
    }

    return WorkshopBrowseRequest(
        strategy = strategy,
        upstreamUrl = urlBuilder.build().toString(),
        parameters = merged,
        upstreamProxy = upstreamProxy?.takeIf { it.isNotBlank() },
    )
}

private fun fetchWorkshopBrowsePage(request: WorkshopBrowseRequest): FetchResult =
    when (request.strategy) {
        FetchStrategyId.BarePwsh -> fetchWithPowerShell(request)
        FetchStrategyId.SteamppLike -> fetchWithSteamppLikeStrategy(request)
        FetchStrategyId.WattDefault -> fetchWithWattDefaultStrategy(request)
    }

private fun fetchWithPowerShell(request: WorkshopBrowseRequest): FetchResult {
    if (request.upstreamProxy != null) {
        val probe = runProcess(
            buildCurlCommand(
                upstreamUrl = request.upstreamUrl,
                upstreamProxy = request.upstreamProxy,
            ),
            timeoutSeconds = 45,
        )
        if (probe.exitCode != 0) {
            throw FetchFailedException(
                "Bare fetch via upstream proxy failed with exit code ${probe.exitCode}.",
                listOf(probe.output.trim()),
            )
        }
        return FetchResult(
            body = probe.output,
            attemptLog = listOf("curl direct via proxy ${request.redactedUpstreamProxy} exit=0"),
        )
    }

    val script =
        """
        ${'$'}ProgressPreference='SilentlyContinue'
        (Invoke-WebRequest -NoProxy -Uri '${toPowerShellSingleQuotedString(request.upstreamUrl)}' -Headers @{ 'User-Agent'='${toPowerShellSingleQuotedString(USER_AGENT)}' }).Content
        """.trimIndent()
    val probe = runProcess(
        listOf("pwsh", "-NoProfile", "-Command", script),
        timeoutSeconds = 45,
    )
    if (probe.exitCode != 0) {
        throw FetchFailedException(
            "Bare PowerShell fetch failed with exit code ${probe.exitCode}.",
            listOf(probe.output.trim()),
        )
    }
    return FetchResult(
        body = probe.output,
        attemptLog = listOf("pwsh direct exit=0"),
    )
}

private fun fetchWithSteamppLikeStrategy(request: WorkshopBrowseRequest): FetchResult {
    val attemptLog = mutableListOf<String>()
    val candidateIps = linkedSetOf<String>()

    DOH_RESOLVERS.forEach { resolver ->
        val resolvedIps = resolveIpv4CandidatesWithDoh(resolver)
        attemptLog += "${resolver.name} resolved ${resolvedIps.ifEmpty { listOf("<empty>") }.joinToString()}"
        candidateIps += resolvedIps
    }

    if (candidateIps.isEmpty()) {
        throw FetchFailedException(
            "Steam++-like strategy did not resolve any candidate IPs.",
            attemptLog,
        )
    }

    candidateIps.forEach { ipAddress ->
        val probe = runProcess(
            buildCurlCommand(
                upstreamUrl = request.upstreamUrl,
                upstreamProxy = request.upstreamProxy,
                resolvedIp = ipAddress,
            ),
            timeoutSeconds = 30,
        )
        if (probe.exitCode == 0 && probe.output.contains("""class="workshopItem"""")) {
            return FetchResult(
                body = probe.output,
                attemptLog = attemptLog + "curl via $ipAddress proxy=${request.redactedUpstreamProxy ?: "<none>"} exit=0",
            )
        }
        val outputSnippet = probe.output.trim().lineSequence().take(8).joinToString(" | ")
        attemptLog += "curl via $ipAddress proxy=${request.redactedUpstreamProxy ?: "<none>"} exit=${probe.exitCode} output=$outputSnippet"
    }

    throw FetchFailedException(
        "Steam++-like strategy failed after trying ${candidateIps.size} candidate IPs.",
        attemptLog,
    )
}

private fun fetchWithWattDefaultStrategy(request: WorkshopBrowseRequest): FetchResult {
    val rule = fetchCurrentWattSteamCommunityRule()
    if (rule.proxyType != 0) {
        throw FetchFailedException(
            "Unexpected Watt Toolkit rule type for $STEAM_COMMUNITY_HOST: ${rule.proxyType}.",
            listOf("Watt rule forward=${rule.forwardDomainNames} proxyType=${rule.proxyType}"),
        )
    }

    val forwardedUrl = buildForwardedUrl(request.upstreamUrl, rule.forwardDomainNames)
    val probe = runProcess(
        buildCurlCommand(
            upstreamUrl = forwardedUrl,
            upstreamProxy = request.upstreamProxy,
            hostHeader = STEAM_COMMUNITY_HOST,
            insecure = rule.ignoreSslCertVerification,
        ),
        timeoutSeconds = 30,
    )
    val attemptLog = listOf(
        "Watt API forward=${rule.forwardDomainNames} proxyType=${rule.proxyType} ignoreCert=${rule.ignoreSslCertVerification}",
        "curl via watt-default target=${forwardedUrl.substringBefore('?')} proxy=${request.redactedUpstreamProxy ?: "<none>"} exit=${probe.exitCode}",
    )
    if (probe.exitCode == 0 && probe.output.contains("""class="workshopItem"""")) {
        return FetchResult(body = probe.output, attemptLog = attemptLog)
    }

    val outputSnippet = probe.output.trim().lineSequence().take(8).joinToString(" | ")
    throw FetchFailedException(
        "Watt Toolkit default strategy failed against ${rule.forwardDomainNames}.",
        attemptLog + "output=$outputSnippet",
    )
}

private fun fetchCurrentWattSteamCommunityRule(): WattAccelerateProject {
    val probe = runProcess(
        listOf(
            "curl.exe",
            "-fsSL",
            "--proxy",
            "",
            "--connect-timeout",
            CURL_CONNECT_TIMEOUT_SECONDS.toString(),
            "-X",
            "POST",
            WATT_ACCELERATOR_PROJECTGROUPS_URL,
            "-H",
            "Content-Type: application/json",
            "-d",
            "{}",
        ),
        timeoutSeconds = 30,
    )
    if (probe.exitCode != 0) {
        throw FetchFailedException(
            "Failed to fetch Watt Toolkit accelerator rules.",
            listOf(probe.output.trim()),
        )
    }

    val response = runCatching {
        json.decodeFromString<WattAccelerateResponse>(probe.output)
    }.getOrElse { throwable ->
        throw FetchFailedException(
            "Failed to decode Watt Toolkit accelerator rules.",
            listOf(throwable.message ?: throwable::class.java.simpleName, probe.output.take(400)),
        )
    }

    return response.groups.asSequence()
        .flatMap { flattenProjects(it.items).asSequence() }
        .firstOrNull { project ->
            project.matchDomainNames
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet() == setOf(STEAM_COMMUNITY_HOST, "www.$STEAM_COMMUNITY_HOST")
        }
        ?: throw FetchFailedException(
            "Could not locate Watt Toolkit Steam community rule.",
            listOf("Received ${response.groups.size} accelerator groups from Watt API."),
        )
}

private fun flattenProjects(items: List<WattAccelerateProject>): List<WattAccelerateProject> =
    buildList {
        items.forEach { item ->
            add(item)
            addAll(flattenProjects(item.items))
        }
    }

private fun buildForwardedUrl(
    upstreamUrl: String,
    forwardDomainNames: String,
): String {
    val original = upstreamUrl.toHttpUrl()
    val trimmedForward = forwardDomainNames.trim()
    return if (trimmedForward.contains("://")) {
        val forwarded = trimmedForward.toHttpUrl()
        forwarded.newBuilder()
            .encodedPath(original.encodedPath)
            .encodedQuery(original.encodedQuery)
            .build()
            .toString()
    } else {
        original.newBuilder()
            .host(trimmedForward)
            .build()
            .toString()
    }
}

private fun resolveIpv4CandidatesWithDoh(resolver: DohResolver): List<String> {
    val script =
        """
        ${'$'}ProgressPreference='SilentlyContinue'
        ${'$'}result = Invoke-RestMethod -Uri '${toPowerShellSingleQuotedString(resolver.resolveUrl)}?name=steamcommunity.com&type=A'
        if (${ '$' }result.Answer) {
            ${ '$' }result.Answer | ForEach-Object { if (${ '$' }_.data) { ${ '$' }_.data } }
        }
        """.trimIndent()
    val probe = runProcess(
        listOf("pwsh", "-NoProfile", "-Command", script),
        timeoutSeconds = 30,
    )
    if (probe.exitCode != 0) {
        return emptyList()
    }
    return probe.output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && IPV4_REGEX.matches(it) }
        .distinct()
        .toList()
}

private fun buildCurlCommand(
    upstreamUrl: String,
    upstreamProxy: String?,
    resolvedIp: String? = null,
    hostHeader: String? = null,
    insecure: Boolean = false,
): List<String> {
    val command = mutableListOf(
        "curl.exe",
        "-fsSL",
        "--connect-timeout",
        CURL_CONNECT_TIMEOUT_SECONDS.toString(),
        "-A",
        USER_AGENT,
    )
    command += listOf("--proxy", upstreamProxy ?: "")
    if (!resolvedIp.isNullOrBlank()) {
        command += listOf("--resolve", "steamcommunity.com:443:$resolvedIp")
    }
    if (insecure) {
        command += "-k"
    }
    if (!hostHeader.isNullOrBlank()) {
        command += listOf("-H", "Host: $hostHeader")
    }
    command += upstreamUrl
    return command
}

private fun runProcess(
    command: List<String>,
    timeoutSeconds: Long,
): ProcessResult {
    val processBuilder = ProcessBuilder(command)
        .redirectErrorStream(true)
    PROXY_ENVIRONMENT_KEYS.forEach { proxyKey ->
        processBuilder.environment().remove(proxyKey)
    }
    val process = processBuilder.start()

    val output = StringBuilder()
    val readerThread = thread(name = "workshop-proxy-lab-process-reader") {
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                output.appendLine(line)
            }
        }
    }

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        readerThread.join(OUTPUT_DRAIN_TIMEOUT_MILLIS)
        return ProcessResult(exitCode = -1, output = "timed out after ${timeoutSeconds}s")
    }

    readerThread.join(OUTPUT_DRAIN_TIMEOUT_MILLIS)
    return ProcessResult(exitCode = process.exitValue(), output = output.toString())
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) {
        return emptyMap()
    }
    return rawQuery.split("&")
        .mapNotNull { pair ->
            val separatorIndex = pair.indexOf('=')
            if (separatorIndex < 0) {
                return@mapNotNull null
            }
            val key = decodeUrlComponent(pair.substring(0, separatorIndex))
            val value = decodeUrlComponent(pair.substring(separatorIndex + 1))
            key to value
        }
        .toMap()
}

private fun decodeUrlComponent(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8)

private fun parseHoverItems(payload: String): List<WorkshopHoverItem> =
    HOVER_REGEX.findAll(payload)
        .mapNotNull { match ->
            runCatching {
                val jsonObject = json.parseToJsonElement(match.groupValues[2]).jsonObject
                WorkshopHoverItem(
                    publishedFileId = match.groupValues[1].toULong(),
                    appId = jsonObject.getValue("appid").jsonPrimitive.content.toUInt(),
                    title = jsonObject.getValue("title").jsonPrimitive.content,
                    description = jsonObject.getValue("description").jsonPrimitive.content,
                )
            }.getOrNull()
        }
        .toList()

private fun toPowerShellSingleQuotedString(value: String): String =
    value.replace("'", "''")

private fun sendJson(exchange: HttpExchange, statusCode: Int, body: String) {
    sendBytes(exchange, statusCode, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
}

private fun sendPlainText(exchange: HttpExchange, statusCode: Int, body: String) {
    sendBytes(exchange, statusCode, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))
}

private fun sendBytes(
    exchange: HttpExchange,
    statusCode: Int,
    contentType: String,
    body: ByteArray,
) {
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(statusCode, body.size.toLong())
    exchange.responseBody.use { output ->
        output.write(body)
    }
}

private data class WorkshopBrowseRequest(
    val strategy: FetchStrategyId,
    val upstreamUrl: String,
    val parameters: Map<String, String>,
    val upstreamProxy: String?,
) {
    val redactedUpstreamProxy: String?
        get() = upstreamProxy?.let(::redactProxyUri)
}

private data class FetchResult(
    val body: String,
    val attemptLog: List<String>,
)

private data class ProcessResult(
    val exitCode: Int,
    val output: String,
)

private data class DohResolver(
    val name: String,
    val resolveUrl: String,
)

private data class WorkshopHoverItem(
    val publishedFileId: ULong,
    val appId: UInt,
    val title: String,
    val description: String,
)

private class FetchFailedException(
    message: String,
    val attemptLog: List<String>,
) : IllegalStateException(message)

private data class ProxyLabConfig(
    val bindHost: String,
    val port: Int,
    val defaultStrategy: FetchStrategyId,
    val upstreamProxy: String?,
) {
    val redactedUpstreamProxy: String?
        get() = upstreamProxy?.let(::redactProxyUri)

    companion object {
        fun fromArgs(args: Array<String>): ProxyLabConfig {
            var bindHost = DEFAULT_HOST
            var port = DEFAULT_PORT
            var strategy = DEFAULT_STRATEGY
            var upstreamProxy: String? = null
            var index = 0
            while (index < args.size) {
                when (args[index]) {
                    "--host" -> {
                        bindHost = args.getOrNull(index + 1) ?: error("Missing value for --host")
                        index += 2
                    }

                    "--port" -> {
                        port = args.getOrNull(index + 1)?.toIntOrNull() ?: error("Missing or invalid value for --port")
                        index += 2
                    }

                    "--strategy" -> {
                        strategy = FetchStrategyId.from(args.getOrNull(index + 1)) ?: error("Missing or invalid value for --strategy")
                        index += 2
                    }

                    "--upstream-proxy" -> {
                        upstreamProxy = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                            ?: error("Missing value for --upstream-proxy")
                        index += 2
                    }

                    else -> error("Unknown argument: ${args[index]}")
                }
            }
            return ProxyLabConfig(
                bindHost = bindHost,
                port = port,
                defaultStrategy = strategy,
                upstreamProxy = upstreamProxy,
            )
        }
    }
}

private enum class FetchStrategyId(val id: String) {
    BarePwsh("bare"),
    SteamppLike("steampp-like"),
    WattDefault("watt-default"),
    ;

    companion object {
        fun from(value: String?): FetchStrategyId? =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
    }
}

@Serializable
private data class HealthResponse(
    val status: String,
    val startedAt: String,
    val bindHost: String,
    val port: Int,
    val defaultStrategy: String,
    val upstreamProxy: String?,
)

@Serializable
private data class SearchSummaryResponse(
    val strategy: String,
    val upstreamUrl: String,
    val upstreamProxy: String?,
    val totalItems: Int,
    val matchedItems: Int,
    val attemptLog: List<String>,
    val items: List<SearchSummaryItem>,
)

@Serializable
private data class SearchSummaryItem(
    val publishedFileId: String,
    val appId: String,
    val title: String,
    val description: String,
)

@Serializable
private data class ErrorResponse(
    val strategy: String,
    val upstreamUrl: String,
    val upstreamProxy: String?,
    val error: String,
    val attemptLog: List<String>,
)

private fun redactProxyUri(value: String): String {
    val schemeSeparator = value.indexOf("://")
    if (schemeSeparator < 0) {
        return value
    }
    val schemePrefix = value.substring(0, schemeSeparator + 3)
    val remainder = value.substring(schemeSeparator + 3)
    val atIndex = remainder.indexOf('@')
    return if (atIndex >= 0) {
        schemePrefix + "***@" + remainder.substring(atIndex + 1)
    } else {
        value
    }
}

private const val USER_AGENT = "SlayTheAmethyst/WorkshopProxyLab"
private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 19080
private const val CURL_CONNECT_TIMEOUT_SECONDS = 12L
private const val OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L
private val DEFAULT_STRATEGY = FetchStrategyId.SteamppLike
private const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
private const val WATT_ACCELERATOR_PROJECTGROUPS_URL = "https://api.steampp.net/accelerator/projectgroups"
private val PROXY_ENVIRONMENT_KEYS = listOf(
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "http_proxy",
    "https_proxy",
    "all_proxy",
    "no_proxy",
)
private val STEAM_COMMUNITY_BASE_URL = "https://steamcommunity.com/".toHttpUrl()
private val IPV4_REGEX = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val HOVER_REGEX = Regex(
    """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)
private val DOH_RESOLVERS = listOf(
    DohResolver(name = "DNSPod", resolveUrl = "https://doh.pub/resolve"),
    DohResolver(name = "AliDNS", resolveUrl = "https://dns.alidns.com/resolve"),
)

@Serializable
private data class WattAccelerateResponse(
    @SerialName("🦓")
    val groups: List<WattAccelerateGroup> = emptyList(),
)

@Serializable
private data class WattAccelerateGroup(
    @SerialName("Items")
    val items: List<WattAccelerateProject> = emptyList(),
)

@Serializable
private data class WattAccelerateProject(
    @SerialName("MatchDomainNames")
    val matchDomainNames: String = "",
    @SerialName("ForwardDomainNames")
    val forwardDomainNames: String = "",
    @SerialName("ProxyType")
    val proxyType: Int = -1,
    @SerialName("IgnoreSSLCertVerification")
    val ignoreSslCertVerification: Boolean = false,
    @SerialName("Items")
    val items: List<WattAccelerateProject> = emptyList(),
)
