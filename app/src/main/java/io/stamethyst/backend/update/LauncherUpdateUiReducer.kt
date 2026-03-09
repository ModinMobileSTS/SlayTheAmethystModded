package io.stamethyst.backend.update

enum class UpdateUiMessage {
    LATEST,
    FAILURE,
}

data class UpdateUiDecision(
    val showPrompt: Boolean,
    val message: UpdateUiMessage?,
)

object LauncherUpdateUiReducer {
    fun reduce(
        result: UpdateCheckExecutionResult,
        userInitiated: Boolean,
    ): UpdateUiDecision {
        return when (result) {
            is UpdateCheckExecutionResult.Success -> {
                if (result.hasUpdate) {
                    UpdateUiDecision(
                        showPrompt = true,
                        message = null
                    )
                } else {
                    UpdateUiDecision(
                        showPrompt = false,
                        message = if (userInitiated) UpdateUiMessage.LATEST else null
                    )
                }
            }

            is UpdateCheckExecutionResult.Failure -> UpdateUiDecision(
                showPrompt = false,
                message = if (userInitiated) UpdateUiMessage.FAILURE else null
            )
        }
    }
}
