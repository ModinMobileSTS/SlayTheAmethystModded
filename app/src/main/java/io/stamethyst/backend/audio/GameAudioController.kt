package io.stamethyst.backend.audio

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
    private var lastAudioActive = false
    private var audioDeviceCallbackRegistered = false
    private var noisyReceiverRegistered = false

    private val routeChangeRunnable = Runnable {
        if (activityResumed) {
            onAudioOutputRouteChanged()
        }
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
        dispatchAudioActive(true)
    }

    fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(routeChangeRunnable)
        unregisterRouteCallbacks()
        dispatchAudioActive(false)
    }

    fun onDestroy() {
        onPause()
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

    private fun dispatchAudioActive(active: Boolean) {
        if (!activityResumed && active) {
            return
        }
        val effectiveActive = activityResumed && active
        if (lastAudioActive == effectiveActive) {
            return
        }
        lastAudioActive = effectiveActive
        onAudioFocusGrantedChanged(effectiveActive)
    }

    private fun containsSink(devices: Array<out AudioDeviceInfo>): Boolean {
        return devices.any { it.isSink }
    }
}
