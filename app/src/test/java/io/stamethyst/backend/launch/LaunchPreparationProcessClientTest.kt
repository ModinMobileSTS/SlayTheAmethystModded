package io.stamethyst.backend.launch

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPreparationProcessClientTest {
    @Test
    fun isPrepProcessStartFailure_detectsGenericStartFailureMessage() {
        val failure = IOException("Failed to start launch preparation service")

        assertTrue(LaunchPreparationProcessClient.isPrepProcessStartFailure(failure))
    }

    @Test
    fun buildPrepRecoveryFailure_preservesOriginalPrepDisconnectCause() {
        val originalPrepFailure = LaunchPreparationProcessClient.buildPrepProcessFailure(
            LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_MISSING
        )
        val restartFailure = IOException("Failed to start launch preparation service")

        val wrapped = LaunchPreparationProcessClient.buildPrepRecoveryFailure(
            restartFailure = restartFailure,
            originalPrepFailure = originalPrepFailure
        )

        assertEquals("Failed to start launch preparation service", wrapped.message)
        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(wrapped))
        assertEquals(originalPrepFailure, wrapped.cause)
        assertEquals(1, wrapped.suppressed.size)
    }
}
