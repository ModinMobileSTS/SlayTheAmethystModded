package io.stamethyst.backend.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AgentToolSchedulerTest {
    @Test(timeout = 10_000)
    fun parallelCallsOverlapButResultsAndCheckpointsKeepModelOrder() {
        val bothStarted = CountDownLatch(2)
        val secondCompleted = CountDownLatch(1)
        val completed = Collections.synchronizedList(mutableListOf<String>())
        val tools = listOf(
            tool("first") {
                bothStarted.countDown()
                await(bothStarted)
                await(secondCompleted)
                "first-result"
            },
            tool("second") {
                bothStarted.countDown()
                await(bothStarted)
                "second-result"
            },
        )
        val registry = AgentToolRegistry(tools, onToolExecution = {
            if (it.result != null) {
                completed += it.name
                if (it.name == "second") secondCompleted.countDown()
            }
        })
        val caller = Thread.currentThread()
        val results = mutableListOf<String>()
        AgentToolScheduler(registry).execute(calls("first", "second")) { call, result ->
            assertSame(caller, Thread.currentThread())
            results += "${call.name()}:${result.text}"
        }
        assertEquals(listOf("second", "first"), completed)
        assertEquals(listOf("first:first-result", "second:second-result"), results)
    }

    @Test(timeout = 10_000)
    fun exclusiveCallIsABarrierForBothEarlierAndLaterReads() {
        val before = CountDownLatch(2)
        val after = CountDownLatch(2)
        val active = AtomicInteger()
        val phase = AtomicInteger()
        fun read(name: String, expectedPhase: Int, latch: CountDownLatch) = tool(name) {
            assertEquals(expectedPhase, phase.get())
            active.incrementAndGet()
            try {
                latch.countDown()
                await(latch)
            } finally {
                active.decrementAndGet()
            }
            "ok"
        }
        val tools = listOf(
            read("before1", 0, before), read("before2", 0, before),
            tool("write", AgentToolConcurrency.EXCLUSIVE) {
                assertEquals(0, active.get())
                assertEquals(0L, before.count)
                phase.set(1)
                "ok"
            },
            read("after1", 1, after), read("after2", 1, after),
        )
        val results = mutableListOf<String>()
        AgentToolScheduler(AgentToolRegistry(tools)).execute(calls(*tools.map { it.specification.name() }.toTypedArray())) { call, _ ->
            results += call.name()
        }
        assertEquals(tools.map { it.specification.name() }, results)
    }

    @Test(timeout = 10_000)
    fun concurrencyIsBoundedAndWorkersAreReusedAsTheyFinish() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstPair = CountDownLatch(2)
        val completedCount = AtomicInteger()
        val tools = (0 until 8).map { index ->
            tool("read$index") {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    if (index < 2) {
                        firstPair.countDown()
                        await(firstPair)
                    }
                    "ok"
                } finally {
                    active.decrementAndGet()
                    completedCount.incrementAndGet()
                }
            }
        }
        AgentToolScheduler(AgentToolRegistry(tools), maxParallelism = 2)
            .execute(calls(*tools.map { it.specification.name() }.toTypedArray())) { _, _ -> }
        assertEquals(2, peak.get())
        assertEquals(8, completedCount.get())
    }

    @Test(timeout = 10_000)
    fun slowFirstCallDoesNotPreventNewWorkUsingFreedSlots() {
        val thirdStarted = CountDownLatch(1)
        val tools = listOf(
            tool("slow") { await(thirdStarted); "first" },
            tool("fast") { "second" },
            tool("third") { thirdStarted.countDown(); "third" },
        )
        val results = mutableListOf<String>()
        AgentToolScheduler(AgentToolRegistry(tools), maxParallelism = 2)
            .execute(calls("slow", "fast", "third")) { _, result -> results += result.text }
        assertEquals(listOf("first", "second", "third"), results)
    }

    @Test
    fun parallelismOneAndUnannotatedToolsRemainSerial() {
        val unannotated = object : AgentTool {
            override val specification = ToolSpecification.builder().name("default").build()
            override val safety = AgentToolSafety.READ_ONLY
            override fun execute(arguments: String) = "ok"
        }
        assertEquals(AgentToolConcurrency.EXCLUSIVE, unannotated.concurrency)
        val caller = Thread.currentThread()
        val parallel = tool("read") { assertSame(caller, Thread.currentThread()); "ok" }
        var count = 0
        AgentToolScheduler(AgentToolRegistry(listOf(parallel, unannotated)), maxParallelism = 1)
            .execute(calls("read", "read", "default")) { _, _ -> count++ }
        assertEquals(3, count)
    }

    @Test(timeout = 10_000)
    fun ordinaryFailureDoesNotCancelSiblingsAndEventsAreSerialized() {
        val started = CountDownLatch(2)
        val inCallback = AtomicInteger()
        val eventCount = AtomicInteger()
        val tools = listOf(
            tool("fails") { started.countDown(); await(started); error("broken") },
            tool("works") { started.countDown(); await(started); "ok" },
        )
        val registry = AgentToolRegistry(tools, onToolExecution = {
            assertEquals(1, inCallback.incrementAndGet())
            Thread.yield()
            eventCount.incrementAndGet()
            inCallback.decrementAndGet()
        })
        val results = mutableListOf<String>()
        AgentToolScheduler(registry).execute(calls("fails", "works")) { _, result -> results += result.text }
        assertTrue(results[0].contains("tool_execution_failed"))
        assertEquals("ok", results[1])
        assertEquals(4, eventCount.get())
    }

    @Test
    fun unknownAndDisallowedToolsStayGatedEvenWhenMarkedParallel() {
        val denied = object : AgentTool {
            override val specification = ToolSpecification.builder().name("denied").build()
            override val safety = AgentToolSafety.REQUIRES_APPROVAL
            override val concurrency = AgentToolConcurrency.PARALLEL
            override fun execute(arguments: String): String = error("must not run")
        }
        val registry = AgentToolRegistry(listOf(denied))
        assertEquals(AgentToolConcurrency.EXCLUSIVE, registry.concurrency(calls("denied").single()))
        val results = mutableListOf<String>()
        AgentToolScheduler(registry).execute(calls("missing", "denied")) { _, result -> results += result.text }
        assertTrue(results[0].contains("unknown_tool"))
        assertTrue(results[1].contains("approval_required"))
    }

    @Test(timeout = 10_000)
    fun cancellationInterruptsWorkersAndDoesNotAdmitLaterCalls() {
        assertCancellation(interruptCaller = false)
    }

    @Test(timeout = 10_000)
    fun callerInterruptionCancelsWholeBatch() {
        assertCancellation(interruptCaller = true)
    }

    private fun assertCancellation(interruptCaller: Boolean) {
        val started = CountDownLatch(2)
        val stopped = CountDownLatch(2)
        val cancelled = AtomicBoolean()
        val laterRan = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val results = AtomicInteger()
        val tools = listOf("one", "two").map { name ->
            tool(name) {
                started.countDown()
                try { CountDownLatch(1).await(); "unreachable" } finally { stopped.countDown() }
            }
        } + tool("later", AgentToolConcurrency.EXCLUSIVE) { laterRan.set(true); "ok" }
        val runner = thread {
            try {
                AgentToolScheduler(AgentToolRegistry(tools), checkCancelled = {
                    if (cancelled.get()) throw CancellationException("stopped")
                }).execute(calls("one", "two", "later")) { _, _ -> results.incrementAndGet() }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            await(started)
            if (interruptCaller) runner.interrupt() else cancelled.set(true)
            runner.join(3_000)
            assertFalse("scheduler did not stop", runner.isAlive)
            await(stopped)
            assertTrue("expected cancellation, got ${failure.get()}", failure.get() is CancellationException)
            assertFalse(laterRan.get())
            assertEquals(0, results.get())
        } finally {
            cancelled.set(true)
            runner.interrupt()
            runner.join(3_000)
        }
    }

    @Test(timeout = 10_000)
    fun toolCancellationIsNotConvertedIntoOrdinaryFailure() {
        val started = CountDownLatch(2)
        val siblingStopped = CountDownLatch(1)
        val tools = listOf(
            tool("slow") {
                started.countDown()
                try { CountDownLatch(1).await(); "unreachable" } finally { siblingStopped.countDown() }
            },
            tool("cancel") { started.countDown(); await(started); throw CancellationException("abort") },
        )
        val failure = runCatching {
            AgentToolScheduler(AgentToolRegistry(tools)).execute(calls("slow", "cancel")) { _, _ -> fail("cancelled batch") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        await(siblingStopped)
    }

    @Test(timeout = 10_000)
    fun checkpointFailureInterruptsSiblingsAndSkipsRemainingCalls() {
        val started = CountDownLatch(2)
        val siblingStopped = CountDownLatch(1)
        val laterRan = AtomicBoolean()
        val tools = listOf(
            tool("first") { started.countDown(); await(started); "ok" },
            tool("slow") {
                started.countDown()
                try { CountDownLatch(1).await(); "unreachable" } finally { siblingStopped.countDown() }
            },
            tool("later", AgentToolConcurrency.EXCLUSIVE) { laterRan.set(true); "ok" },
        )
        val error = java.io.IOException("checkpoint failed")
        val failure = runCatching {
            AgentToolScheduler(AgentToolRegistry(tools)).execute(calls("first", "slow", "later")) { _, _ -> throw error }
        }.exceptionOrNull()
        assertSame(error, failure)
        await(siblingStopped)
        assertFalse(laterRan.get())
    }

    @Test(timeout = 10_000)
    fun toolThatSwallowsInterruptionCannotPublishLateCompletion() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val completions = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val tools = listOf(
            tool("swallows") {
                worker.set(Thread.currentThread())
                started.countDown()
                try {
                    try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
                    await(release)
                    "late result"
                } finally { finished.countDown() }
            },
            tool("cancel") {
                started.countDown()
                await(started)
                cancelled.set(true)
                throw CancellationException("abort")
            },
        )
        val registry = AgentToolRegistry(tools, onToolExecution = {
            if (it.result != null) completions.incrementAndGet()
        })
        try {
            val failure = runCatching {
                AgentToolScheduler(registry, checkCancelled = {
                    if (cancelled.get()) throw CancellationException("abort")
                }).execute(calls("swallows", "cancel")) { _, _ -> fail("cancelled batch") }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } finally {
            release.countDown()
        }
        await(finished)
        worker.get().join(3_000)
        assertFalse("worker did not exit", worker.get().isAlive)
        assertEquals(0, completions.get())
    }

    @Test
    fun emptyBatchDoesNoWorkAndInvalidParallelismIsRejected() {
        val registry = AgentToolRegistry(emptyList())
        AgentToolScheduler(registry).execute(emptyList()) { _, _ -> fail("empty batch") }
        assertTrue(runCatching { AgentToolScheduler(registry, 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun workspaceToolPoliciesAreExplicit() {
        val root = File(".")
        listOf(WorkspaceTextFileTool(root), AgentWorkspaceListTool(root), AgentWorkspaceFileTool(root),
            AgentWorkspaceGlobTool(root), AgentWorkspaceGrepTool(root), AgentModResourceReadTool(root),
            AgentModEntriesListTool(root), AgentModClassInspectTool("parent", root)).forEach {
            assertEquals(it.specification.name(), AgentToolConcurrency.PARALLEL, it.concurrency)
        }
        listOf(AgentWorkspaceWriteTool(root), AgentWorkspaceEditTool(root), AgentWorkspaceDeleteTool(root)).forEach {
            assertEquals(it.specification.name(), AgentToolConcurrency.EXCLUSIVE, it.concurrency)
        }
        val context = android.app.Application()
        listOf(AgentSkillTool(context), AgentApiSearchTool(context, root), AgentApiDescribeTool(context, root),
            AgentPatchTargetInspectTool(context, root), AgentPatchModListTool(context, "parent"),
            AgentApiClassDecompileTool(context, root)).forEach {
            assertEquals(it.specification.name(), AgentToolConcurrency.PARALLEL, it.concurrency)
        }
        listOf(
            AgentListInstalledModsTool(context, "parent"),
            AgentPatchWorkspaceCreateTool(context, "parent", root),
            AgentModSourceDecompileTool(context, "parent", root),
            AgentModClassDecompileTool(context, "parent", root),
            AgentPatchSkeletonTool(context, root, { null }),
            AgentPatchModCompileTool(context, root, { null }),
            AgentPatchPreflightTool(context, root, { null }),
            AgentPatchModPackageTool(context, root, { null }),
            AgentPatchModUpdateTool(context, "parent", root),
            AgentPatchSmokeTestTool(context, "parent", root, { null }),
            AgentPatchModSetEnabledTool(context, "parent", { null }),
            AgentPatchModDeleteTool(context, "parent"),
        ).forEach {
            assertEquals(it.specification.name(), AgentToolConcurrency.EXCLUSIVE, it.concurrency)
        }
    }

    private fun calls(vararg names: String) = names.mapIndexed { index, name ->
        ToolExecutionRequest.builder().id("call-$index").name(name).arguments("{}").build()
    }

    private fun tool(
        name: String,
        concurrency: AgentToolConcurrency = AgentToolConcurrency.PARALLEL,
        body: () -> String,
    ) = object : AgentTool {
        override val specification = ToolSpecification.builder().name(name).build()
        override val safety = AgentToolSafety.READ_ONLY
        override val concurrency = concurrency
        override fun execute(arguments: String) = body()
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(3, TimeUnit.SECONDS)) { "Timed out waiting for concurrent tools" }
    }
}
