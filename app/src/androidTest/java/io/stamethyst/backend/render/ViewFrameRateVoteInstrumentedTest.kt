package io.stamethyst.backend.render

import android.content.Context
import android.os.Build
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records API calls, not compositor acceptance; execution requires separate device authorization. */
class ViewFrameRateVoteInstrumentedTest {
    @Test fun guardedViewApiRequestsHighAndRestoresThePreviousPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = RecordingView(instrumentation.targetContext)
            val vote = ViewFrameRateVote(view)
            repeat(3) { vote.setActive(true) }
            repeat(3) { vote.setActive(false) }
            assertEquals(
                if (Build.VERSION.SDK_INT >= 35) listOf(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, 30f)
                else emptyList<Float>(),
                view.writes,
            )
            assertEquals(30f, view.rate)
        }
    }

    @Test fun vendorApiExceptionDoesNotEscapeIntoTheRenderLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = RecordingView(instrumentation.targetContext).apply { fail = true }
            val vote = ViewFrameRateVote(view)
            vote.setActive(true)
            view.fail = false
            vote.setActive(false)
            assertTrue(view.writes.isEmpty())
            assertEquals(30f, view.rate)
        }
    }

    private class RecordingView(context: Context) : View(context) {
        var rate = 30f
        var fail = false
        val writes = mutableListOf<Float>()
        override fun getRequestedFrameRate(): Float = rate
        override fun setRequestedFrameRate(frameRate: Float) {
            check(!fail) { "API unavailable in this vendor runtime" }
            rate = frameRate
            writes += frameRate
        }
    }
}
