package io.stamethyst.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Serial delivery: validate off-main, then recheck relevance before changing UI state. */
internal class SteamCloudUiEventQueue<Event, Validation>(
    scope: CoroutineScope,
    private val isRelevant: (Event) -> Boolean,
    private val validate: suspend (Event) -> Validation,
    private val apply: (Event, Validation) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val job = scope.launch {
        for (event in events) {
            // Duplicate callback/broadcast deliveries do not perform disk or KeyStore work.
            if (!isRelevant(event)) continue
            try {
                val validation = validate(event)
                // A new request may have replaced this operation while validation was suspended.
                if (isRelevant(event)) apply(event, validation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }

    fun offer(event: Event) { events.trySend(event) }

    fun close() {
        events.cancel()
        job.cancel()
    }
}
