package io.stamethyst.backend.github

import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Keeps existing route fixtures readable while exercising the real explicit transport. */
internal class TestRouting(
    val routeResolvers: List<WattToolkitGithubRouteResolver>,
    val directCallFactory: OkHttpClient,
    val forwardDns: WattToolkitForwardDns? = null,
    val enabledProvider: () -> Boolean = { true },
)

internal fun OkHttpClient.Builder.routing(config: TestRouting) = RoutingTestBuilder(this,
    ExperimentalGithubDirectAccessRuntime(config.routeResolvers, directHostnameVerifier(config),
        config.directCallFactory, config.forwardDns), config.enabledProvider)

private fun directHostnameVerifier(config: TestRouting) = config.directCallFactory.hostnameVerifier

internal fun OkHttpClient.Builder.routing(runtime: ExperimentalGithubDirectAccessRuntime) =
    RoutingTestBuilder(this, runtime) { true }

internal class RoutingTestBuilder(
    private val builder: OkHttpClient.Builder,
    private val runtime: ExperimentalGithubDirectAccessRuntime,
    private val enabled: () -> Boolean,
) {
    fun addInterceptor(interceptor: Interceptor) = apply { builder.addInterceptor(interceptor) }
    fun cookieJar(jar: CookieJar) = apply { builder.cookieJar(jar) }
    fun build() = builder.followRedirects(true).build().withWattRouting(runtime, enabled)
}
