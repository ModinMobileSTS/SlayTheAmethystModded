package io.stamethyst.ui.settings.core

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/** A generation belongs to one domain, never to the entire settings screen. */
internal class SettingsRefreshTask(private val executor: ExecutorService) {
    private val generation = AtomicLong()
    private var task: Future<*>? = null

    @Synchronized
    fun <T> submit(
        dispatch: (() -> Unit) -> Unit,
        load: (checkCurrent: () -> Unit) -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val current = generation.incrementAndGet()
        task?.cancel(true)
        val checkCurrent = {
            if (generation.get() != current || Thread.currentThread().isInterrupted) {
                throw CancellationException("Settings refresh was superseded")
            }
        }
        val next = FutureTask {
            val result = runCatching {
                checkCurrent()
                load(checkCurrent).also { checkCurrent() }
            }
            dispatch {
                // Cancellation alone cannot recall an already queued UI callback.
                if (generation.get() == current) {
                    result.fold(onSuccess, onFailure)
                }
            }
        }
        task = next
        executor.execute(next)
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        task?.cancel(true)
        task = null
    }
}
