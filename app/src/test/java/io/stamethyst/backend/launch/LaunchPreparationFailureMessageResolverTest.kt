package io.stamethyst.backend.launch

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPreparationFailureMessageResolverTest {
    @Test
    fun containsPrepProcessFailure_detectsTopLevelPrepMarker() {
        val throwable = IOException(
            "STS_PREP_PROCESS_FAILURE:${LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_MISSING}"
        )

        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(throwable))
    }

    @Test
    fun containsPrepProcessFailure_detectsNestedPrepMarker() {
        val throwable = IOException(
            "wrapper",
            IOException(
                "STS_PREP_PROCESS_FAILURE:${LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_STALLED}"
            )
        )

        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(throwable))
    }

    @Test
    fun containsPrepProcessFailure_detectsRemoteFormattedPrepMarker() {
        val throwable = IOException(
            "java.io.IOException: STS_PREP_PROCESS_FAILURE:${LaunchPreparationProcessClient.PREP_PROCESS_FAILURE_REASON_MISSING}\nstack"
        )

        assertTrue(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(throwable))
    }

    @Test
    fun containsPrepProcessFailure_ignoresUnrelatedFailures() {
        val throwable = IOException("Launch preparation cancelled")

        assertFalse(LaunchPreparationFailureMessageResolver.containsPrepProcessFailure(throwable))
    }
}
