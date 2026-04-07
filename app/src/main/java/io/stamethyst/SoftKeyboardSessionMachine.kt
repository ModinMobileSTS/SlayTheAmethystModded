package io.stamethyst

internal class SoftKeyboardSessionMachine(
    private val verifyDelaysMs: List<Long> = DEFAULT_VERIFY_DELAYS_MS
) {
    data class State(
        val generation: Int = 0,
        val pendingShow: Boolean = false,
        val visible: Boolean = false,
        val nextRetryIndex: Int = 0
    )

    sealed interface Command {
        data class PerformShowAttempt(
            val generation: Int,
            val attempt: Int
        ) : Command

        data class ScheduleVerify(
            val generation: Int,
            val delayMs: Long
        ) : Command

        data class PerformHide(
            val generation: Int,
            val refocusRenderView: Boolean
        ) : Command
    }

    data class Transition(
        val state: State,
        val commands: List<Command>
    )

    fun requestShow(
        state: State,
        currentlyVisible: Boolean
    ): Transition {
        val generation = state.generation + 1
        if (currentlyVisible) {
            return Transition(
                state = State(
                    generation = generation,
                    pendingShow = false,
                    visible = true,
                    nextRetryIndex = 0
                ),
                commands = emptyList()
            )
        }

        if (verifyDelaysMs.isEmpty()) {
            return Transition(
                state = State(
                    generation = generation,
                    pendingShow = false,
                    visible = false,
                    nextRetryIndex = 0
                ),
                commands = listOf(Command.PerformShowAttempt(generation, attempt = 0))
            )
        }

        return Transition(
            state = State(
                generation = generation,
                pendingShow = true,
                visible = false,
                nextRetryIndex = 1
            ),
            commands = listOf(
                Command.PerformShowAttempt(generation, attempt = 0),
                Command.ScheduleVerify(generation, verifyDelaysMs.first())
            )
        )
    }

    fun onVerify(
        state: State,
        generation: Int,
        currentlyVisible: Boolean
    ): Transition {
        if (generation != state.generation) {
            return Transition(state = state, commands = emptyList())
        }
        if (currentlyVisible) {
            return Transition(
                state = state.copy(
                    pendingShow = false,
                    visible = true
                ),
                commands = emptyList()
            )
        }
        if (!state.pendingShow) {
            return Transition(
                state = state.copy(visible = false),
                commands = emptyList()
            )
        }
        if (state.nextRetryIndex >= verifyDelaysMs.size) {
            return Transition(
                state = state.copy(
                    pendingShow = false,
                    visible = false
                ),
                commands = emptyList()
            )
        }

        val retryIndex = state.nextRetryIndex
        return Transition(
            state = state.copy(
                pendingShow = true,
                visible = false,
                nextRetryIndex = retryIndex + 1
            ),
            commands = listOf(
                Command.PerformShowAttempt(generation, attempt = retryIndex),
                Command.ScheduleVerify(generation, verifyDelaysMs[retryIndex])
            )
        )
    }

    fun requestHide(
        state: State,
        refocusRenderView: Boolean = true
    ): Transition {
        val generation = state.generation + 1
        return Transition(
            state = State(
                generation = generation,
                pendingShow = false,
                visible = false,
                nextRetryIndex = 0
            ),
            commands = listOf(
                Command.PerformHide(
                    generation = generation,
                    refocusRenderView = refocusRenderView
                )
            )
        )
    }

    fun onVisibilityChanged(
        state: State,
        visible: Boolean
    ): State {
        return state.copy(
            pendingShow = if (visible) false else state.pendingShow,
            visible = visible
        )
    }

    companion object {
        internal val DEFAULT_VERIFY_DELAYS_MS = listOf(48L, 160L, 320L)
    }
}
