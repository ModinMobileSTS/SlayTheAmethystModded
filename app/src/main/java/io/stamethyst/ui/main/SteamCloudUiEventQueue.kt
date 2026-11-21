package io.stamethyst.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.stamethyst.backend.steamcloud.SteamCloudAuthStore
import io.stamethyst.backend.steamcloud.SteamCloudControlStore
import io.stamethyst.config.SteamCloudSaveMode

internal data class SteamCloudUiAccess(val cloudMode: Boolean, val disabled: Boolean, val accountMatches: Boolean)

/** Null means validation is temporarily unavailable, not signed out or a different account. */
internal fun validateSteamCloudEvent(controls: SteamCloudControlStore.State, accountId: String,
    read: () -> SteamCloudAuthStore.AuthMaterialRead): SteamCloudUiAccess? {
    val cloud = controls.mode == SteamCloudSaveMode.STEAM_CLOUD.persistedValue && !controls.independentSwitchPending
    if (!cloud || controls.disabled || accountId.isBlank()) return SteamCloudUiAccess(cloud, controls.disabled, accountId.isBlank() || controls.disabled)
    val auth = read()
    return if (auth.readFailed) null else SteamCloudUiAccess(true, false, auth.material?.steamId64 == accountId)
}

/** Serial delivery: validate off-main, then recheck relevance before changing UI state. */
internal class SteamCloudUiEventQueue<Event, Validation : Any>(
    scope: CoroutineScope,
    private val isRelevant: (Event) -> Boolean,
    private val validate: suspend (Event) -> Validation?,
    private val apply: (Event, Validation) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val backoff: suspend (Long) -> Unit = { delay(it) },
) {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch {
        for (event in events) {
            // Duplicate callback/broadcast deliveries do not perform disk or KeyStore work.
            if (!isRelevant(event)) continue
            try {
                var attempt = 0
                while (isRelevant(event)) {
                    val validation = validate(event)
                    if (validation != null) {
                        if (isRelevant(event)) apply(event, validation)
                        break
                    }
                    // Keep the ACK ahead of its terminal. After a bounded burst, only an
                    // explicit lifecycle/operation change wakes the parked validation.
                    if (++attempt <= 3) backoff(150L * attempt) else { wake.receive(); attempt = 0 }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }

    fun offer(event: Event) { events.trySend(event) }
    fun retry() { wake.trySend(Unit) }

    fun close() {
        events.cancel()
        wake.cancel()
        job.cancel()
    }
}
