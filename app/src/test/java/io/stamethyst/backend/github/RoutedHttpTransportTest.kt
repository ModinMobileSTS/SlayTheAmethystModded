package io.stamethyst.backend.github

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

class RoutedHttpTransportTest {
    @Test fun ordinaryCallCancellationStopsForwardWithoutSpecialTagsOrOfficialFallback() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val official = AtomicInteger()
            val transport = transport(forward, official)
            val call = transport.newCall(request())
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> { runCatching { call.execute().close() }.exceptionOrNull() }
                assertNotNull(forward.takeRequest(2, TimeUnit.SECONDS))
                call.cancel()
                assertTrue(result.get(2, TimeUnit.SECONDS) is IOException)
                assertTrue(call.isCanceled())
                assertEquals(0, official.get())
            } finally { call.cancel(); executor.shutdownNow() }
        }
    }

    @Test fun totalTimeoutStopsForwardWithoutSpecialTags() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val official = AtomicInteger()
            val transport = transport(forward, official).configure { callTimeout(200, TimeUnit.MILLISECONDS) }
            val started = System.nanoTime()
            val error = runCatching { transport.newCall(request()).execute().close() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertEquals(0, official.get())
        }
    }

    @Test fun totalTimeoutContinuesThroughResponseBody() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().body("delayed").bodyDelay(30, TimeUnit.SECONDS).build())
            val transport = transport(forward).configure { callTimeout(250, TimeUnit.MILLISECONDS) }
            val error = runCatching { transport.newCall(request()).execute().use { it.body.string() } }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
        }
    }

    @Test fun derivedTransportCancellationCancelsItsActiveBody() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().body("delayed").bodyDelay(30, TimeUnit.SECONDS).build())
            val parent = transport(forward)
            val child = parent.configure { readTimeout(30, TimeUnit.SECONDS) }
            val call = child.newCall(request())
            call.execute().use { response ->
                parent.cancelAll()
                assertTrue(call.isCanceled())
                assertTrue(runCatching { response.body.string() }.exceptionOrNull() is IOException)
            }
        }
    }

    @Test fun configuredReadTimeoutIsUsedByForwardedRequests() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val transport = transport(forward).configure {
                readTimeout(100, TimeUnit.MILLISECONDS)
                callTimeout(1, TimeUnit.SECONDS)
            }
            val start = System.nanoTime()
            assertTrue(runCatching { transport.newCall(request()).execute().close() }.exceptionOrNull() is IOException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        }
    }

    @Test fun routeDiscoveryIsIncludedInTotalDeadline() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val plain = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val resolver = WattToolkitGithubRouteResolver(
            routeStore = object : WattToolkitGithubRouteStore {
                override fun load(): PersistedWattToolkitGithubRoute? {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    return null
                }
                override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                override fun clear() = Unit
            },
            backgroundExecutor = Executor { },
        )
        val runtime = ExperimentalGithubDirectAccessRuntime(listOf(resolver), plain.hostnameVerifier, plain)
        val started = System.nanoTime()
        try {
            val error = runCatching { plain.withWattRouting(runtime).newCall(request()).execute().close() }.exceptionOrNull()
            assertEquals(0L, entered.count)
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        } finally { release.countDown() }
    }

    @Test fun deadlineAlsoCancelsTheForegroundDiscoveryHttpCall() {
        MockWebServer().use { discovery ->
            discovery.start()
            discovery.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val cancelled = CountDownLatch(1)
            val discoveryClient = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val resolver = WattToolkitGithubRouteResolver(
                client = discoveryClient,
                projectGroupsUrl = discovery.url("/routes"),
                bootstrapRouteProvider = { null },
                backgroundExecutor = Executor { },
            )
            val plain = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            val transport = plain.withWattRouting(ExperimentalGithubDirectAccessRuntime(
                listOf(resolver), plain.hostnameVerifier, plain,
            ))
            val error = runCatching { transport.newCall(request()).execute().close() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1, discovery.requestCount)
        }
    }

    @Test fun nodeFallbackSharesTheRemainingTotalBudget() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            second.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())
            val official = AtomicInteger()
            val transport = transport(first, official, listOf(first.url("/").toString(),
                second.url("/").newBuilder().host("127.0.0.1").build().toString())).configure {
                readTimeout(180, TimeUnit.MILLISECONDS)
                callTimeout(300, TimeUnit.MILLISECONDS)
            }
            val started = System.nanoTime()
            val error = runCatching { transport.newCall(request()).execute().close() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message.orEmpty().contains("timeout"))
            assertEquals(1, first.requestCount)
            assertEquals(1, second.requestCount)
            assertEquals(0, official.get())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        } }
    }

    @Test fun requestPreparationAndLogicalCookiesApplyOnAcceleratedAndOfficialPaths() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().body("ok").addHeader("Set-Cookie", "new=value; Path=/").build())
            val saved = mutableListOf<String>()
            val cookie = Cookie.Builder().name("session").value("logical").hostOnlyDomain("api.github.com").build()
            val jar = object : CookieJar {
                override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
                    assertEquals("api.github.com", url.host)
                    return listOf(cookie)
                }
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) { saved += url.host }
            }
            val transport = transport(forward).configure { cookieJar(jar) }.preparing {
                it.newBuilder().header("Accept-Language", "zh-CN").build()
            }
            transport.newCall(request()).execute().use { assertEquals("api.github.com", it.request.url.host) }
            val actual = forward.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("zh-CN", actual.headers["Accept-Language"])
            assertEquals("session=logical", actual.headers["Cookie"])
            assertEquals(listOf("api.github.com"), saved)

            forward.enqueue(MockResponse.Builder().body("official").addHeader("Set-Cookie", "new=value; Path=/").build())
            val plain = OkHttpClient.Builder().cookieJar(jar)
                .dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
            val official = plain.withWattRouting(ExperimentalGithubDirectAccessRuntime(
                emptyList(), plain.hostnameVerifier, plain,
            )).preparing { it.newBuilder().header("Accept-Language", "zh-CN").build() }
            official.newCall(Request.Builder().url(forward.url("/origin").newBuilder().host("api.github.com").build()).build())
                .execute().use { assertEquals("api.github.com", it.request.url.host) }
            val officialRequest = forward.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("zh-CN", officialRequest.headers["Accept-Language"])
            assertEquals("session=logical", officialRequest.headers["Cookie"])
            assertEquals(listOf("api.github.com", "api.github.com"), saved)
        }
    }

    @Test fun mutationsAreNotReplayedAfterRejectedResponseButReadOnlyPostMayFallback() {
        MockWebServer().use { forward ->
            forward.start()
            val official = AtomicInteger()
            val transport = transport(forward, official)
            val validator = AcceleratedResponseValidator { throw IOException("invalid candidate") }
            val post = request().newBuilder().post("query".toRequestBody())
                .tag(AcceleratedResponseValidator::class.java, validator).build()
            forward.enqueue(MockResponse.Builder().body("invalid").build())
            assertTrue(runCatching { transport.newCall(post).execute().close() }.exceptionOrNull() is IOException)
            assertEquals(0, official.get())
            forward.enqueue(MockResponse.Builder().body("invalid").build())
            val query = post.newBuilder().tag(HttpRetrySafety::class.java, HttpRetrySafety.READ_ONLY).build()
            assertTrue(runCatching { transport.newCall(query).execute().close() }.exceptionOrNull() is IOException)
            assertEquals(1, official.get())
        }
    }

    @Test fun readOnlyTagDoesNotAllowOneShotBodyReplay() {
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("one shot") }
        }
        val request = request().newBuilder().post(body).tag(HttpRetrySafety::class.java, HttpRetrySafety.READ_ONLY).build()
        assertFalse(request.canReplayHttpRequest())
    }

    @Test fun disabledAccelerationStillUsesPreparationAndOriginalUrl() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse.Builder().body("official").build())
            val plain = OkHttpClient()
            val runtime = ExperimentalGithubDirectAccessRuntime(emptyList(), plain.hostnameVerifier, plain)
            val transport = plain.withWattRouting(runtime, enabledProvider = { false })
                .preparing { it.newBuilder().header("X-Prepared", "yes").build() }
            transport.newCall(Request.Builder().url(origin.url("/origin")).build()).execute().use {
                assertEquals("official", it.body.string())
            }
            assertEquals("yes", origin.takeRequest(1, TimeUnit.SECONDS)!!.headers["X-Prepared"])
        }
    }

    @Test fun enqueueCallsBackOnceAndCloneHasIndependentLifecycle() {
        MockWebServer().use { forward ->
            forward.start()
            repeat(2) { forward.enqueue(MockResponse.Builder().body("ok").build()) }
            val call = transport(forward).newCall(request())
            val done = CountDownLatch(1)
            val success = AtomicInteger()
            val failure = AtomicInteger()
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure.incrementAndGet(); done.countDown() }
                override fun onResponse(call: Call, response: Response) {
                    response.use { assertEquals("ok", it.body.string()); success.incrementAndGet() }
                    done.countDown()
                }
            })
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, success.get())
            assertEquals(0, failure.get())
            assertTrue(runCatching { call.execute() }.exceptionOrNull() is IllegalStateException)
            val clone = call.clone()
            assertFalse(clone.isExecuted())
            clone.execute().use { assertEquals("ok", it.body.string()) }
        }
    }

    @Test fun equalFakeSniNamesUseIndependentDnsMappingsAndPools() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            first.enqueue(MockResponse.Builder().body("first").build())
            second.enqueue(MockResponse.Builder().body("second").build())
            val lookups = java.util.Collections.synchronizedList(mutableListOf<String>())
            val dns = okhttp3.Dns { host ->
                lookups += host
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
            val routes = listOf(
                WattToolkitGithubRoute(setOf("api.github.com"), listOf("http://first.test:${first.port}"), fakeServerName = "edge.test"),
                WattToolkitGithubRoute(setOf("raw.githubusercontent.com"), listOf("http://second.test:${second.port}"), fakeServerName = "edge.test"),
            )
            val resolvers = routes.mapIndexed { index, route -> WattToolkitGithubRouteResolver(
                routeProfile = WattToolkitRouteProfile("dns-$index", "unused.json", route.logicalHosts, emptyList()),
                routeStore = object : WattToolkitGithubRouteStore {
                    override fun load() = PersistedWattToolkitGithubRoute(route, 1_000L)
                    override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                    override fun clear() = Unit
                }, nowProvider = { 2_000L }, backgroundExecutor = Executor { },
            ) }
            val plain = OkHttpClient.Builder().dns(dns).build()
            val sharedDns = WattToolkitForwardDns(dns).apply { register(routes.last()) }
            val runtime = ExperimentalGithubDirectAccessRuntime(resolvers, plain.hostnameVerifier,
                plain.newBuilder().dns(sharedDns).build(), sharedDns)
            val transport = plain.withWattRouting(runtime)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val a = executor.submit<String> { transport.newCall(request()).execute().use { it.body.string() } }
                val b = executor.submit<String> { transport.newCall(Request.Builder()
                    .url("https://raw.githubusercontent.com/resource").build()).execute().use { it.body.string() } }
                assertEquals("first", a.get(2, TimeUnit.SECONDS))
                assertEquals("second", b.get(2, TimeUnit.SECONDS))
                assertTrue(lookups.containsAll(listOf("first.test", "second.test")))
                assertFalse(lookups.contains("edge.test"))
                assertEquals("api.github.com", first.takeRequest(1, TimeUnit.SECONDS)!!.headers["Host"])
                assertEquals("raw.githubusercontent.com", second.takeRequest(1, TimeUnit.SECONDS)!!.headers["Host"])
            } finally { transport.cancelAll(); executor.shutdownNow() }
        } }
    }

    @Test fun originSniRoutesAlsoBindGithubUserContentSiblingHosts() {
        MockWebServer().use { forward ->
            forward.start()
            forward.enqueue(MockResponse.Builder().body("avatar").build())
            val lookups = mutableListOf<String>()
            val dns = okhttp3.Dns { host ->
                lookups += host
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
            val route = WattToolkitGithubRoute(
                logicalHosts = setOf("raw.githubusercontent.com"),
                logicalHostSuffixes = setOf(".githubusercontent.com"),
                forwardTargets = listOf("http://content-edge.test:${forward.port}"),
                fakeServerName = "{origin}",
            )
            val resolver = WattToolkitGithubRouteResolver(
                routeProfile = GithubUserContentWattToolkitRouteProfile,
                routeStore = object : WattToolkitGithubRouteStore {
                    override fun load() = PersistedWattToolkitGithubRoute(route, 1_000L)
                    override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                    override fun clear() = Unit
                }, nowProvider = { 2_000L }, backgroundExecutor = Executor { },
            )
            val plain = OkHttpClient.Builder().dns(dns).build()
            val transport = plain.withWattRouting(ExperimentalGithubDirectAccessRuntime(
                listOf(resolver), plain.hostnameVerifier, plain,
            ))
            transport.newCall(Request.Builder().url("https://avatars.githubusercontent.com/u/123").build())
                .execute().use { assertEquals("avatar", it.body.string()) }
            assertEquals(listOf("content-edge.test"), lookups)
            assertEquals("avatars.githubusercontent.com", forward.takeRequest(1, TimeUnit.SECONDS)!!.headers["Host"])
        }
    }

    @Test fun githubApiWebRawReleaseAndAvatarHostsAllUseTheTransport() {
        MockWebServer().use { forward ->
            forward.start()
            val hosts = listOf("api.github.com", "github.com", "raw.github.com", "raw.githubusercontent.com",
                "codeload.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
                "media.githubusercontent.com", "avatars.githubusercontent.com")
            val profiles = listOf(GithubApiWattToolkitRouteProfile, GithubWebWattToolkitRouteProfile,
                GithubUserContentWattToolkitRouteProfile)
            val resolvers = profiles.map { profile -> WattToolkitGithubRouteResolver(
                routeProfile = profile,
                routeStore = object : WattToolkitGithubRouteStore {
                    override fun load() = PersistedWattToolkitGithubRoute(WattToolkitGithubRoute(
                        profile.supportedHosts, listOf(forward.url("/").toString()),
                        logicalHostSuffixes = profile.supportedHostSuffixes,
                    ), 1_000L)
                    override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                    override fun clear() = Unit
                }, nowProvider = { 2_000L }, backgroundExecutor = Executor { },
            ) }
            val plain = OkHttpClient()
            val transport = plain.withWattRouting(ExperimentalGithubDirectAccessRuntime(
                resolvers, plain.hostnameVerifier, plain,
            ))
            hosts.forEach { host ->
                forward.enqueue(MockResponse.Builder().body(host).build())
                transport.newCall(Request.Builder().url("https://$host/resource").build()).execute().use {
                    assertEquals(host, it.request.url.host)
                    assertEquals(host, it.body.string())
                }
                assertEquals(host, forward.takeRequest(1, TimeUnit.SECONDS)!!.headers["Host"])
            }
        }
    }

    private fun request() = Request.Builder().url("https://api.github.com/resource").build()

    private fun transport(
        forward: MockWebServer,
        official: AtomicInteger = AtomicInteger(),
        targets: List<String> = listOf(forward.url("/").toString()),
    ): RoutedHttpTransport {
        val route = WattToolkitGithubRoute(setOf("api.github.com"), targets)
        val resolver = WattToolkitGithubRouteResolver(
            routeProfile = GithubApiWattToolkitRouteProfile,
            routeStore = object : WattToolkitGithubRouteStore {
                override fun load() = PersistedWattToolkitGithubRoute(route, 1_000L)
                override fun save(route: PersistedWattToolkitGithubRoute) = Unit
                override fun clear() = Unit
            },
            nowProvider = { 2_000L }, backgroundExecutor = Executor { },
            // No internet and no rediscovery after the configured candidates have failed.
            bootstrapRouteProvider = { null },
            accelerationStrategyProvider = { io.stamethyst.backend.network.AccelerationStrategy.RMBGAME_FIRST },
        )
        val direct = OkHttpClient()
        val runtime = ExperimentalGithubDirectAccessRuntime(listOf(resolver), direct.hostnameVerifier, direct)
        return OkHttpClient.Builder().addInterceptor {
            official.incrementAndGet()
            throw IOException("Official path disabled in fixture")
        }.build().withWattRouting(runtime)
    }
}
