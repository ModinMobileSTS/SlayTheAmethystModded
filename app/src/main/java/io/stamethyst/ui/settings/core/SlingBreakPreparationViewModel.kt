package io.stamethyst.ui.settings.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.minigame.SlingBreakService
import io.stamethyst.backend.resources.ResourcePackDownloadMirrorSwitchController
import io.stamethyst.backend.resources.ResourcePackSlowDownloadMirrorSwitch

/** Retains standalone install state across rotation; only the application context reaches workers. */
class SlingBreakPreparationViewModel : ViewModel() {
    var progress: Int? by mutableStateOf(null); private set
    var message by mutableStateOf(""); private set
    var failure: String? by mutableStateOf(null); private set
    var installed by mutableStateOf(false); private set
    var slow: ResourcePackSlowDownloadMirrorSwitch? by mutableStateOf(null); private set
    private val mirrors = ResourcePackDownloadMirrorSwitchController()

    fun prepare(context: Context) {
        if (progress != null) return
        progress = -1; failure = null
        val main = Handler(Looper.getMainLooper())
        val listener: (ResourcePackSlowDownloadMirrorSwitch?) -> Unit = { value -> main.post { slow = value } }
        mirrors.addSlowDownloadListener(listener)
        SlingBreakService.ensureAsync(context, StartupProgressCallback { value, text -> progress = value; message = text }, mirrors) { error ->
            mirrors.removeSlowDownloadListener(listener)
            slow = null; progress = null; failure = error; installed = error == null
        }
    }

    fun switchMirror() { mirrors.requestSwitchToNextMirror() }
}
