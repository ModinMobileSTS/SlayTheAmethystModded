package io.stamethyst.ui.main

import org.junit.Assert.*
import org.junit.Test

class SteamCloudOperationEventGateTest {
    @Test fun relevanceChecksDoNotConsumeOrRebindEvents() {
        val gate = SteamCloudOperationEventGate()
        assertTrue(gate.canAttach())
        gate.begin("sync")
        assertTrue(gate.canAccept("sync", 1)); assertTrue(gate.canAccept("sync", 1))
        assertTrue(gate.accept("sync", 1)); assertFalse(gate.canAccept("sync", 1))
        assertFalse(gate.canAttach()); assertTrue(gate.canAttach("sync"))
        gate.clear()
        assertFalse(gate.canAccept("sync", 2)); assertTrue(gate.canAttach())
    }
    @Test fun racingCompletionCannotBeRolledBackByAnOlderQuerySnapshot() {
        val gate = SteamCloudOperationEventGate()
        assertTrue(gate.attach("running")); assertTrue(gate.accept("running", 12))
        assertFalse(gate.attach("running")); assertFalse(gate.accept("running", 11))
        assertFalse(gate.accept("running", 12))
    }
    @Test fun queryCanBindAnUnboundUiButCannotHijackANewerRequest() {
        val gate = SteamCloudOperationEventGate()
        assertTrue(gate.attach("active")); assertTrue(gate.accept("active", 7))
        gate.begin("new")
        assertFalse(gate.attach("active")); assertFalse(gate.accept("active", 8))
        assertTrue(gate.accept("new", 1))
    }
    @Test fun busyCheckAttachesToExistingOperationOnlyOnce() {
        val gate = SteamCloudOperationEventGate(); gate.begin("request")
        assertTrue(gate.attach("running", "request")); assertTrue(gate.accept("running", 10))
        assertFalse(gate.attach("running", "request")); assertFalse(gate.accept("running", 10))
    }
    @Test fun callbackAndBroadcastConsumeEventOnlyOnce() {
        val gate = SteamCloudOperationEventGate(); gate.begin("a")
        assertTrue(gate.accept("a", 1)); assertFalse(gate.accept("a", 1))
    }
    @Test fun delayedOldTerminalEventCannotFinishNewOperation() {
        val gate = SteamCloudOperationEventGate(); gate.begin("a"); gate.accept("a", 99); gate.begin("b")
        assertFalse(gate.accept("a", 100)); assertTrue(gate.accept("b", 1))
    }
    @Test fun rejectsUnboundAndOutOfOrderEvents() {
        val gate = SteamCloudOperationEventGate(); assertFalse(gate.accept("a", 1)); gate.begin("a")
        assertTrue(gate.accept("a", 3)); assertFalse(gate.accept("a", 2)); assertFalse(gate.accept(null, 4)); assertFalse(gate.accept("a", null))
    }
}
