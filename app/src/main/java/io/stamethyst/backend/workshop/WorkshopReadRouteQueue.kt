package io.stamethyst.backend.workshop

import io.stamethyst.backend.github.RouteRetryQueue
import okhttp3.Request

internal val WORKSHOP_READ_ROUTE_QUEUE = RouteRetryQueue(
    maxRounds = 2,
    candidateTimeoutMs = 20_000L,
    gatewayFailureCodes = setOf(502, 503, 504),
)

internal fun Request.Builder.withWorkshopReadRouteQueue(
    queue: RouteRetryQueue = WORKSHOP_READ_ROUTE_QUEUE,
): Request.Builder = tag(RouteRetryQueue::class.java, queue)
