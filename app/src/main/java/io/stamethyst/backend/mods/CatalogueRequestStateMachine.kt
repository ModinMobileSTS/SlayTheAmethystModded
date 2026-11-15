package io.stamethyst.backend.mods

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CatalogueRequestPhase { IDLE, FETCHING, READY, FAILED }

data class CatalogueRequestState<T>(
    val phase: CatalogueRequestPhase = CatalogueRequestPhase.IDLE,
    val data: T? = null,
    val error: String? = null,
    val refreshedAtMs: Long? = null,
)

/** One process-owned request: cancelling a screen only cancels its wait, not the download. */
internal class CatalogueRequestStateMachine<T : Any>(
    private val scope: CoroutineScope,
    private val cachedValue: (T) -> T = { it },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(CatalogueRequestState<T>())
    val state: StateFlow<CatalogueRequestState<T>> = mutableState.asStateFlow()
    private var inFlight: Deferred<T>? = null

    suspend fun load(force: Boolean = false, fetch: suspend () -> T): T {
        val request = synchronized(lock) {
            inFlight ?: run {
                val current = mutableState.value
                if (!force && current.phase == CatalogueRequestPhase.READY) {
                    return cachedValue(current.data!!)
                }
                mutableState.value = current.copy(phase = CatalogueRequestPhase.FETCHING, error = null)
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val data = fetch()
                        synchronized(lock) {
                            mutableState.value = CatalogueRequestState(
                                phase = CatalogueRequestPhase.READY,
                                data = data,
                                refreshedAtMs = System.currentTimeMillis(),
                            )
                        }
                        data
                    } catch (error: Exception) {
                        synchronized(lock) {
                            mutableState.value = mutableState.value.copy(
                                phase = CatalogueRequestPhase.FAILED,
                                error = error.message ?: error.javaClass.simpleName,
                            )
                        }
                        throw error
                    } finally {
                        synchronized(lock) { inFlight = null }
                    }
                }.also { inFlight = it }
            }
        }
        request.start()
        return request.await()
    }
}
