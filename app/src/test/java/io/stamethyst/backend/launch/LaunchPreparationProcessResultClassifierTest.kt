package io.stamethyst.backend.launch

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPreparationProcessResultClassifierTest {
    @Test
    fun classifyWorkerThrowable_keepsExplicitCancellationAsCancelled() {
        val throwable = IOException("Launch preparation cancelled")

        val result = LaunchPreparationProcessResultClassifier.classifyWorkerThrowable(
            throwable = throwable,
            explicitCancellationRequested = true
        )

        assertEquals(LaunchPreparationProcessTerminalKind.Cancelled, result.kind)
        assertSame(throwable, result.throwable)
    }

    @Test
    fun classifyWorkerThrowable_convertsUnexpectedCancellationIntoPrepDisconnectFailure() {
        val result = LaunchPreparationProcessResultClassifier.classifyWorkerThrowable(
            throwable = IOException("Launch preparation cancelled"),
            explicitCancellationRequested = false
        )

        assertEquals(LaunchPreparationProcessTerminalKind.Failure, result.kind)
        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(result.throwable))
        assertEquals(
            "STS_PREP_PROCESS_FAILURE:${LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_MISSING}",
            result.throwable.message
        )
    }

    @Test
    fun classifyWorkerThrowable_preservesNonCancellationFailure() {
        val throwable = IOException("desktop-1.0.jar not found")

        val result = LaunchPreparationProcessResultClassifier.classifyWorkerThrowable(
            throwable = throwable,
            explicitCancellationRequested = false
        )

        assertEquals(LaunchPreparationProcessTerminalKind.Failure, result.kind)
        assertSame(throwable, result.throwable)
    }

    @Test
    fun buildDestroyFallback_marksUnexpectedDestroyAsPrepDisconnectFailure() {
        val result = LaunchPreparationProcessResultClassifier.buildDestroyFallback(
            explicitCancellationRequested = false
        )

        assertEquals(LaunchPreparationProcessTerminalKind.Failure, result.kind)
        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(result.throwable))
    }
}
