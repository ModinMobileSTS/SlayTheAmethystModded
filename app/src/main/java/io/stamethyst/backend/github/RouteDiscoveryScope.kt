package io.stamethyst.backend.github

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Foreground discovery's fetches/probes belong to the requesting Call, not orphan workers. */
internal class RouteDiscoveryScope {
    private val calls = mutableSetOf<Call>()
    private val futures = mutableSetOf<Future<*>>()
    private var cancelled = false

    @Synchronized fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Route discovery cancelled")
    }

    @Synchronized fun register(call: Call) {
        if (cancelled) call.cancel()
        checkActive()
        calls += call
    }

    @Synchronized fun unregister(call: Call) { calls -= call }

    @Synchronized fun register(future: Future<*>) {
        if (cancelled) future.cancel(true) else futures += future
    }

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            (calls.toList() to futures.toList()).also { calls.clear(); futures.clear() }
        }
        active.first.forEach(Call::cancel)
        active.second.forEach { it.cancel(true) }
    }
}

internal object RouteDiscoveryContext {
    private val current = ThreadLocal<RouteDiscoveryScope?>()

    fun checkActive() { current.get()?.checkActive() }

    fun <T> run(scope: RouteDiscoveryScope?, block: () -> T): T {
        val previous = current.get()
        current.set(scope)
        return try { scope?.checkActive(); block() } finally { current.set(previous) }
    }

    fun <T> submit(executor: ExecutorService, block: () -> T): Future<T> {
        val scope = current.get()
        scope?.checkActive()
        return executor.submit(Callable { run(scope, block) }).also { scope?.register(it) }
    }

    fun <T> request(client: OkHttpClient, request: Request, block: (Response) -> T): T {
        val scope = current.get()
        val call = client.newCall(request)
        scope?.register(call)
        return try { call.execute().use(block) } finally { scope?.unregister(call) }
    }
}
