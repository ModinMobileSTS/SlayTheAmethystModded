package io.stamethyst.backend.llm

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentRequestControlTest {
    @Test fun firstEventAndIdleHaveSeparateDeadlines() {
        val policy = AgentRequestTimeoutPolicy(1_000, 100, 200)
        assertEquals(AgentRequestTimeoutState(1, null), policy.evaluate(99, null))
        assertEquals(AgentRequestTimeoutKind.FIRST_EVENT, policy.evaluate(100, null).expired)
        assertEquals(AgentRequestTimeoutState(200, null), policy.evaluate(90, 90))
        assertEquals(AgentRequestTimeoutState(1, null), policy.evaluate(289, 90))
        assertEquals(AgentRequestTimeoutKind.STREAM_IDLE, policy.evaluate(290, 90).expired)
    }

    @Test fun continuousProgressNeverExtendsTotalAndTotalWinsTies() {
        val policy = AgentRequestTimeoutPolicy(1_000, 100, 200)
        assertEquals(AgentRequestTimeoutState(1, null), policy.evaluate(999, 990))
        assertEquals(AgentRequestTimeoutKind.TOTAL, policy.evaluate(1_000, 990).expired)
        assertEquals(AgentRequestTimeoutKind.TOTAL, policy.evaluate(1_000, 800).expired)
        assertEquals(AgentRequestTimeoutKind.TOTAL, AgentRequestTimeoutPolicy(100).evaluate(100, null).expired)
    }

    @Test fun invalidAndUnlimitedPoliciesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AgentRequestTimeoutPolicy(0) }
        assertThrows(IllegalArgumentException::class.java) { AgentRequestTimeoutPolicy(100, -1, 100) }
        assertThrows(IllegalArgumentException::class.java) { AgentRequestTimeoutPolicy(100, 100, 0) }
        assertThrows(IllegalArgumentException::class.java) { AgentRequestTimeoutPolicy(100).evaluate(1, 2) }
    }

    @Test fun cumulativeBudgetIncludesAttemptsBackoffAndNetworkRecovery() {
        val clock = FakeTime()
        val budget = AgentRoundBudget(300_000, clock)
        assertEquals(300_000L, budget.requestTimeoutMillis(300_000))
        clock.now += 240_000 // First attempt.
        assertTrue(budget.canWait(5_000))
        clock.now += 5_000 // Backoff.
        clock.now += 25_000 // Network recovery, independent of backoff.
        assertEquals(30_000L, budget.requestTimeoutMillis(300_000))
        assertFalse(budget.canWait(30_000))
        assertFalse(budget.canWait(29_999, minimumAttemptMillis = 2))
        clock.now += 30_000
        assertEquals(0L, budget.remainingMillis())
        assertFalse(budget.canWait(0))
        assertEquals(AgentRequestTimeoutKind.ROUND_BUDGET,
            assertThrows(AgentRequestTimeoutException::class.java) { budget.requestTimeoutMillis(300_000) }.kind)
    }

    @Test fun budgetMathDoesNotOverflowOnLongBackoff() {
        val budget = AgentRoundBudget(100, FakeTime())
        assertFalse(budget.canWait(Long.MAX_VALUE))
        assertFalse(budget.canWait(1, Long.MAX_VALUE))
        assertEquals(10L, budget.requestTimeoutMillis(10))
    }

    @Test fun schedulerExpiresSilentRequestWithoutPolling() {
        val clock = FakeTime()
        val scheduler = FakeScheduler(clock)
        AgentRequestControl(clock, scheduler).use { owner ->
            owner.newRequest(AgentRequestTimeoutPolicy(1_000, 100, 200)).use { scope ->
                scheduler.advanceTo(100)
                assertEquals(AgentRequestTimeoutKind.FIRST_EVENT, (scope.stopReason as AgentRequestTimeoutException).kind)
                assertThrows(AgentRequestTimeoutException::class.java) { scope.bind { fail("must not enter SDK") } }
            }
        }
        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun meaningfulProgressSwitchesToIdleWithoutLeakingScheduledTasks() {
        val clock = FakeTime()
        val scheduler = FakeScheduler(clock)
        AgentRequestControl(clock, scheduler).use { owner ->
            owner.newRequest(AgentRequestTimeoutPolicy(1_000, 100, 200)).use { scope ->
                scheduler.advanceTo(90)
                scope.onMeaningfulEvent()
                scheduler.advanceTo(280)
                assertNull(scope.stopReason)
                scope.onMeaningfulEvent()
                assertEquals(1, scheduler.pendingCount)
                scheduler.advanceTo(480)
                assertEquals(AgentRequestTimeoutKind.STREAM_IDLE, (scope.stopReason as AgentRequestTimeoutException).kind)
            }
        }
    }

    @Test fun totalTimerStillExpiresDuringContinuousProgress() {
        val clock = FakeTime()
        val scheduler = FakeScheduler(clock)
        AgentRequestControl(clock, scheduler).use { owner ->
            owner.newRequest(AgentRequestTimeoutPolicy(1_000, 100, 200)).use { scope ->
                (90L..990L step 100).forEach { scheduler.advanceTo(it); scope.onMeaningfulEvent() }
                scheduler.advanceTo(1_000)
                assertEquals(AgentRequestTimeoutKind.TOTAL, (scope.stopReason as AgentRequestTimeoutException).kind)
                scope.cancel()
                assertTrue("first terminal reason is retained", scope.stopReason is AgentRequestTimeoutException)
            }
        }
    }

    @Test fun lateEventCannotReviveExpiredRequestEvenIfTimerHasNotRun() {
        val clock = FakeTime()
        val scheduler = FakeScheduler(clock)
        AgentRequestControl(clock, scheduler).use { owner ->
            owner.newRequest(AgentRequestTimeoutPolicy(1_000, 100, 200)).use { scope ->
                clock.now = 100
                scope.onMeaningfulEvent()
                assertEquals(AgentRequestTimeoutKind.FIRST_EVENT, (scope.stopReason as AgentRequestTimeoutException).kind)
            }
        }
    }

    @Test fun attemptCancellationDoesNotPoisonNextAttemptOrOtherExecution() {
        val clock = FakeTime()
        val scheduler = FakeScheduler(clock)
        AgentRequestControl(clock, scheduler).use { owner ->
            AgentRequestControl(clock, scheduler).use { other ->
                val first = owner.newRequest(AgentRequestTimeoutPolicy(100))
                first.cancel()
                owner.newRequest(AgentRequestTimeoutPolicy(100)).use { next ->
                    other.newRequest(AgentRequestTimeoutPolicy(100)).use { unrelated ->
                        assertNull(next.stopReason)
                        owner.cancel()
                        assertTrue(next.stopReason is CancellationException)
                        assertNull(unrelated.stopReason)
                        assertThrows(CancellationException::class.java) { owner.newRequest(AgentRequestTimeoutPolicy(100)) }
                    }
                }
            }
        }
        assertEquals(0, scheduler.pendingCount)
    }

    @Test fun nestedBindingRestoresPreviousScopeOnSuccessAndFailure() {
        val clock = FakeTime()
        AgentRequestControl(clock, FakeScheduler(clock)).use { owner ->
            owner.newRequest(AgentRequestTimeoutPolicy(100)).use { first ->
                owner.newRequest(AgentRequestTimeoutPolicy(100)).use { second ->
                    first.bind {
                        assertSame(first, AgentRequestScope.current())
                        assertThrows(IllegalStateException::class.java) {
                            second.bind { assertSame(second, AgentRequestScope.current()); error("test") }
                        }
                        assertSame(first, AgentRequestScope.current())
                    }
                    assertNull(AgentRequestScope.current())
                }
            }
        }
    }

    @Test fun timeoutsUseShortRetriesWhileRealDisconnectsKeepRecoveryPolicy() {
        listOf(SocketTimeoutException("read timed out"), AgentRequestTimeoutException(AgentRequestTimeoutKind.TOTAL)).forEach {
            val wrapped = RuntimeException(it)
            assertFalse(isConnectionAbort(wrapped))
            assertTrue(isRetryableAgentRequestFailure(wrapped))
            assertEquals(listOf(1L, 2L, 4L), (0..2).map { attempt -> retryDelaySeconds(attempt, wrapped) })
        }
        listOf(SocketException("Connection reset"), SocketException("Broken pipe"), EOFException()).forEach {
            assertTrue(isConnectionAbort(RuntimeException(it)))
            assertEquals(5L, retryDelaySeconds(0, it))
        }
        assertFalse(isRetryableAgentRequestFailure(RuntimeException(CancellationException())))
    }

    private class FakeTime(var now: Long = 0) : AgentRequestClock {
        override fun nowMillis() = now
    }

    private class FakeScheduler(private val clock: FakeTime) : AgentRequestScheduler {
        private data class Task(val due: Long, val action: () -> Unit)
        private val tasks = mutableListOf<Task>()
        val pendingCount get() = tasks.size

        override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
            val task = Task(clock.now + delayMillis, action)
            tasks += task
            return AutoCloseable { tasks.remove(task) }
        }

        fun advanceTo(now: Long) {
            clock.now = now
            while (true) {
                val task = tasks.firstOrNull { it.due <= now } ?: return
                tasks.remove(task)
                task.action()
            }
        }
    }
}
