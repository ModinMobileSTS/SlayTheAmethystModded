package io.stamethyst.backend.audio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class GameAudioController(
    private val activity: Activity,
    private val onAudioFocusGrantedChanged: (Boolean) -> Unit,
    private val onAudioOutputRouteChanged: () -> Unit
) {
    companion object {
        private const val OUTPUT_ROUTE_CHANGE_DEBOUNCE_MS = 120L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var activityResumed = false
    private var lastAudioFocusGranted = true
    private var audioDeviceCallbackRegistered = false
    private var noisyReceiverRegistered = false

    private val routeChangeRunnable = Runnable {
        if (activityResumed) {
            onAudioOutputRouteChanged()
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> dispatchAudioFocusGranted(true)
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> dispatchAudioFocusGranted(false)
        }
    }

    private val audioFocusRequest = audioManager?.let {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
            .build()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                scheduleAudioOutputRouteChanged()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (containsSink(addedDevices)) {
                scheduleAudioOutputRouteChanged()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (containsSink(removedDevices)) {
                scheduleAudioOutputRouteChanged()
            }
        }
    }

    fun onResume() {
        activityResumed = true
        registerRouteCallbacks()
        dispatchAudioFocusGranted(requestAudioFocus())
    }

    fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(routeChangeRunnable)
        unregisterRouteCallbacks()
        abandonAudioFocus()
        dispatchAudioFocusGranted(false)
    }

    fun onDestroy() {
        onPause()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = audioFocusRequest ?: return true
        return when (manager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> true
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> false
            else -> false
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = audioFocusRequest ?: return
        manager.abandonAudioFocusRequest(request)
    }

    private fun registerRouteCallbacks() {
        val manager = audioManager
        if (manager != null && !audioDeviceCallbackRegistered) {
            manager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioDeviceCallbackRegistered = true
        }
        if (!noisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.registerReceiver(
                        noisyReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    activity.registerReceiver(noisyReceiver, filter)
                }
                noisyReceiverRegistered = true
            } catch (_: Throwable) {
            }
        }
    }

    private fun unregisterRouteCallbacks() {
        val manager = audioManager
        if (manager != null && audioDeviceCallbackRegistered) {
            manager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
        }
        if (noisyReceiverRegistered) {
            try {
                activity.unregisterReceiver(noisyReceiver)
            } catch (_: IllegalArgumentException) {
            }
            noisyReceiverRegistered = false
        }
    }

    private fun scheduleAudioOutputRouteChanged() {
        if (!activityResumed) {
            return
        }
        mainHandler.removeCallbacks(routeChangeRunnable)
        mainHandler.postDelayed(routeChangeRunnable, OUTPUT_ROUTE_CHANGE_DEBOUNCE_MS)
    }

    private fun dispatchAudioFocusGranted(granted: Boolean) {
        if (!activityResumed && granted) {
            return
        }
        val effectiveGranted = activityResumed && granted
        if (lastAudioFocusGranted == effectiveGranted) {
            return
        }
        lastAudioFocusGranted = effectiveGranted
        onAudioFocusGrantedChanged(effectiveGranted)
    }

    private fun containsSink(devices: Array<out AudioDeviceInfo>): Boolean {
        return devices.any { it.isSink }
    }
}
