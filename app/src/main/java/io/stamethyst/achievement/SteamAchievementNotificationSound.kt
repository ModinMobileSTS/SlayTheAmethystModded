package io.stamethyst.achievement

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import io.stamethyst.R

/** Preloads Steam's desktop_toast_default.wav; never substitutes an Android beep. */
internal class SteamAchievementNotificationSound(context: Context, onReady: () -> Unit) {
    private var loaded = false
    private var released = false
    private var lastPlayedMs: Long? = null
    private val pool = SoundPool.Builder()
        .setMaxStreams(SteamAchievementNotificationSpec.MAX_VISIBLE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundId: Int
    var ready = false
        private set

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            if (!released) {
                loaded = status == 0
                ready = true
                onReady()
            }
            if (status != 0) Log.w(TAG, "Unable to load desktop achievement sound: $status")
        }
        soundId = pool.load(context, R.raw.steam_desktop_toast, 1)
        if (soundId == 0) ready = true
    }

    fun play() {
        if (!loaded || released) return
        val now = SystemClock.uptimeMillis()
        // Steam's GamepadUIAudio rejects sounds played within 50ms. A batch of three unlocks
        // must not triple the original WAV's amplitude by mixing it with itself.
        if (!SteamAchievementNotificationSpec.canPlaySound(now, lastPlayedMs)) return
        if (pool.play(soundId, 1f, 1f, 1, 0, 1f) != 0) lastPlayedMs = now
    }

    fun release() {
        if (released) return
        released = true
        pool.setOnLoadCompleteListener(null)
        pool.release()
    }

    private companion object {
        const val TAG = "AchievementSound"
    }
}
