package io.stamethyst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SoftKeyboardSessionMachineTest {
    @Test
    fun requestShow_fromHidden_schedulesInitialAttemptAndVerification() {
        val machine = SoftKeyboardSessionMachine(
            verifyDelaysMs = listOf(48L, 160L, 320L)
        )

        val transition = machine.requestShow(
            state = SoftKeyboardSessionMachine.State(),
            currentlyVisible = false
        )

        assertEquals(
            SoftKeyboardSessionMachine.State(
                generation = 1,
                pendingShow = true,
                visible = false,
                nextRetryIndex = 1
            ),
            transition.state
        )
        assertEquals(
            listOf(
                SoftKeyboardSessionMachine.Command.PerformShowAttempt(
                    generation = 1,
                    attempt = 0
                ),
                SoftKeyboardSessionMachine.Command.ScheduleVerify(
                    generation = 1,
                    delayMs = 48L
                )
            ),
            transition.commands
        )
    }

    @Test
    fun onVerify_whileStillHidden_retriesInOrderUntilExhausted() {
        val machine = SoftKeyboardSessionMachine(
            verifyDelaysMs = listOf(48L, 160L, 320L)
        )
        val first = machine.requestShow(
            state = SoftKeyboardSessionMachine.State(),
            currentlyVisible = false
        )

        val second = machine.onVerify(
            state = first.state,
            generation = 1,
            currentlyVisible = false
        )

        assertEquals(
            SoftKeyboardSessionMachine.State(
                generation = 1,
                pendingShow = true,
                visible = false,
                nextRetryIndex = 2
            ),
            second.state
        )
        assertEquals(
            listOf(
                SoftKeyboardSessionMachine.Command.PerformShowAttempt(
                    generation = 1,
                    attempt = 1
                ),
                SoftKeyboardSessionMachine.Command.ScheduleVerify(
                    generation = 1,
                    delayMs = 160L
                )
            ),
            second.commands
        )

        val third = machine.onVerify(
            state = second.state,
            generation = 1,
            currentlyVisible = false
        )
        assertEquals(
            listOf(
                SoftKeyboardSessionMachine.Command.PerformShowAttempt(
                    generation = 1,
                    attempt = 2
                ),
                SoftKeyboardSessionMachine.Command.ScheduleVerify(
                    generation = 1,
                    delayMs = 320L
                )
            ),
            third.commands
        )

        val exhausted = machine.onVerify(
            state = third.state,
            generation = 1,
            currentlyVisible = false
        )
        assertEquals(
            third.state.copy(
                pendingShow = false,
                visible = false
            ),
            exhausted.state
        )
        assertEquals(emptyList<SoftKeyboardSessionMachine.Command>(), exhausted.commands)
    }

    @Test
    fun onVisibilityChanged_visible_clearsPendingShow() {
        val machine = SoftKeyboardSessionMachine()
        val requested = machine.requestShow(
            state = SoftKeyboardSessionMachine.State(),
            currentlyVisible = false
        )

        val visibleState = machine.onVisibilityChanged(
            state = requested.state,
            visible = true
        )

        assertEquals(true, visibleState.visible)
        assertFalse(visibleState.pendingShow)
        assertEquals(requested.state.generation, visibleState.generation)
    }

    @Test
    fun requestHide_cancelsPendingShowAndEmitsHideCommand() {
        val machine = SoftKeyboardSessionMachine()
        val requested = machine.requestShow(
            state = SoftKeyboardSessionMachine.State(),
            currentlyVisible = false
        )

        val hidden = machine.requestHide(
            state = requested.state,
            refocusRenderView = true
        )

        assertEquals(
            SoftKeyboardSessionMachine.State(
                generation = 2,
                pendingShow = false,
                visible = false,
                nextRetryIndex = 0
            ),
            hidden.state
        )
        assertEquals(
            listOf(
                SoftKeyboardSessionMachine.Command.PerformHide(
                    generation = 2,
                    refocusRenderView = true
                )
            ),
            hidden.commands
        )
    }
}
