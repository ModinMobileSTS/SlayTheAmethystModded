package io.stamethyst.backend.github

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.AsyncTimeout
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import top.apricityx.workshop.workshop.SteamCdnRequestControl

/** A POST query can opt into replay. Mutations and one-shot bodies are never replayed by default. */
internal enum class HttpRetrySafety { READ_ONLY, NEVER }

internal fun Request.canReplayHttpRequest(): Boolean =
    body?.isOneShot() != true && body?.isDuplex() != true &&
        tag(HttpRetrySafety::class.java) != HttpRetrySafety.NEVER &&
        (method in setOf("GET", "HEAD", "OPTIONS") || tag(HttpRetrySafety::class.java) == HttpRetrySafety.READ_ONLY)

/**
 * Explicit logical HTTP transport. Routing is not an application interceptor: every hop belongs to
 * the same Call, deadline and cancellation scope. Cookies and redirects use the logical origin.
 * [configure] changes transport options; request preparation belongs in [prepareRequest], not in
 * an interceptor which may see a proxy's TLS hostname instead of the application's URL.
 */
class RoutedHttpTransport internal constructor(
    internal val client: OkHttpClient,
    private val runtime: ExperimentalGithubDirectAccessRuntime,
    private val enabledProvider: () -> Boolean = { true },
    private val allowInsecureUrl: (HttpUrl) -> Boolean = { false },
    private val prepareRequest: (Request) -> Request = { it },
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet(),
) : Call.Factory {
    private val routeClients = object : LinkedHashMap<WattToolkitGithubRoute, OkHttpClient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WattToolkitGithubRoute, OkHttpClient>): Boolean {
            if (size <= 32) return false
            eldest.value.connectionPool.evictAll()
            return true
        }
    }
    private val officialClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
    }

    override fun newCall(request: Request): Call = RoutedCall(request)

    internal fun configure(block: OkHttpClient.Builder.() -> Unit): RoutedHttpTransport =
        RoutedHttpTransport(
            client = client.newBuilder().apply(block).build(),
            runtime = runtime,
            enabledProvider = enabledProvider,
            allowInsecureUrl = allowInsecureUrl,
            prepareRequest = prepareRequest,
            activeCalls = activeCalls,
        )

    internal fun preparing(block: (Request) -> Request): RoutedHttpTransport =
        RoutedHttpTransport(
            client = client,
            runtime = runtime,
            enabledProvider = enabledProvider,
            allowInsecureUrl = allowInsecureUrl,
            prepareRequest = { block(prepareRequest(it)) },
            activeCalls = activeCalls,
        )

    fun cancelAll() = activeCalls.toList().forEach(Call::cancel)

    private fun candidateClient(route: WattToolkitGithubRoute?, logicalHost: String): OkHttpClient {
        if (route == null || route.isOfficial) return officialClient
        // A wildcard rule with {origin} SNI must also bind the actual sibling (e.g. avatars),
        // not just the exact hosts enumerated when the rule was discovered.
        val scopedRoute = if (route.usesOriginFakeServerName()) {
            route.copy(logicalHosts = route.logicalHosts + logicalHost)
        } else route
        return synchronized(routeClients) {
            routeClients.getOrPut(scopedRoute) { buildRouteClient(scopedRoute) }
        }
    }

    private fun buildRouteClient(route: WattToolkitGithubRoute): OkHttpClient {
        // A frozen DNS table and a separate pool prevent equal fake SNI names on different
        // nodes (Steam store/community in particular) from sharing addresses or connections.
        val baseDns = runtime.directHttpClient.dns.let { if (it is WattToolkitForwardDns) it.delegate else it }
        val dns = WattToolkitForwardDns(baseDns).apply { register(route) }
        return runtime.directHttpClient.newBuilder()
            .connectTimeout(client.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(client.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(dns)
            .connectionPool(ConnectionPool())
            .hostnameVerifier(
                GithubDirectHostnameVerifier(
                    defaultVerifier = runtime.directHttpClient.hostnameVerifier,
                    unsafeHostBypassProvider = route::shouldBypassHostnameVerification,
                ),
            )
            .apply {
                if (route.ignoreSslCertVerification) {
                    trustWattToolkitForwardCertificates(route::shouldBypassHostnameVerification)
                }
            }
            .retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .build()
    }

    private inner class RoutedCall(private val original: Request) : Call {
        private val executed = AtomicBoolean()
        private val finished = AtomicBoolean()
        private val tags = ConcurrentHashMap<Class<*>, Any>()
        @Volatile private var cancelled = false
        @Volatile private var timedOut = false
        private val lock = Any()
        private var networkCall: Call? = null
        private var discovery: Future<*>? = null
        private val discoveryScope = RouteDiscoveryScope()
        private val callTimeout = object : AsyncTimeout() {
            override fun timedOut() {
                timedOut = true
                this@RoutedCall.cancel()
            }
        }.apply { timeout(client.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS) }

        override fun request(): Request = original
        override fun timeout(): Timeout = callTimeout
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = cancelled
        override fun clone(): Call = newCall(original)
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = synchronized(tags) {
            type.cast(tags.getOrPut(type) { computeIfAbsent() })
        }

        override fun cancel() {
            val active = synchronized(lock) {
                cancelled = true
                networkCall to discovery
            }
            active.first?.cancel()
            active.second?.cancel(true)
            discoveryScope.cancel()
        }

        private fun checkActive() {
            if (cancelled || Thread.currentThread().isInterrupted) throw failure()
            original.tag(HttpRequestControl::class.java)?.checkActive()
            original.tag(SteamCdnRequestControl::class.java)?.checkActive()
        }

        private fun failure(cause: IOException? = null): IOException =
            if (timedOut) InterruptedIOException("HTTP request timeout").apply { initCause(cause) }
            else if (cancelled) IOException("HTTP request cancelled", cause)
            else cause ?: InterruptedIOException("HTTP request interrupted")

        private fun finish() {
            if (finished.compareAndSet(false, true)) {
                callTimeout.exit()
                activeCalls.remove(this)
                synchronized(lock) { networkCall = null; discovery = null }
                discoveryScope.cancel()
            }
        }

        private fun start() {
            check(executed.compareAndSet(false, true)) { "Already Executed" }
            activeCalls += this
            callTimeout.enter()
        }

        override fun execute(): Response {
            start()
            return executeStarted()
        }

        override fun enqueue(responseCallback: Callback) {
            start()
            asyncExecutor.execute {
                val response = try { executeStarted() } catch (error: IOException) {
                    responseCallback.onFailure(this, error)
                    return@execute
                }
                if (cancelled) {
                    response.close()
                    responseCallback.onFailure(this, failure())
                } else {
                    // A callback exception is the caller's failure, not a second network result.
                    responseCallback.onResponse(this, response)
                }
            }
        }

        private fun <T> discover(block: () -> T): T {
            checkActive()
            val task = discoveryExecutor.submit(Callable { RouteDiscoveryContext.run(discoveryScope, block) })
            synchronized(lock) {
                discovery = task
                if (cancelled) task.cancel(true)
            }
            return try { task.get() } catch (error: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                throw failure()
            } catch (error: java.util.concurrent.CancellationException) {
                throw failure()
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is IOException) throw cause
                throw IOException("Acceleration route discovery failed", cause)
            } finally {
                synchronized(lock) { if (discovery === task) discovery = null }
                checkActive()
            }
        }

        private fun executeStarted(): Response = try {
            checkActive()
            val prepared = prepareRequest(original)
            val executor = WattRouteExecutor(
                routeResolvers = if (enabledProvider()) runtime.resolvers else emptyList(),
                cookieJar = client.cookieJar,
                requireHttps = runtime.requireHttps,
                allowInsecureUrl = allowInsecureUrl,
                followRedirects = client.followRedirects,
                checkActive = ::checkActive,
                resolve = { resolver, host, failed -> discover {
                    if (failed == null) resolver.resolveRouteForHost(host)
                    else resolver.refreshRouteForHost(host, failed)
                } },
                executeCandidate = { logical, network, route ->
                    checkActive()
                    val call = candidateClient(route, logical.url.host).newCall(network)
                    original.tag(HttpRequestControl::class.java)?.register(call)
                    original.tag(SteamCdnRequestControl::class.java)?.register(call)
                    synchronized(lock) {
                        networkCall = call
                        if (cancelled) call.cancel()
                    }
                    checkActive()
                    call.execute().newBuilder().request(logical).build()
                },
            )
            val response = executor.execute(prepared)
            if (cancelled) {
                response.close()
                throw failure()
            }
            val body = response.body
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    checkActive()
                    super.read(sink, byteCount).also { if (it == -1L) finish() }
                } catch (error: IOException) {
                    finish()
                    throw failure(error)
                }

                override fun close() {
                    try { super.close() } finally { finish() }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        } catch (error: IOException) {
            finish()
            throw failure(error)
        } catch (error: Throwable) {
            finish()
            throw error
        }
    }

    private companion object {
        val asyncExecutor = Executors.newFixedThreadPool(8) { runnable ->
            Thread(runnable, "routed-http").apply { isDaemon = true }
        }
        val discoveryExecutor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "http-route-discovery").apply { isDaemon = true }
        }
    }
}

internal fun Call.Factory.withHttpOptions(block: OkHttpClient.Builder.() -> Unit): Call.Factory = when (this) {
    is RoutedHttpTransport -> configure(block)
    is OkHttpClient -> newBuilder().apply(block).build()
    else -> error("HTTP options require an OkHttp or routed transport")
}

internal fun Call.Factory.cancelAllHttpCalls() = when (this) {
    is RoutedHttpTransport -> cancelAll()
    is OkHttpClient -> dispatcher.cancelAll()
    else -> Unit
}

internal fun Call.Factory.preparingHttpRequests(block: (Request) -> Request): Call.Factory = when (this) {
    is RoutedHttpTransport -> preparing(block)
    is OkHttpClient -> newBuilder().addInterceptor { chain -> chain.proceed(block(chain.request())) }.build()
    else -> error("Request preparation requires an OkHttp or routed transport")
}

/** WebSockets use their own explicit factory; HTTP routing must not intercept the upgrade. */
internal fun Call.Factory.webSocketClient(): OkHttpClient = when (this) {
    is RoutedHttpTransport -> client
    is OkHttpClient -> this
    else -> error("A WebSocket client is required")
}
