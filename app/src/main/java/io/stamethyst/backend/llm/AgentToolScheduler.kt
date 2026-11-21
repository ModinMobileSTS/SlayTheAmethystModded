package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit opt-in: safety/approval classification alone does not imply thread safety. */
enum class AgentToolConcurrency {
    PARALLEL,
    /** A barrier: finish earlier calls, run alone, then admit later calls. */
    EXCLUSIVE,
}

/**
 * Runs consecutive parallel-safe calls with bounded concurrency. Exclusive calls never overlap any
 * other call, including reads. Like OpenCode's step-scoped tool fibers, all workers belong to this
 * batch and are interrupted on cancellation/failure; the next model turn waits for the whole batch.
 *
 * Only tool bodies and their registry events run on workers. [onResult] runs on the caller, in model
 * call order, so protocol checkpoints/context state never need concurrent mutation. Completed later
 * results are buffered until earlier results arrive. No work is reordered across an exclusive call.
 */
class AgentToolScheduler(
    private val registry: AgentToolRegistry,
    private val maxParallelism: Int = 4,
    private val checkCancelled: () -> Unit = {},
) {
    init {
        require(maxParallelism > 0) { "Tool parallelism must be positive." }
    }

    fun execute(
        requests: List<ToolExecutionRequest>,
        onResult: (ToolExecutionRequest, AgentToolOutput) -> Unit,
    ) {
        var executor: ExecutorService? = null
        val stopped = AtomicBoolean(false)
        val checkBatchActive = {
            if (stopped.get()) throw CancellationException("Agent tool batch cancelled")
            checkActive()
        }
        try {
            if (requests.size > 1 && requests.any { it.name() == SWITCH_WORKSPACE || it.name() == "create_agent_patch_workspace" }) {
                // Reject the whole batch, including a write before or after the switch.
                requests.forEach { onResult(it, registry.rejectMixedWorkspaceBatch(it, checkBatchActive)) }
                return
            }
            var index = 0
            while (index < requests.size) {
                checkActive()
                val start = index
                if (registry.concurrency(requests[index]) == AgentToolConcurrency.EXCLUSIVE) {
                    val request = requests[index++]
                    val result = registry.execute(request, checkBatchActive)
                    checkActive()
                    onResult(request, result)
                    continue
                }
                while (index < requests.size &&
                    registry.concurrency(requests[index]) == AgentToolConcurrency.PARALLEL
                ) index++
                val group = requests.subList(start, index)
                if (group.size == 1 || maxParallelism == 1) {
                    group.forEach { request ->
                        checkActive()
                        val result = registry.execute(request, checkBatchActive)
                        checkActive()
                        onResult(request, result)
                    }
                } else {
                    val pool = executor ?: Executors.newFixedThreadPool(maxParallelism) { runnable ->
                        Thread(runnable, "STS-AgentTool").apply { isDaemon = true }
                    }.also { executor = it }
                    executeParallel(pool, group, stopped, checkBatchActive, onResult)
                }
            }
        } finally {
            // Even a tool which swallows interruption must not publish a late completion event.
            stopped.set(true)
            executor?.shutdownNow()
        }
    }

    private fun executeParallel(
        executor: ExecutorService,
        requests: List<ToolExecutionRequest>,
        stopped: AtomicBoolean,
        checkBatchActive: () -> Unit,
        onResult: (ToolExecutionRequest, AgentToolOutput) -> Unit,
    ) {
        val completion = ExecutorCompletionService<Pair<Int, AgentToolOutput>>(executor)
        val pending = HashSet<Future<Pair<Int, AgentToolOutput>>>()
        val results = arrayOfNulls<AgentToolOutput>(requests.size)
        var submitted = 0
        var delivered = 0
        fun submit() {
            checkActive()
            val index = submitted++
            pending += completion.submit(Callable {
                checkBatchActive()
                index to registry.execute(requests[index], checkBatchActive)
            })
        }
        try {
            repeat(minOf(maxParallelism, requests.size)) { submit() }
            while (pending.isNotEmpty()) {
                checkActive()
                val finished = completion.poll(50, TimeUnit.MILLISECONDS) ?: continue
                pending.remove(finished)
                val (index, result) = try {
                    finished.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
                results[index] = result
                while (delivered < results.size && results[delivered] != null) {
                    checkActive()
                    onResult(requests[delivered], requireNotNull(results[delivered]))
                    results[delivered] = null
                    delivered++
                }
                if (submitted < requests.size) submit()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Agent tool batch cancelled").apply { initCause(error) }
        } finally {
            if (pending.isNotEmpty()) stopped.set(true)
            pending.forEach { it.cancel(true) }
        }
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Agent tool batch cancelled")
        checkCancelled()
    }
}
