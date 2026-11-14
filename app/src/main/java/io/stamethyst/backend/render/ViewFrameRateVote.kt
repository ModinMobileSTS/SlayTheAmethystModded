package io.stamethyst.backend.render

import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi

/** API 35+ View/ARR hint. Does not invalidate, post callbacks or change any device setting. */
internal class ViewFrameRateVote(view: View) {
    private val state = if (Build.VERSION.SDK_INT >= 35) Api35.createState(view) else null

    fun setActive(active: Boolean): Throwable? {
        try {
            state?.setActive(active)
            return null
        } catch (error: RuntimeException) {
            Log.w("STS-ViewFrameRate", "Could not update the View frame-rate preference", error)
            return error
        } catch (error: LinkageError) {
            // Some vendor/compatibility runtimes expose an SDK level without the full API.
            Log.w("STS-ViewFrameRate", "View frame-rate API unavailable on this runtime", error)
            return error
        }
    }

    @RequiresApi(35)
    private object Api35 {
        fun createState(view: View) = ViewFrameRateVoteState(
            highRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
            readRate = { view.requestedFrameRate },
            writeRate = { view.requestedFrameRate = it },
        )
    }
}
