package io.stamethyst

import io.stamethyst.SlingBreakX5PreInitGate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class SlingBreakX5PreInitGateTest {
    @Test
    fun firstLoad_usesNormalPreInit() {
        assertEquals(Action.START, SlingBreakX5PreInitGate().nextAction(isX5Ready = false))
    }

    @Test
    fun retryAfterFailure_requiresRestartInsteadOfWaitingForAMissingCallback() {
        val gate = SlingBreakX5PreInitGate()
        assertEquals(Action.START, gate.nextAction(isX5Ready = false))
        repeat(3) {
            assertEquals(Action.RESTART_REQUIRED, gate.nextAction(isX5Ready = false))
        }
    }

    @Test
    fun initializedCore_isReusedWithoutCallingPreInitAgain() {
        val gate = SlingBreakX5PreInitGate()
        assertEquals(Action.START, gate.nextAction(isX5Ready = false))
        assertEquals(Action.ALREADY_READY, gate.nextAction(isX5Ready = true))
    }

    @Test
    fun coreInitializedElsewhere_requiresRestartIfItBecomesUnavailable() {
        val gate = SlingBreakX5PreInitGate()
        assertEquals(Action.ALREADY_READY, gate.nextAction(isX5Ready = true))
        assertEquals(Action.RESTART_REQUIRED, gate.nextAction(isX5Ready = false))
    }
}
