package io.stamethyst.backend.launch

import java.io.IOException

internal enum class LaunchPreparationProcessTerminalKind {
    Failure,
    Cancelled
}

internal data class LaunchPreparationProcessTerminalResult(
    val kind: LaunchPreparationProcessTerminalKind,
    val throwable: Throwable
)

internal object LaunchPreparationProcessResultClassifier {
    fun classifyWorkerThrowable(
        throwable: Throwable,
        explicitCancellationRequested: Boolean
    ): LaunchPreparationProcessTerminalResult {
        if (explicitCancellationRequested && isCancellation(throwable)) {
            return LaunchPreparationProcessTerminalResult(
                kind = LaunchPreparationProcessTerminalKind.Cancelled,
                throwable = throwable
            )
        }
        return LaunchPreparationProcessTerminalResult(
            kind = LaunchPreparationProcessTerminalKind.Failure,
            throwable = normalizeUnexpectedCancellation(throwable)
        )
    }

    fun buildDestroyFallback(
        explicitCancellationRequested: Boolean
    ): LaunchPreparationProcessTerminalResult {
        return if (explicitCancellationRequested) {
            LaunchPreparationProcessTerminalResult(
                kind = LaunchPreparationProcessTerminalKind.Cancelled,
                throwable = IOException("Launch preparation cancelled")
            )
        } else {
            LaunchPreparationProcessTerminalResult(
                kind = LaunchPreparationProcessTerminalKind.Failure,
                throwable = buildPrepProcessDisconnectedFailure()
            )
        }
    }

    private fun normalizeUnexpectedCancellation(throwable: Throwable): Throwable {
        if (!isCancellation(throwable)) {
            return throwable
        }
        return buildPrepProcessDisconnectedFailure()
    }

    private fun buildPrepProcessDisconnectedFailure(): IOException {
        return LaunchPreparationProcessClient.buildPrepProcessFailure(
            LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_MISSING
        )
    }

    private fun isCancellation(throwable: Throwable): Boolean {
        if (throwable is InterruptedException) {
            return true
        }
        val message = throwable.message?.lowercase().orEmpty()
        return message.contains("cancel")
    }
}
